package com.choidev.tailhop

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * 받는 쪽의 이어받기 장부(TransferBook)를 앱 전용 저장소(files/xfers.json)에 두고, 조각 파일을 만들고 지운다(§11.2).
 * 상대 기기 ID는 PC IP다. 장부는 작고(조각 32개, 끝난 xfer 5000개, 24시간) 바뀔 때마다 통째로 쓴다.
 */
object Transfers {
    private var book: TransferBook? = null

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "xfers.json")

    private fun book(ctx: Context): TransferBook {
        book?.let { return it }
        val raw = try { file(ctx).readText() } catch (e: Exception) { null }
        return TransferBook.parse(raw).also { book = it }
    }

    private fun save(ctx: Context) {
        val f = file(ctx)
        val tmp = File(f.path + ".tmp")
        try {
            tmp.writeText(book(ctx).encode())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (e: Exception) { } // 장부를 못 써도 받기는 된다(다음에 이어받기만 못 할 뿐)
    }

    /** 24시간 지난 조각(파일까지)과 끝난 기록을 지운다. */
    fun prune(ctx: Context) {
        val gone = synchronized(this) { book(ctx).prune().also { save(ctx) } }
        gone.forEach { PartFiles.delete(ctx, it.loc) }
    }

    /** hello 응답의 (have, done). have는 실제 조각 길이(size를 넘지 않게). */
    fun helloState(ctx: Context, peer: String, xfer: String, size: Long): Pair<Long, Boolean> {
        val (rec, done) = synchronized(this) { book(ctx).partial(peer, xfer, size) to book(ctx).isDone(peer, xfer) }
        if (done) return 0L to true
        val have = rec?.let { PartFiles.length(ctx, it.loc) } ?: 0L
        return minOf(have, size) to false
    }

    @Synchronized fun isDone(ctx: Context, peer: String, xfer: String): Boolean = book(ctx).isDone(peer, xfer)

    @Synchronized fun claim(ctx: Context, peer: String, xfer: String, size: Long): PartialRec? =
        book(ctx).claim(peer, xfer, size)?.also { save(ctx) }

    fun begin(ctx: Context, peer: String, xfer: String, size: Long, loc: String, name: String) {
        val old = synchronized(this) { book(ctx).begin(peer, xfer, size, loc, name).also { save(ctx) } }
        old?.let { PartFiles.delete(ctx, it.loc) }
    }

    /** 끊김: 조각을 남긴다. 그 사이 다른 조각이 같은 키를 차지했으면 내 조각을 지운다. */
    fun keep(ctx: Context, peer: String, xfer: String, loc: String) {
        val kept = synchronized(this) { book(ctx).keep(peer, xfer, loc).also { save(ctx) } }
        if (!kept) PartFiles.delete(ctx, loc)
    }

    fun drop(ctx: Context, peer: String, xfer: String, loc: String) {
        synchronized(this) { book(ctx).drop(peer, xfer, loc); save(ctx) }
        PartFiles.delete(ctx, loc)
    }

    @Synchronized fun finish(ctx: Context, peer: String, xfer: String) { book(ctx).finish(peer, xfer); save(ctx) }

    /** 연결 해제: 그 PC의 조각 파일과 기록을 지운다. */
    fun forgetPeer(ctx: Context, peer: String) {
        val gone = synchronized(this) { book(ctx).forgetPeer(peer).also { save(ctx) } }
        gone.forEach { PartFiles.delete(ctx, it.loc) }
    }
}

/**
 * 조각 파일. API 29+는 MediaStore 대기 항목(IS_PENDING=1, 다른 앱에 보이지 않음)이고 그 content URI를 장부에 적는다 —
 * 프로세스가 바뀌어도 만든 앱(owner)은 openFileDescriptor(uri, "rw")로 다시 열 수 있다. 시스템은 오래된 대기 항목을
 * 약 7일 뒤 지우지만 장부는 24시간이면 먼저 지운다. API 26–28은 Download/TailHop의 `.part` 파일 경로를 적는다.
 */
object PartFiles {
    private val MEDIA_DIR = Environment.DIRECTORY_DOWNLOADS + "/TailHop"

    fun mime(name: String): String = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    @Suppress("DEPRECATION")
    private fun legacyDir(): File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TailHop")

    /** 장부의 위치가 이 앱이 만든 조각 자리인지(장부는 앱 전용 저장소에 있지만 그래도 엉뚱한 곳을 지우지 않게). */
    private fun mediaUri(loc: String): Uri? =
        Uri.parse(loc).takeIf { it.scheme == "content" && it.authority == MediaStore.AUTHORITY }

    private fun partFile(loc: String): File? {
        val f = File(loc)
        return f.takeIf { it.name.endsWith(".part") && it.parentFile?.absolutePath == legacyDir().absolutePath }
    }

