package com.reach.app.talk

import com.reach.app.grounding.COLOR_WORDS
import com.reach.app.grounding.Candidate
import com.reach.app.grounding.ObjectMemory
import com.reach.app.grounding.Registered
import com.reach.app.grounding.SupportedClasses
import com.reach.app.grounding.TargetPick
import com.reach.app.grounding.TargetSpec
import com.reach.app.grounding.clarifyQuestion
import com.reach.app.grounding.nearAnchor
import com.reach.app.grounding.pickByColor
import com.reach.app.grounding.pickByPosition
import com.reach.app.grounding.pickNear
import com.reach.app.grounding.pickTarget
import com.reach.app.grounding.sideWord
import com.reach.app.hand.Side

/** What the controller should do with one utterance. */
sealed interface Act {
    /** A new object request: interpret it (Claude when reachable, else offline) and lock what it resolves to. */
    data class Search(val text: String) : Act
    /** A follow-up resolved locally against the remembered task; [prefix] says how, in the same utterance. */
    data class Lock(val c: Candidate, val name: String, val prefix: String) : Act
    data class Say(val line: String) : Act
    data object Guide : Act          // "guide me", "continue", "keep going", "try again"
    data object Done : Act           // "got it"
    data object Cancel : Act
    data class Hands(val side: Side?) : Act   // set up both hands, or one
    data class UseHand(val side: Side) : Act  // "guide my left hand"
    data object Help : Act
    data object Where : Act          // "where was it?", "I turned around"
    /** "Remember this laptop as my work laptop": save a reference photo of the one such object in view. */
    data class Register(val name: String, val spec: TargetSpec?) : Act
    data class Forget(val name: String) : Act
    /** Look for a registered object by its photo. [explicit]: the user said its name; otherwise "find my laptop"
     *  with one registered laptop, which falls back to the ordinary category request when the photo does not match. */
    data class FindRegistered(val obj: Registered, val spec: TargetSpec, val explicit: Boolean, val text: String) : Act
}

/**
 * The conversation and short-lived task memory. Pure Kotlin (JVM-tested), deterministic, works offline.
 * It never decides anything per frame: it turns an utterance into an [Act], and remembers just enough to resolve
 * follow-ups — the requested category, the options offered in a clarification (left to right), the selected
 * target's id, and the colour words the user used. Claude, when reachable, only interprets new object requests.
 *
 * Follow-ups are resolved against what the detector sees now: "the left one" by position, "the blue one" only when
 * one option's pixels clearly have more of that colour than the others ([pickByColor]), "the other bag" as the one
 * matching object that is not the current target, "the one next to my bottle" as the option clearly nearest the only
 * bottle in view. A request matching several objects is never resolved by picking one: it is asked about. Anything it
 * cannot resolve confidently is asked again, never guessed. Cancelling or asking for a different object resets the
 * memory. Registered objects ([memory]) are found by name; the photo match itself happens in the controller.
 */
class Conversation(private val ttlMs: Long = TASK_TTL_MS, private val memory: ObjectMemory = ObjectMemory()) {

    data class Task(val spec: TargetSpec, val options: List<Int>, val targetId: Int, val colors: Set<String>, val atMs: Long)

    var task: Task? = null; private set

    fun reset() { task = null }

    /** An interpretation asked "which one?": remember the options (left to right) for the answer. */
    fun onClarify(spec: TargetSpec, options: List<Candidate>, nowMs: Long) {
        task = Task(spec, options.sortedBy { it.box.centerX() }.map { it.id }, -1, task?.colors.orEmpty(), nowMs)
    }

    /** A target was locked for [spec] (by any path). */
    fun onLocked(spec: TargetSpec?, id: Int, nowMs: Long) {
        val s = spec ?: task?.spec ?: return
        task = Task(s, emptyList(), id, task?.colors.orEmpty(), nowMs)
    }

