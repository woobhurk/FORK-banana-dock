package com.car.dockbar.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.car.dockbar.R
import com.car.dockbar.util.Prefs
import kotlin.math.roundToInt

/** 通用图标按钮：一个居中的 ImageView，带点击水波纹和激活态着色 */
class DockIconView @JvmOverloads constructor(
    context: Context,
    @DrawableRes iconRes: Int = 0,
    private val iconPx: Int = 26,
    /**
     * true = 保留矢量图自带的颜色（如座椅通风里的蓝色档位标识）；
     * false = 整体染成前景色 / 强调色。
     */
    private val preserveColors: Boolean = false,
    private val inactiveAlpha: Float = 1f
) : FrameLayout(context) {

    private var palette = DockBarPalette.DEFAULT

    val image = ImageView(context)
    private var active = false
    /** true = 图标用外部传入的 Drawable 自绘（如风向/座椅/循环的复合图标），不再上色 */
    private var custom = false

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        image.layoutParams = LayoutParams(iconPx, iconPx, Gravity.CENTER)
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        addView(image)
        if (iconRes != 0) image.setImageResource(iconRes)
        setBackgroundResource(R.drawable.bg_item_press)
        isClickable = true
        isFocusable = false
        applyTint()
    }

    fun setIcon(@DrawableRes res: Int) {
        custom = false
        image.setImageResource(res)
        applyTint()
    }

    /**
     * ★ v3.13.7：运行时改图标像素尺寸（播放键跟随槽宽自适应用）。
     * 传入的 px 是「屏幕上真正看到的图标边长」，直接改 ImageView 的布局参数。
     */
    fun setIconSizePx(px: Int) {
        val lp = image.layoutParams
        if (lp.width != px || lp.height != px) {
            lp.width = px
            lp.height = px
            image.layoutParams = lp
        }
    }

    /** 用自绘 Drawable 作为图标（颜色由 Drawable 自己控制，不再染白/强调色） */
    fun setDynamic(d: android.graphics.drawable.Drawable) {
        custom = true
        // ★ v3.1：先清掉颜色滤镜。
        //   init 里 applyTint() 已经给 ImageView 挂上白色 ColorFilter，
        //   而 setImageDrawable 不会自动清滤镜 → 用户自己的 App 图标被整张罩成白色。
        //   （系统 Dock 的第三方 App 图标是原色显示的。）
        image.clearColorFilter()
        image.setImageDrawable(d)
    }

    fun setActive(a: Boolean) {
        active = a
        applyTint()
    }

    /** 反色切换后重新上色（非自绘图标按当前前景色 / 强调色重新着色） */
    internal fun applyPalette(palette: DockBarPalette) {
        this.palette = palette
        applyTint()
    }

    private fun applyTint() {
        if (custom) return
        image.alpha = if (active) 1f else inactiveAlpha
        if (preserveColors) {
            image.clearColorFilter()
        } else {
            image.setColorFilter(if (active) palette.accent else palette.text)
        }
    }
}

/**
 * 音量按钮：喇叭缩到原来的 0.8，右下角用剩下的 0.2 显示媒体档位。
 */
class VolumeDockButton(context: Context, iconPx: Int) : FrameLayout(context) {
    private val icon = DockIconView(
        context,
        R.drawable.ic_volume,
        (iconPx * 0.8f).roundToInt().coerceAtLeast(1)
    )
    private val level = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, iconPx * 0.2f * 2f)
        setTextColor(DockBarPalette.DEFAULT.text)
        typeface = AppFont.bold(context)
        includeFontPadding = false
        gravity = Gravity.CENTER
        text = "0"
    }

    var shownLevel: Int = 0
        private set

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setBackgroundResource(R.drawable.bg_item_press)
        isClickable = true
        isFocusable = false
        clipChildren = false
        clipToPadding = false
        icon.isClickable = false
        icon.background = null
        addView(icon, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val inset = (iconPx * 0.04f).roundToInt()
        addView(level, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = inset + 3
            bottomMargin = inset + 25
        })
    }

    fun setLevel(n: Int) {
        shownLevel = n
        val text = n.toString()
        if (level.text.toString() != text) level.text = text
    }

    internal fun applyPalette(palette: DockBarPalette) {
        icon.applyPalette(palette)
        level.setTextColor(palette.text)
    }
}

/**
 * Dock 上的数值显示：大号主值 + 可选小号副值 + 可选小号单位。
 *
 * 温度：22 .5 °
 * 风速：3 档   /   自动：3 A（A 用强调色）
 * 没数据时显示 "--"
 */
