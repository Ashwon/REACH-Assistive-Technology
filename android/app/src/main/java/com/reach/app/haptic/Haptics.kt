package com.reach.app.haptic

/**
 * Which of the user's two enrolled hands is reliably closer to the target, from 3D distances only. Internal: it picks
 * the hand voice guidance follows and is shown in diagnostics. It is NOT a motor command: since Haptic Language v1.0
 * the motors say where to move the guided hand (HapticCue), which is a different question from which hand is closer.
 */
enum class HapticCommand { RIGHT_HAND_CLOSER, LEFT_HAND_CLOSER, EQUAL_OR_UNCERTAIN, STOP, REACHED, TRACKING_LOST }

/** Where live guidance cues go (BleBand). [connected]: the band's link is ready and live cues may drive it. */
interface HapticLink {
    val connected: Boolean
    val status: String
    /** The cue live guidance wants now, every camera frame (null: none). Duplicates are free; HapticPlayer arbitrates. */
    fun request(cue: HapticCue?)
    /** Cancel everything now and ask the band to stop both motors. */
    fun stop()
}

/**
 * Turns per-frame hand distances into a hand comparison. Pure, JVM-tested; ReachCore owns one under its lock.
 *
 * Distances are 3D fingertip-to-target distances in metres in one camera frame (depth mode), or null when that hand
 * has no valid measurement this frame; 2D pixel distances must never be passed in. Each hand's distance is smoothed
 * (EMA); a hand becomes "closer" only when it is ahead by more than the margin — max([minMarginM], [relMargin] x the
 * farther distance, covering the fingertip-depth estimate's error) — for [holdMs]; it stays selected until the lead
 * falls below half the margin (hysteresis). One hand unknown -> EQUAL_OR_UNCERTAIN: an unseen hand is never "farther".
 * Commands are emitted only when they change (no repeated transmissions).
 */
class HapticPolicy(
    private val minMarginM: Float = 0.05f,
    private val relMargin: Float = 0.10f,
    private val holdMs: Long = 300,
    private val alpha: Float = 0.4f
) {
    var current = HapticCommand.STOP; private set
    private var l = Float.NaN; private var r = Float.NaN
    private var pending: HapticCommand? = null; private var pendingSince = 0L

    /** Forces a terminal / safety command (STOP, REACHED, TRACKING_LOST, EQUAL_OR_UNCERTAIN). Returns it if new. */
    fun force(cmd: HapticCommand): HapticCommand? {
        l = Float.NaN; r = Float.NaN; pending = null
        return set(cmd)
    }

    /** While guiding: compare the hands. Returns the new command, or null when unchanged. */
    fun compare(leftM: Float?, rightM: Float?, nowMs: Long): HapticCommand? {
        l = smooth(l, leftM); r = smooth(r, rightM)
        if (l.isNaN() || r.isNaN()) { pending = null; return set(HapticCommand.EQUAL_OR_UNCERTAIN) }
        val margin = maxOf(minMarginM, relMargin * maxOf(l, r))
        val lead = r - l // > 0: the left fingertip is nearer the target
        val want = when {
            current == HapticCommand.LEFT_HAND_CLOSER && lead > margin / 2 -> HapticCommand.LEFT_HAND_CLOSER
            current == HapticCommand.RIGHT_HAND_CLOSER && -lead > margin / 2 -> HapticCommand.RIGHT_HAND_CLOSER
            lead > margin -> HapticCommand.LEFT_HAND_CLOSER
            -lead > margin -> HapticCommand.RIGHT_HAND_CLOSER
            else -> HapticCommand.EQUAL_OR_UNCERTAIN
        }
        if (want == current) { pending = null; return null }
        if (want == HapticCommand.EQUAL_OR_UNCERTAIN) { pending = null; return set(want) } // dropping a side is never delayed
        if (pending != want) { pending = want; pendingSince = nowMs }
        return if (nowMs - pendingSince >= holdMs) { pending = null; set(want) } else null
    }

    private fun smooth(prev: Float, v: Float?): Float =
        if (v == null || !v.isFinite() || v <= 0f) Float.NaN else if (prev.isNaN()) v else prev + alpha * (v - prev)

    private fun set(cmd: HapticCommand): HapticCommand? = if (cmd == current) null else { current = cmd; cmd }
}
