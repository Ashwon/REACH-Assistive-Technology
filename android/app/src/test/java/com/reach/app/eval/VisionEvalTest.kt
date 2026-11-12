package com.reach.app.eval

import com.reach.app.claude.ClaudeClient
import com.reach.app.grounding.Box
import com.reach.app.grounding.SupportedClasses
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private fun spec() = CategorySpec.load(File(System.getProperty("reach.dataset"), "categories.json"))

/** dataset/categories.json, SupportedClasses and the model's embedded labels must agree. */
class CategorySpecTest {
    private val modelLabels = javaClass.getResource("/coco_labels.txt")!!.readText().lines().filter { it.isNotEmpty() }.toSet()

    @Test fun everyMappedLabelIsARealModelLabelThatTheAppSurfaces() {
        for (c in spec().categories) for (l in c.modelLabels) {
            assertTrue("${c.name}: $l not in model", l in modelLabels)
            assertTrue("${c.name}: $l not surfaced by the app", l in SupportedClasses.DETECTED)
        }
    }

    @Test fun everyLabelTheAppSurfacesBelongsToExactlyOneCategory() {
        for (l in SupportedClasses.DETECTED) assertEquals(l, 1, spec().categories.count { l in it.modelLabels })
    }

    @Test fun idsAreUniqueAndZeroIsLeftForBackground() {
        val ids = (spec().categories + spec().handCategories).map { it.id }
        assertEquals(ids.size, ids.toSet().size); assertTrue(ids.all { it > 0 })
    }

    @Test fun categoriesTheModelCannotRecogniseAreAnsweredHonestly() {
        val none = spec().categories.filter { it.support == "none" || it.support == "excluded" }
        assertTrue(none.any { it.name == "laptop bag" }); assertTrue(none.any { it.name == "person" })
        for (c in none) {
            assertTrue(c.modelLabels.isEmpty())
            assertFalse(c.name, SupportedClasses.resolve("find my ${c.name}")!!.supported)
        }
        // Before: "laptop bag" matched its shorter word and targeted the laptop.
        val r = ClaudeClient().interpretLocal("find my laptop bag", emptyList())
        assertTrue(r.needsClarification); assertTrue(r.question, r.question.startsWith("I can't tell a laptop bag from other bags."))
        assertEquals(setOf("bottle"), SupportedClasses.resolve("my water bottle")!!.labels)
    }
}

class VisionEvalTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun matchingAndAveragePrecision() {
        val a = Box(0.1f, 0.1f, 0.3f, 0.3f); val b = Box(0.6f, 0.6f, 0.8f, 0.8f)
        assertEquals(Triple(1, 1, 1), VisionEval.match(listOf(a, b), listOf(a, Box(0.4f, 0f, 0.5f, 0.1f))))
        assertEquals(1.0, VisionEval.ap(listOf(0.9f to true, 0.8f to true), 2), 1e-9)
        assertEquals(0.5, VisionEval.ap(listOf(0.9f to false, 0.8f to true), 1), 1e-9) // a higher-scored false alarm
        assertTrue(VisionEval.ap(emptyList(), 0).isNaN())
    }

    /** Sequence s1: a laptop in 3 frames. Sequence s2: a dark frame where the model fires "laptop" once. */
    @Test fun scoresModelAndAppLevelsSeparatelyAndHonoursPartialLabels() {
        val dir = tmp.newFolder("ds")
        File(dir, "annotations.json").writeText("""{"images":[
            {"id":1,"file_name":"a1.png","width":100,"height":100},{"id":2,"file_name":"a2.png","width":100,"height":100},
            {"id":3,"file_name":"a3.png","width":100,"height":100},{"id":4,"file_name":"d1.png","width":100,"height":100}],
          "annotations":[
            {"image_id":1,"category_id":2,"bbox":[10,10,40,30],"track_id":"lap"},
            {"image_id":2,"category_id":2,"bbox":[10,10,40,30],"track_id":"lap"},
            {"image_id":3,"category_id":2,"bbox":[10,10,40,30],"track_id":"lap"}],
          "categories":[]}""")
        File(dir, "images.csv").writeText("file_name,session,sequence,frame_ms,lighting,synthetic,labeled\n" +
            "a1.png,s1,s1,0,normal,0,all\na2.png,s1,s1,300,normal,0,all\na3.png,s1,s1,600,normal,0,laptop\n" +
            "d1.png,s2,s2,0,dark,1,laptop|bottle\n")
        val lap = """{"label":"laptop","score":0.7,"box":[0.1,0.1,0.5,0.4]}"""
        val cup = """{"label":"cup","score":0.6,"box":[0.7,0.7,0.8,0.8]}"""
        val pred = File(tmp.root, "predictions_test.jsonl")
        pred.writeText(listOf(
            """{"image":"a1.png","det_ms":10,"hand_ms":5,"luma":120,"dets":[$lap],"hands":[]}""",
            """{"image":"a2.png","det_ms":12,"hand_ms":5,"luma":120,"dets":[$lap],"hands":[]}""",
            """{"image":"a3.png","det_ms":14,"hand_ms":5,"luma":120,"dets":[$lap,$cup],"hands":[]}""",
            """{"image":"d1.png","det_ms":11,"hand_ms":5,"luma":20,"dets":[{"label":"laptop","score":0.4,"box":[0.2,0.2,0.6,0.6]}],"hands":[]}"""
        ).joinToString("\n"))
        val e = VisionEval(spec(), loadDataset(dir, spec()), loadPredictions(pred))

        val model = e.counts("model").getValue("laptop")
        assertEquals(3, model.tp); assertEquals(1, model.fp); assertEquals(1.0, model.fpPerNegImage, 1e-9) // dark false laptop
        val app = e.counts("app").getValue("laptop")
        assertEquals(1, app.tp); assertEquals(2, app.fn); assertEquals(0, app.fp) // confirmed only on its 3rd sighting
        assertEquals(0.0, app.fpPerNegImage, 1e-9)                              // one-frame false laptop never offered
        assertEquals(0, e.counts("model").getValue("cup").fp)                   // a3: cup not exhaustively labelled -> ignored
        assertEquals(mapOf("laptop" to 3), e.confusion()["laptop"])
        assertEquals(1, e.stability().single().detectedFrames)
        assertEquals(10.0 + 12.0, e.latency().getValue("det_p50") * 2, 1e-9)   // p50 of 10,11,12,14 -> 11
    }

    /** With the replay's pixel measurements, the app level applies the app's checks: a weak "laptop" on shadow is never offered. */
    @Test fun weakBoxesOnDarkFeaturelessPixelsAreDroppedAtTheAppLevel() {
        val dir = tmp.newFolder("dark")
        File(dir, "annotations.json").writeText("""{"images":[""" + (1..3).joinToString(",") {
            """{"id":$it,"file_name":"d$it.png","width":100,"height":100}""" } + """],"annotations":[],"categories":[]}""")
        File(dir, "images.csv").writeText("file_name,session,sequence,frame_ms,lighting,synthetic,labeled\n" +
            (1..3).joinToString("") { "d$it.png,s,s,${it * 300},dark,1,all\n" })
        val lap = """{"label":"laptop","score":0.45,"box":[0.2,0.2,0.6,0.6]"""
        val cup = """{"label":"cup","score":0.7,"box":[0.65,0.65,0.9,0.9]"""
        fun write(name: String, px: String) = File(tmp.root, name).apply { writeText((1..3).joinToString("\n") {
            """{"image":"d$it.png","det_ms":10,"hand_ms":5,"luma":20,"dets":[$lap$px},$cup$px}],"hands":[]}""" }) }
        val frames = loadDataset(dir, spec())

        val old = VisionEval(spec(), frames, loadPredictions(write("predictions_old.jsonl", "")))  // no pixels: old files
        assertEquals(1, old.counts("app").getValue("laptop").fp)
        val e = VisionEval(spec(), frames, loadPredictions(write("predictions_new.jsonl", ""","luma":12.0,"contrast":3.0""")))
        assertEquals(3, e.counts("model").getValue("laptop").fp)                // the model fires every frame
        assertEquals(0, e.counts("app").getValue("laptop").fp)                  // weak on shadow: dropped
        assertEquals(1, e.counts("app").getValue("cup").fp)                     // strong: kept once confirmed...
        assertEquals(0, e.counts("sure").getValue("cup").fp)                    // ...but never named as sure in the dark
        assertFalse("wine glass" in e.counts("app"))                            // excluded categories are not scored
    }
}
