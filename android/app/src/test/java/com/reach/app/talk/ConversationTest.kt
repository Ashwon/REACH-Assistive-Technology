package com.reach.app.talk

import com.reach.app.grounding.Appearance
import com.reach.app.grounding.Box
import com.reach.app.grounding.Candidate
import com.reach.app.grounding.ObjectMemory
import com.reach.app.grounding.SupportedClasses
import com.reach.app.hand.Side
import org.junit.Assert.*
import org.junit.Test

class ConversationTest {
    private val talk = Conversation()
    private var t = 0L

    private fun px(rgb: Int, n: Int) = IntArray(n) { 0xFF000000.toInt() or rgb }
    private val blueLook = Appearance.of(px(0x1E40D0, 70) + px(0xF0F0F0, 30))
    private val whiteLook = Appearance.of(px(0xF0F0F0, 95) + px(0x1E40D0, 5))
    private fun obj(id: Int, label: String, cx: Float, look: Appearance? = null) =
        Candidate(id, label, "unknown", Box(cx - 0.05f, 0.4f, cx + 0.05f, 0.7f), 0.8f, null, look = look)
    private val blueLeft = obj(1, "bottle", 0.25f, blueLook)
    private val whiteRight = obj(2, "bottle", 0.75f, whiteLook)
    private val bottles = listOf(whiteRight, blueLeft)

    private fun say(text: String, visible: List<Candidate> = bottles, locked: Int = -1, canGuide: Boolean = locked >= 0) =
        talk.handle(text, visible, locked, canGuide, t).also { t += 2000 }

    /** Two bottles in view: the request is never resolved by picking one; it is asked about, locally. */
    private fun askForBottle() {
        assertEquals(Act.Say("I can help. I see two bottles. Do you want the one on the left or the right?"),
            say("Can you help me find my bottle?"))
        assertEquals(listOf(1, 2), talk.task!!.options)
    }

    @Test fun theBlueOneIsResolvedFromThePixelsAndSaidAsAPosition() {
        askForBottle()
        val a = say("The blue one.") as Act.Lock
        assertEquals(1, a.c.id); assertEquals("bottle", a.name)
        assertEquals("The bottle on the left looks the most blue. ", a.prefix)
        assertEquals(1, talk.task!!.targetId)
    }

    @Test fun aColourTheEvidenceDoesNotSupportIsAskedAgainNotGuessed() {
        askForBottle()
        val plain = listOf(obj(1, "bottle", 0.25f), obj(2, "bottle", 0.75f)) // no pixels for either
        val a = say("the blue one", visible = plain)
        assertEquals(Act.Say("I can't tell which bottle is blue from what I see. Do you want the one on the left or the right?"), a)
        assertEquals("red has no support", Act.Say::class, say("the red one")::class)
    }

    @Test fun positionAnswersAndTheOtherOne() {
        askForBottle()
        assertEquals(2, (say("the one on the right") as Act.Lock).c.id)
        assertEquals(1, (say("no, the other one", locked = 2) as Act.Lock).c.id)
    }

    @Test fun theOtherBagIsANewCategoryExcludingTheCurrentTarget() {
        val bags = listOf(obj(3, "backpack", 0.3f), obj(4, "handbag", 0.7f), blueLeft)
        assertEquals(Act.Search("find my backpack"), say("find my backpack", bags))
        talk.onLocked(SupportedClasses.resolve("backpack"), 3, t)
        val a = say("actually the other bag", bags, locked = 3) as Act.Lock
        assertEquals(4, a.c.id); assertEquals("bag", a.name)
    }

    @Test fun thatBottleKeepsTheCurrentTarget() {
        askForBottle(); say("left")
        assertEquals(Act.Say("I'm still on the bottle."), say("that bottle", locked = 1))
    }

    @Test fun continueRetryAndWhere() {
        askForBottle(); say("left")
        assertEquals(Act.Guide, say("continue", locked = 1))
        assertEquals(Act.Guide, say("try it", locked = 1))
        assertEquals(Act.Search("find the bottle"), say("try again", canGuide = false)) // nothing locked: look again
        assertEquals(Act.Where, say("I turned around. Where was it?", locked = 1))
        assertEquals(Act.Where, say("where is the bottle", locked = 1))         // not a new search that drops it
        assertEquals(Act.Search("where is the cup"), say("where is the cup", locked = 1))
    }

    @Test fun cancelForgetsTheTaskSoALateAnswerLocksNothing() {
        askForBottle()
        assertEquals(Act.Cancel, say("never mind"))
        assertNull(talk.task)
        assertEquals(Act.Search("the left one"), say("the left one")) // no memory: an ordinary request, not a lock
    }

    @Test fun askingForAnotherObjectResetsTheTask() {
        askForBottle()
        assertEquals(Act.Search("find my cup"), say("find my cup"))
        assertEquals("cup", talk.task!!.spec.name); assertTrue(talk.task!!.options.isEmpty())
    }

    @Test fun aColourInTheRequestItselfPicksWhenTheEvidenceIsClear() {
        val a = say("find the blue bottle") as Act.Lock
        assertEquals(1, a.c.id)
        assertEquals(Act.Say("I can't tell which bottle is green from what I see. Do you want the one on the left or the right?"),
            say("find the green bottle"))                                       // unclear: asked, never guessed
    }

    @Test fun unsupportedObjectsGoToTheInterpreterWhichSaysSo() {
        askForBottle()
        assertEquals(Act.Search("find my laptop bag"), say("find my laptop bag"))
        assertNull(talk.task) // before: "phone"/"laptop bag" became the task, and "the left one" was asked about it
        assertEquals(Act.Search("find my phone"), say("find my phone"))
        assertEquals(Act.Search("the left one"), say("the left one"))
    }