    /**
     * [visible]: detections the user can pick from now. [lockedId]: the core's locked target, -1 if none.
     * [canGuide]: the core has a locked target to (re)start guidance to.
     */
    fun handle(text: String, visible: List<Candidate>, lockedId: Int, canGuide: Boolean, nowMs: Long): Act {
        task?.let { if (nowMs - it.atMs > ttlMs) task = null }
        val t = text.lowercase().trim()
        val w = t.split(Regex("[^a-z]+")).filter { it.isNotEmpty() }.toSet()
        fun has(vararg p: String) = p.any { ph -> if (' ' in ph) " $ph " in " ${w.joinToString(" ")} " || ph in t else ph in w }
        val side = when { "left" in w && "right" !in w -> Side.LEFT; "right" in w && "left" !in w -> Side.RIGHT; else -> null }
        val near = nearAnchor(t) // "next to my bottle": the bottle is where to look, not what to find
        val spec = if (near != null) SupportedClasses.resolve(near.first) else SupportedClasses.resolve(t)
        val colors = w.mapNotNull { COLOR_WORDS[it] }.toSet()
        val registered = memory.named(t)

        if (has("forget", "delete", "remove") && registered != null) return Act.Forget(registered.name)
        if (has("remember", "save", "register") && " as " in " $t ") {
            val name = ObjectMemory.cleanName(" $t ".substringAfter(" as "))
            return if (name.isEmpty()) Act.Say("What should I call it? Say, for example: remember this laptop as my work laptop.")
            else Act.Register(name, SupportedClasses.resolve(" $t ".substringBefore(" as ")))
        }
        when {
            has("cancel", "never mind", "nevermind", "forget it", "reset", "stop guidance", "stop") -> { task = null; return Act.Cancel }
            has("got it", "have it", "holding it", "touching it", "touched it", "found it", "reached", "reached it", "grabbed it", "picked it up", "done") -> return Act.Done
            lockedId >= 0 && canGuide && task?.options.isNullOrEmpty() && has("yes", "yeah", "yep") -> return Act.Done
            "hand" in w || "hands" in w -> {
                if (has("set", "setup", "learn", "enrol", "enroll", "register", "recognise", "recognize", "confirm"))
                    return Act.Hands(side)
                if (side != null && has("use", "guide", "with", "switch")) return Act.UseHand(side)
            }
            has("where was it", "where is it", "where did it go", "which way", "turned around", "i turned", "turned away") -> return Act.Where
            // "where is the bottle?" while that bottle is locked: where it is, not a new search that drops it
            "where" in w && lockedId >= 0 && (spec == null || spec.name == task?.spec?.name) -> return Act.Where
            has("what do you see", "what can you see", "what is in front", "scan", "look around") && spec == null ->
                return Act.Say(describeScene(visible))
            has("help", "tutorial", "how does this work", "what can you do", "explain") && spec == null -> return Act.Help
        }

        if (registered != null) {
            val rs = SupportedClasses.resolve(registered.label)!!
            task = Task(rs, emptyList(), -1, colors, nowMs)
            return Act.FindRegistered(registered, rs, explicit = true, text)
        }

        val current = task
        val pointing = has("left", "right", "middle", "center", "centre") || colors.isNotEmpty() || "other" in w || near != null
        // "find the green bottle" is a new request even mid-task; "the green one" / "the other bag" are follow-ups.
        val request = spec != null && "other" !in w && has("find", "look for", "locate", "get", "fetch", "search", "help", "where")
        if (has("guide", "continue", "keep going", "go on", "resume", "try again", "retry", "start", "try it") && !pointing) {
            if (canGuide) return Act.Guide
            current?.let { return Act.Search("find the ${it.spec.name}") } // nothing locked: look again for the same thing
            return Act.Say("Tell me what to find first, like: find my bottle.")
        }

        val reference = current != null && !request && (spec == null || spec.name == current.spec.name || "other" in w) &&
            (pointing || has("that", "this", "same", "one", "yes", "use it"))
        if (reference && current != null) return resolve(current, spec ?: current.spec, w, colors, near?.second, visible, lockedId, nowMs)

        if (spec != null) {
            // The interpreter says it can't, honestly; and there is nothing to refer back to afterwards.
            if (!spec.supported) { task = null; return Act.Search(text) }
            task = Task(spec, emptyList(), -1, colors, nowMs) // a new request replaces the old task
            val qualified = pointing || spec.beverage != null
            // "find my laptop" with one registered laptop: try its photo first (the controller falls back if it doesn't match)
            memory.ofLabels(spec.labels).singleOrNull()?.takeIf { !qualified }?.let { return Act.FindRegistered(it, spec, explicit = false, text) }
            val pick = pickTarget(spec, visible)
            // Several in view: never pick one. "find the blue bottle" / "...on the left" / "...next to my cup" decide only
            // when the evidence clearly supports one; otherwise ask, here and now (no interpreter needed).
            if (pick is TargetPick.Ambiguous) {
                task = Task(spec, pick.options.map { it.id }, -1, colors, nowMs)
                return resolve(task!!, spec, w, colors, near?.second, visible, lockedId, nowMs, intro = "I can help. ")
            }
            return Act.Search(text)
        }
        if (current != null && current.options.isNotEmpty()) return Act.Say(ask(current, visible))
        return Act.Search(text) // free-form ("I'm thirsty"): let the interpreter try
    }

