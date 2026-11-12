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
import com.reach.app.HandView
import com.reach.app.R
import com.reach.app.grounding.Box
import com.reach.app.grounding.Candidate
import com.reach.app.hand.Finger
import com.reach.app.hand.HandResult
import com.reach.app.hand.HandSelector.SlotState
import com.reach.app.hand.Side

/**
 * Draws the detector's real boxes and the hand landmarker's real fingertips over the live preview.
 * The preview fills the screen (fillCenter: scaled to cover, sides cropped on screen only); boxes and points use the
 * same transform from the actual analysis frame size, so they stay on the objects for any lens or camera mode.
 * Anything the models see outside the visible part is clipped at the edge; its label is kept on screen.
 */
class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    @Volatile var candidates: List<Candidate> = emptyList()
    @Volatile var targetId: Int = -1
    /** The locked target's last known box while it is NOT detected (drawn dashed as "hidden"); null otherwise. */
    @Volatile var hiddenTarget: Box? = null
    /** The user's two enrolled hands; [guided] is the one voice guidance follows. */
    @Volatile var hands: List<HandView> = emptyList()
    @Volatile var guided: Side? = null
    /** Hands in view that are not a tracked enrolled hand: shown dimmed, never guided. */
    @Volatile var others: List<HandResult> = emptyList()
    /** While guiding: draw the line the engine measures, fingertip -> target centre. */
    @Volatile var guiding = false
    /** Measured fingertip-to-target distance label, drawn at the fingertip; null without valid depth. */
    @Volatile var distance: String? = null
    /** Analysis frame size (upright). Portrait 3:4 until the first frame says otherwise. */
    @Volatile var frameW = 480
    @Volatile var frameH = 640

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
    private val warn = ContextCompat.getColor(context, R.color.reach_warn)
    private val lastSeenPaint = Paint(linePaint).apply { color = warn }
    private val hiddenPaint = Paint(linePaint).apply { color = warn; strokeWidth = dp(3f) }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt() }

    private val r = RectF()
    private val bg = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val (sx, sy, ox, oy) = fillCenter(width.toFloat(), height.toFloat(), frameW.toFloat(), frameH.toFloat())
        val corner = dp(10f)
        val pad = dp(6f)

        var target: Candidate? = null
        for (c in candidates) {
            val isTarget = c.id == targetId
            if (isTarget) target = c
            r.set(ox + c.box.left * sx, oy + c.box.top * sy, ox + c.box.right * sx, oy + c.box.bottom * sy)
            boxPaint.alpha = if (c.confirmed) 0xCC else 0x55 // seen in one frame only: not offered as a target
            canvas.drawRoundRect(r, corner, corner, if (isTarget) targetPaint else boxPaint)
            // Label pill above the box (inside it when the box touches the top). No colour guess: it is unverified.
            val name = c.label.replaceFirstChar { it.uppercase() }
            val tag = if (isTarget) "Target · $name" else "$name ${(c.score * 100).toInt()}%${if (c.sure) "" else "?"}"
            val tp = if (isTarget) targetLabelText else labelText
            val tw = tp.measureText(tag); val th = tp.textSize
            val top = (if (r.top - th - 2 * pad >= 0f) r.top - th - 2 * pad else r.top + pad).coerceIn(0f, height - th - 2 * pad)
            val left = r.left.coerceIn(0f, maxOf(0f, width - tw - 2 * pad)) // boxes cropped off-screen keep a readable label
            bg.set(left, top, left + tw + 2 * pad, top + th + 2 * pad)
            canvas.drawRoundRect(bg, bg.height() / 2, bg.height() / 2, if (isTarget) targetLabelBg else labelBg)
            canvas.drawText(tag, bg.left + pad, bg.bottom - pad - tp.descent() / 2, tp)
        }

        hiddenTarget?.let { b ->
            r.set(ox + b.left * sx, oy + b.top * sy, ox + b.right * sx, oy + b.bottom * sy)
            canvas.drawRoundRect(r, corner, corner, hiddenPaint)
            pill(canvas, "Target · hidden", r.left, r.top - labelText.textSize - 2 * pad, labelBg, labelText, pad)
        }

        tipPaint.alpha = 110 // hands that are not an enrolled, tracked hand: shown, never guided
        for (o in others) for (tip in o.tips) canvas.drawCircle(ox + tip.x * sx, oy + tip.y * sy, dp(5f), tipPaint)
        for (v in hands) {
            val tag = if (v.side == Side.LEFT) "L" else "R"
            if (v.state == SlotState.MISSING) v.last?.let { last -> // where it was last seen: diagnostics only, never guided
                val x = ox + last.palmX * sx; val y = oy + last.palmY * sy
                canvas.drawCircle(x, y, dp(16f), lastSeenPaint)
                pill(canvas, "$tag?", x + dp(18f), y - dp(10f), labelBg, labelText, pad)
            }
            val h = v.hand
            if (!h.present) continue
            val isGuided = v.side == guided
            tipPaint.alpha = 255
            dotPaint.alpha = 0xAA
            for (lm in h.landmarks) canvas.drawCircle(ox + lm.x * sx, oy + lm.y * sy, dp(2.5f), dotPaint)
            val index = h.index
            val t = target
            if (guiding && isGuided && index != null && t != null) {
                canvas.drawLine(ox + index.x * sx, oy + index.y * sy, ox + t.box.centerX() * sx, oy + t.box.centerY() * sy, linePaint)
            }
            for (tip in h.tips) {
                val x = ox + tip.x * sx; val y = oy + tip.y * sy
                if (tip.finger == Finger.INDEX && isGuided) {
                    canvas.drawCircle(x, y, dp(11f), tipPaint); canvas.drawCircle(x, y, dp(11f), ringPaint)
                } else canvas.drawCircle(x, y, dp(6f), tipPaint)
            }
            pill(canvas, tag, ox + h.palmX * sx - dp(8f), oy + h.palmY * sy + dp(12f), labelBg, labelText, pad)
            val d = distance
            if (d != null && index != null && isGuided) {
                pill(canvas, d, ox + index.x * sx + dp(16f), oy + index.y * sy - labelText.textSize / 2 - pad, labelBg, labelText, pad)
            }
        }
    }

    private fun pill(canvas: Canvas, text: String, x: Float, y: Float, bgPaint: Paint, tp: Paint, pad: Float) {
        val tw = tp.measureText(text); val th = tp.textSize
        val left = x.coerceIn(0f, maxOf(0f, width - tw - 2 * pad)); val top = y.coerceIn(0f, maxOf(0f, height - th - 2 * pad))
        bg.set(left, top, left + tw + 2 * pad, top + th + 2 * pad)
        canvas.drawRoundRect(bg, bg.height() / 2, bg.height() / 2, bgPaint)
        canvas.drawText(text, bg.left + pad, bg.bottom - pad - tp.descent() / 2, tp)
    }
}

/** FILL_CENTER (PreviewView fillCenter / ImageView centerCrop): scale the frame to cover the view, centred. Returns
 *  scaleX, scaleY, offsetX, offsetY so that viewX = offsetX + normX * scaleX (offsets are <= 0 on the cropped axis). */
internal fun fillCenter(w: Float, h: Float, frameW: Float, frameH: Float): FloatArray {
    val k = maxOf(w / frameW, h / frameH)
    val fw = frameW * k; val fh = frameH * k
    return floatArrayOf(fw, fh, (w - fw) / 2f, (h - fh) / 2f)
}
