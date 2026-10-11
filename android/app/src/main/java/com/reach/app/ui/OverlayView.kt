package com.reach.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.reach.app.R
import com.reach.app.grounding.Candidate
import com.reach.app.hand.Finger
import com.reach.app.hand.HandResult

/**
 * Draws the detector's real boxes and the hand landmarker's real fingertips over the live preview.
 * The preview letterboxes (fitCenter), so the whole analysis frame is visible and nothing is cropped.
 */
class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    @Volatile var candidates: List<Candidate> = emptyList()
    @Volatile var targetId: Int = -1
    @Volatile var hand: HandResult = HandResult.NONE
    /** While guiding: draw the line the engine measures, fingertip -> target centre. */
    @Volatile var guiding = false

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val good = ContextCompat.getColor(context, R.color.reach_good)
    private val primary = ContextCompat.getColor(context, R.color.reach_primary)
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f); color = 0xCCFFFFFF.toInt() }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(4f); color = good }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.reach_scrim) }
    private val targetLabelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = good }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = sp(14f); isFakeBoldText = true }
    private val targetLabelText = Paint(labelText).apply { color = ContextCompat.getColor(context, R.color.reach_bg) }
    private val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primary }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3f); color = 0xFFFFFFFF.toInt() }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2.5f); color = primary; pathEffect = DashPathEffect(floatArrayOf(dp(8f), dp(6f)), 0f)
    }

    /** Analysis frames are portrait 3:4. */
    private val frameAspect = 3f / 4f
    private val r = RectF()
    private val bg = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val (sx, sy, ox, oy) = fitCenter(width.toFloat(), height.toFloat(), frameAspect)
        val corner = dp(10f)
        val pad = dp(6f)

        var target: Candidate? = null
        for (c in candidates) {
            val isTarget = c.id == targetId
            if (isTarget) target = c
            r.set(ox + c.box.left * sx, oy + c.box.top * sy, ox + c.box.right * sx, oy + c.box.bottom * sy)
            canvas.drawRoundRect(r, corner, corner, if (isTarget) targetPaint else boxPaint)
            // Label pill above the box (inside it when the box touches the top). No colour guess: it is unverified.
            val name = c.label.replaceFirstChar { it.uppercase() }
            val tag = if (isTarget) "Target · $name" else "$name ${(c.score * 100).toInt()}%"
            val tp = if (isTarget) targetLabelText else labelText
            val tw = tp.measureText(tag); val th = tp.textSize
            val top = if (r.top - th - 2 * pad >= oy) r.top - th - 2 * pad else r.top + pad
            bg.set(r.left, top, r.left + tw + 2 * pad, top + th + 2 * pad)
            canvas.drawRoundRect(bg, bg.height() / 2, bg.height() / 2, if (isTarget) targetLabelBg else labelBg)
            canvas.drawText(tag, bg.left + pad, bg.bottom - pad - tp.descent() / 2, tp)
        }

        val h = hand
        if (!h.present) return
        tipPaint.alpha = if (h.likelyWearer) 255 else 110 // a far-away hand is shown dimmed: it is not guided
        val index = h.index
        val t = target
        if (guiding && h.likelyWearer && index != null && t != null) {
            canvas.drawLine(ox + index.x * sx, oy + index.y * sy, ox + t.box.centerX() * sx, oy + t.box.centerY() * sy, linePaint)
        }
        for (tip in h.tips) {
            val x = ox + tip.x * sx; val y = oy + tip.y * sy
            if (tip.finger == Finger.INDEX) {
                canvas.drawCircle(x, y, dp(11f), tipPaint); canvas.drawCircle(x, y, dp(11f), ringPaint)
            } else canvas.drawCircle(x, y, dp(6f), tipPaint)
        }
    }
}

/** FIT_CENTER: scale the whole frame into the view and centre it (letterbox). Returns scaleX, scaleY, offsetX, offsetY
 *  so that viewX = offsetX + normX * scaleX. */
internal fun fitCenter(w: Float, h: Float, frameAspect: Float): FloatArray {
    val fw = h * frameAspect
    return if (fw <= w) floatArrayOf(fw, h, (w - fw) / 2f, 0f)
    else floatArrayOf(w, w / frameAspect, 0f, (h - w / frameAspect) / 2f)
}
