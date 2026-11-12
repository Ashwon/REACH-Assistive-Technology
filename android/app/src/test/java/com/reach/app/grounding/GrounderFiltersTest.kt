package com.reach.app.grounding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrounderFiltersTest {

    private fun squareScores(vararg overrides: Pair<Int, Float>): FloatArray {
        val scores = FloatArray(GrounderFilters.YOLO_COCO_LABELS.size)
        for ((idx, v) in overrides) scores[idx] = v
        return scores
    }

    @Test
    fun rectangularRivalsSuppressAmbiguousLaptopAtAnchorLevel() {
        // Closed book or notebook where book slightly trails or leads weak laptop logit
        assertTrue(
            GrounderFilters.isAnchorSuppressed(
                GrounderFilters.CLASS_LAPTOP,
                0.42f,
                squareScores(GrounderFilters.CLASS_LAPTOP to 0.42f, 73 to 0.35f) // book
            )
        )
        // Monitor / TV stronger or close to weak laptop
        assertTrue(
            GrounderFilters.isAnchorSuppressed(
                GrounderFilters.CLASS_LAPTOP,
                0.50f,
                squareScores(GrounderFilters.CLASS_LAPTOP to 0.50f, 62 to 0.48f) // tv
            )
        )
        // Clear genuine laptop with weak background noise is kept
        assertFalse(
            GrounderFilters.isAnchorSuppressed(
                GrounderFilters.CLASS_LAPTOP,
                0.78f,
                squareScores(GrounderFilters.CLASS_LAPTOP to 0.78f, 60 to 0.35f) // dining table below
            )
        )
    }

    @Test
    fun tvBookAndCellPhoneNeverBecomeLaptopEvenIfAllowed() {
        val rawTargets = listOf(
            RawDetection("tv", Box(0.2f, 0.2f, 0.7f, 0.6f), 0.85f),
            RawDetection("keyboard", Box(0.2f, 0.5f, 0.7f, 0.7f), 0.82f),
            RawDetection("book", Box(0.2f, 0.2f, 0.5f, 0.5f), 0.90f),
            RawDetection("cell phone", Box(0.4f, 0.4f, 0.5f, 0.6f), 0.88f)
        )
        val (kept, _) = GrounderFilters.filterAndMap(
            rawTargets = rawTargets,
            rawRivals = rawTargets,
            allowed = setOf("bottle", "laptop"),
            frameWidth = 640,
            frameHeight = 480
        )
        assertTrue("Expected zero laptops from non-laptop COCO classes, got $kept", kept.isEmpty())
    }

    @Test
    fun postNmsRivalBookOrPhoneOrMonitorSuppressesOverlappingLaptop() {
        val box = Box(0.25f, 0.25f, 0.60f, 0.55f)
        val (keptBook, diagBook) = GrounderFilters.filterAndMap(
            rawTargets = listOf(RawDetection("laptop", box, 0.44f)),
            rawRivals = listOf(RawDetection("book", Box(0.26f, 0.26f, 0.59f, 0.54f), 0.39f)),
            allowed = setOf("laptop"),
            frameWidth = 640,
            frameHeight = 480
        )
        assertTrue("Book should suppress ambiguous laptop: $diagBook", keptBook.isEmpty())

        val (keptTv, _) = GrounderFilters.filterAndMap(
            rawTargets = listOf(RawDetection("laptop", box, 0.52f)),
            rawRivals = listOf(RawDetection("tv", Box(0.24f, 0.24f, 0.62f, 0.56f), 0.45f)),
            allowed = setOf("laptop"),
            frameWidth = 640,
            frameHeight = 480
        )
        assertTrue("Monitor/TV should suppress competing laptop box", keptTv.isEmpty())
    }

    @Test
    fun internalKeyboardSupportsRealLaptopAndDiningTableDoesNotSuppressIt() {
        val laptopBox = Box(0.20f, 0.20f, 0.70f, 0.70f)
        val internalKeyboard = Box(0.24f, 0.48f, 0.66f, 0.68f) // lower half inside laptop
        val table = Box(0.02f, 0.15f, 0.98f, 0.95f)           // big desk under laptop
        val tinyPhoneBeside = Box(0.72f, 0.55f, 0.79f, 0.68f)

        val (kept, diag) = GrounderFilters.filterAndMap(
            rawTargets = listOf(RawDetection("laptop", laptopBox, 0.58f)),
            rawRivals = listOf(
                RawDetection("keyboard", internalKeyboard, 0.56f),
                RawDetection("dining table", table, 0.65f),
                RawDetection("cell phone", tinyPhoneBeside, 0.75f)
            ),
            allowed = setOf("laptop"),
            frameWidth = 640,
            frameHeight = 480
        )
        assertEquals("Real laptop on desk with internal keyboard should survive: $diag", 1, kept.size)
        assertEquals("laptop", kept[0].label)
    }

    @Test
    fun standaloneKeyboardOrSkinnyVerticalStripIsRejected() {
        val standaloneKb = Box(0.20f, 0.40f, 0.70f, 0.65f)
        val (keptKb, _) = GrounderFilters.filterAndMap(
            rawTargets = listOf(RawDetection("laptop", standaloneKb, 0.42f)),
            rawRivals = listOf(RawDetection("keyboard", standaloneKb, 0.52f)),
            allowed = setOf("laptop"),
            frameWidth = 640,
            frameHeight = 480
        )
        assertTrue(keptKb.isEmpty())

        val skinnyVerticalRect = Box(0.45f, 0.15f, 0.53f, 0.65f) // 51x240 px -> aspect 0.21
        val (keptSkinny, _) = GrounderFilters.filterAndMap(
            rawTargets = listOf(RawDetection("laptop", skinnyVerticalRect, 0.65f)),
            rawRivals = emptyList(),
            allowed = setOf("laptop"),
            frameWidth = 640,
            frameHeight = 480
        )
        assertTrue("Skinny vertical box must not pass as laptop", keptSkinny.isEmpty())
    }

    @Test
    fun bottleAcceptsTallCylindricalCupAtHighConfidenceButRejectsWideMugsAndVases() {
        val tallCan = Box(0.40f, 0.30f, 0.48f, 0.56f)  // ~51x125 px -> ratio 2.45
        val wideMug = Box(0.40f, 0.30f, 0.55f, 0.42f)  // ~96x58 px -> ratio 0.60

        val (keptCan, _) = GrounderFilters.filterAndMap(
            rawTargets = listOf(RawDetection("cup", tallCan, 0.62f)),
            rawRivals = emptyList(),
            allowed = setOf("bottle", "laptop"),
            frameWidth = 640,
            frameHeight = 480
        )
        assertEquals(1, keptCan.size)
        assertEquals("bottle", keptCan[0].label)

        val (keptMug, _) = GrounderFilters.filterAndMap(
            rawTargets = listOf(RawDetection("cup", wideMug, 0.78f)),
            rawRivals = emptyList(),
            allowed = setOf("bottle", "laptop"),
            frameWidth = 640,
            frameHeight = 480
        )
        assertTrue("Wide coffee mug must not become bottle", keptMug.isEmpty())

        val (keptVase, _) = GrounderFilters.filterAndMap(
            rawTargets = listOf(RawDetection("bottle", tallCan, 0.40f)),
            rawRivals = listOf(RawDetection("vase", tallCan, 0.48f)),
            allowed = setOf("bottle", "laptop"),
            frameWidth = 640,
            frameHeight = 480
        )
        assertTrue("Vase stronger than weak bottle must suppress bottle", keptVase.isEmpty())
    }
}
