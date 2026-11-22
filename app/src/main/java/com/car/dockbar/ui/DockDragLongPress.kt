package com.car.dockbar.ui

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.car.dockbar.util.Prefs

/** 使用 Dock 长按延迟启动拖动；短按仍由 View 处理。 */
internal object DockDragLongPress {
    fun attach(
        view: View,
        onDragTouch: ((MotionEvent) -> Unit)? = null,
        action: (Float, Float) -> Unit,
    ) {
        val slop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
        var x = 0f
        var y = 0f
        var tracking = false
        var fired = false
        val trigger = Runnable {
            if (tracking && view.isAttachedToWindow && view.isEnabled) {
                fired = true
                view.isPressed = false
                action(x, y)
            }
        }
        view.setOnLongClickListener(null)
        view.isLongClickable = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.removeCallbacks(trigger)
                    x = event.x
                    y = event.y
                    tracking = true
                    fired = false
                    view.postDelayed(trigger, Prefs.dockLongPressDelayMs(view.context))
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (fired) {
                        onDragTouch?.invoke(event)
                    } else if (kotlin.math.abs(event.x - x) > slop ||
                        kotlin.math.abs(event.y - y) > slop) {
                        tracking = false
                        view.removeCallbacks(trigger)
                    }
                    fired
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tracking = false
                    view.removeCallbacks(trigger)
                    val dragged = fired
                    fired = false
                    if (dragged) onDragTouch?.invoke(event)
                    dragged && event.actionMasked == MotionEvent.ACTION_UP
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    tracking = false
                    view.removeCallbacks(trigger)
                    false
                }
                else -> false
            }
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                tracking = false
                fired = false
                v.removeCallbacks(trigger)
            }
        })
    }
}