    /** 새 조각을 만들고 위치를 돌려준다. */
    fun create(ctx: Context, name: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime(name))
                put(MediaStore.MediaColumns.RELATIVE_PATH, MEDIA_DIR)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw ProtocolException("storage")
            return uri.toString()
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            throw ProtocolException("storage_permission")
        val dir = legacyDir()
        if (!dir.isDirectory && !dir.mkdirs()) throw ProtocolException("storage_folder")
        synchronized(LegacyNames.LOCK) {
            val target = LegacyNames.unique(dir, name)
            val part = File(dir, target.name + ".part")
            try {
                java.nio.file.Files.newOutputStream(part.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE).close()
            } catch (e: Exception) { throw ProtocolException("storage") }
            return part.absolutePath
        }
    }

    /** 지금 조각 길이. 없어졌으면(사용자·시스템이 지움) null. */
    fun length(ctx: Context, loc: String): Long? = try {
        mediaUri(loc)?.let { u -> ctx.contentResolver.openFileDescriptor(u, "r")?.use { it.statSize.takeIf { s -> s >= 0 } } }
            ?: partFile(loc)?.takeIf { it.isFile }?.length()
    } catch (e: Exception) { null }

    private fun openRead(ctx: Context, loc: String): InputStream =
        mediaUri(loc)?.let { ctx.contentResolver.openInputStream(it) } ?: partFile(loc)?.let { FileInputStream(it) }
            ?: throw IOException("no part")

    /**
     * 이어 쓰기: 조각의 앞 offset 바이트를 md에 넣고(받는 쪽도 파일 전체를 다시 확인한다), 조각을 offset 길이로 자른 뒤
     * 그 끝에 쓰는 스트림을 돌려준다. 조각이 offset보다 짧거나 열 수 없으면 resume_lost.
     */
    fun openAt(ctx: Context, loc: String, offset: Long, md: MessageDigest): OutputStream {
        try {
            if (offset > 0) openRead(ctx, loc).use { src ->
                val buf = ByteArray(256 * 1024)
                var left = offset
                while (left > 0) {
                    val n = src.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                    if (n < 0) throw ProtocolException("resume_lost")
                    md.update(buf, 0, n)
                    left -= n
                }
            }
            mediaUri(loc)?.let { u ->
                val pfd = ctx.contentResolver.openFileDescriptor(u, "rw") ?: throw IOException("no part")
                val out = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
                try {
                    out.channel.truncate(offset)
                    out.channel.position(offset)
                } catch (e: Exception) { out.close(); throw e }
                return out
            }
            val f = partFile(loc) ?: throw IOException("no part")
            if (!f.isFile) throw IOException("no part")
            RandomAccessFile(f, "rw").use { it.setLength(offset) }
            return FileOutputStream(f, true)
        } catch (e: ProtocolException) { throw e } catch (e: Exception) { throw ProtocolException("resume_lost") }
    }

    /** 다 받고 검증됨: 공개한다(API 29+ IS_PENDING=0, 26–28 이름 변경). (열 URI, 실제 이름). */
    fun commit(ctx: Context, loc: String, fallbackName: String): Pair<Uri, String> {
        mediaUri(loc)?.let { u ->
            if (Build.VERSION.SDK_INT >= 29) {
                ctx.contentResolver.update(u, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            var name = fallbackName
            try {
                ctx.contentResolver.query(u, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) name = it.getString(0) ?: name
                }
            } catch (e: Exception) { }
            return u to name
        }
        val part = partFile(loc) ?: throw ProtocolException("storage")
        val dir = part.parentFile ?: throw ProtocolException("storage")
        val target = synchronized(LegacyNames.LOCK) {
            var t = File(dir, part.name.removeSuffix(".part"))
            if (t.exists()) t = LegacyNames.unique(dir, t.name)
            if (!part.renameTo(t)) throw ProtocolException("storage")
            t
        }
        MediaScannerConnection.scanFile(ctx, arrayOf(target.absolutePath), arrayOf(mime(target.name)), null)
        return FileProvider.getUriForFile(ctx, ctx.packageName + ".files", target) to target.name
    }

    fun delete(ctx: Context, loc: String) {
        try {
            mediaUri(loc)?.let { ctx.contentResolver.delete(it, null, null) } ?: partFile(loc)?.delete()
        } catch (e: Exception) { }
    }
}

/** API 26–28 공용 Download/TailHop의 겹치지 않는 이름(받는 중인 `.part`가 잡은 이름도 피한다). */
object LegacyNames {
    val LOCK = Any()

    fun unique(dir: File, n: String): File {
        var f = File(dir, n)
        var i = 1
        while (f.exists() || File(dir, f.name + ".part").exists()) { f = File(dir, Protocol.numberedName(n, i++)) }
        return f
    }
}
