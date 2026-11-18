package com.nex.robotpet.v6.data

import android.graphics.Color

/**
 * All 32 EMO-inspired robot eye expressions for NEX 6.0 (design-locked).
 * Every expression uses the SAME square robot eye shape, size, position and glow identity —
 * only scale, slant, colour and particle accents vary. Renders offline at 60 FPS on
 * [com.nex.robotpet.v6.ui.RobotEyesView]. SLEEP is the "Sleeping" expression (kept for
 * backward compatibility); SLEEPY is the distinct drowsy "Sleepy" expression.
 */
enum class NexExpression(val displayName: String) {
    NORMAL("Normal"),
    BLINK("Blink"),
    DOUBLE_BLINK("Double Blink"),
    HAPPY("Happy"),
    VERY_HAPPY("Very Happy"),
    SAD("Sad"),
    ANGRY("Angry"),
    SURPRISED("Surprised"),
    SLEEPY("Sleepy"),
    SLEEP("Sleeping"),
    THINKING("Thinking"),
    LISTENING("Listening"),
    SPEAKING("Speaking"),
    LAUGH("Laughing"),
    CRY("Crying"),
    LOVE("Love"),
    HEART("Heart"),
    EXCITED("Excited"),
    CURIOUS("Curious"),
    CONFUSED("Confused"),
    SHY("Shy"),
    WINKING("Winking"),
    GAMING("Gaming"),
    DANCE("Dancing"),
    BORED("Bored"),
    SCARED("Scared"),
    TIRED("Tired"),
    PROUD("Proud"),
    SUSPICIOUS("Suspicious"),
    FOCUSED("Focused"),
    GREETING("Greeting"),
    CUSTOM("Custom");

    companion object {
        fun fromBackendTag(tag: String?): NexExpression {
            if (tag.isNullOrBlank()) return HAPPY
            val normalized = tag.trim().uppercase().replace('-', '_').replace(' ', '_')
            return when (normalized) {
                "LOVE_HEART" -> LOVE
                "HEARTS" -> HEART
                "SLEEPING" -> SLEEP
                "DANCING" -> DANCE
                "LAUGHING" -> LAUGH
                "CRYING" -> CRY
                "GAME" -> GAMING
                "THINK" -> THINKING
                "LISTEN" -> LISTENING
                "SURPRISE" -> SURPRISED
                "DOUBLEBLINK", "DOUBLE" -> DOUBLE_BLINK
                "WINK" -> WINKING
                "SPEAK" -> SPEAKING
                "GREET", "HELLO" -> GREETING
                "FOCUS" -> FOCUSED
                "VERY_HAPPY_FACE" -> VERY_HAPPY
                else -> entries.firstOrNull { it.name == normalized } ?: HAPPY
            }
        }
    }
}

/**
 * User-configurable parameters for the Custom expression ([NexExpression.CUSTOM]).
 */
data class CustomEyeStyle(
    val colorArgb: Int = Color.parseColor("#00E5FF"),
    val heightScale: Float = 1.0f,
    val browSlant: Float = 0.0f,
    val glowStrength: Float = 1.0f,
)
