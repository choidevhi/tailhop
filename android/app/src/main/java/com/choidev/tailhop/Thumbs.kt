package com.choidev.tailhop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.io.File
import java.util.Collections
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 미리보기 그림(2.0.0): 클라우드 격자·목록(허브의 cloud_thumb, §15)과 대화방 사진·영상 모아 보기(이 폰의 파일).
 * - 일은 작은 실행기(스레드 2개, 마지막에 요청한 것부터 — 지금 화면에 보이는 칸이 먼저)에서만 하고, 다 쓰면 스레드도 끝난다.
 * - 메모리 LRU(최대 40 MB, 앱 메모리의 1/8 이하). 클라우드 것은 앱 캐시 폴더에도 작게(최대 24 MB) 둔다 — 잠긴 폴더 안의 것은 두지 않는다.
 * - 칸이 다른 파일로 바뀌거나(스크롤) 화면이 가려지면 취소한다: 진행 중인 연결의 소켓을 닫고(SendToken) 로컬 디코딩도 멈춘다.
 * 주기적으로 깨는 일은 없다.
 */
object Thumbs {
    private const val DISK_MAX = 24L * 1024 * 1024
    private val MEM_MAX = minOf(40L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8).toInt()

    private val mem = object : LruCache<String, Bitmap>(MEM_MAX) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    /** 미리보기를 만들 수 없다고 들은 것(not_found 등) — 같은 화면에서 다시 묻지 않게. */
    private val missing = LruCache<String, Boolean>(1024)
    private val main = Handler(Looper.getMainLooper())
    private val live: MutableSet<Job> = Collections.synchronizedSet(HashSet())

    /** 마지막에 넣은 일부터(스크롤하면 새로 보이는 칸이 먼저). */
    private class Lifo : LinkedBlockingDeque<Runnable>() {
        override fun offer(e: Runnable): Boolean = offerFirst(e)
    }

    private val exec = ThreadPoolExecutor(2, 2, 20, TimeUnit.SECONDS, Lifo()).apply { allowCoreThreadTimeOut(true) }

    /** 한 칸의 일. 취소하면 연결(token)과 로컬 디코딩(signal)을 함께 멈춘다. */
    class Job(val key: String) {
        @Volatile var cancelled = false
            private set
        val token = SendToken()
        val signal = CancellationSignal()

        fun cancel() {
            cancelled = true
            token.cancel()
            try { signal.cancel() } catch (e: Exception) { }
        }
    }

    /**
     * view에 key의 그림을 붙인다. 메모리에 있으면 바로 onBitmap, 없으면 load를 실행기에서 돌려 끝나면(그 칸이 아직 같은 key일 때만)
     * onBitmap. 이미 같은 key를 받는 중이면 그대로 둔다. 부르기 전에 칸에 기본 아이콘을 그려 두는 것은 부른 쪽 일.
     */
    fun bind(view: ImageView, key: String, load: (Job) -> Bitmap?, onBitmap: (Bitmap) -> Unit) {
        val old = view.getTag(R.id.thumb_job) as? Job
        if (old != null && old.key == key && !old.cancelled) return
        old?.cancel()
        view.setTag(R.id.thumb_job, null)
        mem.get(key)?.let { onBitmap(it); return }
        if (missing.get(key) != null) return
        val job = Job(key)
        view.setTag(R.id.thumb_job, job)
        live += job
        exec.execute {
            try {
                if (job.cancelled) return@execute
                val bmp = try { load(job) } catch (e: Exception) {
                    if (!job.cancelled && (e as? ProtocolException)?.code == "not_found") missing.put(key, true)
                    null
                }
                if (bmp == null) return@execute
                mem.put(key, bmp)
                main.post { if (!job.cancelled && view.getTag(R.id.thumb_job) === job) onBitmap(bmp) }
            } finally { live -= job }
        }
    }

    /** 이 칸의 일을 멈춘다(칸이 재활용될 때). */
    fun unbind(view: ImageView) {
        (view.getTag(R.id.thumb_job) as? Job)?.cancel()
        view.setTag(R.id.thumb_job, null)
    }

    /** 화면이 가려짐: 남은 일을 모두 멈춘다(보이지 않는 동안 아무 일도 하지 않게). */
    fun cancelAll() {
        val jobs = synchronized(live) { live.toList().also { live.clear() } }
        jobs.forEach { it.cancel() }
        exec.queue.clear()
    }

    fun clearMemory() { mem.evictAll(); missing.evictAll() }

