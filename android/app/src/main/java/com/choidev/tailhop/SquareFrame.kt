package com.choidev.tailhop

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * 폭과 같은 높이의 칸(격자 미리보기, 2.0.0). 배경 모양(rounded-lg)으로 안을 잘라 그림 모서리도 둥글다.
 */
class SquareFrame @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    init { clipToOutline = true }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
