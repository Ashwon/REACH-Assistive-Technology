package com.reach.app.eval

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Two physically different black laptops ("A" registered, "B" someone else's), hand-made embeddings. Checks the
 * protocol and the counting; the real embedder's accuracy needs real instance-labelled photos (dataset/README.md).
 */
class IdentityEvalTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun countsCorrectWrongAskedAndFalseClaimsAcrossSessions() {
        val dir = tmp.newFolder("ids")
        val L = """{"image_id":%d,"category_id":2,"bbox":[%d,10,30,30],"instance":"%s"}"""
        File(dir, "annotations.json").writeText("""{"images":[""" + (1..6).joinToString(",") {
            """{"id":$it,"file_name":"f$it.png","width":100,"height":100}""" } + """],"annotations":[""" + listOf(
            L.format(1, 10, "A"),                       // f1 (session s1): registration photo of A
            L.format(2, 10, "A"), L.format(2, 60, "B"), // f2: both, A on the left
            L.format(3, 60, "B"),                       // f3: only B (exhaustively labelled)
            L.format(4, 60, "B"),                       // f4: only B, and B looks like A to the embedder
            L.format(5, 10, "A"),                       // f5: A present but the detector misses it; B detected unannotated? no: labelled
            L.format(6, 10, "A")                        // f6: same session as the reference: never a query
        ).joinToString(",") + """],"categories":[]}""")
        File(dir, "images.csv").writeText("file_name,session,sequence,frame_ms,lighting,synthetic,labeled,target,reach_ms,reference\n" +
            "f1.png,s1,a,0,normal,0,all,,,A\nf2.png,s2,b,0,normal,0,all,,,\nf3.png,s2,b,300,normal,0,all,,,\n" +
            "f4.png,s3,c,0,normal,0,all,,,\nf5.png,s3,c,300,normal,0,laptop,,,\nf6.png,s1,a,300,normal,0,all,,,\n")
        fun det(x: Double, e: String) = """{"label":"laptop","score":0.8,"box":[$x,0.1,${x + 0.3},0.4],"emb":[$e]}"""
        val a = "1,0,0"; val aLike = "0.97,0.1,0"; val b = "0,1,0"; val bLikeA = "0.95,0.2,0"
        File(tmp.root, "p.jsonl").writeText(listOf(
            "f1" to listOf(det(0.1, a)), "f2" to listOf(det(0.1, aLike), det(0.6, b)), "f3" to listOf(det(0.6, b)),
            "f4" to listOf(det(0.6, bLikeA)), "f5" to listOf(det(0.6, b)), "f6" to listOf(det(0.1, a))
        ).joinToString("\n") { (f, d) -> """{"image":"$f.png","det_ms":1,"hand_ms":1,"dets":[${d.joinToString(",")}],"hands":[]}""" })

        val spec = CategorySpec.load(File(System.getProperty("reach.dataset"), "categories.json"))
        val r = IdentityEval(loadDataset(dir, spec), loadPredictions(File(tmp.root, "p.jsonl"))).run().associateBy { it.instance }
        val ra = r.getValue("A")
        assertEquals(1, ra.refs); assertEquals("laptop", ra.label)
        assertEquals(1, ra.present); assertEquals(1, ra.correct); assertEquals(0, ra.wrongObject)   // f2 (f6 excluded: same session)
        assertEquals(1, ra.notDetected)                                                            // f5: A missed, B not claimed
        assertEquals(2, ra.absent); assertEquals(1, ra.falseClaims)                                // f3 refused, f4 claimed: the look-alike
        assertEquals(1, ra.sameSims.size); assertEquals(4, ra.otherSims.size)
        assertEquals(0, r.getValue("B").refs)                                                      // never registered: nothing to match
        assertTrue(IdentityEval.report(r.values.toList()).contains("| A | laptop | 1 | 1: 1 / 0 / 0 | 1 | 2 | 1 |"))
    }
}
