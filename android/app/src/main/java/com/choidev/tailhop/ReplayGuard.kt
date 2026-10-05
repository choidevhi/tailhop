package com.choidev.tailhop

import java.io.File

/** 최근 10분 안에 본 메시지 id를 디스크에 남겨 재전송을 막는다. 파일 형식: 줄마다 "id 시각ms". */
class ReplayGuard(private val file: File) {
    private val seen = HashMap<String, Long>()

    init {
        try {
            file.readLines().forEach { line ->
                val parts = line.split(' ')
                if (parts.size == 2 && Protocol.isValidId(parts[0])) parts[1].toLongOrNull()?.let { seen[parts[0]] = it }
            }
        } catch (e: Exception) { }
    }

    @Synchronized
    fun checkAndAdd(id: Any?, ts: Any?, now: Long = System.currentTimeMillis()) {
        val t = (ts as? Number)?.takeIf { it is Int || it is Long }?.toLong()
        if (t == null || Math.abs(t - now) > Protocol.MAX_SKEW_MS)
            throw ProtocolException("clock_skew")
        if (!Protocol.isValidId(id)) throw ProtocolException("bad_id")
        seen.entries.removeIf { now - it.value > WINDOW_MS }
        if (seen.containsKey(id as String)) throw ProtocolException("replay")
        seen[id] = now
        try {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(seen.entries.joinToString("\n") { "${it.key} ${it.value}" })
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (e: Exception) { }
    }

    companion object { const val WINDOW_MS = 600_000L }
}
