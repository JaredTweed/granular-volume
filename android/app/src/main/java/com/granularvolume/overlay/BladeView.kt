package com.granularvolume.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * The Quiet Blade (1.5.1): the dial, collapsed to a sliver at the screen edge.
 *
 * The window that hosts this view is 24dp wide so the strip is honestly tappable; only the
 * outer 6dp are painted, hugging the edge the user pushed the dial against. Inside those 6dp
 * it still tells the truth about the level: a fill that rises from the bottom to the current
 * step over the COMBINED scale, and the orange device-minimum tick at the height where the
 * dial draws its line. Muted tints the strip red, the same accent the dial uses; locked
 * draws it at the dial's "unavailable" alpha, so the blade never promises a control the
 * range is not offering.
 *
 * Idle dimming is drawn, not applied as a view alpha, because the spec keeps the orange tick
 * at full opacity inside a faded blade: [dim] scales everything except the tick.
 */
class BladeView(context: Context) : View(context) {

    /** Painted strip width. The view itself is wider (the touch target). */
    var stripWidthPx: Float = 0f
    /** True when the strip hugs the right edge of the screen, so paint it at the right. */
    var onRight: Boolean = false

    /** 0..1, share of the strip's height filled from the bottom. */
    var fill: Float = 0f
    /** 0..1 from the top: where the device-minimum line sits on the combined scale. */
    var tick: Float = 0.6f
    var muted: Boolean = false
    var locked: Boolean = false
    /** 1 = awake, IDLE_ALPHA = idle. Multiplies everything but the tick. */
    var dim: Float = 1f
        set(value) { field = value; invalidate() }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun update(fill: Float, tick: Float, muted: Boolean, locked: Boolean) {
        this.fill = fill.coerceIn(0f, 1f)
        this.tick = tick.coerceIn(0f, 1f)
        this.muted = muted
        this.locked = locked
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || stripWidthPx <= 0f) return
        val left = if (onRight) w - stripWidthPx else 0f
        val right = left + stripWidthPx
        val radius = stripWidthPx / 2f
        val density = resources.displayMetrics.density

        // Pill surface, same navy as bg_pill_overlay, scaled by the idle dim.
        bgPaint.color = argb(0xE6, 0x1A, 0x1A, 0x2E, dim)
        rect.set(left, 0f, right, h)
        canvas.drawRoundRect(rect, radius, radius, bgPaint)

        // Level fill from the bottom. Red when muted (the dial's mute accent), faint when locked.
        val fillAlpha = when {
            locked -> 0.04f
            muted  -> 0.35f
            else   -> 0.55f
        }
        fillPaint.color = if (muted) argb(0xFF, 0xFF, 0x5A, 0x5F, fillAlpha * dim)
                          else argb(0xFF, 0xFF, 0xFF, 0xFF, fillAlpha * dim)
        val fillTop = if (muted) 0f else h * (1f - fill)
        val inset = 1f * density
        rect.set(left + inset, fillTop + inset, right - inset, h - inset)
        if (rect.height() > 0f) canvas.drawRoundRect(rect, radius - inset, radius - inset, fillPaint)

        // The device-minimum tick: full opacity whatever the dim, exactly as the dial's line.
        tickPaint.color = argb(0xFF, 0xFF, 0x98, 0x00, 1f)
        val tickY = h * tick
        val tickH = 2f * density
        rect.set(left, tickY - tickH / 2f, right, tickY + tickH / 2f)
        canvas.drawRect(rect, tickPaint)
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int, scale: Float): Int {
        val alpha = (a * scale.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (r shl 16) or (g shl 8) or b
    }
}
