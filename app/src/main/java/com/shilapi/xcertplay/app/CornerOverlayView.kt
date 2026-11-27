package com.shilapi.xcertplay.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * CarPlay 圆角黑边叠加（移植自上游 DiPlay 的 CarPlayBlackCornerOverlay，简化版）。
 *
 * 视频流本身是直角满幅的；真 CarPlay 车的圆角由车机在视频上方画黑角实现。
 * 优先用 iPhone 下发的 topLeftCornerMask（随会话 SETUP/SET_PARAMETER 到达），
 * 没拿到时用本地默认半径过渡——观感与真车一致。
 *
 * 纯绘制、不消费触摸（无 click 监听，onTouchEvent 默认 false），事件穿透到
 * 下方 SurfaceView 的触摸回传。
 */
internal class CornerOverlayView(context: Context) : View(context) {
    /** XML inflate 必需：LayoutInflater 在 API 19 走双参构造反射实例化，缺了直接 InflateException。 */
    constructor(context: Context, attrs: android.util.AttributeSet?) : this(context)
    constructor(context: Context, attrs: android.util.AttributeSet?, defStyleAttr: Int) : this(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.FILL }
    private val ring = Path()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var iphoneMask: IphoneCornerMask? = null

    /** iPhone 遮罩未到达前的本地半径（dp）；真车观感 24dp。 */
    var fallbackRadiusDp: Int = DEFAULT_RADIUS_DP
        set(value) {
            field = value.coerceIn(0, MAX_RADIUS_DP)
            if (iphoneMask == null) invalidate()
        }

    fun setIphoneMask(mask: IphoneCornerMask?) {
        val old = iphoneMask
        iphoneMask = mask
        invalidate()
        if (old !== mask) old?.blackPixels?.recycle()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val mask = iphoneMask
        if (mask != null && mask.displayWidth > 0) {
            val side = (width.toFloat() * mask.size / mask.displayWidth)
                .coerceAtMost(minOf(width, height) / 2f)
            if (side > 0f) {
                for (mirrorX in listOf(false, true)) for (mirrorY in listOf(false, true)) {
                    canvas.save()
                    canvas.translate(if (mirrorX) width.toFloat() else 0f, if (mirrorY) height.toFloat() else 0f)
                    canvas.scale(if (mirrorX) -1f else 1f, if (mirrorY) -1f else 1f)
                    canvas.drawBitmap(mask.blackPixels, null, RectF(0f, 0f, side, side), bitmapPaint)
                    canvas.restore()
                }
            }
            return
        }
        if (fallbackRadiusDp == 0) return
        val radius = (fallbackRadiusDp * resources.displayMetrics.density)
            .coerceAtMost(minOf(width, height) / 2f)
        if (radius <= 0f) return
        val content = RectF(0f, 0f, width.toFloat(), height.toFloat())
        ring.reset()
        ring.fillType = Path.FillType.EVEN_ODD
        ring.addRect(content, Path.Direction.CW)
        ring.addRoundRect(content, radius, radius, Path.Direction.CW)
        canvas.drawPath(ring, paint)
    }

    companion object {
        const val DEFAULT_RADIUS_DP = 24
        const val MAX_RADIUS_DP = 48
    }
}
