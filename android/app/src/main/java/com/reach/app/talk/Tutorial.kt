package com.reach.app.talk

import com.reach.app.grounding.SupportedClasses

/**
 * The spoken introduction (first launch, and the help button / "help"). It describes only what is true right now:
 * the vibration lines are said only while the band is connected and has passed its motor test (live haptics on), and
 * hand tracking is described as pending until both hands are enrolled.
 */
object Tutorial {
    fun text(hapticsConnected: Boolean, depthMode: Boolean, bothHandsEnrolled: Boolean): String = buildString {
        append("Welcome to REACH. I can help you locate objects. ${SupportedClasses.SUPPORTED_LINE} ")
        append(if (bothHandsEnrolled) "Your left and right hands are tracked separately. "
               else "I track your left and right hands separately, once you've shown me each one. ")
        if (hapticsConnected) {
            append("The vibration band is on. Two pulses on the left mean move left; two on the right mean move right. " +
                "One long buzz means hold still. Three long buzzes mean you reached the object. ")
            append(if (depthMode) "Alternating pulses mean reach forward, and taps that speed up mean you are close. "
                   else "Reach-forward and near pulses need depth mode, in settings. ")
            append("Settings has every pattern, under review haptic instructions. ")
        } else append("The vibration band isn't connected, so I'll guide you by voice. ")
        append("If tracking becomes uncertain, I will pause rather than guess. You can ask me conversationally to find an object.")
    }
}
