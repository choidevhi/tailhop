package com.choidev.tailhop

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Android 15+ 전체화면 강제에서 상태바·내비바·키보드에 내용이 가려지지 않게 여백을 준다. */
fun Activity.applySystemBarInsets() {
    val root = findViewById<View>(android.R.id.content)
    val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !night
        isAppearanceLightNavigationBars = !night
    }
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout())
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        WindowInsetsCompat.CONSUMED
    }
    ViewCompat.requestApplyInsets(root)
}
