package com.reach.app.haptic

/**
 * REACH Haptic Language v1.0: every pulse, power, gap, repeat interval and near-zone band lives here, so the language
 * can be tuned without touching the tracking engine. These are prototype values from the spec, not validated with users.
 *
 * Wire format: the existing firmware's "MOTOR,POWER,DURATION_MS", one pulse per write. Gaps are timed on the phone
 * (HapticPlayer), so a firmware that blocks for a pulse can never block for a whole pattern.
 */
const val HAPTIC_LANGUAGE_VERSION = "1.0"

data class Pulse(val motor: Char, val power: Int, val ms: Int, val gapAfterMs: Int = 0) {
    init {
        require(motor == 'L' || motor == 'R' || motor == 'B') { "motor $motor" }
        require(power in 1..255) { "power $power" }
        require(ms in 1..MAX_PULSE_MS) { "duration $ms" }
        require(gapAfterMs in 0..MAX_GAP_MS) { "gap $gapAfterMs" }
    }

    val payload get() = "$motor,$power,$ms"

    companion object {
        /** HOLD's 400 ms is the longest pulse in v1.0; anything longer is a bug, not a pattern. */
        const val MAX_PULSE_MS = 400
        const val MAX_GAP_MS = 2000
        /** Both motors off: power 0 in the existing protocol (how the unpatched firmware treats it is unverified). */
        const val STOP = "B,0,0"
        /** The motor test: one conservative pulse on one side. */
        fun test(motor: Char) = Pulse(motor, 150, 300)
    }
}

private fun p(m: Char, power: Int, ms: Int, gap: Int = 0) = Pulse(m, power, ms, gap)

/**
 * The logical cues REACH can request. [priority] 1 = highest (spec §4). [repeatMs] 0 = played once per entry into the
 * state, otherwise repeated at that interval while the state lasts. REACQUIRED is not ranked by the spec; it sits with
 * TARGET_LOST (both report tracking status). The three NEAR entries are H4 at its three cadences.
 */
enum class HapticCue(val priority: Int, val repeatMs: Long, val pulses: List<Pulse>, val label: String, val meaning: String) {
    HOLD(1, 0, listOf(p('B', 180, 400)), "Hold still",
        "One long buzz on both sides. Pause and keep your hand still until I say to continue."),
    REACHED(2, 0, listOf(p('B', 200, 250, 120), p('B', 200, 250, 120), p('B', 200, 250)), "Object reached",
        "Three long buzzes on both sides. The task is complete."),
    TARGET_LOST(3, 0, listOf(p('B', 200, 70, 130), p('B', 200, 70, 130), p('B', 200, 70)), "Target lost",
        "Three short buzzes on both sides. I can no longer guide you safely to the object."),
    REACQUIRED(3, 0, listOf(p('B', 170, 100, 150), p('B', 170, 100)), "Found again",
        "Two buzzes on both sides. I can see the object again. Wait for directions before moving."),
    MOVE_LEFT(4, 1200, listOf(p('L', 180, 100, 100), p('L', 180, 100)), "Move left",
        "Two pulses on the left. Move your hand to the left."),
    MOVE_RIGHT(4, 1200, listOf(p('R', 180, 100, 100), p('R', 180, 100)), "Move right",
        "Two pulses on the right. Move your hand to the right."),
    FORWARD(5, 1500, listOf(p('L', 150, 100, 50), p('R', 150, 100, 50), p('L', 150, 100, 50), p('R', 150, 100)),
        "Reach forward", "Quick pulses alternating left, right, left, right. Your hand is lined up: reach forward slowly. " +
            "Only used when depth mode measures the distance."),
    NEAR_FAR(6, 800, listOf(p('B', 150, 80)), "Near",
        "Short taps on both sides that get faster as your hand gets closer. Only used when depth mode measures the distance."),
    NEAR_CLOSE(6, 400, listOf(p('B', 150, 80)), "Near, closer", NEAR_FAR.meaning),
    NEAR_VERY_CLOSE(6, 200, listOf(p('B', 150, 80)), "Near, very close", NEAR_FAR.meaning),
    SEARCH(7, 2500, listOf(p('L', 140, 120, 600), p('R', 140, 120)), "Searching",
        "A slow pulse on the left, then on the right, repeating. I'm looking for the object. It is not a direction.");

    val oneShot get() = repeatMs == 0L
    /** From the start of the first pulse to the end of the last. */
    val durationMs get() = pulses.sumOf { it.ms + it.gapAfterMs }.toLong()

    /** A practice run for the setup screen: once for one-shots, twice for repeating cues, H4 at all three cadences. */
    fun demo(): List<Pulse> = when (this) {
        NEAR_FAR, NEAR_CLOSE, NEAR_VERY_CLOSE -> listOf(NEAR_FAR to 3, NEAR_CLOSE to 3, NEAR_VERY_CLOSE to 4).flatMap { (c, n) ->
            List(n) { c.pulses.single().let { it.copy(gapAfterMs = (c.repeatMs - it.ms).toInt()) } }
        }
        else -> if (oneShot) pulses else pulses.dropLast(1) + pulses.last().copy(gapAfterMs = (repeatMs - durationMs).toInt()) + pulses
    }

    companion object {
        // Cadence bands on the smoothed forward gap (target Z - fingertip Z, metres), inside SpatialEngine's NEAR zone
        // (gap <= NEAR_M = 0.15) and its contact band. Starting values, not validated thresholds.
        const val NEAR_CLOSE_M = 0.10f
        const val NEAR_VERY_CLOSE_M = 0.05f
        fun near(gapM: Float) = when {
            gapM <= NEAR_VERY_CLOSE_M -> NEAR_VERY_CLOSE
            gapM <= NEAR_CLOSE_M -> NEAR_CLOSE
            else -> NEAR_FAR
        }
        /** What the setup screen teaches, in spec order (H1..H9); H4 once. */
        val taught = listOf(MOVE_LEFT, MOVE_RIGHT, FORWARD, NEAR_FAR, REACHED, HOLD, TARGET_LOST, SEARCH, REACQUIRED)
    }
}
