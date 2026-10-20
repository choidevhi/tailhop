package com.choidev.tailhop

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * 클라우드 모드 설정(1.8.0, docs/PROTOCOL.md §12). 둘 다 기본 꺼짐.
 * - 이 폰을 클라우드로 쓰기: 페어링된 PC가 이 폰의 Download/TailHop/Cloud를 보고, 가져오고, 올리고, 지울 수 있다.
 * - 받은 파일도 클라우드에 모으기: 대화방으로 받은 파일을 Download/TailHop 대신 클라우드 맨 위에 둔다(클라우드가 켜져 있을 때만).
 */
object CloudMode {
    private const val PREFS = "settings"
    private const val K_ON = "cloud"
    private const val K_COLLECT = "cloudCollect"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOn(ctx: Context): Boolean = prefs(ctx).getBoolean(K_ON, false)

    fun setOn(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean(K_ON, on).apply() }

    /** 설정 값(화면의 체크 표시). 실제로 모으는지는 collects. */
    fun collectSetting(ctx: Context): Boolean = prefs(ctx).getBoolean(K_COLLECT, false)

    fun setCollect(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean(K_COLLECT, on).apply() }

    /** 대화방으로 받은 파일을 클라우드 맨 위에 둘지. */
    fun collects(ctx: Context): Boolean = isOn(ctx) && collectSetting(ctx)
}

/**
 * 허브(이 폰)의 클라우드 폴더 Download/TailHop/Cloud.
 *
 * API 29+: MediaStore Downloads. 이 앱이 만든 파일만 보이고 지울 수 있다(다른 앱·사용자가 넣은 파일은 MediaStore가 이 앱에 돌려주지
 * 않는다 — 문서에 적은 한계). 폴더는 파일의 RELATIVE_PATH 앞부분이고, 빈 폴더(cloud_mkdir, 올리기가 만든 폴더)는 앱 전용
 * 저장소의 cloud_dirs.json에 따로 적는다(CloudDirs). 경로는 검사한 조각을 이어 붙인 문자열이라 클라우드 폴더 밖을 가리킬 수 없고,
 * MediaStore에는 링크가 없다.
 *
 * API 26–28: 진짜 폴더. 조각마다 내려가며 심볼릭 링크면 bad_path이고, 마지막에 실제 위치가 클라우드 폴더 안인지 다시 본다.
 * 이 폴더 안의 것은 모두 클라우드 것으로 본다(누가 만들었는지 알 수 없다).
 */
object CloudStore {
    /** Download/TailHop 아래 클라우드 폴더 이름(PartFiles의 relDir). */
    private const val REL = "Cloud"
    private val LOCK = Any()
    private var dirs: CloudDirs? = null

    /** 받을 조각·파일의 relDir(Download/TailHop 아래). 장부의 dest로도 쓴다. */
    fun partDir(dir: String): String = if (dir.isEmpty()) REL else "$REL/$dir"

    // ---------- 요청 ----------

    /** cloud_list: 그 폴더의 항목(정렬 전). 폴더가 없으면 not_found. */
    fun list(ctx: Context, dir: String): List<CloudItem> {
        CloudPath.segments(dir)
        if (Build.VERSION.SDK_INT >= 29) synchronized(LOCK) {
            val files = scan(ctx)
            val d = dirs(ctx)
            if (!CloudTree.dirExists(files, d, dir)) throw ProtocolException("not_found") // 파일 경로도 여기서 걸린다
            return CloudTree.list(files, d, dir)
        }
        val f = legacyResolve(dir)
        if (!f.exists() && dir.isEmpty()) return emptyList()
        if (!isRealDir(f)) throw ProtocolException("not_found")
        val out = ArrayList<CloudItem>()
        for (c in f.listFiles().orEmpty()) {
            if (!CloudPath.isListable(c.name)) continue
            val a = attrs(c) ?: continue
            if (a.isSymbolicLink || a.isOther) continue
            if (a.isDirectory) out += CloudItem(c.name, true, 0, a.lastModifiedTime().toMillis())
            else if (a.isRegularFile) out += CloudItem(c.name, false, a.size(), a.lastModifiedTime().toMillis())
        }
        return out
    }

