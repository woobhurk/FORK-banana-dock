package com.car.dockbar.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.view.View
import com.car.dockbar.util.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class, qualifiers = "mdpi")
class DockEditorPreviewViewTest {
    private lateinit var context: Context
    private lateinit var preview: DockEditorPreviewView

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        Prefs.sp(context).edit().clear().commit()
        Prefs.setDockWidthPx(context, 1920)
        Prefs.setDockHeightPx(context, 128)
        Prefs.setBgMode(context, BgMode.TRANSPARENT)
        preview = DockEditorPreviewView(context)
    }

    @Test
    fun renderBeforeFirstLayoutFitsBitmapWhenSizeBecomesAvailable() {
        // 复现编辑页首次进入时先生成位图、后获得预览尺寸的顺序。
        assertEquals(0, preview.width)
        assertEquals(0, preview.height)
        preview.render(emptyList())

        layoutPreview(1000, 154)

        assertTargetFits(1000, 154)
    }

    @Test
    fun resizeUpdatesTheSharedDrawingAndHitTestBounds() {
        preview.render(emptyList())
        layoutPreview(1000, 154)
        val before = targetBounds()

        layoutPreview(640, 200)

        assertTargetFits(640, 200)
        assertTrue(targetBounds().width() < before.width())
    }

    @Test
    fun heightOnlyChangeRefitsTheBitmap() {
        preview.render(emptyList())
        layoutPreview(640, 200)
        val before = targetBounds()

        layoutPreview(640, 40)

        assertTargetFits(640, 40)
        assertTrue(targetBounds().height() < before.height())
    }

    @Test
    fun renderAfterLayoutUsesTheCurrentSize() {
        layoutPreview(1000, 154)

        preview.render(emptyList())

        assertTargetFits(1000, 154)
    }

    @Test
    fun replacingBitmapAtTheSameViewSizeRefitsItsAspectRatio() {
        layoutPreview(1000, 154)
        preview.render(emptyList())

        preview.render(emptyList(), nativeWidth = 320)

        assertTargetFits(1000, 154, imageWidth = 320)
    }

    @Test
    fun offscreenBitmapDoesNotRequireThePreviewToBeLaidOut() {
        // 候选缩略图也会创建未布局的临时预览，不能因此跳过离屏绘制。
        preview.render(emptyList(), nativeWidth = 320)
        val field = DockEditorPreviewView::class.java.getDeclaredField("bitmap")
        field.isAccessible = true
        val bitmap = field.get(preview) as Bitmap

        assertEquals(320, bitmap.width)
        assertEquals(128, bitmap.height)
    }

    private fun layoutPreview(width: Int, height: Int) {
        preview.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        preview.layout(0, 0, width, height)
    }

    private fun targetBounds(): RectF {
        // 绘制、长按命中和插入位置共用此矩形；不为测试扩展生产接口。
        val field = DockEditorPreviewView::class.java.getDeclaredField("target")
        field.isAccessible = true
        return RectF(field.get(preview) as RectF)
    }

    private fun assertTargetFits(width: Int, height: Int, imageWidth: Int = 1920) {
        val target = targetBounds()
        val inset = 16 * context.resources.displayMetrics.density
        val scale = minOf((width - inset) / imageWidth, (height - inset) / 128)
        assertEquals(imageWidth * scale, target.width(), 0.001f)
        assertEquals(128 * scale, target.height(), 0.001f)
        assertEquals(width / 2f, target.centerX(), 0.001f)
        assertEquals(height / 2f, target.centerY(), 0.001f)
        assertTrue(target.width() > 1f && target.height() > 1f)
        assertTrue(target.left >= 0f && target.top >= 0f)
        assertTrue(target.right <= width && target.bottom <= height)
    }
}
