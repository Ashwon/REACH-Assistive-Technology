package com.reach.app.haptic

/** WELCOME is the REACH introduction; every launch of REACH starts there (see [BandSetup.showIntro]). */
enum class SetupStep { WELCOME, EXPLAIN, CONNECT, LEFT, RIGHT, LEARN, CONFIRM }

/** Where the startup introduction stands in this app process (not persisted: a new process starts NOT_STARTED). */
enum class IntroState { NOT_STARTED, OPEN, DONE }

/** The user's answer after a motor test pulse. Only the user can say a motor was felt; a BLE write cannot. */
enum class Felt { FELT, NOT_FELT, OTHER_SIDE }

/**
 * The vibration band setup (spec §6) as a pure state machine; BandSetupActivity only renders it. JVM-tested.
 *
 * Persisted through [prefs] (the app's SharedPreferences): "done" or "skipped", the band that passed the motor test, and
 * the haptic language version that was taught. Live guidance uses the band only while the connected band is the one
 * that passed ([liveAllowed]); a motor reported as not felt or felt on the other side revokes that at once. A stored
 * completion is never proof the motors work today, so the status line always shows the live link state.
 */
class BandSetup(private val prefs: Prefs, start: SetupStep = SetupStep.WELCOME) {
    interface Prefs { fun get(key: String): String?; fun put(key: String, value: String?) }

    companion object {
        const val KEY_STATE = "band_setup"   // "done" | "skipped" | absent
        const val KEY_ADDRESS = "band_address"
        const val KEY_LANGUAGE = "band_language"

        /**
         * Whether a newly created main screen opens the introduction: on every fresh launch (no saved state), and after the
         * process was terminated (NOT_STARTED even when Android restores the screen), but never while it is already open,
         * and not when an existing screen of this live process is only being recreated. Never from a stored preference.
         */
        fun showIntro(state: IntroState, restored: Boolean) = state != IntroState.OPEN && (!restored || state == IntroState.NOT_STARTED)

        /** Whether a stored setup still needs teaching (used to tell a settings "review" from a first setup). */
        fun startStep(prefs: Prefs): SetupStep? = when (prefs.get(KEY_STATE)) {
            null -> SetupStep.EXPLAIN
            "done" -> if (prefs.get(KEY_LANGUAGE) != HAPTIC_LANGUAGE_VERSION) SetupStep.LEARN else null
            else -> null
        }

        fun liveAllowed(prefs: Prefs, connectedAddress: String?) = prefs.get(KEY_STATE) == "done" &&
            prefs.get(KEY_LANGUAGE) == HAPTIC_LANGUAGE_VERSION && connectedAddress != null && connectedAddress == prefs.get(KEY_ADDRESS)

        /** Why live guidance does not use the band, in the chip's words; null when it does. */
        fun offReason(prefs: Prefs, state: BandState, connectedAddress: String?): String? = when {
            prefs.get(KEY_STATE) == "skipped" -> "skipped"
            prefs.get(KEY_STATE) == null -> "not set up"
            state != BandState.READY -> state.words.lowercase()
            !liveAllowed(prefs, connectedAddress) -> "motor test needed"
            else -> null
        }
    }

    var step = start; private set
    /** This run's answers per motor ('L', 'R'). */
    val felt = HashMap<Char, Felt>()
    /** How many times each motor's test pulse was played (first play and retries). */
    val plays = HashMap<Char, Int>()

    val motor get() = when (step) { SetupStep.LEFT -> 'L'; SetupStep.RIGHT -> 'R'; else -> null }

    fun played(m: Char) { plays[m] = (plays[m] ?: 0) + 1 }

    fun answer(m: Char, f: Felt) {
        felt[m] = f
        if (f != Felt.FELT) prefs.put(KEY_ADDRESS, null) // a motor that failed its test is not used for live guidance
    }

    val bothFelt get() = felt['L'] == Felt.FELT && felt['R'] == Felt.FELT

    fun canContinue(connected: Boolean) = when (step) {
        SetupStep.WELCOME, SetupStep.EXPLAIN, SetupStep.LEARN -> true
        SetupStep.CONNECT -> connected
        SetupStep.LEFT, SetupStep.RIGHT -> connected && felt[motor!!] != null && plays[motor!!] != null
        SetupStep.CONFIRM -> connected && bothFelt
    }

    /** Moves on when allowed; false when this step still needs something (a connection, an answer). */
    fun next(connected: Boolean): Boolean {
        if (!canContinue(connected) || step == SetupStep.CONFIRM) return false
        step = SetupStep.values()[step.ordinal + 1]
        return true
    }

    /** Back: the previous step, never past the first. Answers already given are kept. */
    fun back(): Boolean {
        if (step.ordinal == 0) return false
        step = SetupStep.values()[step.ordinal - 1]
        return true
    }

    /** This run's progress as one string, so recreating the screen neither skips nor restarts a step. */
    fun save() = step.name + "|" + felt.entries.joinToString(",") { "${it.key}=${it.value}" } + "|" +
        plays.entries.joinToString(",") { "${it.key}=${it.value}" }

    fun restore(saved: String) {
        val (s, f, p) = saved.split("|").let { Triple(it[0], it.getOrElse(1) { "" }, it.getOrElse(2) { "" }) }
        step = SetupStep.valueOf(s)
        felt.clear(); plays.clear()
        f.split(",").filter { it.isNotEmpty() }.forEach { val (m, v) = it.split("="); felt[m[0]] = Felt.valueOf(v) }
        p.split(",").filter { it.isNotEmpty() }.forEach { val (m, v) = it.split("="); plays[m[0]] = v.toInt() }
    }

    /** "Test again": back to the left motor with fresh answers. */
    fun testAgain() { felt.clear(); plays.clear(); step = SetupStep.LEFT }

    /** "Continue to REACH": the only way setup is marked done, and only with both motors confirmed on this band. */
    fun complete(connectedAddress: String?): Boolean {
        if (step != SetupStep.CONFIRM || !bothFelt || connectedAddress == null) return false
        prefs.put(KEY_STATE, "done"); prefs.put(KEY_ADDRESS, connectedAddress); prefs.put(KEY_LANGUAGE, HAPTIC_LANGUAGE_VERSION)
        return true
    }

    /** "Skip haptics for now": REACH runs in its voice-only mode and the band is not used. */
    fun skip() { prefs.put(KEY_STATE, "skipped"); prefs.put(KEY_ADDRESS, null) }

    /** Plain-language summary of this run's answers, for the confirmation step. */
    fun summary(): String {
        fun one(m: Char, side: String) = "$side motor: " + when (felt[m]) {
            Felt.FELT -> "felt."
            Felt.NOT_FELT -> "not felt."
            Felt.OTHER_SIDE -> "felt on the wrong side. The motors may be swapped; REACH will not swap them for you."
            null -> "not tested."
        }
        return one('L', "Left") + " " + one('R', "Right") + " " + if (bothFelt) "Both motors passed."
            else "Haptic guidance stays off until both motors pass. Check the band's position and test again, or skip haptics for now."
    }
}
