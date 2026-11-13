package com.reach.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.reach.app.HandView
import com.reach.app.grounding.Box
import com.reach.app.grounding.Candidate
import com.reach.app.hand.HandResult
import com.reach.app.hand.HandSelector.SlotState
import com.reach.app.hand.Side
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class FillCenter(val sx: Float, val sy: Float, val ox: Float, val oy: Float)

/** Maps normalized frame coordinates `[0..1]` onto a view using `FILL_CENTER` / `CENTER_CROP` scaling. */
fun fillCenter(viewW: Float, viewH: Float, frameW: Float, frameH: Float): FillCenter {
    val fw = frameW.coerceAtLeast(1f)
    val fh = frameH.coerceAtLeast(1f)
    val scale = maxOf(viewW / fw, viewH / fh)
    val sx = fw * scale
    val sy = fh * scale
    return FillCenter(sx, sy, (viewW - sx) / 2f, (viewH - sy) / 2f)
}

/**
 * Spatial AR HUD overlay drawn directly over the live camera stream.
 * Coordinates map using PreviewView's FILL_CENTER scale/crop from [frameW] x [frameH].
 */
class OverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    @Volatile var candidates: List<Candidate> = emptyList()
    @Volatile var targetId: Int = -1
    @Volatile var hiddenTarget: Box? = null
    @Volatile var hands: List<HandView> = emptyList()
    @Volatile var guided: Side? = null
    @Volatile var others: List<HandResult> = emptyList()
    @Volatile var guiding: Boolean = false
    @Volatile var distance: String? = null
    @Volatile var frameW: Int = 1
    @Volatile var frameH: Int = 1
    @Volatile var alignmentPercent: Int = 0
        private set

    private val dp = resources.displayMetrics.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val rect = RectF()
    private val cornerPath = Path()

    private val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.3f * dp
        color = 0x55FFFFFF
        strokeCap = Paint.Cap.ROUND
    }

    private val candidatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * dp
        color = 0xB854B6FF.toInt()
    }

    private val candidateFadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f * dp
        color = 0x5599A8BE
        pathEffect = DashPathEffect(floatArrayOf(5f * dp, 5f * dp), 0f)
    }

    private val targetFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x1D38DF87
    }

    private val targetBracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.2f * dp
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFF38DF87.toInt()
    }

    private val targetFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * dp
        color = 0x5538DF87
    }

    private val hiddenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f * dp
        color = 0xCCFFB547.toInt()
        pathEffect = DashPathEffect(floatArrayOf(7f * dp, 6f * dp), 0f)
    }

    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.0f * dp
    }

    private val boneGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 5.0f * dp
        color = 0x2E54B6FF
    }

    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val tipRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * dp
    }

    private val guideLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.6f * dp
        strokeCap = Paint.Cap.ROUND
    }

    private val guideGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7.0f * dp
        strokeCap = Paint.Cap.ROUND
        color = 0x2B38DF87
    }

    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val pillStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.0f * dp
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        color = 0xFFF4F7FB.toInt()
    }

    private val subLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        color = 0xFFD6EEFF.toInt()
    }

    private val missingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * dp
        color = 0xAAFFB547.toInt()
        pathEffect = DashPathEffect(floatArrayOf(5f * dp, 5f * dp), 0f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return

        val (sx, sy, ox, oy) = fillCenter(vw, vh, frameW.toFloat(), frameH.toFloat())
        fun fx(x: Float) = ox + x * sx
        fun fy(y: Float) = oy + y * sy

        drawReticle(canvas, vw / 2f, vh / 2f)

        var lockedTarget: Candidate? = null
        for (c in candidates) {
            if (c.id == targetId) {
                lockedTarget = c
                continue
            }
            rect.set(fx(c.box.left), fy(c.box.top), fx(c.box.right), fy(c.box.bottom))
            if (rect.width() < 4f || rect.height() < 4f) continue
            val corner = 12f * dp
            val paint = if (c.confirmed) candidatePaint else candidateFadePaint
            canvas.drawRoundRect(rect, corner, corner, paint)

            val name = c.beverage.takeIf { it != "unknown" && it.isNotBlank() } ?: c.label
            val conf = (c.score * 100f).toInt().coerceIn(0, 99)
            val badgeText = if (c.confirmed) "$name · $conf%" else "$name?"
            val badgeFill = if (c.confirmed) 0xCC0D131F.toInt() else 0x990D131F.toInt()
            val badgeBorder = if (c.confirmed) 0x6654B6FF else 0x33FFFFFF
            drawPill(canvas, badgeText, rect.left + rect.width() / 2f, rect.top - 14f * dp, badgeFill, badgeBorder, vw)
        }

        val target = lockedTarget
        if (target != null) {
            rect.set(fx(target.box.left), fy(target.box.top), fx(target.box.right), fy(target.box.bottom))
            val radius = 14f * dp
            canvas.drawRoundRect(rect, radius, radius, targetFillPaint)
            canvas.drawRoundRect(rect, radius, radius, targetFramePaint)
            drawCornerBrackets(canvas, rect)

            // Center beacon on target
            val tcx = rect.centerX()
            val tcy = rect.centerY()
            jointPaint.color = 0xFF38DF87.toInt()
            canvas.drawCircle(tcx, tcy, 4.5f * dp, jointPaint)
            jointPaint.color = 0xFF06080D.toInt()
            canvas.drawCircle(tcx, tcy, 1.8f * dp, jointPaint)

            val name = target.beverage.takeIf { it != "unknown" && it.isNotBlank() } ?: target.label
            val conf = (target.score * 100f).toInt().coerceIn(0, 99)
            drawPill(
                canvas,
                "LOCKED · ${name.uppercase()} $conf%",
                tcx,
                rect.top - 16f * dp,
                0xE6081F15.toInt(),
                0xCC38DF87.toInt(),
                vw
            )
        } else {
            hiddenTarget?.let { b ->
                rect.set(fx(b.left), fy(b.top), fx(b.right), fy(b.bottom))
                canvas.drawRoundRect(rect, 14f * dp, 14f * dp, hiddenPaint)
                drawPill(
                    canvas,
                    "LAST SEEN",
                    rect.centerX(),
                    rect.top - 15f * dp,
                    0xD92B1B07.toInt(),
                    0xBBFFB547.toInt(),
                    vw
                )
            }
        }

        // Unenrolled bystanders (faint reference only)
        for (h in others) {
            if (!h.present) continue
            drawHandSkeleton(canvas, h, 0x44FFFFFF, 0x55FFFFFF, false, ::fx, ::fy)
        }

        var guidedHandResult: HandResult? = null
        for (v in hands) {
            val isGuided = v.side == guided
            if (isGuided && v.state == SlotState.TRACKED && v.hand.present) {
                guidedHandResult = v.hand
            }
            if (v.state == SlotState.MISSING) {
                v.last?.let { last ->
                    val px = fx(last.palmX)
                    val py = fy(last.palmY)
                    canvas.drawCircle(px, py, 16f * dp, missingPaint)
                    drawPill(canvas, v.side.word.uppercase(), px, py - 24f * dp, 0xBB1E1609.toInt(), 0x88FFB547.toInt(), vw)
                }
                continue
            }
            if (!v.hand.present) continue

            val wireColor = when {
                isGuided && guiding -> 0xFF54B6FF.toInt()
                isGuided -> 0xCC54B6FF.toInt()
                else -> 0x77D6EEFF
            }
            val dotColor = if (isGuided) 0xFFF4F7FB.toInt() else 0xAA99A8BE.toInt()
            drawHandSkeleton(canvas, v.hand, wireColor, dotColor, isGuided && guiding, ::fx, ::fy)

            val px = fx(v.hand.palmX)
            val py = fy(v.hand.palmY)
            drawPill(
                canvas,
                if (isGuided) "${v.side.word.uppercase()} · GUIDED" else v.side.word.uppercase(),
                px,
                py + 26f * dp,
                if (isGuided) 0xD90B1A2B.toInt() else 0xAA0D131F.toInt(),
                if (isGuided) 0xAA54B6FF.toInt() else 0x33FFFFFF,
                vw
            )
        }

        // Guidance vector + alignment metric
        val tip = guidedHandResult?.index
        if (guiding && target != null && tip != null) {
            val hx = fx(tip.x)
            val hy = fy(tip.y)
            val tx = fx(target.box.centerX())
            val ty = fy(target.box.centerY())

            val normDist = hypot((target.box.centerX() - tip.x).toDouble(), (target.box.centerY() - tip.y).toDouble()).toFloat()
            val pct = ((1f - (normDist / 0.45f)).coerceIn(0f, 1f) * 100f).toInt()
            alignmentPercent = pct

            val alignedColor = if (pct >= 75) 0xFF38DF87.toInt() else 0xFF54B6FF.toInt()
            guideLinePaint.color = alignedColor
            guideGlowPaint.color = if (pct >= 75) 0x3338DF87 else 0x2E54B6FF

            val pxDist = hypot(tx - hx, ty - hy)
            if (pxDist > 12f * dp) {
                canvas.drawLine(hx, hy, tx, ty, guideGlowPaint)
                canvas.drawLine(hx, hy, tx, ty, guideLinePaint)

                // Crisp arrow chevron near target end
                val angle = atan2(ty - hy, tx - hx)
                val arrowEndOffset = 14f * dp
                val ax = tx - cos(angle) * arrowEndOffset
                val ay = ty - sin(angle) * arrowEndOffset
                val headLen = 9f * dp
                val spread = 0.55f
                canvas.drawLine(ax, ay, ax - cos(angle - spread) * headLen, ay - sin(angle - spread) * headLen, guideLinePaint)
                canvas.drawLine(ax, ay, ax - cos(angle + spread) * headLen, ay - sin(angle + spread) * headLen, guideLinePaint)
            }

            // Triple-ring fingertip beacon
            tipRingPaint.color = alignedColor
            jointPaint.color = if (pct >= 75) 0x3338DF87 else 0x3354B6FF
            canvas.drawCircle(hx, hy, 14f * dp, jointPaint)
            canvas.drawCircle(hx, hy, 9f * dp, tipRingPaint)
            jointPaint.color = 0xFFF4F7FB.toInt()
            canvas.drawCircle(hx, hy, 3.6f * dp, jointPaint)

            val distTag = distance?.let { "$it · " } ?: ""
            drawPill(
                canvas,
                "$distTag$pct% aligned",
                (hx + tx) / 2f,
                (hy + ty) / 2f - 14f * dp,
                0xE60B1524.toInt(),
                alignedColor,
                vw
            )
        } else {
            alignmentPercent = if (guiding && target != null) alignmentPercent else 0
            // Still highlight guided fingertip when idle/locked if visible
            if (tip != null) {
                val hx = fx(tip.x)
                val hy = fy(tip.y)
                tipRingPaint.color = 0xFF54B6FF.toInt()
                canvas.drawCircle(hx, hy, 7.5f * dp, tipRingPaint)
                jointPaint.color = 0xFFF4F7FB.toInt()
                canvas.drawCircle(hx, hy, 3f * dp, jointPaint)
            }
        }
    }

    private fun drawReticle(canvas: Canvas, cx: Float, cy: Float) {
        val gap = 6f * dp
        val arm = 12f * dp
        canvas.drawLine(cx - gap - arm, cy, cx - gap, cy, reticlePaint)
        canvas.drawLine(cx + gap, cy, cx + gap + arm, cy, reticlePaint)
        canvas.drawLine(cx, cy - gap - arm, cx, cy - gap, reticlePaint)
        canvas.drawLine(cx, cy + gap, cx, cy + gap + arm, reticlePaint)
    }

    private fun drawCornerBrackets(canvas: Canvas, r: RectF) {
        val w = r.width()
        val h = r.height()
        val len = minOf(22f * dp, minOf(w, h) * 0.28f)
        val c = minOf(12f * dp, len * 0.65f)
        cornerPath.reset()
        // Top-left
        cornerPath.moveTo(r.left, r.top + len)
        cornerPath.lineTo(r.left, r.top + c)
        cornerPath.quadTo(r.left, r.top, r.left + c, r.top)
        cornerPath.lineTo(r.left + len, r.top)
        // Top-right
        cornerPath.moveTo(r.right - len, r.top)
        cornerPath.lineTo(r.right - c, r.top)
        cornerPath.quadTo(r.right, r.top, r.right, r.top + c)
        cornerPath.lineTo(r.right, r.top + len)
        // Bottom-right
        cornerPath.moveTo(r.right, r.bottom - len)
        cornerPath.lineTo(r.right, r.bottom - c)
        cornerPath.quadTo(r.right, r.bottom, r.right - c, r.bottom)
        cornerPath.lineTo(r.right - len, r.bottom)
        // Bottom-left
        cornerPath.moveTo(r.left + len, r.bottom)
        cornerPath.lineTo(r.left + c, r.bottom)
        cornerPath.quadTo(r.left, r.bottom, r.left, r.bottom - c)
        cornerPath.lineTo(r.left, r.bottom - len)
        canvas.drawPath(cornerPath, targetBracketPaint)
    }

    private inline fun drawHandSkeleton(
        canvas: Canvas,
        hand: HandResult,
        wireColor: Int,
        jointColor: Int,
        glow: Boolean,
        fx: (Float) -> Float,
        fy: (Float) -> Float
    ) {
        val lm = hand.landmarks
        if (lm.size < 21) return
        bonePaint.color = wireColor
        for ((a, b) in HAND_BONES) {
            val x1 = fx(lm[a].x)
            val y1 = fy(lm[a].y)
            val x2 = fx(lm[b].x)
            val y2 = fy(lm[b].y)
            if (glow) canvas.drawLine(x1, y1, x2, y2, boneGlowPaint)
            canvas.drawLine(x1, y1, x2, y2, bonePaint)
        }
        jointPaint.color = jointColor
        val r = 2.5f * dp
        for (p in lm) {
            canvas.drawCircle(fx(p.x), fy(p.y), r, jointPaint)
        }
    }

    private fun drawPill(
        canvas: Canvas,
        text: String,
        cx: Float,
        cy: Float,
        bgColor: Int,
        borderColor: Int,
        viewWidth: Float
    ) {
        val tw = labelPaint.measureText(text)
        val padH = 9f * dp
        val h = 21f * dp
        val halfW = tw / 2f + padH
        val clampedCx = cx.coerceIn(halfW + 6f * dp, (viewWidth - halfW - 6f * dp).coerceAtLeast(halfW + 6f * dp))
        val top = (cy - h / 2f).coerceAtLeast(6f * dp)
        rect.set(clampedCx - halfW, top, clampedCx + halfW, top + h)

        pillBgPaint.color = bgColor
        pillStrokePaint.color = borderColor
        canvas.drawRoundRect(rect, h / 2f, h / 2f, pillBgPaint)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, pillStrokePaint)

        val fm = labelPaint.fontMetrics
        val baseline = rect.centerY() - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, rect.centerX() - tw / 2f, baseline, labelPaint)
    }

    companion object {
        private val HAND_BONES = arrayOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 4,       // thumb
            0 to 5, 5 to 6, 6 to 7, 7 to 8,       // index
            0 to 9, 9 to 10, 10 to 11, 11 to 12,  // middle
            0 to 13, 13 to 14, 14 to 15, 15 to 16,// ring
            0 to 17, 17 to 18, 18 to 19, 19 to 20,// little
            5 to 9, 9 to 13, 13 to 17             // palm arch
        )
    }
}
