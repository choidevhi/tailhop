package com.choidev.tailhop

import android.app.Activity
import android.app.Dialog
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog

/**
 * 캡처 막기(2.2.1, docs/PROTOCOL.md §18.4): 클라우드 안을 보여 주는 창(클라우드·라이트박스·재생·PIN·비밀번호 창)에 FLAG_SECURE.
 * 화면 캡처·녹화·화면 공유·최근 앱 미리보기에서 그 창이 검게 가려진다. 대화 상자는 따로 창이라 그 창에도 단다.
 * 카메라로 화면을 찍는 것은 막을 수 없다.
 */
fun Activity.secureWindow() {
    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
}

/** 대화 상자 창에도(보이기 전에 단다 — 처음 그린 장면부터 가려지게). */
fun <D : Dialog> D.secure(): D {
    window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    return this
}

/** AlertDialog.Builder.show()와 같고, 창에 FLAG_SECURE를 단 뒤 보인다. */
fun AlertDialog.Builder.showSecure(): AlertDialog = create().secure().also { it.show() }
