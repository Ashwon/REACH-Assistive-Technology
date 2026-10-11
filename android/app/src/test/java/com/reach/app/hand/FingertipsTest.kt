package com.reach.app.hand

import org.junit.Assert.*
import org.junit.Test

class FingertipsTest {
    // 21 landmarks where landmark i sits at x = i/100, so each tip's source index is readable from x.
    private val hand = List(21) { Lm(it / 100f, 0.5f, -it / 1000f) }

    @Test fun extractsTheFiveTipsFromTheMediaPipeIndices() {
        val r = HandResult.fromLandmarks(hand, score = 0.9f)
        assertTrue(r.present); assertEquals(0.9f, r.score, 0f)
        assertEquals(Finger.values().toList(), r.tips.map { it.finger })
        assertEquals(listOf(4, 8, 12, 16, 20), r.tips.map { Math.round(it.x * 100) })
        assertEquals(-0.008f, r.tip(Finger.INDEX)!!.z, 1e-6f)
    }

    @Test fun indexStaysTheGuidancePoint() {
        assertEquals(Finger.INDEX, HandResult.fromLandmarks(hand, 1f).index!!.finger)
    }

    @Test fun handSizeIsWristToMiddleMcp() {
        val lm = hand.toMutableList(); lm[0] = Lm(0.2f, 0.8f); lm[9] = Lm(0.5f, 0.4f)
        assertEquals(0.5f, HandResult.fromLandmarks(lm, 1f).handSize, 1e-6f)
    }

    @Test fun farAwayHandIsNotTheWearers() {
        val lm = hand.toMutableList(); lm[0] = Lm(0.50f, 0.50f)
        lm[9] = Lm(0.50f, 0.46f); assertFalse(HandResult.fromLandmarks(lm, 1f).likelyWearer) // 0.04: bystander-sized
        lm[9] = Lm(0.50f, 0.40f); assertTrue(HandResult.fromLandmarks(lm, 1f).likelyWearer)  // 0.10: arm's reach
        assertFalse(HandResult.NONE.likelyWearer)
    }

    @Test fun missingOrPartialHandIsNotPresent() {
        assertFalse(HandResult.fromLandmarks(emptyList(), 0f).present)
        assertFalse(HandResult.fromLandmarks(hand.take(20), 1f).present)
        assertNull(HandResult.NONE.index); assertTrue(HandResult.NONE.tips.isEmpty())
    }

    @Test fun visibilityIsPassedThroughOrLeftUnknown() {
        val lm = hand.toMutableList(); lm[4] = Lm(0.1f, 0.1f, 0f, visibility = 0.7f, presence = 0.6f)
        val r = HandResult.fromLandmarks(lm, 1f)
        assertEquals(0.7f, r.tip(Finger.THUMB)!!.visibility!!, 0f); assertEquals(0.6f, r.tip(Finger.THUMB)!!.presence!!, 0f)
        assertNull(r.tip(Finger.LITTLE)!!.visibility)
    }

    @Test fun extrapolatedOffFrameTipsAreFlagged() {
        val lm = hand.toMutableList(); lm[20] = Lm(1.08f, 0.5f); lm[4] = Lm(0.5f, -0.01f)
        val r = HandResult.fromLandmarks(lm, 1f)
        assertFalse(r.tip(Finger.LITTLE)!!.inFrame); assertFalse(r.tip(Finger.THUMB)!!.inFrame)
        assertTrue(r.tip(Finger.INDEX)!!.inFrame)
    }
}
