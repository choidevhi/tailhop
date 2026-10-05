package com.choidev.tailhop

import android.content.Context

/**
 * 오류 코드 → 화면 문장. 코드는 PC(locales의 err.<code>)와 같은 이름이라 PC가 응답에 실어 보낸 "code"도 번역된다.
 * 상대에게 보내는 거부 응답은 1.4까지처럼 한국어 문장("error")에 코드("code")를 더해 보낸다(docs/PROTOCOL.md §3).
 */
object Errors {
    private val ids: Map<String, Int> = mapOf(
        "auth_failed" to R.string.err_auth_failed,
        "disconnected" to R.string.err_disconnected,
        "not_tailhop" to R.string.err_not_tailhop,
        "after_last" to R.string.err_after_last,
        "bad_frame_len" to R.string.err_bad_frame_len,
        "qr_not_tailhop" to R.string.err_qr_not_tailhop,
        "qr_bad" to R.string.err_qr_bad,
        "qr_version" to R.string.err_qr_version,
        "qr_no_host" to R.string.err_qr_no_host,
        "not_ts_host" to R.string.err_not_ts_host,
        "qr_no_port" to R.string.err_qr_no_port,
        "port_bad" to R.string.err_port_bad,
        "qr_key_bad" to R.string.err_qr_key_bad,
        "clock_skew" to R.string.err_clock_skew,
        "bad_id" to R.string.err_bad_id,
        "replay" to R.string.err_replay,
        "phone_no_pair" to R.string.err_phone_no_pair,
        "no_body" to R.string.err_no_body,
        "bad_header" to R.string.err_bad_header,
        "unsupported" to R.string.err_unsupported,
        "bad_text" to R.string.err_bad_text,
        "bad_text_format" to R.string.err_bad_text_format,
        "bad_file_info" to R.string.err_bad_file_info,
        "file_too_big" to R.string.err_file_too_big,
        "file_incomplete" to R.string.err_file_incomplete,
        "storage" to R.string.err_storage,
        "storage_permission" to R.string.err_storage_permission,
        "storage_folder" to R.string.err_storage_folder,
        "storage_denied" to R.string.err_storage_denied,
        "connect_failed" to R.string.err_connect_failed,
        "pc_forgot" to R.string.err_pc_forgot,
        "bad_reply" to R.string.err_bad_reply,
        "unreadable_reply" to R.string.err_unreadable_reply,
        "pc_refused" to R.string.err_pc_refused,
        "pair_first" to R.string.err_pair_first,
        "text_too_long" to R.string.err_text_too_long,
        "text_empty" to R.string.err_text_empty,
        "size_unknown" to R.string.err_size_unknown,
        "file_too_large" to R.string.err_file_too_large,
        "file_open" to R.string.err_file_open,
        "file_changed" to R.string.err_file_changed,
        "disk_full" to R.string.err_disk_full,
        "tagged_node" to R.string.err_tagged_node,
    )

    private val CODE_RE = Regex("[a-z_]{1,40}")

    /** 상대 응답의 code가 아는 코드인지(모르면 상대 문장을 그대로 보여 준다). */
    fun knows(code: String?): Boolean = code != null && CODE_RE.matches(code) && code in ids

    fun text(ctx: Context, e: Throwable): String = when (e) {
        is ProtocolException -> when {
            e.code == "pc_refused_reason" -> ctx.getString(R.string.err_pc_refused_reason, e.detail ?: "")
            else -> ids[e.code]?.let { ctx.getString(it) } ?: e.detail ?: ctx.getString(R.string.unknown_error)
        }
        else -> e.message ?: ctx.getString(R.string.unknown_error)
    }

    /** 상대에게 보내는 한국어 문장(예전 PC 호환). */
    fun wireText(ctx: Context, e: ProtocolException): String =
        ids[e.code]?.let { Lang.korean(ctx).getString(it) } ?: e.code
}