class DockValueView @JvmOverloads constructor(
    context: Context,
    private val mainPx: Float = 25f,
    private val subPx: Float = 16f,
    private val unitPx: Float = 12f
) : LinearLayout(context) {

    private val tvMain = TextView(context)
    private val tvSub = TextView(context)
    private val tvUnit = TextView(context)
    private var palette = DockBarPalette.DEFAULT

    /** 上次渲染的灰显 / 强调色状态，换色时按它重刷颜色 */
    private var lastMuted = false
    private var lastUnitAccent = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setBackgroundResource(R.drawable.bg_item_press)
        isClickable = true
        isFocusable = false

        listOf(tvMain to mainPx, tvSub to subPx, tvUnit to unitPx).forEach { (tv, size) ->
            tv.includeFontPadding = false
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
            tv.typeface = AppFont.bold(context)
            tv.gravity = Gravity.CENTER
            addView(tv, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }

        // 副值下沉成下标，单位符号略微上浮
        tvSub.setPadding(0, (mainPx * 0.30f).toInt(), 0, 0)
        tvUnit.setPadding(1, (mainPx * 0.06f).toInt(), 0, 0)
        tvUnit.alpha = 0.85f

        renderEmpty()
    }

    fun render(main: String, sub: String?, unit: String?, unitAccent: Boolean = false, muted: Boolean = false) {
        lastMuted = muted
        lastUnitAccent = unitAccent
        tvMain.text = main
        setPart(tvSub, sub)
        setPart(tvUnit, unit)
        refreshColors()
    }

    /** 反色切换后按当前内容重刷文字颜色，不改内容 */
    private fun refreshColors() {
        val mutedColor = if (lastMuted) palette.off else palette.text
        val mutedAlpha = if (lastMuted) 0.45f else 1f
        tvMain.setTextColor(mutedColor)
        tvMain.alpha = mutedAlpha
        tvSub.setTextColor(mutedColor)
        tvSub.alpha = mutedAlpha
        tvUnit.setTextColor(
            when {
                lastMuted -> palette.off
                lastUnitAccent -> palette.accent
                else -> palette.text
            }
        )
        tvUnit.alpha = when {
            lastMuted -> 0.45f
            lastUnitAccent -> 1f
            else -> 0.85f
        }
    }

    internal fun applyPalette(palette: DockBarPalette) {
        this.palette = palette
        refreshColors()
    }

    private fun setPart(tv: TextView, text: String?) {
        if (text.isNullOrEmpty()) tv.visibility = View.GONE
        else {
            tv.text = text
            tv.visibility = View.VISIBLE
        }
    }

    fun renderEmpty() {
        tvMain.text = "--"
        tvSub.visibility = View.GONE
        tvUnit.visibility = View.GONE
    }

    /** 温度：22.5°；达到极值显示 HI/LO；空调关闭时保留温度并整体灰显 */
    fun renderTemp(temp: Float?, muted: Boolean = false) {
        if (temp == null) {
            // 没有可恢复的历史温度时用未知占位符，不把「无数据」误显示成空调 OFF。
            render("--", null, null, muted = true)
            return
        }
        val max = Prefs.tempMaxC(context)
        val min = Prefs.tempMinC(context)
        if (temp >= max) {
            render("HI", null, null, unitAccent = !muted, muted = muted)
            return
        }
        if (temp <= min) {
            render("LO", null, null, unitAccent = !muted, muted = muted)
            return
        }
        val whole = kotlin.math.floor(temp).toInt()
        val frac = Math.round((temp - whole) * 10)
        render(whole.toString(), if (frac == 0) null else ".$frac", "°", muted = muted)
    }

    /** 风速：AUTO 档显示「AUTO」；手动显示「n 档」；0 档（关）显示 OFF */
    /**
     * 风速显示。
     *
     * ★ v1.4.85：AUTO 模式下按 [DockValueView] 的设计注释显示「**档位 + A**」
     *   （原文：「风速：3 档 / 自动：3 A（A 用强调色）」）——
     *   大号主值 = 当前 AUTO 风道档位（1..5），蓝色小字 A = AUTO 标记。
     *   之前写成 `render("AUTO", null, null)`，只显示 AUTO 字样、看不到是第几档
     *   （用户反馈「AUTO 图标旁边应该加一个档位显示」）。
     *   读不到档位（level<=0）时才退回纯 "AUTO"。
     */
    fun renderFan(level: Int, auto: Boolean) {
        if (auto) {
            if (level > 0) {
                render(level.toString(), null, "A", unitAccent = true)
            } else {
                render("AUTO", null, null, unitAccent = true)
            }
            return
        }
        if (level <= 0) {
            render("OFF", null, null, muted = true)
            return
        }
        render(level.toString(), null, "档")
    }
}

