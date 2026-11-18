package com.nex.robotpet.v6.memory

import android.content.Context
import android.graphics.Color
import com.nex.robotpet.v6.data.CustomEyeStyle
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

data class StoredTurn(
    val role: String,
    val content: String
)

/**
 * Privacy-first local storage for NEX 6.0 (Part L & Part M).
 * - Stores user's preferred name, voluntary preferences, and bounded conversation context ONLY when permitted.
 * - Defaults to the clean NEX 6.0 HTTPS Render URL ([DEFAULT_NEX6_BACKEND_URL]) and allows
 *   secure HTTPS overrides via the external [com.nex.robotpet.v6.BackendSetupActivity].
 * - Never stores or accepts a Gemini API key on the phone.
 * - Provides complete privacy wipe via [clearAllUserData].
 */
class MemoryManager(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isMemoryEnabled: Boolean
        get() = prefs.getBoolean(KEY_MEMORY_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_MEMORY_ENABLED, value).apply()
            if (!value) {
                prefs.edit()
                    .remove(KEY_USER_NAME)
                    .remove(KEY_USER_PREFERENCES)
                    .remove(KEY_HISTORY_JSON)
                    .apply()
            }
        }

    var isWakeWordForegroundEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKE_WORD_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_WAKE_WORD_ENABLED, value).apply()

    var isAutonomousIdleEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTONOMOUS_IDLE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTONOMOUS_IDLE, value).apply()

    var userName: String?
        get() = if (isMemoryEnabled) prefs.getString(KEY_USER_NAME, null)?.takeIf { it.isNotBlank() } else null
        set(value) {
            if (!isMemoryEnabled) return
            val cleaned = value?.trim()?.take(40)
            if (cleaned.isNullOrBlank()) {
                prefs.edit().remove(KEY_USER_NAME).apply()
            } else {
                prefs.edit().putString(KEY_USER_NAME, cleaned).apply()
            }
        }

    var userPreferences: String?
        get() = if (isMemoryEnabled) prefs.getString(KEY_USER_PREFERENCES, null)?.takeIf { it.isNotBlank() } else null
        set(value) {
            if (!isMemoryEnabled) return
            val cleaned = value?.trim()?.take(240)
            if (cleaned.isNullOrBlank()) {
                prefs.edit().remove(KEY_USER_PREFERENCES).apply()
            } else {
                prefs.edit().putString(KEY_USER_PREFERENCES, cleaned).apply()
            }
        }

    fun rememberPreference(preferenceItem: String) {
        if (!isMemoryEnabled) return
        val item = preferenceItem.trim().take(80)
        if (item.isBlank()) return
        val existing = userPreferences
            ?.split("; ")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toMutableList()
            ?: mutableListOf()
        if (existing.none { it.equals(item, ignoreCase = true) }) {
            existing.add(item)
        }
        userPreferences = existing.takeLast(5).joinToString("; ")
    }

    var backendBaseUrl: String
        get() {
            val saved = prefs.getString(KEY_BACKEND_URL, null)?.trim()
            val normalized = validateHttpsBackendUrl(saved)
            return normalized ?: DEFAULT_NEX6_BACKEND_URL
        }
        set(value) {
            val validated = validateHttpsBackendUrl(value)
                ?: throw IllegalArgumentException("Backend URL must be a valid HTTPS URL (e.g. $DEFAULT_NEX6_BACKEND_URL).")
            prefs.edit().putString(KEY_BACKEND_URL, validated).apply()
        }

    fun resetBackendUrlToDefault() {
        prefs.edit().remove(KEY_BACKEND_URL).apply()
    }

    fun getConversationHistory(): List<StoredTurn> {
        if (!isMemoryEnabled) return emptyList()
        val raw = prefs.getString(KEY_HISTORY_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<StoredTurn>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val role = obj.optString("role")
                val content = obj.optString("content")
                if ((role == "user" || role == "assistant") && content.isNotBlank()) {
                    list.add(StoredTurn(role, content))
                }
            }
            list.takeLast(MAX_TURNS)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun appendConversationExchange(userText: String, assistantText: String) {
        if (!isMemoryEnabled) return
        val current = getConversationHistory().toMutableList()
        current.add(StoredTurn("user", userText.trim().take(800)))
        current.add(StoredTurn("assistant", assistantText.trim().take(800)))
        val bounded = current.takeLast(MAX_TURNS)
        val arr = JSONArray()
        for (turn in bounded) {
            arr.put(JSONObject().put("role", turn.role).put("content", turn.content))
        }
        prefs.edit().putString(KEY_HISTORY_JSON, arr.toString()).apply()
    }

    fun getCustomEyeStyle(): CustomEyeStyle {
        return CustomEyeStyle(
            colorArgb = prefs.getInt(KEY_CUSTOM_COLOR, Color.parseColor("#00E5FF")),
            heightScale = prefs.getFloat(KEY_CUSTOM_HEIGHT, 1.0f).coerceIn(0.55f, 1.35f),
            browSlant = prefs.getFloat(KEY_CUSTOM_SLANT, 0.0f).coerceIn(-0.6f, 0.6f),
            glowStrength = prefs.getFloat(KEY_CUSTOM_GLOW, 1.0f).coerceIn(0.4f, 1.5f)
        )
    }

    fun saveCustomEyeStyle(style: CustomEyeStyle) {
        prefs.edit()
            .putInt(KEY_CUSTOM_COLOR, style.colorArgb)
            .putFloat(KEY_CUSTOM_HEIGHT, style.heightScale)
            .putFloat(KEY_CUSTOM_SLANT, style.browSlant)
            .putFloat(KEY_CUSTOM_GLOW, style.glowStrength)
            .apply()
    }

    fun getGameBestScore(gameId: String): Int = prefs.getInt("best_$gameId", 0)

    fun updateGameBestScore(gameId: String, newScore: Int, lowerIsBetter: Boolean = false): Int {
        val current = getGameBestScore(gameId)
        val shouldUpdate = if (lowerIsBetter) {
            newScore > 0 && (current == 0 || newScore < current)
        } else {
            newScore > current
        }
        if (shouldUpdate) {
            prefs.edit().putInt("best_$gameId", newScore).apply()
            return newScore
        }
        return current
    }

    /**
     * Complete privacy wipe of stored user memory (name, preferences, conversation history, custom eyes, game scores).
     * Preserves backend URL override unless [includeBackendUrl] is true.
     */
    fun clearAllUserData(includeBackendUrl: Boolean = false) {
        val customUrl = prefs.getString(KEY_BACKEND_URL, null)
        val keepMemorySetting = isMemoryEnabled
        prefs.edit().clear().apply()
        prefs.edit().putBoolean(KEY_MEMORY_ENABLED, keepMemorySetting).apply()
        if (!includeBackendUrl && !customUrl.isNullOrBlank()) {
            prefs.edit().putString(KEY_BACKEND_URL, customUrl).apply()
        }
    }

    companion object {
        const val DEFAULT_NEX6_BACKEND_URL = "https://nex-ai-robot-pet-6-backend.onrender.com"
        private const val LEGACY_NO_SERVER_TYPO_HOST = "nex-ai-robot-pet-6.onrender.com"

        private const val PREFS_NAME = "nex_v6_private_memory"
        private const val KEY_MEMORY_ENABLED = "memory_enabled"
        private const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
        private const val KEY_AUTONOMOUS_IDLE = "autonomous_idle_enabled"
        private const val KEY_USER_NAME = "preferred_user_name"
        private const val KEY_USER_PREFERENCES = "voluntary_user_preferences"
        private const val KEY_HISTORY_JSON = "conversation_history_json"
        private const val KEY_BACKEND_URL = "https_backend_base_url"
        private const val KEY_CUSTOM_COLOR = "custom_eye_color"
        private const val KEY_CUSTOM_HEIGHT = "custom_eye_height"
        private const val KEY_CUSTOM_SLANT = "custom_eye_slant"
        private const val KEY_CUSTOM_GLOW = "custom_eye_glow"
        private const val MAX_TURNS = 10

        /**
         * Validates that [rawUrl] is a clean, secure HTTPS base URL with a real hostname,
         * no embedded userinfo/API keys, and no legacy NEX 5.0 references.
         * Auto-corrects the common `nex-ai-robot-pet-6.onrender.com` (missing `-backend`) typo
         * to the live deployed service `https://nex-ai-robot-pet-6-backend.onrender.com`.
         */
        fun validateHttpsBackendUrl(rawUrl: String?): String? {
            val trimmed = rawUrl?.trim()?.trimEnd('/') ?: return null
            if (trimmed.isEmpty() || trimmed.length > 256) return null
            val uri = try {
                URI(trimmed)
            } catch (_: Exception) {
                return null
            }
            if (!"https".equals(uri.scheme, ignoreCase = true)) return null
            if (!uri.userInfo.isNullOrEmpty()) return null
            if (!uri.query.isNullOrEmpty() || !uri.fragment.isNullOrEmpty()) return null
            val rawHost = uri.host?.lowercase() ?: return null
            if (rawHost.isBlank() || !rawHost.contains('.')) return null
            if (rawHost.contains("nex-5") || rawHost.contains("nex5")) return null
            val host = if (rawHost == LEGACY_NO_SERVER_TYPO_HOST) {
                "nex-ai-robot-pet-6-backend.onrender.com"
            } else {
                rawHost
            }
            val portPart = if (uri.port != -1 && uri.port != 443) ":${uri.port}" else ""
            val cleanPath = (uri.path ?: "").trimEnd('/')
            return "https://$host$portPart$cleanPath"
        }
    }
}