    /** 가져올 파일 하나: 지금 크기·수정 시각과 offset부터 읽는 스트림. */
    class Opened(val head: CloudWire.Head, private val open: (Long) -> InputStream) {
        fun streamAt(offset: Long): InputStream = open(offset)
    }

    /** cloud_get: 파일이어야 한다(폴더·없는 것·숨은 이름은 not_found). 맨 위는 bad_path. */
    fun file(ctx: Context, path: String): Opened {
        if (CloudPath.segments(path).isEmpty()) throw ProtocolException("bad_path")
        if (Build.VERSION.SDK_INT >= 29) {
            val e = synchronized(LOCK) { CloudTree.find(scan(ctx), path) } ?: throw ProtocolException("not_found")
            val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, e.id.toLong())
            // 크기는 MediaStore의 SIZE 열보다 실제 파일 크기(statSize)를 믿는다.
            val size = try { ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } } catch (x: Exception) { null }
                ?: throw ProtocolException("not_found")
            return Opened(CloudWire.Head(size, e.mtime)) { offset ->
                val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("gone")
                ParcelFileDescriptor.AutoCloseInputStream(pfd).also { s ->
                    try { s.channel.position(offset) } catch (x: Exception) { s.close(); throw x }
                }
            }
        }
        val f = legacyResolve(path)
        val a = attrs(f)
        if (a == null || !a.isRegularFile || !CloudPath.isListable(f.name)) throw ProtocolException("not_found")
        return Opened(CloudWire.Head(a.size(), a.lastModifiedTime().toMillis())) { offset ->
            FileInputStream(f).also { s -> try { s.channel.position(offset) } catch (x: Exception) { s.close(); throw x } }
        }
    }

    /**
     * 올릴 폴더·새 폴더(cloud_put의 dir, cloud_mkdir): 없으면 만든다. 중간 조각이 파일이면 bad_path.
     * API 29+는 기록만 하고(첫 파일이 들어갈 때 MediaStore가 실제 폴더를 만든다), 26–28은 진짜 폴더를 만든다.
     */
    fun ensureDir(ctx: Context, dir: String) {
        val parts = CloudPath.segments(dir)
        if (parts.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 29) synchronized(LOCK) {
            val files = scan(ctx)
            var p = ""
            for (s in parts) {
                p = CloudPath.join(p, s)
                if (files.any { it.path == p }) throw ProtocolException("bad_path")
            }
            dirs(ctx).add(dir)
            saveDirs(ctx)
            return
        }
        needLegacyPermission(ctx)
        var cur = legacyBase()
        if (Files.isSymbolicLink(cur.toPath())) throw ProtocolException("bad_path")
        if (!cur.isDirectory && !cur.mkdirs()) throw ProtocolException("storage_folder")
        for (s in parts) {
            cur = File(cur, s)
            val a = attrs(cur)
            when {
                a == null -> if (!cur.mkdir() && !cur.isDirectory) throw ProtocolException("storage_folder")
                a.isSymbolicLink || !a.isDirectory -> throw ProtocolException("bad_path")
            }
        }
        checkInside(cur)
    }

    /**
     * cloud_del: 파일 또는 폴더(안의 것까지). 맨 위는 bad_path, 없으면 not_found, 지우지 못한 것이 있으면 cloud_denied.
     * API 29+는 이 앱이 만든 파일만 지운다(다른 것은 MediaStore가 보여 주지도, 지우게 하지도 않는다).
     */
    fun delete(ctx: Context, path: String) {
        if (CloudPath.segments(path).isEmpty()) throw ProtocolException("bad_path")
        if (Build.VERSION.SDK_INT >= 29) synchronized(LOCK) {
            val files = scan(ctx)
            val d = dirs(ctx)
            val single = CloudTree.find(files, path)
            val targets = when {
                single != null -> listOf(single)
                CloudTree.dirExists(files, d, path) -> CloudTree.under(files, path)
                else -> throw ProtocolException("not_found")
            }
            var denied = false
            for (e in targets) {
                val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, e.id.toLong())
                val n = try { ctx.contentResolver.delete(uri, null, null) } catch (x: Exception) { 0 }
                if (n <= 0) denied = true
            }
            if (single == null && d.removeTree(path)) saveDirs(ctx)
            if (denied) throw ProtocolException("cloud_denied")
            return
        }
        needLegacyPermission(ctx)
        val f = legacyResolve(path)
        val a = attrs(f)
        if (a == null || !CloudPath.isListable(f.name) || a.isOther) throw ProtocolException("not_found")
        try {
            if (a.isDirectory && !a.isSymbolicLink) deleteTree(f.toPath()) else Files.delete(f.toPath())
        } catch (e: Exception) { throw ProtocolException("cloud_denied") }
    }

    /** cloud_mkdir: 이미 있으면 그대로 성공. 맨 위("")도 성공(PC와 같다). */
    fun mkdir(ctx: Context, path: String) = ensureDir(ctx, path)

    // ---------- API 29+ ----------

    /** 이 앱이 만든 클라우드 파일(공개된 것만 — 받는 중인 대기 항목은 MediaStore가 기본으로 빼고 돌려준다). */
    @RequiresApi(29)
    private fun scan(ctx: Context): List<CloudEntry> {
        val base = PartFiles.mediaDir(REL)
        val out = ArrayList<CloudEntry>()
        val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
        try {
            ctx.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cols,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$base%"), null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    val rel = c.getString(2) ?: continue
                    val owner = c.getString(5)
                    if (owner != null && owner != ctx.packageName) continue
                    if (!rel.regionMatches(0, base, 0, base.length, ignoreCase = true)) continue
                    val dir = rel.substring(base.length).removeSuffix("/")
                    if (!CloudPath.isValid(dir) || !CloudPath.isSegment(name)) continue
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    out += CloudEntry(dir, name, size, c.getLong(4) * 1000, c.getLong(0).toString())
                }
            }
        } catch (e: SecurityException) { throw ProtocolException("storage_denied") }
        return out
    }

    private fun dirsFile(ctx: Context) = File(ctx.applicationContext.filesDir, "cloud_dirs.json")

    private fun dirs(ctx: Context): CloudDirs {
        dirs?.let { return it }
        val raw = try { dirsFile(ctx).readText() } catch (e: Exception) { null }
        return CloudDirs.parse(raw).also { dirs = it }
    }

    private fun saveDirs(ctx: Context) {
        val f = dirsFile(ctx)
        val tmp = File(f.path + ".tmp")
        try {
            tmp.writeText(dirs(ctx).encode())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (e: Exception) { } // 못 쓰면 빈 폴더만 다음에 안 보인다(파일이 든 폴더는 그대로 보인다)
    }

    // ---------- API 26–28 ----------

    private fun legacyBase(): File = File(PartFiles.legacyDir(), REL)

    private fun needLegacyPermission(ctx: Context) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            throw ProtocolException("storage_permission")
    }

    /** 조각마다 내려가며 링크면 bad_path. 마지막 조각이 있는지는 보지 않는다. */
    private fun legacyResolve(path: String): File {
        var cur = legacyBase()
        if (Files.isSymbolicLink(cur.toPath())) throw ProtocolException("bad_path")
        for (s in CloudPath.segments(path)) {
            cur = File(cur, s)
            if (Files.isSymbolicLink(cur.toPath())) throw ProtocolException("bad_path")
        }
        if (cur.exists()) checkInside(cur)
        return cur
    }

    /** 실제 위치(링크를 푼 곳)가 클라우드 폴더 안인지. */
    private fun checkInside(f: File) {
        val inside = try {
            val base = legacyBase().canonicalPath
            val real = f.canonicalPath
            real == base || real.startsWith("$base/")
        } catch (e: IOException) { false }
        if (!inside) throw ProtocolException("bad_path")
    }

    private fun attrs(f: File): BasicFileAttributes? = try {
        Files.readAttributes(f.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (e: Exception) { null }

    private fun isRealDir(f: File): Boolean = attrs(f)?.let { it.isDirectory && !it.isSymbolicLink } == true

    /** 폴더를 안의 것까지 지운다. 링크는 따라가지 않고 링크 자체만 지운다(walkFileTree의 기본). */
    private fun deleteTree(root: Path) {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file); return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir); return FileVisitResult.CONTINUE
            }
        })
    }
}