    @Test fun anUnclearReplyToAQuestionRepeatsTheQuestion() {
        askForBottle()
        assertEquals(Act.Say("I see two bottles. Do you want the one on the left or the right?"), say("hmm what"))
    }

    @Test fun theTaskIsShortLived() {
        askForBottle()
        t += Conversation.TASK_TTL_MS + 1
        assertEquals(Act.Search("the left one"), say("the left one"))
    }

    @Test fun handAndOtherCommands() {
        assertEquals(Act.Hands(null), say("set up my hands"))
        assertEquals(Act.Hands(Side.LEFT), say("set my left hand"))
        assertEquals(Act.UseHand(Side.RIGHT), say("guide my right hand"))
        assertEquals(Act.Help, say("help"))
        assertEquals(Act.Search("help me find my cup"), say("help me find my cup"))
        assertEquals(Act.Done, say("I've got it"))
        assertEquals(Act.Cancel, say("stop"))
    }

    // ---- several instances of one class ----

    private val black = Appearance.of(px(0x101010, 90) + px(0x808080, 10))
    private val silver = Appearance.of(px(0xC0C0C8, 90) + px(0x101010, 10))
    private val lapL = obj(11, "laptop", 0.25f, black)
    private val lapR = obj(12, "laptop", 0.70f, silver)
    private val bottleByR = obj(13, "bottle", 0.88f)

    @Test fun twoLaptopsAreAskedAboutAndTheAnswerUsesTheCurrentEvidence() {
        val scene = listOf(lapR, lapL, bottleByR)
        assertEquals(Act.Say("I can help. I see two laptops. Do you want the one on the left or the right?"), say("find my laptop", scene))
        val a = say("the one next to my bottle", scene) as Act.Lock
        assertEquals(12, a.c.id); assertEquals("The laptop next to the bottle, on the right. ", a.prefix)
        talk.reset(); say("find my laptop", scene)
        assertEquals(11, (say("the black one", scene) as Act.Lock).c.id)
    }

    @Test fun aDescriptionInTheRequestDecidesOnlyWhenTheEvidenceIsClear() {
        val scene = listOf(lapR, lapL, bottleByR)
        assertEquals(12, (say("find the laptop on the right", scene) as Act.Lock).c.id)
        assertEquals(12, (say("find the laptop next to my bottle", scene) as Act.Lock).c.id) // a laptop, not the bottle
        assertEquals(11, (say("find the black laptop", scene) as Act.Lock).c.id)
    }

    @Test fun anAmbiguousMissingOrEquidistantNeighbourIsAskedAgain() {
        val twoBottles = listOf(lapL, lapR, obj(13, "bottle", 0.88f), obj(14, "bottle", 0.05f))
        say("find my laptop", twoBottles)
        assertEquals(Act.Say("I see 2 bottles too, so that doesn't tell me which. Do you want the one on the left or the right?"),
            say("the one next to the bottle", twoBottles))
        assertEquals(Act.Say("I can't see a cup right now. Do you want the one on the left or the right?"),
            say("the one next to the cup", twoBottles))
        val middle = listOf(lapL, lapR, obj(15, "cup", 0.475f))
        assertEquals(Act.Say("The laptops are about as close to the cup. Do you want the one on the left or the right?"),
            say("the one near the cup", middle))
        assertEquals(listOf(11, 12), talk.task!!.options) // still the same question
    }

    @Test fun twoBottlesNeverResolveToTheFirstDetection() {
        assertEquals(Act.Say::class, say("find my bottle")::class)
        assertEquals(Act.Say::class, say("where's my bottle")::class)
        assertEquals(Act.Search("find my bottle"), say("find my bottle", listOf(blueLeft))) // one: the interpreter confirms
    }

    // ---- registered objects ----

    private fun withWorkLaptop() = Conversation(memory = ObjectMemory().apply { add("work laptop", "laptop", floatArrayOf(1f, 0f)) })

    @Test fun registeringFindingAndForgetting() {
        val c = withWorkLaptop()
        fun h(text: String, visible: List<Candidate> = listOf(lapL, lapR)) = c.handle(text, visible, -1, false, t)
        assertEquals(Act.Register("work laptop", SupportedClasses.resolve("laptop")), h("Remember this laptop as my work laptop"))
        assertEquals(Act.Register("blue bottle", null), h("save this as my blue bottle"))
        assertEquals(Act.Say::class, h("remember this as")::class)
        val find = h("can you find my work laptop") as Act.FindRegistered
        assertTrue(find.explicit); assertEquals("work laptop", find.obj.name); assertEquals("laptop", find.spec.name)
        assertFalse((h("find my laptop") as Act.FindRegistered).explicit)  // the only registered laptop: try its photo
        assertEquals(12, (h("find the laptop on the right") as Act.Lock).c.id) // a description wins over the photo
        assertEquals(Act.Forget("work laptop"), h("forget my work laptop"))
        assertEquals(Act.Cancel, h("forget it"))
    }

    @Test fun anUnconfirmedSingleCandidateNeedsAYes() {
        val laptop = SupportedClasses.resolve("laptop")!!
        say("find my laptop", listOf(lapL)); talk.onClarify(laptop, listOf(lapL), t) // "I can't confirm it's your work laptop..."
        assertEquals(11, (say("yes", listOf(lapL)) as Act.Lock).c.id)
    }
}
