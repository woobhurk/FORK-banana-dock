package com.car.dockbar.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import com.car.dockbar.R

/** 设置页根容器，保留左导航与右内容的原有横向结构。 */
internal class SettingsHostLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val minSidebarWidth = (94 * density).toInt()
    private val maxSidebarWidth = (242 * density).toInt()
    private lateinit var content: LinearLayout

    init {
        orientation = HORIZONTAL
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        content = findViewById(R.id.llRoot)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (width > 0) {
            val sidebar = getChildAt(0)
            sidebar.layoutParams.width = (width * 0.22f).toInt()
                .coerceIn(minSidebarWidth, maxSidebarWidth)
                .coerceAtMost(width / 3)
            val horizontalPadding = ((if (width >= 1100 * density) 28 else 12) * density).toInt()
            if (content.paddingStart != horizontalPadding) {
                content.setPaddingRelative(horizontalPadding, content.paddingTop, horizontalPadding, content.paddingBottom)
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
