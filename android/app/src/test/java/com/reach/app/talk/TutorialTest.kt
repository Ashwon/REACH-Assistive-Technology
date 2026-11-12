package com.reach.app.talk

import org.junit.Assert.*
import org.junit.Test

class TutorialTest {
    @Test fun saysOnlyWhatIsTrueNow() {
        val off = Tutorial.text(hapticsConnected = false, depthMode = false, bothHandsEnrolled = false)
        assertFalse(off, "vibration means" in off)
        assertTrue(off, "isn't connected" in off && "once you've shown me each one" in off)
        val on = Tutorial.text(hapticsConnected = true, depthMode = true, bothHandsEnrolled = true)
        assertEquals("Welcome to REACH. I can help you locate objects. I can find a bottle, laptop, book, backpack, handbag, or cup. Your left and right hands are tracked separately. " +
            "The vibration band is on. Two pulses on the left mean move left; two on the right mean move right. " +
            "One long buzz means hold still. Three long buzzes mean you reached the object. " +
            "Alternating pulses mean reach forward, and taps that speed up mean you are close. " +
            "Settings has every pattern, under review haptic instructions. " +
            "If tracking becomes uncertain, I will pause rather than guess. You can ask me conversationally to find an object.", on)
        assertFalse(on, "closer to the object" in on) // the motors no longer compare the hands
        val twoD = Tutorial.text(hapticsConnected = true, depthMode = false, bothHandsEnrolled = true)
        assertTrue(twoD, "need depth mode" in twoD && "Alternating pulses mean" !in twoD) // no promise 2D cannot keep
    }
}
