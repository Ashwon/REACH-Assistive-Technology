package com.reach.app.grounding

import org.junit.Assert.*
import org.junit.Test

/** Embeddings are hand-made unit vectors: the rule is tested here, the real embedder's accuracy only by IdentityEval. */
class ObjectMemoryTest {
    private fun v(vararg x: Float) = x
    private val work = v(1f, 0f, 0f)          // the registered laptop's reference photo
    private fun cand(id: Int, label: String, cx: Float, confirmed: Boolean = true, conflict: String? = null) =
        Candidate(id, label, "unknown", Box(cx - 0.1f, 0.4f, cx + 0.1f, 0.7f), 0.8f, null, confirmed, conflict)

    private fun memory() = ObjectMemory().apply { add("work laptop", "laptop", work) }

    @Test fun amongSeveralLaptopsTheOneMatchingThePhotoIsFoundWhicheverTrackIdItHas() {
        val m = memory()
        val emb = mapOf(7 to v(0.2f, 1f, 0f), 42 to v(0.95f, 0.1f, 0f), 3 to v(0f, 0f, 1f))
        val r = m.match(m.get("work laptop")!!, listOf(cand(7, "laptop", 0.2f), cand(42, "laptop", 0.5f), cand(3, "laptop", 0.8f))) { emb[it.id] }
        assertEquals(42, (r as IdMatch.Match).c.id)
    }

    @Test fun twoInstancesThatLookAlikeAreNeverDecidedByAHairsbreadth() {
        val m = memory()
        val emb = mapOf(1 to v(0.97f, 0.1f, 0f), 2 to v(0.95f, 0.12f, 0.02f)) // both above the bar, too close to call
        val r = m.match(m.get("work laptop")!!, listOf(cand(1, "laptop", 0.3f), cand(2, "laptop", 0.7f))) { emb[it.id] }
        assertTrue(r is IdMatch.Unsure); assertEquals(listOf(1, 2), (r as IdMatch.Unsure).options.map { it.id })
    }

    @Test fun theOnlyLaptopInViewIsNotClaimedUnlessItsPhotoMatches() {
        val m = memory()
        val r = m.match(m.get("work laptop")!!, listOf(cand(5, "laptop", 0.5f))) { v(0.3f, 1f, 0f) } // someone else's laptop
        assertTrue(r is IdMatch.Unsure)
        assertTrue(m.match(m.get("work laptop")!!, listOf(cand(5, "laptop", 0.5f))) { v(0.99f, 0.05f, 0f) } is IdMatch.Match)
    }

    @Test fun missingRegisteredObjectOtherClassesUnsettledOrNoPixels() {
        val m = memory(); val obj = m.get("work laptop")!!
        assertEquals(IdMatch.NotInView, m.match(obj, listOf(cand(1, "backpack", 0.5f), cand(2, "bottle", 0.2f))) { work }) // same look, other class
        assertEquals(IdMatch.NotInView, m.match(obj, listOf(cand(1, "laptop", 0.5f, confirmed = false))) { work })
        assertEquals(IdMatch.NotInView, m.match(obj, listOf(cand(1, "laptop", 0.5f, conflict = "book"))) { work })
        assertTrue(m.match(obj, listOf(cand(1, "laptop", 0.5f))) { null } is IdMatch.Unsure) // no crop: never a match
    }

    @Test fun multipleBottlesWithSeveralReferencePhotos() {
        val m = ObjectMemory()
        m.add("blue bottle", "bottle", v(0f, 1f, 0f)); m.add("blue bottle", "bottle", v(0f, 0.7f, 0.7f)) // photo from another side
        assertEquals(2, m.get("blue bottle")!!.refs.size)
        val emb = mapOf(1 to v(1f, 0f, 0f), 2 to v(0f, 0.72f, 0.69f))
        val r = m.match(m.get("blue bottle")!!, listOf(cand(1, "bottle", 0.3f), cand(2, "bottle", 0.7f))) { emb[it.id] }
        assertEquals(2, (r as IdMatch.Match).c.id) // matches the second photo
        m.add("blue bottle", "cup", v(1f, 0f, 0f))   // re-registered as another class: replaced, not mixed
        assertEquals(listOf("cup"), m.objects.map { it.label }); assertEquals(1, m.get("blue bottle")!!.refs.size)
    }

    @Test fun namesStorageAndForgetting() {
        val m = memory(); m.add("blue bottle", "bottle", v(0f, 1f, 0f))
        assertEquals("work laptop", m.named("Can you find my Work Laptop?")!!.name)
        assertNull(m.named("find my laptop"))                     // a category is not a name
        assertEquals("work laptop", ObjectMemory.cleanName(" my Work-Laptop! "))
        val back = ObjectMemory.parse(m.serialize())
        assertEquals(m.objects.map { it.name to it.label }, back.objects.map { it.name to it.label })
        assertArrayEquals(work, back.get("work laptop")!!.refs[0], 0f)
        assertTrue(back.remove("work laptop")); assertNull(back.get("work laptop"))
        assertEquals(0, ObjectMemory.parse("garbage\nwith\ttabs").objects.size)
    }
}
