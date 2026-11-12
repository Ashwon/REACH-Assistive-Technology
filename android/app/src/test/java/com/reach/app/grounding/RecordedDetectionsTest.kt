package com.reach.app.grounding

import com.reach.app.claude.ClaudeClient
import org.junit.Assert.*
import org.junit.Test

/**
 * Real EfficientDet-Lite0 output, recorded by running the app's model on the Galaxy A15 over captured frames
 * (DebugFrames replay), fed through the same admission / tracking / evidence code as LocalDetectorGrounder.
 * These test what the app DOES with real model output. They say nothing about how accurate the model is
 * in general: that needs a labelled set of many scenes (see README "Model limitations").
 */
class RecordedDetectionsTest {
    private data class Frame(val ms: Long, val dets: List<Det>)

    private fun load(name: String): List<Frame> =
        javaClass.getResource("/recorded/$name")!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .groupBy({ it.substringBefore(' ').toLong() }) { line ->
                val p = line.split(' ', limit = 7)
                if (p[1] == "-") null else Det(p[6], p[1].toFloat(), Box(p[2].toFloat(), p[3].toFloat(), p[4].toFloat(), p[5].toFloat()))
            }
            .map { (ms, ds) -> Frame(ms, ds.filterNotNull()) }.sortedBy { it.ms }

    /** LocalDetectorGrounder.ground() minus the pixels: admission, tracking, evidence, supported classes only. */
    private fun replay(frames: List<Frame>): List<List<Candidate>> {
        val tracker = CandidateTracker()
        return frames.map { f ->
            val kept = f.dets.filter { rejectReason(it.score, it.box) == null }
            val ids = tracker.assign(kept, f.ms)
            kept.indices.filter { kept[it].label in SupportedClasses.DETECTED }.map { i ->
                val ev = tracker.evidence(ids[i])!!
                Candidate(ids[i], kept[i].label, "unknown", kept[i].box, ev.score, null, ev.confirmed, ev.conflict)
            }
        }
    }

    @Test fun darkAndUnderexposedFramesProduceNoCandidate() {
        val frames = load("dark_synthetic_a15.txt")
        assertEquals(40, frames.size)
        // The model does emit junk here (laptop 0.21, tv 0.18, clock 0.28 ...), all whole-frame and below threshold.
        assertTrue(frames.any { f -> f.dets.any { it.label == "laptop" } })
        assertTrue(replay(frames).all { it.isEmpty() })
    }

    @Test fun personLyingWithABlackLaptopIsNotOfferedAndTheLaptopIsNotCalledABag() {
        val frames = load("person_laptop_floor_a15.txt")
        assertEquals(30, frames.size)
        val out = replay(frames)
        // The black laptop lid is "backpack" in every frame (0.22..0.30, below threshold) and never "laptop":
        // a real model miss. The app neither offers a backpack nor invents a laptop.
        assertTrue(frames.all { f -> f.dets.any { it.label == "backpack" && it.score < MIN_SCORE } })
        assertTrue(frames.none { f -> f.dets.any { it.label == "laptop" && it.score >= MIN_SCORE } })
        assertTrue(out.flatten().none { it.label in setOf("backpack", "handbag", "laptop") })
        // The lying person is "bed" (0.50) or "person" (0.50): neither is a class REACH offers as a target.
        assertTrue(frames.any { f -> f.dets.any { it.label == "person" && it.score >= MIN_SCORE } })
        assertTrue(out.flatten().none { it.label == "person" || it.label == "bed" })
        // "find my bag" / "find my laptop" say they can't see one; "find the person" says people are not targets.
        assertTrue(ClaudeClient().interpretLocal("find my bag", out.last()).needsClarification)
        assertTrue(ClaudeClient().interpretLocal("find my laptop", out.last()).needsClarification)
        assertEquals("I only help find objects, not people.", ClaudeClient().interpretLocal("find the person", out.last()).question)
    }
}
