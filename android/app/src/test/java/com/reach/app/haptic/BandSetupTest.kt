package com.reach.app.haptic

import com.reach.app.haptic.SetupStep.*
import org.junit.Assert.*
import org.junit.Test

/** The band setup's steps, answers and persistence (spec §6), with an in-memory stand-in for SharedPreferences. */
class BandSetupTest {
    private val store = HashMap<String, String>()
    private val prefs = object : BandSetup.Prefs {
        override fun get(key: String) = store[key]
        override fun put(key: String, value: String?) { if (value == null) store.remove(key) else store[key] = value }
    }
    private val band = "AA:BB:CC:DD:EE:FF"

    /** Plays and answers both motor tests as felt, from the LEFT step. */
    private fun passMotors(s: BandSetup) {
        s.played('L'); s.answer('L', Felt.FELT); assertTrue(s.next(true))
        s.played('R'); s.answer('R', Felt.FELT); assertTrue(s.next(true))
    }

    @Test fun everyLaunchStartsAtTheIntroductionWhateverIsStored() {
        for (stored in listOf(null, "done", "skipped")) {
            if (stored == null) store.remove(BandSetup.KEY_STATE) else store[BandSetup.KEY_STATE] = stored
            assertTrue("$stored", BandSetup.showIntro(IntroState.NOT_STARTED, restored = false)) // launcher launch
            assertTrue("$stored", BandSetup.showIntro(IntroState.DONE, restored = false))        // new screen, same process
            assertTrue("$stored", BandSetup.showIntro(IntroState.NOT_STARTED, restored = true))  // process was terminated
        }
        assertEquals(WELCOME, BandSetup(prefs).step)
    }

    @Test fun recreatingAScreenInTheSameProcessNeitherRestartsNorDuplicatesTheIntroduction() {
        assertFalse(BandSetup.showIntro(IntroState.DONE, restored = true))   // e.g. rotation after the introduction
        assertFalse(BandSetup.showIntro(IntroState.OPEN, restored = true))   // the introduction is on top already
        assertFalse(BandSetup.showIntro(IntroState.OPEN, restored = false))
    }

    @Test fun nothingIsStoredUntilTheUserChooses() {
        val s = BandSetup(prefs)
        assertTrue(s.next(false)); assertEquals(EXPLAIN, s.step)
        assertTrue(s.next(false)); assertEquals(CONNECT, s.step)
        assertTrue(store.isEmpty())
    }

    @Test fun backGoesOneStepBackAndNeverPastTheFirst() {
        val s = BandSetup(prefs, LEFT)
        s.played('L'); s.answer('L', Felt.FELT)
        assertTrue(s.back()); assertEquals(CONNECT, s.step)
        assertEquals(Felt.FELT, s.felt['L'])               // answers are kept
        repeat(5) { s.back() }
        assertEquals(WELCOME, s.step); assertFalse(s.back())
    }

    @Test fun progressSurvivesRecreation() {
        val s = BandSetup(prefs, LEFT)
        s.played('L'); s.played('L'); s.answer('L', Felt.OTHER_SIDE); s.next(true)
        val again = BandSetup(prefs, WELCOME).apply { restore(s.save()) }
        assertEquals(RIGHT, again.step); assertEquals(Felt.OTHER_SIDE, again.felt['L']); assertEquals(2, again.plays['L'])
        val fresh = BandSetup(prefs).apply { restore(BandSetup(prefs).save()) }
        assertEquals(WELCOME, fresh.step); assertTrue(fresh.felt.isEmpty())
    }

    @Test fun voiceOnlyIsOfferedAfterTheIntroductionAndKeepsReachUsable() {
        val s = BandSetup(prefs)
        assertEquals(WELCOME, s.step); s.next(false)       // the introduction is always shown first
        s.skip()                                           // "Continue with voice only"
        assertFalse(BandSetup.liveAllowed(prefs, band))
        assertTrue(BandSetup.showIntro(IntroState.NOT_STARTED, restored = false)) // still introduced on the next launch
    }

    @Test fun theMotorTestStartsOnlyAfterAConnection() {
        val s = BandSetup(prefs, CONNECT)
        assertFalse(s.next(connected = false)); assertEquals(CONNECT, s.step)
        assertTrue(s.next(connected = true)); assertEquals(LEFT, s.step)
    }

