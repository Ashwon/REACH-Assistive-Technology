package com.reach.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.reach.app.grounding.Candidate

/**
 * Debug overlay — "target and hand positions visible/debuggable" success
 * criterion. Draws normalized coords scaled to the view; PreviewView uses
 * FILL_CENTER so this is approximate but good enough for debugging.
 */
class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    @Volatile var candidates: List<Candidate> = emptyList()
    @Volatile var targetId: Int = -1
    @Volatile var hand: Pair<Float, Float>? = null
    @Volatile var statusText: String = ""

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.YELLOW
    }
    private val targetPaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 8f; color = Color.GREEN
    }
    private val handPaint = Paint().apply { color = Color.CYAN }
    private val textPaint = Paint().apply {
        color = Color.WHITE; textSize = 42f; setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    /** Analysis frames are portrait 3:4; PreviewView fills the screen and center-crops (FILL_CENTER). */
    private val frameAspect = 3f / 4f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        val fw = h * frameAspect
        val sx: Float; val sy: Float; val ox: Float; val oy: Float
        if (fw >= w) { sx = fw; sy = h; ox = (w - fw) / 2f; oy = 0f }
        else { sx = w; sy = w / frameAspect; ox = 0f; oy = (h - sy) / 2f }

        for (c in candidates) {
            val isTarget = c.id == targetId
            val r = RectF(ox + c.box.left * sx, oy + c.box.top * sy, ox + c.box.right * sx, oy + c.box.bottom * sy)
            canvas.drawRect(r, if (isTarget) targetPaint else boxPaint)
            val bev = if (c.beverage != "unknown") " (${c.beverage})" else ""
            val tag = "${if (isTarget) "TARGET " else ""}#${c.id} ${c.label}$bev ${(c.score * 100).toInt()}%"
            canvas.drawText(tag, r.left, (r.top - 10f).coerceAtLeast(textPaint.textSize), textPaint)
        }
        hand?.let { (x, y) -> canvas.drawCircle(ox + x * sx, oy + y * sy, 22f, handPaint) }
        canvas.drawText(statusText, 24f, h - 400f, textPaint) // above the button row
    }
}
