package com.reach.app.haptic

/**
 * The one owner of the motors (spec §4). Pure and clock-driven, used on a single thread (the band's): [set] says which
 * cue REACH wants now, [tick] sends the pulses that are due and returns when it next needs to run.
 *
 * - Setting the cue that is already wanted does nothing: patterns repeat on their own interval, never per camera frame,
 *   and a repeating cue never restarts sooner than its interval after its last start (toggling cannot speed it up).
 * - A one-shot (HOLD, REACHED, TARGET_LOST, REACQUIRED) plays once per entry into its state, and not again within
 *   [ONE_SHOT_COOLDOWN_MS] of its last start, so a state flickering in and out cannot turn it into a buzz train.
 * - A cue of higher or equal priority pre-empts the running pattern at once. A lower-priority cue, or nothing, lets a
 *   running one-shot finish (it is short and bounded) but cuts a repeating pattern that is no longer wanted.
 * - Nothing starts while a sent pulse may still be running, and a different pattern only after [SWITCH_GAP_MS] of quiet,
 *   so two patterns never overlap or blur into one.
 * - [stop] (cancel, disconnect, Stop button, app in background) drops everything and sends STOP.
 */
class HapticPlayer(private val send: (String) -> Unit) {
    companion object {
        const val SWITCH_GAP_MS = 150L
        const val ONE_SHOT_COOLDOWN_MS = 1500L
    }

    /** What REACH wants now (null: nothing). */
    var desired: HapticCue? = null; private set
    /** The pattern that owns the motors until its last pulse ends; null when idle. A practice run has no cue. */
    var playing: HapticCue? = null; private set
    private var running = false
    private var consumed = false              // the one-shot for this entry of [desired] has been played
    private val due = ArrayDeque<Pair<Long, Pulse>>() // the running pattern's unsent pulses, at absolute times
    private var runEnd = 0L
    private var busyUntil = 0L                // a pulse already sent may run until then
    private var lastPattern: Any? = null      // the cue (or practice run) played last, for the switch gap
    private val lastStart = HashMap<HapticCue, Long>()
    private var nextRun = -1L                 // when [desired] (re)starts; -1: not scheduled

    fun set(cue: HapticCue?, now: Long) {
        if (cue == desired) return
        desired = cue; consumed = false
        val p = playing
        val finishFirst = running && p != null && p.oneShot && (cue == null || cue.priority > p.priority)
        if (running && !finishFirst) cancelRun(now)
        schedule(now)
    }

    /** Cancel everything now and ask the band to stop. A one-shot that was cut counts as played, so it is not replayed. */
    fun stop(now: Long) {
        if (desired?.oneShot == true) consumed = true else desired = null
        cancelRun(now, stop = false); nextRun = -1
        send(Pulse.STOP)
    }

    /** Practice (setup screen): plays [pulses] once, replacing anything running; live cues are not involved. */
    fun practice(pulses: List<Pulse>, now: Long) {
        desired = null; cancelRun(now)
        start(null, pulses, maxOf(now, busyUntil + SWITCH_GAP_MS))
    }

    /** Sends what is due. Returns the next time it must run, or null when there is nothing left to do. */
    fun tick(now: Long): Long? {
        if (running && due.isEmpty() && now >= runEnd) { running = false; playing = null; schedule(now) }
        val d = desired
        if (!running && d != null && nextRun in 0..now) start(d, d.pulses, now)
        due.firstOrNull()?.let { (at, pulse) ->
            if (at <= now) {
                due.removeFirst()
                val late = now - at // a late tick shifts the rest, so gaps never shrink into an overlap
                if (late > 0) { val rest = due.map { it.first + late to it.second }; due.clear(); due.addAll(rest); runEnd += late }
                send(pulse.payload); busyUntil = now + pulse.ms
            }
        }
        return listOfNotNull(due.firstOrNull()?.first, runEnd.takeIf { running }, nextRun.takeIf { !running && it >= 0 }).minOrNull()
    }

    private fun start(cue: HapticCue?, pulses: List<Pulse>, at: Long) {
        due.clear()
        var t = at
        for (pulse in pulses) { due.addLast(t to pulse); t += pulse.ms + pulse.gapAfterMs }
        running = true; playing = cue; runEnd = at + pulses.sumOf { it.ms + it.gapAfterMs } - pulses.last().gapAfterMs
        lastPattern = cue ?: pulses
        if (cue != null) { lastStart[cue] = at; if (cue.oneShot) consumed = true }
        nextRun = -1
    }

    private fun cancelRun(now: Long, stop: Boolean = true) {
        if (!running) return
        due.clear(); running = false; playing = null
        if (stop && now < busyUntil) send(Pulse.STOP) // a pulse may still be running: ask for it to end now
    }

    private fun schedule(now: Long) {
        val c = desired
        if (c == null || (c.oneShot && consumed)) { nextRun = -1; return }
        var at = maxOf(now, busyUntil, if (running) runEnd else 0L)
        if (lastPattern != null && c != lastPattern) at = maxOf(at, busyUntil + SWITCH_GAP_MS)
        lastStart[c]?.let { at = maxOf(at, it + if (c.oneShot) ONE_SHOT_COOLDOWN_MS else c.repeatMs) }
        nextRun = at
    }
}
