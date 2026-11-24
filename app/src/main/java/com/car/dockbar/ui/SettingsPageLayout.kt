package com.car.dockbar.ui

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.car.dockbar.R

/** 复用已绑定的控件及其监听器；仅调整展示方式，不改变设置行为。 */
internal open class SettingsPageLayout(protected val activity: Activity) {
    protected fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    protected fun color(id: Int) = ContextCompat.getColor(activity, id)
    protected fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    protected fun row() = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
    protected fun params(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    protected fun take(view: View): View {
        (view.parent as? ViewGroup)?.removeView(view)
        return view
    }

    protected fun text(value: String, size: Float, secondary: Boolean = false) = TextView(activity).apply {
        text = value
        textSize = size
        setTextColor(color(if (secondary) R.color.settings_secondary else R.color.settings_text))
        includeFontPadding = false
    }

    protected fun heading(parent: LinearLayout, title: String, description: String, action: View? = null) {
        val titleBlock = if (action == null) parent else column()
        titleBlock.addView(text(title, 30f).apply {
            typeface = AppFont.bold(activity)
            isAccessibilityHeading = true
        }, params())
        titleBlock.addView(text(description, 14f, true), params(8))
        if (action != null) {
            parent.addView(settingsColumns(activity, titleBlock, action, minWidthDp = 430,
                gapDp = 12, wrapSecond = true).apply { gravity = Gravity.CENTER_VERTICAL }, params())
        }
    }

    protected fun card(parent: LinearLayout, title: String, description: String? = null): LinearLayout {
        val card = column().apply {
            background = ContextCompat.getDrawable(activity, R.drawable.bg_card)
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        parent.addView(card, params(18))
        card.addView(text(title, 21f).apply {
            typeface = AppFont.bold(activity)
            isAccessibilityHeading = true
        }, params())
        if (description != null) card.addView(text(description, 14f, true), params(6))
        return card
    }

    protected fun add(parent: LinearLayout, view: View, top: Int = 16) {
        parent.addView(take(view), params(top))
    }

    protected fun divider(parent: LinearLayout) {
        parent.addView(View(activity).apply { setBackgroundColor(color(R.color.settings_outline)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(12)
                bottomMargin = dp(8)
            })
    }

}

internal fun settingsColumns(
    context: Context,
    first: View,
    second: View,
    minWidthDp: Int = 560,
    firstWeight: Float = 1f,
    gapDp: Int = 18,
    wrapSecond: Boolean = false
): LinearLayout = object : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val threshold = (minWidthDp * density).toInt()
    private val gap = (gapDp * density).toInt()
    private val firstParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    private val secondParams = LayoutParams(
        if (wrapSecond) LayoutParams.WRAP_CONTENT else LayoutParams.MATCH_PARENT,
        LayoutParams.WRAP_CONTENT
    ).apply { topMargin = gap }

    init {
        orientation = VERTICAL
        isBaselineAligned = false
        addView(first, firstParams)
        addView(second, secondParams)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wide = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED ||
            MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight >= threshold
        val direction = if (wide) HORIZONTAL else VERTICAL
        if (orientation != direction) {
            orientation = direction
            val width = if (wide) 0 else LayoutParams.MATCH_PARENT
            firstParams.width = width
            secondParams.width = if (wrapSecond) LayoutParams.WRAP_CONTENT else width
            firstParams.weight = if (wide) firstWeight else 0f
            secondParams.weight = if (wide && !wrapSecond) 1f else 0f
            secondParams.marginStart = if (wide) gap else 0
            secondParams.topMargin = if (wide) 0 else gap
            secondParams.resolveLayoutDirection(layoutDirection)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
