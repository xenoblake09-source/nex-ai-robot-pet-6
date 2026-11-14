package com.nex.robotpet.v6.data

import android.graphics.Color

/**
 * All 17 required EMO-inspired robot eye expressions for NEX 6.0 (Part B).
 * Every expression renders smoothly offline at 60 FPS on [com.nex.robotpet.v6.ui.RobotEyesView].
 */
enum class NexExpression(val displayName: String) {
    HAPPY("Happy"),
    SAD("Sad"),
    ANGRY("Angry"),
    SLEEP("Sleep"),
    DANCE("Dance"),
    BLINK("Blink"),
    SURPRISED("Surprised"),
    THINKING("Thinking"),
    LISTENING("Listening"),
    GAMING("Gaming"),
    LAUGH("Laugh"),
    CRY("Cry"),
    LOVE("Love / Heart"),
    CURIOUS("Curious"),
    EXCITED("Excited"),
    CONFUSED("Confused"),
    CUSTOM("Custom");

    companion object {
        fun fromBackendTag(tag: String?): NexExpression {
            if (tag.isNullOrBlank()) return HAPPY
            val normalized = tag.trim().uppercase().replace('-', '_').replace(' ', '_')
            return when (normalized) {
                "LOVE_HEART", "HEART", "HEARTS" -> LOVE
                "SLEEPY", "SLEEPING" -> SLEEP
                "DANCING" -> DANCE
                "LAUGHING" -> LAUGH
                "CRYING" -> CRY
                "GAME" -> GAMING
                "THINK" -> THINKING
                "LISTEN" -> LISTENING
                "SURPRISE" -> SURPRISED
                else -> entries.firstOrNull { it.name == normalized } ?: HAPPY
            }
        }
    }
}

/**
 * User-configurable parameters for the 17th expression ([NexExpression.CUSTOM]).
 */
data class CustomEyeStyle(
    val colorArgb: Int = Color.parseColor("#00E5FF"),
    val heightScale: Float = 1.0f,
    val browSlant: Float = 0.0f,
    val glowStrength: Float = 1.0f,
)
