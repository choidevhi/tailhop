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
        // 1.7.0 전송 신뢰성(§11.4)과 보내기 목록(이 폰 안에서만 쓰는 코드)
        "resume_lost" to R.string.err_resume_lost,
        "hash_mismatch" to R.string.err_hash_mismatch,
        "bad_xfer" to R.string.err_bad_xfer,
        "file_gone" to R.string.err_file_gone,
        "unpaired" to R.string.err_unpaired,
        // 1.8.0 클라우드 모드(§12.3)와 이 폰 안에서만 쓰는 cloud_unsupported(상대가 클라우드를 모르는 예전 버전)
        "cloud_off" to R.string.err_cloud_off,
        "bad_path" to R.string.err_bad_path,
        "not_found" to R.string.err_not_found,
        "cloud_changed" to R.string.err_cloud_changed,
        "cloud_denied" to R.string.err_cloud_denied,
        "cloud_unsupported" to R.string.err_cloud_unsupported,
        // 1.9.0 업데이트(§13.3 update_signer는 화면용, 나머지 둘도 이 폰 안에서만)
        "update_signer" to R.string.err_update_signer,
        "update_bad" to R.string.err_update_bad,
        "update_install" to R.string.err_update_install,
        // 2.0.0 잠긴 폴더(§14.3)
        "vault_locked" to R.string.err_vault_locked,
        "vault_pin" to R.string.err_vault_pin,
        "vault_wait" to R.string.err_vault_wait,
        "vault_busy" to R.string.err_vault_busy,
        // 2.1.0 여러 항목 옮기기(§16.1)
        "bad_move" to R.string.err_bad_move,
        "vault_move" to R.string.err_vault_move,
        // 2.2.0 암호화 클라우드 저장소(§17.6). 허브의 storage(암호문 손상)는 Net이 store_damaged(이 폰 안에서만 쓰는 코드)로 바꾼다
        "store_locked" to R.string.err_store_locked,
        "store_missing" to R.string.err_store_missing,
        "store_damaged" to R.string.err_store_damaged,
        "exists" to R.string.err_exists,
        "too_large" to R.string.err_too_large,
        // 2.2.1 이름 바꾸기(§18.2 항목 오류)·강한 잠금(§18.3)
        "bad_name" to R.string.err_bad_name,
        "store_password" to R.string.err_store_password,
        "store_state" to R.string.err_store_state,
        // 2.3.0 모든 기기 동기화(§19.4). sync_space는 화면용, mirror_damaged는 이 폰 안에서만 쓰는 코드(사본을 풀 수 없음)
        "sync_vault" to R.string.err_sync_vault,
        "sync_space" to R.string.err_sync_space,
        "mirror_damaged" to R.string.err_mirror_damaged,
        // 2.3.1 사본 잠금(§19.4, 화면용): 지문·화면 잠금으로 열어야 함 / 사본을 잠글 화면 잠금이 없음
        "mirror_locked" to R.string.err_mirror_locked,
        "mirror_setup" to R.string.err_mirror_setup,
        // 2.3.1 사본의 잠긴 폴더(§19.6, 이 폰 안에서만): PIN 확인(Argon2id 256 MiB)에 쓸 메모리가 모자람
        "vault_memory" to R.string.err_vault_memory,
    )

    private val CODE_RE = Regex("[a-z_]{1,40}")

    /** 상대 응답의 code가 아는 코드인지(모르면 상대 문장을 그대로 보여 준다). */
    fun knows(code: String?): Boolean = code != null && CODE_RE.matches(code) && code in ids

    fun text(ctx: Context, e: Throwable): String = when (e) {
        is ProtocolException -> when {
            e.code == "pc_refused_reason" -> ctx.getString(R.string.err_pc_refused_reason, e.detail ?: "")
            // 너무 많이 틀려 막힘: 허브가 알린 기다릴 시간(초)을 함께(§14.2)
            e.code == "vault_wait" && e is HubRefusal && Vault.retrySeconds(e.reply) != null ->
                ctx.getString(R.string.err_vault_wait_s, Vault.retrySeconds(e.reply)!!)
            else -> ids[e.code]?.let { ctx.getString(it) } ?: e.detail ?: ctx.getString(R.string.unknown_error)
        }
        else -> e.message ?: ctx.getString(R.string.unknown_error)
    }

    /** 상대에게 보내는 한국어 문장(예전 PC 호환). */
    fun wireText(ctx: Context, e: ProtocolException): String =
        ids[e.code]?.let { Lang.korean(ctx).getString(it) } ?: e.code
}
