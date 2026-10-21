package com.choidev.tailhop

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Collections
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * 앱 안 영상 재생(2.2.0)의 한 번 재생: 허브의 한 파일(원본 또는 딸린 버전 play, §17.4)을 cloud_get 범위(§17.3)로 메모리에만 받는다.
 * 디스크 캐시는 쓰지 않는다(잠긴 폴더 안 영상도 평문이 이 폰 디스크에 남지 않게). 처음 받은 머리(size·mtime)를 기억해 다음 범위마다
 * 함께 보낸다 — 그 사이 파일이 바뀌면 허브가 cloud_changed로 거부한다. close()는 진행 중인 연결을 모두 끊는다(SendToken).
 */
class CloudVideo(private val p: Pairing, private val path: String, private val variant: String?) {
    @Volatile private var head: CloudWire.Head? = null
    @Volatile private var closed = false
    private val live: MutableSet<SendToken> = Collections.synchronizedSet(HashSet())
    /**
     * 받기는 모두 이 스레드들에서 하고 재생기의 읽기 스레드는 Future로 기다린다 — 건너뛰기·닫기로 재생기가 읽기 스레드를 interrupt하면
     * 바로 그 연결을 끊고 빠져나오게(소켓 읽기는 interrupt로 풀리지 않는다). 지금 것과 미리 받는 것, 쓰지 않으면 스레드도 끝난다.
     */
    private val pool: ExecutorService = Executors.newCachedThreadPool()

    /** 객체 전체 크기(첫 범위를 받은 뒤). */
    val size: Long? get() = head?.size

    /** at부터 len바이트(CloudRange.MAX_LEN 이하)를 받는다. 잠긴 폴더 안이면 Net이 열쇠를 붙인다. */
    fun fetch(at: Long, len: Long, token: SendToken = SendToken()): CloudChunk {
        if (closed) throw ProtocolException("disconnected")
        live += token
        if (closed) token.cancel()
        try {
            val h = head
            val c = Net.cloudReadRange(p, path, at, len, variant, h?.size, h?.mtime, token)
            if (h == null) head = c.head
            return c
        } finally { live -= token }
    }

    /** 받기를 스레드에 맡긴다(돌려준 token을 취소하면 멈춘다). 닫힌 뒤면 disconnected. */
    fun submit(at: Long, len: Long): Pair<Future<CloudChunk>, SendToken> {
        val t = SendToken()
        return try { pool.submit<CloudChunk> { fetch(at, len, t) } to t } catch (e: java.util.concurrent.RejectedExecutionException) {
            throw ProtocolException("disconnected")
        }
    }

    /** 재생을 끝냄: 받던 연결을 모두 끊고 미리 받기 스레드도 끝낸다. */
    fun close() {
        closed = true
        synchronized(live) { live.toList() }.forEach { it.cancel() }
        pool.shutdownNow()
    }

    @OptIn(UnstableApi::class)
    fun factory(): DataSource.Factory = DataSource.Factory { Source(this) }

    /**
     * ExoPlayer의 DataSource: open(dataSpec)마다 그 위치부터 범위를 받아 메모리 버퍼에서 읽어 준다. 버퍼를 다 읽으면 미리 받아 둔
     * 다음 범위로 넘어가고 그다음을 또 미리 받는다. 건너뛰기는 새 open이라 그 자리부터 다시 받는다. 길이는 첫 범위의 size로 안다.
     */
    @OptIn(UnstableApi::class)
    private class Source(private val v: CloudVideo) : BaseDataSource(true) {
        private var uri: Uri? = null
        private var opened = false
        /** 이 open에서 아직 넘겨주지 않은 바이트. */
        private var left = 0L
        /** 다음에 받을 위치(객체 안). */
        private var nextAt = 0L
        private var cur = ByteArray(0)
        private var curOff = 0
        private var pending: Future<CloudChunk>? = null
        private var pendingToken: SendToken? = null

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            transferInitializing(dataSpec)
            val pos = dataSpec.position
            val known = v.size
            if (known != null && pos > known) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            // 처음 범위는 작게(재생·건너뛰기가 빨리 시작되게), 그 뒤는 CHUNK씩.
            val want = if (dataSpec.length == C.LENGTH_UNSET.toLong()) START else minOf(START, dataSpec.length)
            val first = now(pos, want)
            val total = first.head.size
            if (pos > total) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            left = if (dataSpec.length == C.LENGTH_UNSET.toLong()) total - pos else minOf(dataSpec.length, total - pos)
            cur = first.data
            curOff = 0
            nextAt = pos + first.data.size
            opened = true
            transferStarted(dataSpec)
            prefetch()
            return left
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (left == 0L) return C.RESULT_END_OF_INPUT
            if (curOff >= cur.size) {
                cur = take()
                curOff = 0
            }
            val n = minOf(length.toLong(), (cur.size - curOff).toLong(), left).toInt()
            System.arraycopy(cur, curOff, buffer, offset, n)
            curOff += n
            left -= n
            bytesTransferred(n)
            return n
        }

        /** 다음 범위: 미리 받아 둔 것(없으면 지금 받는다). 받은 뒤 그다음을 미리 받는다. */
        private fun take(): ByteArray {
            val f = pending
            pending = null
            val t = pendingToken
            pendingToken = null
            val c = if (f != null && t != null) await(f, t) else now(nextAt, minOf(CHUNK, left))
            // 남은 것이 있는데 빈 범위 = 허브의 파일이 그 사이 줄었다.
            if (c.data.isEmpty()) throw ProtocolException("file_incomplete")
            nextAt += c.data.size
            prefetch()
            return c.data
        }

        /** 지금 받아 기다린다. */
        private fun now(at: Long, len: Long): CloudChunk = v.submit(at, len).let { (f, t) -> await(f, t) }

        /** 받기를 기다린다. 재생기가 이 스레드를 interrupt하면(건너뛰기·닫기) 그 연결을 끊고 InterruptedIOException. */
        private fun await(f: Future<CloudChunk>, t: SendToken): CloudChunk = try { f.get() } catch (e: ExecutionException) {
            throw (e.cause as? IOException) ?: IOException(e.cause)
        } catch (e: InterruptedException) {
            t.cancel()
            f.cancel(true)
            Thread.currentThread().interrupt()
            throw InterruptedIOException()
        }

        private fun prefetch() {
            val unfetched = left - (cur.size - curOff)
            if (unfetched <= 0 || pending != null) return
            val (f, t) = try { v.submit(nextAt, minOf(CHUNK, unfetched)) } catch (e: ProtocolException) { return } // 닫는 중
            pending = f
            pendingToken = t
        }

        override fun getUri(): Uri? = uri

        override fun close() {
            pendingToken?.cancel()
            pending?.cancel(true)
            pending = null
            pendingToken = null
            cur = ByteArray(0)
            curOff = 0
            uri = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    companion object {
        /** 한 번에 받는 범위(메모리 버퍼 하나, §17.3 length). */
        const val CHUNK = CloudRange.MAX_LEN.toLong()
        /** open 뒤 처음 받는 범위. */
        const val START = 1024L * 1024
    }
}
