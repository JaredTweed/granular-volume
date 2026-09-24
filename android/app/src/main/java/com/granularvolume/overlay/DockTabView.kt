package com.granularvolume.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * The docked tab (1.5.1, pilot 2): the dial, collapsed to a D-shaped handle at the screen
 * edge with a plus sign on it.
 *
 * Pilot 1 drew a 6dp sliver here and the owner could not grab it, which is exactly what
 * Fitts's law predicts. This tab is 30dp wide and 72dp tall, sits inside a 44x88dp touch
 * window, and carries a glyph that says what a tap does. It is still a live readout: the
 * fill rises from the bottom to the current level over the COMBINED scale, and the orange
 * tick sits where the dial draws its device-minimum line. Muted tints the fill red, the
 * dial's own accent; locked draws the fill at the dial's "unavailable" alpha and dims the
 * plus, so the tab never promises a control the range is not offering.
 *
 * Idle dimming is drawn rather than applied as a view alpha, so the tick stays at full
 * opacity inside a faded tab: [dim] scales everything except the tick.
 */
class DockTabView(context: Context) : View(context) {

    var tabWidthPx = 0f
    var tabHeightPx = 0f
    /** True when the tab hugs the right edge of the screen. */
    var onRight = false

    var fill = 0f
    var tick = 0.6f
    var muted = false
    var locked = false
    var dim = 1f
        set(value) { field = value; invalidate() }
    /** Finger down on the tab: the button brightens and sinks a little, like the dial's keys. */
    var pressedLook = false
        set(value) { field = value; invalidate() }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val buttonStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shape = Path()
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
        if (w <= 0f || h <= 0f || tabWidthPx <= 0f || tabHeightPx <= 0f) return
        val d = resources.displayMetrics.density

        // Tab bounds inside the touch window: flush with the wall, centred vertically.
        val left = if (onRight) w - tabWidthPx else 0f
        val right = left + tabWidthPx
        val top = (h - tabHeightPx) / 2f
        val bottom = top + tabHeightPx
        val r = tabWidthPx / 2f

        // D shape: full semicircle on the inner side, square against the wall.
        val radii = if (onRight) floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                    else floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
        shape.reset()
        rect.set(left, top, right, bottom)
        shape.addRoundRect(rect, radii, Path.Direction.CW)

        // Surface: the pill's navy and hairline, scaled by the idle dim.
        bgPaint.color = argb(0xE6, 0x1A, 0x1A, 0x2E, dim)
        canvas.drawPath(shape, bgPaint)

        // Level fill from the bottom, clipped to the D.
        val fillAlpha = when {
            locked -> 0.04f
            muted  -> 0.35f
            else   -> 0.16f
        }
        fillPaint.color = if (muted) argb(0xFF, 0xFF, 0x5A, 0x5F, fillAlpha * dim)
                          else argb(0xFF, 0xFF, 0xFF, 0xFF, fillAlpha * dim)
        val fillTop = if (muted) top else bottom - tabHeightPx * fill
        canvas.save()
        canvas.clipPath(shape)
        canvas.drawRect(left, fillTop, right, bottom, fillPaint)
        // The device-minimum tick, full opacity whatever the dim.
        tickPaint.color = argb(0xFF, 0xFF, 0x98, 0x00, 1f)
        val tickY = top + tabHeightPx * tick
        canvas.drawRect(left, tickY - d, right, tickY + d, tickPaint)
        canvas.restore()

        strokePaint.color = argb(0x26, 0xFF, 0xFF, 0xFF, dim)
        strokePaint.strokeWidth = d
        canvas.drawPath(shape, strokePaint)

        // The plus, drawn as a BUTTON: the same circular surface as the dial's close, minimize
        // and info keys (bg_overlay_close: faint fill, hairline stroke), so it reads as one
        // family and as something a finger presses. Pressed: brighter fill, sunk to 0.92.
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val press = if (pressedLook) 0.92f else 1f
        val radius = 11f * d * press
        // An opaque base first, so the level fill and the tick read as passing BEHIND the
        // button instead of through it; then the same faint white the dial's keys use.
        buttonPaint.color = argb(0xFF, 0x1A, 0x1A, 0x2E, dim)
        canvas.drawCircle(cx, cy, radius, buttonPaint)
        buttonPaint.color = argb(0xFF, 0xFF, 0xFF, 0xFF, (if (pressedLook) 0.30f else 0.12f) * dim)
        canvas.drawCircle(cx, cy, radius, buttonPaint)
        buttonStrokePaint.strokeWidth = d
        buttonStrokePaint.color = argb(0xFF, 0xFF, 0xFF, 0xFF, 0.16f * dim)
        canvas.drawCircle(cx, cy, radius, buttonStrokePaint)
        val arm = 5.5f * d * press
        glyphPaint.strokeWidth = 2f * d
        glyphPaint.color = argb(0xFF, 0xFF, 0xFF, 0xFF, (if (locked) 0.45f else 0.92f) * dim)
        canvas.drawLine(cx - arm, cy, cx + arm, cy, glyphPaint)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, glyphPaint)
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int, scale: Float): Int {
        val alpha = (a * scale.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (r shl 16) or (g shl 8) or b
    }
}