    @Test fun aMotorNeedsAnExplicitAnswerNotJustASuccessfulWrite() {
        val s = BandSetup(prefs, LEFT)
        s.played('L')                                     // the pulse was written fine...
        assertFalse(s.next(true)); assertEquals(LEFT, s.step) // ...but the user has not said they felt it
        s.played('L'); assertEquals(2, s.plays['L'])      // "Play it again"
        s.answer('L', Felt.FELT); assertTrue(s.next(true)); assertEquals(RIGHT, s.step)
    }

    @Test fun aMotorNotFeltOrOnTheWrongSideBlocksCompletionAndIsNeverSwapped() {
        val s = BandSetup(prefs, LEFT)
        s.played('L'); s.answer('L', Felt.OTHER_SIDE); assertTrue(s.next(true)) // Continue is allowed...
        s.played('R'); s.answer('R', Felt.FELT); s.next(true); s.next(true)
        assertEquals(CONFIRM, s.step)
        assertFalse(s.canContinue(true)); assertFalse(s.complete(band)) // ...but "Continue to REACH" is not
        assertTrue(s.summary(), "wrong side" in s.summary() && "will not swap" in s.summary())
        assertNull(store[BandSetup.KEY_STATE])
        s.testAgain(); assertEquals(LEFT, s.step); assertTrue(s.felt.isEmpty())
        passMotors(s); s.next(true)
        assertTrue(s.complete(band))
    }

    @Test fun completingStoresTheTestedBandAndLiveGuidanceUsesOnlyThatBand() {
        val s = BandSetup(prefs, LEFT); passMotors(s); assertTrue(s.next(true)); assertEquals(CONFIRM, s.step)
        assertFalse(s.complete(null))                     // not connected now: cannot complete
        assertTrue(s.complete(band))
        assertEquals("done", store[BandSetup.KEY_STATE]); assertEquals(HAPTIC_LANGUAGE_VERSION, store[BandSetup.KEY_LANGUAGE])
        assertNull(BandSetup.startStep(prefs))            // a returning user is not forced through the tutorial
        assertTrue(BandSetup.liveAllowed(prefs, band))
        assertFalse(BandSetup.liveAllowed(prefs, null))   // a past setup is not proof the band works now
        assertFalse(BandSetup.liveAllowed(prefs, "11:22:33:44:55:66")) // a different band needs its own motor test
        assertEquals("motor test needed", BandSetup.offReason(prefs, BandState.READY, "11:22:33:44:55:66"))
        assertEquals("not connected", BandSetup.offReason(prefs, BandState.DISCONNECTED, band))
        assertNull(BandSetup.offReason(prefs, BandState.READY, band))
    }

    @Test fun aLaterFailedSelfTestTurnsLiveHapticsOffAtOnce() {
        val s = BandSetup(prefs, LEFT); passMotors(s); s.next(true); s.complete(band)
        val again = BandSetup(prefs, LEFT)                // "Motor self-test" from settings
        again.played('L'); again.answer('L', Felt.NOT_FELT)
        assertFalse(BandSetup.liveAllowed(prefs, band))   // even if the user backs out now
    }

    @Test fun skippingKeepsReachUsableWithoutTheBand() {
        val s = BandSetup(prefs)
        s.skip()
        assertEquals("skipped", store[BandSetup.KEY_STATE])
        assertNull(BandSetup.startStep(prefs))            // not asked again on every launch
        assertFalse(BandSetup.liveAllowed(prefs, band))   // and the band is never used
        assertEquals("skipped", BandSetup.offReason(prefs, BandState.READY, band))
    }

    @Test fun aNewHapticLanguageIsTaughtAgain() {
        store[BandSetup.KEY_STATE] = "done"; store[BandSetup.KEY_ADDRESS] = band; store[BandSetup.KEY_LANGUAGE] = "0.9"
        assertEquals(LEARN, BandSetup.startStep(prefs))
        assertFalse(BandSetup.liveAllowed(prefs, band))
    }

    @Test fun theStepsRunInTheSpecOrder() {
        val s = BandSetup(prefs)
        val seen = mutableListOf(s.step)
        s.next(true); seen += s.step; s.next(true); seen += s.step; s.next(true); seen += s.step
        passMotors(s); seen += RIGHT; seen += s.step; s.next(true); seen += s.step
        assertEquals(listOf(WELCOME, EXPLAIN, CONNECT, LEFT, RIGHT, LEARN, CONFIRM), seen)
        assertFalse(s.next(true))                         // the last step ends only by an explicit choice
    }
}
