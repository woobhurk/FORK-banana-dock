package com.car.dockbar.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.car.dockbar.R

/** 一个应用分组，滚动时只绑定当前需要显示的行。 */
internal class ResidentAppPickerAdapter(
    private val title: String,
    private val emptyLabel: String,
    private val choices: List<ResidentAppChoice>,
    private val bindIcon: (String, ImageView) -> Unit,
    private val select: (ResidentAppItem) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private class LabelHolder(val label: TextView) : RecyclerView.ViewHolder(label)
    private class AppHolder(val row: View, val icon: ImageView, val check: CheckBox) : RecyclerView.ViewHolder(row)

    override fun getItemCount() = 1 + choices.size.coerceAtLeast(1)

    override fun getItemViewType(position: Int) = if (position == 0 || choices.isEmpty()) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val context = parent.context
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        if (viewType == 0) {
            return LabelHolder(TextView(context).apply {
                setPadding(dp(8), dp(6), dp(8), dp(6))
            })
        }
        val row = LinearLayout(context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(4) }
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundResource(R.drawable.bg_settings_choice)
            isFocusable = true
        }
        val icon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        row.addView(icon, LinearLayout.LayoutParams(dp(34), dp(34)))
        val check = CheckBox(context).apply {
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.settings_text))
            buttonTintList = ContextCompat.getColorStateList(context, R.color.settings_choice_tint)
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setPadding(dp(8), 0, 0, 0)
        }
        row.addView(check, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return AppHolder(row, icon, check).also { holder ->
            row.setOnClickListener {
                val choice = choices.getOrNull(holder.bindingAdapterPosition - 1)
                if (choice != null && !choice.selected) select(choice.app)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is LabelHolder -> with(holder.label) {
                val heading = position == 0
                text = if (heading) title else emptyLabel
                textSize = if (heading) 16f else 13f
                typeface = if (heading) AppFont.bold(context) else AppFont.regular(context)
                setTextColor(ContextCompat.getColor(context,
                    if (heading) R.color.settings_text else R.color.settings_secondary))
                isAccessibilityHeading = heading
            }
            is AppHolder -> {
                val choice = choices[position - 1]
                val app = choice.app
                holder.check.text = "${app.label}\n${app.pkg}"
                holder.check.isChecked = choice.selected
                holder.row.isSelected = choice.selected
                holder.row.isClickable = !choice.selected
                val state = if (choice.selected) "已加入黑名单" else "点击加入黑名单"
                holder.row.contentDescription = "${app.label}，${app.pkg}，$state"
                bindIcon(app.pkg, holder.icon)
            }
        }
    }
}
