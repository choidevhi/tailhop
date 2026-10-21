package com.choidev.tailhop

import java.util.Locale

/**
 * 대화방 찾기·종류 거르기와 사진·영상 모아 보기(2.0.0)의 고르기. 안드로이드 의존성 없음(JVM 단위 테스트).
 * 글은 기록에 든 앞부분(최대 HistoryCodec.TEXT_INLINE자)에서 찾는다 — 긴 글 전체를 파일에서 읽지 않게(검색이 가볍게).
 */
object ChatFilter {
    const val ALL = "all"
    const val TEXT = "text"
    const val MEDIA = "media"
    const val FILE = "file"

    val KINDS = listOf(ALL, TEXT, MEDIA, FILE)

    /** 파일 이름으로 본 사진·영상(FileKinds 표). */
    fun isMedia(item: HistoryItem): Boolean = !item.isText && FileKinds.previewable(FileKinds.of(item.name ?: ""))

    fun isVideo(item: HistoryItem): Boolean = !item.isText && FileKinds.of(item.name ?: "") == FileKinds.VIDEO

    fun matches(item: HistoryItem, query: String, kind: String): Boolean {
        val kindOk = when (kind) {
            TEXT -> item.isText
            MEDIA -> isMedia(item)
            FILE -> !item.isText && !isMedia(item)
            else -> true
        }
        if (!kindOk) return false
        val q = query.trim()
        if (q.isEmpty()) return true
        val hay = (if (item.isText) item.text else item.name) ?: return false
        return hay.lowercase(Locale.ROOT).contains(q.lowercase(Locale.ROOT))
    }

    fun active(query: String, kind: String): Boolean = query.isNotBlank() || kind != ALL

    /** 모아 보기: 이 대화의 사진·영상, 최신 먼저(기록 순서 그대로). 열 수 있는 URI(content:)가 있는 것만. */
    fun media(items: List<HistoryItem>): List<HistoryItem> = items.filter { isMedia(it) && it.uri?.startsWith("content:") == true }
}
