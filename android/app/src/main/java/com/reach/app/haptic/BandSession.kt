package com.reach.app.haptic

/** The band's connection as the user is told it. Only READY means the REACH service was found and writes can go out. */
enum class BandState(val words: String) {
    NO_BLUETOOTH("This phone has no Bluetooth LE"),
    BLUETOOTH_OFF("Bluetooth is off"),
    NEEDS_PERMISSION("Bluetooth permission needed"),
    DISCONNECTED("Not connected"),
    SCANNING("Scanning for the band"),
    FOUND("Band found"),
    CONNECTING("Connecting"),
    READY("Connected and ready"),
    FAILED("Connection failed")
}

/**
 * The band link's state and write queue without Android types (JVM-tested). BleBand feeds it GATT events on its one
 * thread and performs the writes it starts through [write] (true: a GATT write was started).
 *
 * - One write in flight at a time: the next starts only once the last one completed, failed or timed out.
 * - STOP jumps the queue and drops everything queued. A queued pulse older than [STALE_MS] is dropped, never played late.
 * - The first write after every (re)connect is STOP.
 * - A disconnect drops the queue and calls [onLost] (cancel guidance) after the state stops being READY.
 * - [motors] says "off" only when the band itself reports it on TX ("OFF", from the patched firmware). A STOP that was
 *   only queued or written is "stop sent".
 */
class BandSession(private val write: (String) -> Boolean, private val onLost: () -> Unit = {}) {
    companion object {
        const val WRITE_TIMEOUT_MS = 1500L
        const val STALE_MS = 300L
        const val MAX_RECONNECTS = 3
        const val RECONNECT_DELAY_MS = 2000L
    }

    @Volatile var state = BandState.DISCONNECTED; private set
    @Volatile var detail = ""; private set
    @Volatile var motors = "unknown"; private set
    @Volatile var lastFromBand: String? = null; private set
    var written = 0; private set
    var failed = 0; private set
    private val queue = ArrayDeque<Pair<String, Long>>()
    private var inFlight: String? = null
    private var inFlightAt = 0L
    private var reconnects = 0

    /** Scanning / found / connecting / failed / Bluetooth problems, as BleBand sees them. */
    fun status(s: BandState, why: String = "") {
        val was = state
        state = s; detail = why
        if (was == BandState.READY && s != BandState.READY) drop()
    }

    fun onReady(now: Long) {
        state = BandState.READY; detail = ""; reconnects = 0
        queue.clear(); inFlight = null
        send(Pulse.STOP, now) // whatever the band was doing before the link dropped, it starts from off
    }

    /** Queues [payload]; false (dropped) unless READY. */
    fun send(payload: String, now: Long): Boolean {
        if (state != BandState.READY) return false
        if (payload == Pulse.STOP) { queue.clear(); queue.addFirst(payload to now) } else queue.addLast(payload to now)
        pump(now)
        return true
    }

    fun onWritten(ok: Boolean, now: Long) {
        val p = inFlight ?: return
        inFlight = null
        if (ok) { written++; motors = if (p == Pulse.STOP) "stop sent" else "pulse sent" } else failed++
        pump(now)
    }

    /** True when a write has had no answer for [WRITE_TIMEOUT_MS]: the link is stuck and must be dropped. */
    fun writeTimedOut(now: Long) = inFlight != null && now - inFlightAt > WRITE_TIMEOUT_MS

    /** The link went down. Returns the delay before a reconnect attempt, or null to stay down ([wanted] false, or out of attempts). */
    fun onDisconnected(wanted: Boolean, why: String): Long? {
        val (s, d, delay) = when {
            !wanted -> Triple(BandState.DISCONNECTED, why, null)
            reconnects >= MAX_RECONNECTS -> Triple(BandState.FAILED, "connection lost: $why", null)
            else -> { reconnects++; Triple(BandState.CONNECTING, "reconnecting ($reconnects of $MAX_RECONNECTS) after: $why", RECONNECT_DELAY_MS) }
        }
        state = s; detail = d // not READY any more before guidance is cancelled, so the cancel's STOP is not written
        drop()
        return delay
    }

    /** The user asked to connect again: a fresh set of reconnect attempts. */
    fun resetAttempts() { reconnects = 0 }

    fun onNotify(text: String) {
        lastFromBand = text
        if (text == "OFF") motors = "off (reported by band)"
    }

    private fun drop() {
        queue.clear(); inFlight = null; motors = "unknown"
        onLost()
    }

    private fun pump(now: Long) {
        while (inFlight == null && state == BandState.READY) {
            val (p, at) = queue.removeFirstOrNull() ?: return
            if (p != Pulse.STOP && now - at > STALE_MS) { failed++; continue }
            inFlight = p; inFlightAt = now
            if (!write(p)) { inFlight = null; failed++ }
        }
    }
}