/**
 * 音乐信息卡片：封面 + 歌名 + 歌手（仅 2 行文字）+ 播放/暂停。
 * 歌名和歌手放得下时静止显示，超出宽度时在卡片内左右来回滚动。
 * 滚到开头或结尾后，按设置里的停顿时间再反向。
 */
class DockMusicView @JvmOverloads constructor(
    context: Context,
    private val barHeightPx: Int = 40,
    private val textScale: Float = 1f,
    private val iconScale: Float = 1f
) : LinearLayout(context) {

    val noteOverlay = ImageView(context)
    val tvTitle: TextView = OverflowTextView(context)
    val tvArtist: TextView = OverflowTextView(context)

    /** ★ v3.13.11：当前生效的字号缓存（避免每次 onMeasure 都 setTextSize 触发 requestLayout 死循环） */
    private var curTitlePx = -1f
    private var curArtistPx = -1f
    // 媒体矢量裁去多余留白，避免 FIT_CENTER 缩小播放、暂停和下一首的可见轮廓。
    val btnPlay = DockIconView(context, R.drawable.ic_pause, (18 * 1.8f * iconScale).toInt())
    val btnNext = DockIconView(context, R.drawable.ic_next, (18 * 1.8f * iconScale).toInt()).apply {
        contentDescription = "下一首"
    }

    // 专辑图：原 0.80× 缩到 0.7×（即 0.56× 栏高），左移腾出空间给歌曲信息
    private val albumSize = (barHeightPx * 0.80f * 0.7f).toInt().coerceAtLeast(8)
    private val albumRadius = albumSize * 0.16f
    private val albumPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val albumMatrix = Matrix()
    private val albumPlaceholder = (context.getDrawable(R.drawable.bg_album)!!.mutate() as GradientDrawable).apply {
        cornerRadius = albumRadius
    }
    private var albumArt: Bitmap? = null
    private val albumFrame = object : FrameLayout(context) {
        override fun onDraw(canvas: Canvas) {
            if (albumArt == null) return
            // 直接绘制带抗锯齿的圆弧，不使用 clipPath 裁切封面。
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), albumRadius, albumRadius, albumPaint)
        }
    }.apply {
        background = albumPlaceholder
        setWillNotDraw(false)
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setBackgroundResource(R.drawable.bg_item_press)
        isClickable = true
        setPadding((4 * textScale).toInt(), 0, (10 * textScale).toInt(), 0)

        // 封面（占位是紫粉渐变 + 音符）
        albumFrame.addView(
            noteOverlay, FrameLayout.LayoutParams(
                (albumSize * 0.55f).toInt(),
                (albumSize * 0.55f).toInt(),
                Gravity.CENTER
            )
        )
        noteOverlay.setImageResource(R.drawable.ic_music_note)
        addView(albumFrame, LayoutParams(albumSize, albumSize).apply {
            marginEnd = (9 * iconScale).toInt()
        })

        // 文字列
        val textCol = LinearLayout(context)
        textCol.orientation = VERTICAL
        textCol.gravity = Gravity.CENTER_VERTICAL

        tvTitle.includeFontPadding = false
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f * textScale)
        tvTitle.typeface = AppFont.bold(context)
        tvTitle.text = ""

        tvArtist.includeFontPadding = false
        tvArtist.setTextSize(TypedValue.COMPLEX_UNIT_PX, 11f * textScale)
        tvArtist.text = ""

        textCol.addView(tvTitle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        textCol.addView(
            tvArtist, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (2 * textScale).toInt()
            }
        )
        addView(textCol, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        // 两个独立的媒体按钮共用尺寸规格，保留歌曲信息区域的弹性空间。
        addView(btnPlay, LayoutParams(0, LayoutParams.MATCH_PARENT).apply {
            marginStart = (6 * iconScale).toInt()
        })
        addView(btnNext, LayoutParams(0, LayoutParams.MATCH_PARENT))

        applyPalette(DockBarPalette.DEFAULT)
        renderEmpty()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!scrollHoldViews.contains(this)) scrollHoldViews.add(this)
    }

    override fun onDetachedFromWindow() {
        scrollHoldViews.remove(this)
        super.onDetachedFromWindow()
    }

    private fun restartTextScroll() {
        (tvTitle as OverflowTextView).applyHoldChange()
        (tvArtist as OverflowTextView).applyHoldChange()
    }

    /**
     * 播放/暂停和下一首使用相同的图标大小与点击热区：
     * 卡宽 295px → 图标 32px / 热区 48px；卡宽 500px → 图标 40px / 热区 64px。
     * 两个按钮并排时仍给歌曲信息留出空间，图标同时受卡片高度约束。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cardW = MeasureSpec.getSize(widthMeasureSpec)
        val cardH = MeasureSpec.getSize(heightMeasureSpec)
        val iconPx = lerp(cardW, PLAY_ICON_MIN_PX, PLAY_ICON_MAX_PX)
            .coerceAtMost((cardH - 12).coerceAtLeast(20))
        btnPlay.setIconSizePx(iconPx)
        btnNext.setIconSizePx(iconPx)
        val hotW = lerp(cardW, PLAY_HOT_MIN_PX, PLAY_HOT_MAX_PX)
            .coerceAtLeast(iconPx + 12)
        // 先把宽度写进 LayoutParams 再交给 LinearLayout 测量，
        // 这样布局/边距都由 LinearLayout 正常处理（保留 marginStart）。
        btnPlay.layoutParams = btnPlay.layoutParams.apply { width = hotW }
        btnNext.layoutParams = btnNext.layoutParams.apply { width = hotW }
        // ★ v3.13.11：歌名 / 歌手字号随音乐卡宽度自动增加（用户指定）
        //   歌名 295px→14px、500px→20px；歌手 295px→11px、500px→14px（线性）
        //   ※ setTextSize 每次调用都会 requestLayout，必须「值变了才设」，
        //     否则在 onMeasure 里会陷入 measure → requestLayout → measure 死循环。
        val titlePx = lerp(cardW, TITLE_MIN_PX, TITLE_MAX_PX) * textScale
        if (curTitlePx != titlePx) {
            curTitlePx = titlePx
            tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, titlePx)
        }
        val artistPx = lerp(cardW, ARTIST_MIN_PX, ARTIST_MAX_PX) * textScale
        if (curArtistPx != artistPx) {
            curArtistPx = artistPx
            tvArtist.setTextSize(TypedValue.COMPLEX_UNIT_PX, artistPx)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** 卡宽 [cardW] 在 [CARD_MIN_WIDTH_PX, CARD_MAX_WIDTH_PX] 区间线性插值到 [vMin, vMax] */
    private fun lerp(cardW: Int, vMin: Int, vMax: Int): Int {
        val t = ((cardW - CARD_MIN_WIDTH_PX).toFloat() /
            (CARD_MAX_WIDTH_PX - CARD_MIN_WIDTH_PX)).coerceIn(0f, 1f)
        return (vMin + t * (vMax - vMin)).roundToInt()
    }

    fun renderEmpty() {
        renderArtwork(null)
        // 其它 Dock 状态变化也会重绑音乐卡。文字没变时不要 setText，否则滚动会被拉回开头。
        setTextIfChanged(tvTitle, "暂无播放")
        setTextIfChanged(tvArtist, "点此打开播放器")
        btnPlay.visibility = View.INVISIBLE
        btnNext.visibility = View.INVISIBLE
    }

    companion object {
        private val scrollHoldViews = ArrayList<DockMusicView>()

        /** 设置里的首尾停顿变了，让正在显示的音乐卡按新时间重新滚动。 */
        fun refreshScrollHold() {
            scrollHoldViews.toList().forEach { it.restartTextScroll() }
        }

        /** 两个媒体按钮的自适应规格（px），热区至少比图标宽 12px。 */
        const val CARD_MIN_WIDTH_PX = 295
        const val CARD_MAX_WIDTH_PX = 500
        const val PLAY_ICON_MIN_PX = 32
        const val PLAY_ICON_MAX_PX = 40
        const val PLAY_HOT_MIN_PX = 48
        const val PLAY_HOT_MAX_PX = 64

        /** ★ v3.13.11：歌名 / 歌手字号随卡宽线性伸缩（px，用户指定） */
        const val TITLE_MIN_PX = 14
        const val TITLE_MAX_PX = 20
        const val ARTIST_MIN_PX = 11
        const val ARTIST_MAX_PX = 14
    }

    /** 反色切换后重刷歌名 / 歌手 / 音符占位 / 媒体按钮颜色，不改内容 */
    internal fun applyPalette(palette: DockBarPalette) {
        tvTitle.setTextColor(palette.text)
        tvArtist.setTextColor(palette.secondary)
        noteOverlay.setColorFilter(palette.text)
        btnPlay.applyPalette(palette)
        btnNext.applyPalette(palette)
    }

    fun render(
        title: String,
        artist: String,
        art: Bitmap?,
        playing: Boolean
    ) {
        renderArtwork(art)
        setTextIfChanged(tvTitle, title.ifBlank { "未知曲目" })
        setTextIfChanged(tvArtist, artist.ifBlank { "—" })
        btnPlay.visibility = View.VISIBLE
        btnNext.visibility = View.VISIBLE
        btnPlay.setIcon(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        btnPlay.contentDescription = if (playing) "暂停" else "播放"
    }

    private fun renderArtwork(art: Bitmap?) {
        if (albumArt === art) return
        albumArt = art
        albumPaint.shader = art?.let { bitmap ->
            // 封面框的宽高固定为 albumSize；按短边缩放，保留原有居中裁切。
            val size = albumSize.toFloat()
            val scale = size / minOf(bitmap.width, bitmap.height)
            albumMatrix.setScale(scale, scale)
            albumMatrix.postTranslate((size - bitmap.width * scale) / 2f, (size - bitmap.height * scale) / 2f)
            BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(albumMatrix) }
        }
        albumFrame.background = if (art == null) albumPlaceholder else null
        noteOverlay.visibility = if (art == null) View.VISIBLE else View.GONE
        albumFrame.invalidate()
    }

    private fun setTextIfChanged(view: TextView, value: String) {
        if (view.text?.toString() != value) view.text = value
    }
}

