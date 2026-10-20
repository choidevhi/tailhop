package com.choidev.tailhop

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

/**
 * 유리 카드(1.9.0 shadcn/ui 유리 디자인): 반투명 면·머리카락 테두리·rounded-xl(bg_glass)을 깔고 그 둥근 모양으로 안을 자른다 —
 * 안의 줄을 누를 때 물결이 모서리를 넘지 않게. 뒤는 이미 흐린 오로라 배경이라 실시간 흐림은 하지 않는다(매 프레임 일이 없다).
 * 레이아웃에서 background를 따로 주면 그것을 쓴다.
 */
class GlassCard @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    init {
        if (background == null) setBackgroundResource(R.drawable.bg_glass)
        clipToOutline = true
    }
}
