package com.reach.app.grounding

import kotlin.math.sqrt

/**
 * One physical object the user registered ("remember this laptop as my work laptop"): a name, the detector class it
 * must belong to, and image embeddings of the reference photos the app captured. Its identity lives here, never in a
 * tracker id: track ids change across frames and sessions, so a registered object is matched again from pixels.
 */
class Registered(val name: String, val label: String, val refs: List<FloatArray>)

/** The outcome of looking for a registered object among the current detections. */
sealed interface IdMatch {
    /** One candidate matches the reference clearly better than every other one of that class. Still an estimate. */
    data class Match(val c: Candidate, val similarity: Float) : IdMatch
    /** Candidates of that class are visible, but none matches confidently (left to right): the user must choose. */
    data class Unsure(val options: List<Candidate>, val best: Float) : IdMatch
    /** No settled candidate of that class in view. */
    data object NotInView : IdMatch
}

/**
 * The user's registered objects and the one matching rule. Pure Kotlin (JVM-tested); embeddings come from outside
 * ([match]'s embed function), so the rule is tested without a model.
 *
 * A candidate is the registered object only if its class matches, its embedding's cosine similarity to the closest
 * reference photo is >= [minSimilarity], and it beats every other candidate of that class by >= [minMargin]. A class
 * prediction, a colour, or being the only one of its class in view is never enough. An image embedding cannot tell
 * two identical products apart; that case lands in [IdMatch.Unsure] only when they look alike to the embedding.
 */
class ObjectMemory(private val minSimilarity: Float = MIN_SIMILARITY, private val minMargin: Float = MIN_MARGIN) {
    private val list = ArrayList<Registered>()
    val objects: List<Registered> get() = synchronized(list) { list.toList() }

    /** Registers [name] or adds another reference photo to it (same name, same class). A new class replaces it. */
    fun add(name: String, label: String, embedding: FloatArray): Registered = synchronized(list) {
        val old = list.firstOrNull { it.name == name }
        val r = Registered(name, label, (old?.takeIf { it.label == label }?.refs ?: emptyList()) + embedding)
        list.remove(old); list.add(r); r
    }

    fun remove(name: String): Boolean = synchronized(list) { list.removeAll { it.name == name } }

    fun get(name: String): Registered? = objects.firstOrNull { it.name == name }

    /** The registered object whose name the utterance says (longest name wins), or null. */
    fun named(utterance: String): Registered? {
        val padded = " " + words(utterance).joinToString(" ") + " "
        return objects.sortedByDescending { it.name.length }.firstOrNull { " ${it.name} " in padded }
    }

    fun ofLabels(labels: Set<String>) = objects.filter { it.label in labels }

    fun match(obj: Registered, cands: List<Candidate>, embed: (Candidate) -> FloatArray?): IdMatch {
        val options = cands.filter { it.label == obj.label && it.confirmed && it.conflict == null }.sortedBy { it.box.centerX() }
        if (options.isEmpty()) return IdMatch.NotInView
        val sims = options.map { c -> embed(c)?.let { e -> obj.refs.maxOf { cosine(it, e) } } ?: -1f }
        val order = sims.indices.sortedByDescending { sims[it] }
        val best = sims[order[0]]; val second = order.getOrNull(1)?.let { sims[it] } ?: -1f
        return if (best >= minSimilarity && best - second >= minMargin) IdMatch.Match(options[order[0]], best)
        else IdMatch.Unsure(options, best)
    }

    /** One line per reference photo: name, class, embedding. Names are cleaned on registration ([cleanName]). */
    fun serialize(): String = objects.joinToString("") { r ->
        r.refs.joinToString("") { e -> "${r.name}\t${r.label}\t${e.joinToString(",")}\n" }
    }

    companion object {
        // shortcut: unvalidated starting values; calibrate from IdentityEval's same/different similarity ranges on real data.
        const val MIN_SIMILARITY = 0.80f
        const val MIN_MARGIN = 0.08f

        fun parse(text: String) = ObjectMemory().apply {
            text.lines().forEach { line ->
                val p = line.split('\t')
                val e = p.getOrNull(2)?.split(',')?.mapNotNull { it.toFloatOrNull() }?.toFloatArray()
                if (p.size == 3 && e != null && e.isNotEmpty()) add(p[0], p[1], e)
            }
        }

        fun cosine(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size) return -1f
            var d = 0.0; var na = 0.0; var nb = 0.0
            for (i in a.indices) { d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
            return if (na == 0.0 || nb == 0.0) -1f else (d / sqrt(na * nb)).toFloat()
        }

        /** "My Work Laptop!" -> "work laptop": lower-case words, no leading my/the/a. Empty = no usable name. */
        fun cleanName(raw: String) = words(raw).dropWhile { it in setOf("my", "the", "a", "an", "our") }.joinToString(" ")

        private fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
    }
}