/**
 * 单行文字超出自身宽度时，在可视区域内左右来回滚动；未超出则静止。
 * 两端各停一会，方便看清开头和结尾。
 */
private class OverflowTextView(context: Context) : TextView(context) {

    private var animator: ValueAnimator? = null
    private var holdMs = 0L
    private var overflow = 0
    private var shown = false
    private var syncPosted = false

    private val moveRunnable = Runnable {
        val movement = animator ?: return@Runnable
        if (!ValueAnimator.areAnimatorsEnabled()) {
            resetScroll()
        } else if (scrollX == 0) {
            movement.start()
        } else {
            movement.reverse()
        }
    }

    private val syncRunnable = Runnable {
        syncPosted = false
        syncScroll()
    }

    init {
        setSingleLine(true)
        ellipsize = null
        gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength(16)
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        resetScroll()
        requestSync()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (shown) requestSync()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        shown = isVisible
        if (isVisible) requestSync() else resetScroll()
    }

    override fun onDetachedFromWindow() {
        shown = false
        removeCallbacks(syncRunnable)
        syncPosted = false
        resetScroll()
        super.onDetachedFromWindow()
    }

    override fun getLeftFadingEdgeStrength(): Float = if (scrollX > 1) 1f else 0f

    override fun getRightFadingEdgeStrength(): Float = if (overflow - scrollX > 1) 1f else 0f