    private fun resolve(task: Task, spec: TargetSpec, w: Set<String>, colors: Set<String>, anchor: TargetSpec?,
                        visible: List<Candidate>, lockedId: Int, nowMs: Long, intro: String = ""): Act {
        val selected = if (lockedId >= 0) lockedId else task.targetId
        val matching = visible.filter { spec.matches(it) }
        // The options offered in the question, if the detector still knows them by id; otherwise what matches now.
        val options = (if (task.options.isNotEmpty() && spec.name == task.spec.name) matching.filter { it.id in task.options } else emptyList())
            .ifEmpty { matching }.sortedBy { it.box.centerX() }
        if ("other" in w) {
            val rest = matching.filter { it.id != selected }.sortedBy { it.box.centerX() }
            return when (rest.size) {
                0 -> Act.Say("I don't see another ${spec.name} right now.")
                1 -> lockLine(rest[0], spec, null, nowMs)
                else -> { this.task = Task(spec, rest.map { it.id }, selected, colors, nowMs); Act.Say(clarifyQuestion(spec, rest.size)) }
            }
        }
        if (options.isEmpty()) return Act.Say("I can't see the ${spec.name}s right now. Point the camera back at them and tell me which one.")
        if (anchor != null) {
            val anchors = visible.filter { anchor.matches(it) && it.id !in options.map { o -> o.id } }
            val again = ask(task.copy(options = options.map { it.id }), visible).substringAfter(". ")
            this.task = task.copy(options = options.map { it.id }, atMs = nowMs)
            return when {
                anchors.isEmpty() -> Act.Say("${intro}I can't see a ${anchor.name} right now. $again")
                anchors.size > 1 -> Act.Say("${intro}I see ${anchors.size} ${anchor.name}s too, so that doesn't tell me which. $again")
                else -> pickNear(options, anchors[0])?.let { lockLine(it, spec, null, nowMs, "The ${spec.name} next to the ${anchor.name}, ${it.box.sideWord()}. ") }
                    ?: Act.Say("${intro}The ${spec.name}s are about as close to the ${anchor.name}. $again")
            }
        }
        if (colors.size == 1) {
            val color = colors.first()
            if (options.size == 1) return lockLine(options[0], spec, null, nowMs)
            return pickByColor(options, color)?.let { lockLine(it, spec, color, nowMs) }
                ?: Act.Say("I can't tell which ${spec.name} is $color from what I see. ${ask(task, visible).substringAfter(". ")}")
        }
        pickByPosition(options, w.joinToString(" "))?.let { return lockLine(it, spec, null, nowMs) }
        if (w.any { it in setOf("that", "this", "same") } && selected >= 0 && task.options.isEmpty())
            return Act.Say("I'm still on the ${spec.name}.") // nothing changes
        if (options.size == 1) return lockLine(options[0], spec, null, nowMs)
        this.task = task.copy(options = options.map { it.id }, atMs = nowMs)
        return Act.Say(intro + clarifyQuestion(spec, options.size))
    }

    private fun lockLine(c: Candidate, spec: TargetSpec, color: String?, nowMs: Long, how: String? = null): Act {
        task = Task(spec, emptyList(), c.id, task?.colors.orEmpty() + listOfNotNull(color), nowMs)
        val prefix = how ?: if (color != null) "The ${spec.name} ${c.box.sideWord()} looks the most $color. " else "The one ${c.box.sideWord()}. "
        return Act.Lock(c, spec.name, prefix)
    }

    private fun ask(task: Task, visible: List<Candidate>): String {
        val n = visible.count { it.id in task.options }.takeIf { it >= 2 } ?: task.options.size
        return clarifyQuestion(task.spec, maxOf(n, 2))
    }

    private fun describeScene(visible: List<Candidate>): String {
        val items = visible.filter { it.confirmed }.sortedBy { it.box.centerX() }
        if (items.isEmpty()) return "I don't see a bottle or laptop right now. Slowly pan the camera."
        val parts = items.map { "a ${it.label} ${it.box.sideWord()}" }
        return when (parts.size) {
            1 -> "I see ${parts[0]}."
            2 -> "I see ${parts[0]} and ${parts[1]}."
            else -> "I see ${parts.dropLast(1).joinToString(", ")}, and ${parts.last()}."
        }
    }

    companion object { const val TASK_TTL_MS = 5 * 60_000L }
}
