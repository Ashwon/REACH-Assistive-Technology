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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()

        for (c in candidates) {
            val p = if (c.id == targetId) targetPaint else boxPaint
            val r = RectF(c.box.left * w, c.box.top * h, c.box.right * w, c.box.bottom * h)
            canvas.drawRect(r, p)
            canvas.drawText("${c.beverage} ${(c.score * 100).toInt()}%", r.left, r.top - 10f, textPaint)
        }
        hand?.let { (x, y) -> canvas.drawCircle(x * w, y * h, 22f, handPaint) }
        canvas.drawText(statusText, 24f, h - 400f, textPaint) // above the button row
    }
}