    private fun requestSync() {
        if (syncPosted) return
        syncPosted = true
        post(syncRunnable)
    }

    /** 按当前停顿时间重算；时间变了会从开头重新滚动。 */
    fun applyHoldChange() = requestSync()

    private fun resetScroll() {
        removeCallbacks(moveRunnable)
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
        overflow = 0
        if (scrollX != 0) scrollTo(0, 0)
    }

    private fun syncScroll() {
        if (!shown || !isAttachedToWindow) {
            resetScroll()
            return
        }
        val nextOverflow = overflowPx().coerceAtLeast(0)
        val nextHoldMs = Prefs.musicScrollHoldMs(context)
        if (nextOverflow == overflow && nextHoldMs == holdMs) return
        resetScroll()
        overflow = nextOverflow
        holdMs = nextHoldMs
        if (overflow <= 1) return
        val distance = overflow
        val pxPerSec = 30f * resources.displayMetrics.density.coerceAtLeast(1f)
        val travelMs = (distance / pxPerSec * 1000f).toLong().coerceIn(1400L, 8000L)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = travelMs
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val x = (distance * anim.animatedFraction).roundToInt()
                if (scrollX != x) scrollTo(x, 0)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // 停顿按主线程时钟计时，不随系统动画倍率缩放。
                    postDelayed(moveRunnable, holdMs)
                }
            })
        }
        postDelayed(moveRunnable, holdMs)
    }

    private fun overflowPx(): Int {
        val avail = width - compoundPaddingLeft - compoundPaddingRight
        if (avail <= 0) return 0
        val shownText = visibleText()
        if (shownText.isEmpty()) return 0
        var textWidth = paint.measureText(shownText, 0, shownText.length)
        val laid = layout
        if (laid != null && laid.lineCount > 0 && laid.text.contentEquals(shownText)) {
            textWidth = maxOf(textWidth, laid.getLineWidth(0))
        }
        return (textWidth - avail).roundToInt()
    }

    private fun visibleText(): CharSequence {
        val raw = text ?: return ""
        return transformationMethod?.getTransformation(raw, this) ?: raw
    }
}