    /** 메모리에 이미 있는 그림(라이트박스가 원본을 받는 동안 먼저 보인다). 없으면 null — 받지 않는다. */
    fun cached(key: String): Bitmap? = mem.get(key)

    // ---------- 클라우드(허브의 미리보기) ----------

    fun cloudKey(pc: String, path: String, item: CloudItem, px: Int) = "c|$pc|$path|${item.mtime}|${item.size}|$px"

    /**
     * 허브에서 미리보기를 받아 그림으로. inVault면(잠긴 폴더 안) 디스크에 두지 않는다 — 메모리에만.
     */
    fun cloud(ctx: Context, p: Pairing, path: String, px: Int, key: String, inVault: Boolean, job: Job): Bitmap? {
        if (!inVault) diskGet(ctx, key)?.let { b -> decode(b)?.let { return it } }
        if (job.cancelled) return null
        val bytes = Net.cloudThumb(p, path, px, job.token)
        if (job.cancelled) return null
        val bmp = decode(bytes) ?: return null
        if (!inVault) diskPut(ctx, key, bytes)
        return bmp
    }

    private fun decode(b: ByteArray): Bitmap? = try { BitmapFactory.decodeByteArray(b, 0, b.size) } catch (e: Exception) { null }

    // ---------- 동기화 사본(2.3.0, §19.3) ----------

    /**
     * 이 폰의 동기화 사본에 든 허브 미리보기를 메모리로만 풀어 px 가까이 줄여 읽는다. 디스크 캐시에는 두지 않는다(평문이 남지 않게).
     */
    fun mirror(ctx: Context, ip: String, e: MirrorEntry, px: Int, job: Job): Bitmap? {
        if (job.cancelled) return null
        val bytes = Mirror.peek(ctx, ip)?.thumb(e) ?: return null
        return try {
            if (job.cancelled) return null
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= px) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (x: Exception) { null } finally { bytes.fill(0) }
    }

    private fun diskDir(ctx: Context) = File(ctx.cacheDir, "thumbs")

    private fun diskFile(ctx: Context, key: String) = File(diskDir(ctx), Xfer.hex(Xfer.sha256().digest(key.toByteArray())) + ".jpg")

    private fun diskGet(ctx: Context, key: String): ByteArray? = try {
        diskFile(ctx, key).takeIf { it.isFile }?.let { f -> f.setLastModified(System.currentTimeMillis()); f.readBytes() }
    } catch (e: Exception) { null }

    /** 캐시 폴더에 쓴다. 넘치면 오래 안 쓴 것부터 지워 3/4까지 줄인다. */
    @Synchronized
    private fun diskPut(ctx: Context, key: String, bytes: ByteArray) {
        try {
            val dir = diskDir(ctx)
            dir.mkdirs()
            val f = diskFile(ctx, key)
            val tmp = File(f.path + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            val files = dir.listFiles().orEmpty()
            var total = files.sumOf { it.length() }
            if (total <= DISK_MAX) return
            for (old in files.sortedBy { it.lastModified() }) {
                if (total <= DISK_MAX * 3 / 4) break
                total -= old.length()
                old.delete()
            }
        } catch (e: Exception) { } // 캐시를 못 써도 보이는 데는 문제없다
    }

    // ---------- 이 폰의 파일(대화방 모아 보기) ----------

    fun localKey(uri: String, px: Int) = "l|$uri|$px"

    /**
     * 이 폰에 있는 사진·영상의 작은 그림. API 29+는 ContentResolver.loadThumbnail(시스템 썸네일), 그 아래는 사진은 줄여 읽고
     * 영상은 첫 장면. 읽을 수 없으면(지워짐·권한 끝남) null — 칸은 아이콘으로 남는다.
     */
    fun local(ctx: Context, uri: Uri, video: Boolean, px: Int, job: Job): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) {
            return try { ctx.contentResolver.loadThumbnail(uri, Size(px, px), job.signal) } catch (e: Exception) { null }
        }
        if (video) return try {
            MediaMetadataRetriever().run {
                try { setDataSource(ctx, uri); getFrameAtTime(0)?.let { scale(it, px) } } finally { release() }
            }
        } catch (e: Exception) { null }
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= px) sample *= 2
            if (job.cancelled) return null
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }?.let { scale(it, px) }
        } catch (e: Exception) { null }
    }

    private fun scale(b: Bitmap, px: Int): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= px) return b
        val r = px.toFloat() / m
        return Bitmap.createScaledBitmap(b, maxOf(1, (b.width * r).toInt()), maxOf(1, (b.height * r).toInt()), true)
    }
}
