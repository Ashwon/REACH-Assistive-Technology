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
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Draws the detector's real boxes, target corner reticles, 21-landmark hand skeletons, and guidance vectors
 * over the live preview.
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
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3.5f); color = good }
    private val targetFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = (good and 0x00FFFFFF) or 0x22000000 }
    private val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f); color = good }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.reach_scrim) }
    private val targetLabelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = good }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = sp(14f); isFakeBoldText = true }
    private val targetLabelText = Paint(labelText).apply { color = ContextCompat.getColor(context, R.color.reach_bg) }
    private val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primary }
    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.2f); color = 0xAA00E5FF.toInt() }
    private val secondaryBonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.6f); color = 0x77FFFFFF }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3f); color = 0xFFFFFFFF.toInt() }
    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f); color = good }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(3f); color = primary; pathEffect = DashPathEffect(floatArrayOf(dp(10f), dp(6f)), 0f)
    }
    private val warn = ContextCompat.getColor(context, R.color.reach_warn)
    private val lastSeenPaint = Paint(linePaint).apply { color = warn }
    private val hiddenPaint = Paint(linePaint).apply { color = warn; strokeWidth = dp(3f) }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xDDFFFFFF.toInt() }

    private val r = RectF()
    private val bg = RectF()

    companion object {
        /** MediaPipe 21-point hand skeleton connections. */
        private val HAND_BONES = arrayOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 4,         // thumb
            0 to 5, 5 to 6, 6 to 7, 7 to 8,         // index
            0 to 17, 5 to 9, 9 to 13, 13 to 17,     // palm arch
            9 to 10, 10 to 11, 11 to 12,            // middle
            13 to 14, 14 to 15, 15 to 16,          // ring
            17 to 18, 18 to 19, 19 to 20            // pinky
        )
    }

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
            boxPaint.alpha = if (c.confirmed) 0xCC else 0x55
            if (isTarget) {
                canvas.drawRoundRect(r, corner, corner, targetFillPaint)
                canvas.drawRoundRect(r, corner, corner, targetPaint)
                val cx = r.centerX(); val cy = r.centerY()
                canvas.drawCircle(cx, cy, dp(7f), reticlePaint)
                canvas.drawLine(cx - dp(12f), cy, cx + dp(12f), cy, reticlePaint)
                canvas.drawLine(cx, cy - dp(12f), cx, cy + dp(12f), reticlePaint)
            } else {
                canvas.drawRoundRect(r, corner, corner, boxPaint)
            }
            val name = c.label.replaceFirstChar { it.uppercase() }
            val tag = if (isTarget) "TARGET · $name" else "$name ${(c.score * 100).toInt()}%"
            val tp = if (isTarget) targetLabelText else labelText
            val tw = tp.measureText(tag); val th = tp.textSize
            val top = (if (r.top - th - 2 * pad >= 0f) r.top - th - 2 * pad else r.top + pad).coerceIn(0f, height - th - 2 * pad)
            val left = r.left.coerceIn(0f, maxOf(0f, width - tw - 2 * pad))
            bg.set(left, top, left + tw + 2 * pad, top + th + 2 * pad)
            canvas.drawRoundRect(bg, bg.height() / 2, bg.height() / 2, if (isTarget) targetLabelBg else labelBg)
            canvas.drawText(tag, bg.left + pad, bg.bottom - pad - tp.descent() / 2, tp)
        }

        hiddenTarget?.let { b ->
            r.set(ox + b.left * sx, oy + b.top * sy, ox + b.right * sx, oy + b.bottom * sy)
            canvas.drawRoundRect(r, corner, corner, hiddenPaint)
            pill(canvas, "Target · remembered", r.left, r.top - labelText.textSize - 2 * pad, labelBg, labelText, pad)
        }

        tipPaint.alpha = 110
        for (o in others) for (tip in o.tips) canvas.drawCircle(ox + tip.x * sx, oy + tip.y * sy, dp(5f), tipPaint)

        for (v in hands) {
            val tag = if (v.side == Side.LEFT) "Left Hand" else "Right Hand"
            if (v.state == SlotState.MISSING) v.last?.let { last ->
                val x = ox + last.palmX * sx; val y = oy + last.palmY * sy
                canvas.drawCircle(x, y, dp(16f), lastSeenPaint)
                pill(canvas, "${if (v.side == Side.LEFT) "L" else "R"}?", x + dp(18f), y - dp(10f), labelBg, labelText, pad)
            }
            val h = v.hand
            if (!h.present) continue
            val isGuided = v.side == guided
            tipPaint.alpha = 255
            dotPaint.alpha = if (isGuided) 0xEE else 0x99

            // Draw 21-point hand skeleton wireframe
            val lms = h.landmarks
            if (lms.size >= 21) {
                val bp = if (isGuided) bonePaint else secondaryBonePaint
                for ((a, b) in HAND_BONES) {
                    val p0 = lms[a]; val p1 = lms[b]
                    canvas.drawLine(ox + p0.x * sx, oy + p0.y * sy, ox + p1.x * sx, oy + p1.y * sy, bp)
                }
            }
            for (lm in lms) canvas.drawCircle(ox + lm.x * sx, oy + lm.y * sy, dp(3f), dotPaint)

            val index = h.index
            val t = target
            if (guiding && isGuided && index != null && t != null) {
                val ix = ox + index.x * sx; val iy = oy + index.y * sy
                val tx = ox + t.box.centerX() * sx; val ty = oy + t.box.centerY() * sy
                canvas.drawLine(ix, iy, tx, ty, linePaint)
                // Compute 2D alignment percentage (100% when index tip is over the target center)
                val normDist = hypot(index.x - t.box.centerX(), index.y - t.box.centerY())
                val alignPct = ((1f - (normDist / 0.55f)).coerceIn(0f, 1f) * 100f).roundToInt()
                val midX = (ix + tx) * 0.5f; val midY = (iy + ty) * 0.5f
                pill(
                    canvas,
                    "$alignPct% aligned",
                    midX - dp(36f),
                    midY - dp(14f),
                    if (alignPct >= 85) targetLabelBg else labelBg,
                    if (alignPct >= 85) targetLabelText else labelText,
                    pad
                )
            }
            for (tip in h.tips) {
                val x = ox + tip.x * sx; val y = oy + tip.y * sy
                if (tip.finger == Finger.INDEX && isGuided) {
                    canvas.drawCircle(x, y, dp(12f), tipPaint)
                    canvas.drawCircle(x, y, dp(12f), ringPaint)
                    canvas.drawCircle(x, y, dp(17f), outerRingPaint)
                } else {
                    canvas.drawCircle(x, y, dp(5.5f), tipPaint)
                }
            }
            pill(canvas, tag, ox + h.palmX * sx - dp(28f), oy + h.palmY * sy + dp(14f), labelBg, labelText, pad)
            val d = distance
            if (d != null && index != null && isGuided) {
                pill(canvas, d, ox + index.x * sx + dp(18f), oy + index.y * sy - labelText.textSize / 2 - pad, labelBg, labelText, pad)
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

/** FILL_CENTER (PreviewView fillCenter / ImageView centerCrop): scale the frame to cover the view, centred. */
internal fun fillCenter(w: Float, h: Float, frameW: Float, frameH: Float): FloatArray {
    val k = maxOf(w / frameW, h / frameH)
    val fw = frameW * k; val fh = frameH * k
    return floatArrayOf(fw, fh, (w - fw) / 2f, (h - fh) / 2f)
}
