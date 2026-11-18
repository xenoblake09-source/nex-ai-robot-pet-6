package com.nex.robotpet.v6.util

import android.graphics.Color
import com.nex.robotpet.v6.data.AnimalSoundCatalog
import com.nex.robotpet.v6.data.AnimalSoundItem
import com.nex.robotpet.v6.data.NexExpression
import com.nex.robotpet.v6.data.SchoolKnowledgeRepository

sealed class RoutedAction {
    data class SpeakImmediate(
        val speech: String,
        val expression: NexExpression,
        val language: LanguageMode
    ) : RoutedAction()

    data class PlayAnimal(
        val animal: AnimalSoundItem,
        val language: LanguageMode
    ) : RoutedAction()

    data class SetExpression(
        val expression: NexExpression,
        val customColorOverride: Int? = null,
        val language: LanguageMode
    ) : RoutedAction()

    data class OpenScreen(
        val screen: ScreenTarget,
        val gameIndex: Int? = null,
        val initialVisionPrompt: String? = null,
        val language: LanguageMode
    ) : RoutedAction()

    data class AnalyzeCameraOnFace(
        val prompt: String,
        val useFrontCamera: Boolean = false,
        val language: LanguageMode
    ) : RoutedAction()

    data class SetFaceTrackingOnFace(
        val enabled: Boolean,
        val checkPresenceOnly: Boolean = false,
        val language: LanguageMode
    ) : RoutedAction()

    data class SpeakTimeOrDate(
        val askDateOnly: Boolean,
        val language: LanguageMode
    ) : RoutedAction()

    data class FetchWeather(
        val cityHint: String?,
        val language: LanguageMode
    ) : RoutedAction()

    data class SaveUserName(
        val name: String,
        val language: LanguageMode
    ) : RoutedAction()

    data class SaveUserPreference(
        val preference: String,
        val language: LanguageMode
    ) : RoutedAction()

    data class SetMemoryEnabled(
        val enabled: Boolean,
        val language: LanguageMode
    ) : RoutedAction()

    data class ClearMemory(
        val language: LanguageMode
    ) : RoutedAction()

    data class AskGeminiChat(
        val cleanText: String,
        val language: LanguageMode
    ) : RoutedAction()
}

/**
 * Dedicated full-screen feature Activities reachable by voice command.
 * Note: No HUB or SETTINGS popup exists on the main robot face—keeping NEX's face 100% pure.
 */
enum class ScreenTarget {
    ANIMALS,
    VISION,
    MUSIC_DANCE,
    GAMES_HUB,
    MINI_GAME,
    WEATHER_TIME
}

/**
 * Deterministic voice & text intent router for NEX 6.0.
 * Handles immediate robot commands (creator identity, NEX self-ID, Montfort Bagaha school
 * verified facts, 20 animal sounds, all 32 expressions, background camera/vision & face tracking
 * on the pure robot face, music/dance, 7 mini-games, time/date, live weather, and memory/privacy
 * controls) and delegates open-ended conversation/questions/live-search to the Google Gemini backend.
 */
object CommandRouter {

    private val wakePrefixRegex = Regex(
        "^(hey\\s+nex|hello\\s+nex|hi\\s+nex|ok\\s+nex|okay\\s+nex|hay\\s+nex|हे\\s+नेक्स|हेलो\\s+नेक्स)[,!.?\\s]*",
        RegexOption.IGNORE_CASE
    )

    private val nameIntroPatterns = listOf(
        Regex("\\bmy\\s+name\\s+is\\s+([A-Za-z][A-Za-z'-]{1,22})\\b", RegexOption.IGNORE_CASE),
        Regex("\\bcall\\s+me\\s+([A-Za-z][A-Za-z'-]{1,22})\\b", RegexOption.IGNORE_CASE),
        Regex("\\bmera\\s+naam\\s+([A-Za-z][A-Za-z'-]{1,22})\\s+hai\\b", RegexOption.IGNORE_CASE),
        Regex("मेरा\\s+नाम\\s+([^\\s।,.!?]{2,22})\\s+है")
    )

    private val preferencePatterns = listOf(
        Regex("\\bremember\\s+that\\s+i\\s+(like|love|prefer|enjoy|study|play)\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("\\bmy\\s+favou?rite\\s+(subject|sport|game|food|color|colour|animal|hobby|topic)\\s+is\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("\\byaad\\s+rakhna\\s+mujhe\\s+(.+?)\\s+pasand\\s+hai\\b", RegexOption.IGNORE_CASE)
    )

    fun stripWakePhrase(raw: String): Pair<Boolean, String> {
        val trimmed = raw.trim()
        val match = wakePrefixRegex.find(trimmed) ?: return false to trimmed
        val remainder = trimmed.substring(match.range.last + 1).trim()
        return true to remainder
    }

    fun route(rawInput: String): RoutedAction {
        val (_, cleanedInput) = stripWakePhrase(rawInput)
        val text = cleanedInput.ifBlank { rawInput.trim() }
        val lower = text.lowercase()
        val lang = LanguageHelper.detectLanguage(text)

        // 1. Canonical Creator Identity (Part A)
        if (LanguageHelper.isCreatorQuestion(text)) {
            return RoutedAction.SpeakImmediate(
                speech = LanguageHelper.EXACT_CREATOR_RESPONSE,
                expression = NexExpression.PROUD,
                language = LanguageMode.ENGLISH
            )
        }

        // 2. Direct NEX Identity (Never AURA)
        if (Regex("\\b(what\\s+is\\s+your\\s+name|who\\s+are\\s+you|are\\s+you\\s+aura|tumhara\\s+naam\\s+kya\\s+hai|tum\\s+kaun\\s+ho)\\b").containsMatchIn(lower)
            || text.contains("तुम्हारा नाम क्या है") || text.contains("तुम कौन हो")
        ) {
            val reply = when (lang) {
                LanguageMode.HINDI -> "मेरा नाम NEX है! मैं ARNAV, DIVYA AND ARYAN द्वारा बनाया गया एक AI वर्चुअल रोबोट पेट हूँ।"
                LanguageMode.HINGLISH -> "Mera naam NEX hai! Main ek AI virtual robot pet hoon, aur ARNAV, DIVYA AND ARYAN created me."
                LanguageMode.ENGLISH -> "I am NEX, an AI-powered virtual robot pet! ARNAV, DIVYA AND ARYAN created me."
            }
            return RoutedAction.SpeakImmediate(reply, NexExpression.GREETING, lang)
        }

        // 3. Memory & Privacy Controls (Voice Clear / Disable / Enable)
        if (Regex("\\b(clear\\s+(my\\s+)?(memory|data|history)|forget\\s+(my\\s+name|everything|me)|delete\\s+my\\s+(data|memory|history))\\b").containsMatchIn(lower)
            || (lower.contains("memory") && (lower.contains("hatao") || lower.contains("saaf") || lower.contains("delete")))
            || text.contains("मेरी मेमोरी हटाओ") || text.contains("डेटा साफ़ करो")
        ) {
            return RoutedAction.ClearMemory(lang)
        }
        if (Regex("\\b(disable\\s+memory|turn\\s+off\\s+memory|stop\\s+remembering)\\b").containsMatchIn(lower)) {
            return RoutedAction.SetMemoryEnabled(enabled = false, language = lang)
        }
        if (Regex("\\b(enable\\s+memory|turn\\s+on\\s+memory|start\\s+remembering)\\b").containsMatchIn(lower)) {
            return RoutedAction.SetMemoryEnabled(enabled = true, language = lang)
        }

        // 4. Explicit Standalone Name Introduction & Preference Consent Memory
        if (!text.contains("?")) {
            for (pat in nameIntroPatterns) {
                val m = pat.find(text)
                if (m != null && text.split(Regex("\\s+")).size <= 7) {
                    val candidate = m.groupValues[1].trim()
                    if (candidate.lowercase() !in setOf("nex", "aura", "happy", "sad", "fine")) {
                        return RoutedAction.SaveUserName(candidate, lang)
                    }
                }
            }
            for (pat in preferencePatterns) {
                val m = pat.find(text)
                if (m != null) {
                    val pref = m.groupValues.drop(1).joinToString(" ").trim('?', '.', '!', ' ')
                    if (pref.isNotBlank()) {
                        return RoutedAction.SaveUserPreference(pref, lang)
                    }
                }
            }
        }

        // 5. Verified Montfort Senior Secondary School Bagaha Facts (Part K)
        SchoolKnowledgeRepository.answerSchoolQuery(text, lang)?.let { schoolReply ->
            return RoutedAction.SpeakImmediate(schoolReply, NexExpression.HAPPY, lang)
        }

        // 6. Camera, Face Tracking & Object Recognition
        // 6a. Stop face tracking on the main face
        if (Regex("\\b(stop\\s+face\\s+tracking|stop\\s+tracking(\\s+my\\s+face)?|face\\s+tracking\\s+off|camera\\s+off)\\b").containsMatchIn(lower)
            || text.contains("फेस ट्रैकिंग बंद")
        ) {
            return RoutedAction.SetFaceTrackingOnFace(enabled = false, checkPresenceOnly = false, language = lang)
        }
        // 6b. Detect whether a person is visible in the camera view ("Can you see me?", "Is anyone there?")
        if (Regex("\\b(can\\s+you\\s+see\\s+me|do\\s+you\\s+see\\s+me|is\\s+anyone\\s+(there|visible|in\\s+front)|is\\s+a\\s+person\\s+visible|who\\s+is\\s+in\\s+front\\s+of\\s+you|kya\\s+tum\\s+mujhe\\s+dekh\\s+sakte\\s+ho)\\b").containsMatchIn(lower)
            || text.contains("क्या तुम मुझे देख सकते हो") || text.contains("कोई दिख रहा है")
        ) {
            return RoutedAction.SetFaceTrackingOnFace(enabled = true, checkPresenceOnly = true, language = lang)
        }
        // 6c. Continuous face tracking on the pure black NEX face ("Track my face", "Look at me")
        if (Regex("\\b(track\\s+my\\s+face|start\\s+face\\s+tracking|follow\\s+my\\s+face|look\\s+at\\s+me)\\b").containsMatchIn(lower)
            || text.contains("मेरा चेहरा ट्रैक करो")
        ) {
            return RoutedAction.SetFaceTrackingOnFace(enabled = true, checkPresenceOnly = false, language = lang)
        }
        // 6d. Explicit request to open the dedicated Vision camera screen (preserves existing VisionActivity)
        if (Regex("\\b(open\\s+camera|start\\s+camera|camera\\s+on|open\\s+vision|camera\\s+kholo)\\b").containsMatchIn(lower)
            || text.contains("कैमरा खोलो")
        ) {
            return RoutedAction.OpenScreen(
                screen = ScreenTarget.VISION,
                initialVisionPrompt = text,
                language = lang
            )
        }
        // 6e. Background object/scene recognition directly on the pure black NEX face ("What is this?", "Describe what you see")
        if (Regex("\\b(what\\s+is\\s+this|what's\\s+this|look\\s+at\\s+this|identify\\s+this|what\\s+am\\s+i\\s+holding|describe\\s+(what\\s+you\\s+see|this\\s+scene|the\\s+scene)|what\\s+do\\s+you\\s+see|what\\s+does\\s+the\\s+camera\\s+see|yeh\\s+kya\\s+hai|ye\\s+kya\\s+hai|samne\\s+kya\\s+hai)\\b").containsMatchIn(lower)
            || text.contains("यह क्या है") || text.contains("सामने क्या है")
        ) {
            return RoutedAction.AnalyzeCameraOnFace(
                prompt = text,
                useFrontCamera = false,
                language = lang
            )
        }

        // 7. 20 Animal Sounds (Soundboard + All 20 Individual Animals in English, Hindi, Hinglish)
        if (lower.contains("animal sound") || lower.contains("animal sounds") || lower.contains("janwaron ki aawaz") || text.contains("जानवरों की आवाज़")) {
            val specific = AnimalSoundCatalog.findByText(text)
            return if (specific != null) {
                RoutedAction.PlayAnimal(specific, lang)
            } else {
                RoutedAction.OpenScreen(ScreenTarget.ANIMALS, language = lang)
            }
        }
        val soundTriggerWords = listOf(
            "sound", "noise", "bark", "meow", "moo", "roar", "trumpet", "whinny",
            "bleat", "quack", "cluck", "crow", "howl", "hiss", "croak", "chirp",
            "imitate", "aawaz", "awaaz", "bolta", "bolti",
            "आवाज़", "आवाज", "कैसे बोलता", "कैसे बोलती"
        )
        val asksAnimalVoice = soundTriggerWords.any { lower.contains(it) || text.contains(it) } ||
            lower.startsWith("play ") ||
            Regex("\\b(what|how)\\s+does\\s+a(n)?\\s+[a-z]+\\s+(say|go|sound)\\b").containsMatchIn(lower)
        if (asksAnimalVoice) {
            AnimalSoundCatalog.findByText(text)?.let { matchedAnimal ->
                return RoutedAction.PlayAnimal(matchedAnimal, lang)
            }
        }

        // 8. Music & Dance
        if (Regex("\\b(play\\s+music|open\\s+music|music\\s+player|local\\s+music|gaana\\s+chalao|gaana\\s+bajao)\\b").containsMatchIn(lower)
            || text.contains("गाना चलाओ") || text.contains("संगीत चलाओ")
        ) {
            return RoutedAction.OpenScreen(ScreenTarget.MUSIC_DANCE, language = lang)
        }
        if (Regex("\\b(let's\\s+dance|dance\\s+for\\s+me|start\\s+dancing|do\\s+a\\s+dance|dance\\s+mode|dancing\\s+(eyes|face|expression)|naach\\s+ke\\s+dikhao|dance\\s+karo)\\b").containsMatchIn(lower)
            || lower == "dance" || text.contains("नाच के दिखाओ") || text.contains("डांस करो")
        ) {
            return RoutedAction.SetExpression(NexExpression.DANCE, language = lang)
        }

        // 9. Mini-Games (All 7 Games + Arcade Hub)
        if (lower.contains("tic tac toe") || lower.contains("tic-tac-toe")) {
            return RoutedAction.OpenScreen(ScreenTarget.MINI_GAME, gameIndex = 0, language = lang)
        }
        if (lower.contains("memory match") || lower.contains("memory matrix") || lower.contains("memory game")) {
            return RoutedAction.OpenScreen(ScreenTarget.MINI_GAME, gameIndex = 1, language = lang)
        }
        if (lower.contains("falling star") || lower.contains("catch star") || lower.contains("star catcher")) {
            return RoutedAction.OpenScreen(ScreenTarget.MINI_GAME, gameIndex = 2, language = lang)
        }
        if (lower.contains("simon")) {
            return RoutedAction.OpenScreen(ScreenTarget.MINI_GAME, gameIndex = 3, language = lang)
        }
        if (lower.contains("whack") || lower.contains("glitch game")) {
            return RoutedAction.OpenScreen(ScreenTarget.MINI_GAME, gameIndex = 4, language = lang)
        }
        if (lower.contains("reaction game") || lower.contains("reaction speed") || lower.contains("reflex test")) {
            return RoutedAction.OpenScreen(ScreenTarget.MINI_GAME, gameIndex = 5, language = lang)
        }
        if (lower.contains("math quiz") || lower.contains("math game") || lower.contains("math blaster")) {
            return RoutedAction.OpenScreen(ScreenTarget.MINI_GAME, gameIndex = 6, language = lang)
        }
        if (Regex("\\b(open\\s+games|play\\s+a\\s+game|mini\\s*games|let's\\s+play|game\\s+khelo|games\\s+kholo)\\b").containsMatchIn(lower)
            || text.contains("गेम खेलो") || text.contains("गेम खोलो")
        ) {
            return RoutedAction.OpenScreen(ScreenTarget.GAMES_HUB, language = lang)
        }

        // 10. Current Time & Date
        if (Regex("\\b(open\\s+weather|weather\\s+station|open\\s+clock)\\b").containsMatchIn(lower)) {
            return RoutedAction.OpenScreen(ScreenTarget.WEATHER_TIME, language = lang)
        }
        if (Regex("\\b(what\\s+time\\s+is\\s+it|current\\s+time|tell\\s+me\\s+the\\s+time|kitne\\s+baje\\s+hain|samay\\s+kya\\s+hua|time\\s+kya\\s+hua)\\b").containsMatchIn(lower)
            || text.contains("समय क्या हुआ") || text.contains("कितने बजे")
        ) {
            return RoutedAction.SpeakTimeOrDate(askDateOnly = false, language = lang)
        }
        if (Regex("\\b(what\\s+is\\s+today's\\s+date|what's\\s+the\\s+date|today\\s+date|aaj\\s+ki\\s+tarikh|aaj\\s+kya\\s+tarikh|aaj\\s+kaun\\s+sa\\s+din)\\b").containsMatchIn(lower)
            || text.contains("आज की तारीख") || text.contains("आज कौन सा दिन")
        ) {
            return RoutedAction.SpeakTimeOrDate(askDateOnly = true, language = lang)
        }

        // 11. Live Weather
        if (Regex("\\b(weather|temperature|forecast|mausam)\\b").containsMatchIn(lower)
            || text.contains("मौसम") || text.contains("तापमान")
        ) {
            val cityMatch = Regex("\\b(?:in|at|for|of)\\s+([A-Za-z][A-Za-z\\s]{1,24})$", RegexOption.IGNORE_CASE)
                .find(text.trim('?', '.', '!'))
            val city = cityMatch?.groupValues?.get(1)?.trim()
            return RoutedAction.FetchWeather(cityHint = city, language = lang)
        }

        // 12. Direct Eye Expression Commands (All 32 Expressions + Voice Custom Color Presets)
        matchDirectExpressionCommand(lower, text)?.let { (expr, customColor) ->
            return RoutedAction.SetExpression(expr, customColorOverride = customColor, language = lang)
        }

        // 13. Everything else -> Real Google Gemini AI Backend (/chat, including grounded live search)
        return RoutedAction.AskGeminiChat(cleanText = text, language = lang)
    }

    private fun matchDirectExpressionCommand(lower: String, rawText: String): Pair<NexExpression, Int?>? {
        // Custom expression + optional color preset by voice
        if (Regex("\\b(custom\\s+(eyes?|face|expression|mode))\\b").containsMatchIn(lower) || rawText.contains("कस्टम आँखें")) {
            val colorOverride = when {
                lower.contains("green") -> Color.parseColor("#00E676")
                lower.contains("gold") || lower.contains("yellow") -> Color.parseColor("#FFD740")
                lower.contains("pink") || lower.contains("red") -> Color.parseColor("#FF4081")
                lower.contains("violet") || lower.contains("purple") -> Color.parseColor("#B388FF")
                lower.contains("cyan") || lower.contains("blue") -> Color.parseColor("#00E5FF")
                else -> null
            }
            return NexExpression.CUSTOM to colorOverride
        }

        // Short command guard so long open-ended questions still flow to Gemini
        if (lower.split(Regex("\\s+")).size > 8) return null

        val expr = when {
            Regex("\\b(normal\\s+(eyes|face|expression|mode)|default\\s+(eyes|face))\\b").containsMatchIn(lower)
                || rawText.contains("सामान्य आँखें") -> NexExpression.NORMAL

            Regex("\\b(double\\s+blink|blink\\s+twice|two\\s+blinks)\\b").containsMatchIn(lower)
                || rawText.contains("दो बार पलक") -> NexExpression.DOUBLE_BLINK

            Regex("\\b(blink|blink\\s+(your\\s+)?eyes|palak\\s+jhapkao)\\b").containsMatchIn(lower)
                || rawText.contains("पलक झपकाओ") -> NexExpression.BLINK

            Regex("\\b(very\\s+happy|super\\s+happy|extra\\s+happy|bahut\\s+khush)\\b").containsMatchIn(lower)
                || rawText.contains("बहुत खुश") -> NexExpression.VERY_HAPPY

            Regex("\\b(be\\s+happy|happy\\s+(face|eyes|expression)|look\\s+happy|smile|khush\\s+ho\\s+jao)\\b").containsMatchIn(lower)
                || rawText.contains("खुश हो जाओ") -> NexExpression.HAPPY

            Regex("\\b(be\\s+sad|sad\\s+(face|eyes|expression)|look\\s+sad|udas\\s+ho\\s+jao)\\b").containsMatchIn(lower)
                || rawText.contains("उदास चेहरा") -> NexExpression.SAD

            Regex("\\b(be\\s+angry|angry\\s+(face|eyes|expression)|look\\s+angry|gussa\\s+dikhao)\\b").containsMatchIn(lower)
                || rawText.contains("गुस्सा दिखाओ") -> NexExpression.ANGRY

            Regex("\\b(be\\s+surprised|surprised\\s+(face|eyes|expression)|look\\s+surprised|hairan\\s+ho\\s+jao)\\b").containsMatchIn(lower)
                || rawText.contains("हैरान चेहरा") -> NexExpression.SURPRISED

            Regex("\\b(sleepy\\s+(eyes|face|expression)|look\\s+sleepy|feel\\s+sleepy|neend\\s+aa\\s+rahi)\\b").containsMatchIn(lower)
                || rawText.contains("नींद आ रही") -> NexExpression.SLEEPY

            Regex("\\b(go\\s+to\\s+sleep|sleep\\s+(mode|face|eyes|expression)|sleeping\\s+(eyes|face|expression)|so\\s+jao)\\b").containsMatchIn(lower)
                || rawText.contains("सो जाओ") -> NexExpression.SLEEP

            Regex("\\b(thinking\\s+(face|eyes|expression|mode)|look\\s+thoughtful|sochne\\s+wala\\s+face)\\b").containsMatchIn(lower)
                || rawText.contains("सोचने वाला चेहरा") -> NexExpression.THINKING

            Regex("\\b(listening\\s+(eyes|face|mode|expression))\\b").containsMatchIn(lower) -> NexExpression.LISTENING

            Regex("\\b(speaking\\s+(eyes|face|mode|expression)|talking\\s+(eyes|face|expression))\\b").containsMatchIn(lower) -> NexExpression.SPEAKING

            Regex("\\b(laugh|laughing\\s+(face|eyes|expression)|haso|hasi\\s+dikhao)\\b").containsMatchIn(lower)
                || rawText.contains("हँसो") -> NexExpression.LAUGH

            Regex("\\b(cry|crying\\s+(face|eyes|expression)|ro\\s+ke\\s+dikhao)\\b").containsMatchIn(lower)
                || rawText.contains("रोने वाला चेहरा") -> NexExpression.CRY

            Regex("\\b(heart\\s+(eyes|face|expression)|show\\s+hearts?)\\b").containsMatchIn(lower)
                || rawText.contains("हार्ट आईज") -> NexExpression.HEART

            Regex("\\b(love\\s+(eyes|face|expression)|show\\s+love|i\\s+love\\s+you|love\\s+you\\s+nex|pyaar\\s+dikhao)\\b").containsMatchIn(lower)
                || rawText.contains("प्यार दिखाओ") -> NexExpression.LOVE

            Regex("\\b(be\\s+excited|excited\\s+(face|eyes|expression)|look\\s+excited)\\b").containsMatchIn(lower)
                || rawText.contains("उत्साहित") -> NexExpression.EXCITED

            Regex("\\b(be\\s+curious|curious\\s+(face|eyes|expression)|look\\s+curious)\\b").containsMatchIn(lower)
                || rawText.contains("जिज्ञासु") -> NexExpression.CURIOUS

            Regex("\\b(be\\s+confused|confused\\s+(face|eyes|expression)|look\\s+confused)\\b").containsMatchIn(lower)
                || rawText.contains("उलझन") -> NexExpression.CONFUSED

            Regex("\\b(be\\s+shy|shy\\s+(face|eyes|expression)|look\\s+shy|sharmila)\\b").containsMatchIn(lower)
                || rawText.contains("शर्मीला") -> NexExpression.SHY

            Regex("\\b(wink|winking\\s+(eyes|face|expression)|wink\\s+at\\s+me|aankh\\s+maaro)\\b").containsMatchIn(lower)
                || rawText.contains("आँख मारो") -> NexExpression.WINKING

            Regex("\\b(gaming\\s+(eyes|face|mode|expression)|gamer\\s+(face|eyes|mode))\\b").containsMatchIn(lower) -> NexExpression.GAMING

            Regex("\\b(be\\s+bored|bored\\s+(face|eyes|expression)|look\\s+bored)\\b").containsMatchIn(lower)
                || rawText.contains("बोर चेहरा") -> NexExpression.BORED

            Regex("\\b(be\\s+scared|scared\\s+(face|eyes|expression)|look\\s+scared|afraid\\s+eyes|dara\\s+hua)\\b").containsMatchIn(lower)
                || rawText.contains("डरा हुआ") -> NexExpression.SCARED

            Regex("\\b(be\\s+tired|tired\\s+(face|eyes|expression)|look\\s+tired|thaka\\s+hua)\\b").containsMatchIn(lower)
                || rawText.contains("थका हुआ") -> NexExpression.TIRED

            Regex("\\b(be\\s+proud|proud\\s+(face|eyes|expression)|look\\s+proud|garv\\s+dikhao)\\b").containsMatchIn(lower)
                || rawText.contains("गर्व") -> NexExpression.PROUD

            Regex("\\b(be\\s+suspicious|suspicious\\s+(face|eyes|expression)|look\\s+suspicious|shak\\s+wala)\\b").containsMatchIn(lower)
                || rawText.contains("शक वाला") -> NexExpression.SUSPICIOUS

            Regex("\\b(be\\s+focused|focused\\s+(face|eyes|expression|mode)|look\\s+focused|focus\\s+mode)\\b").containsMatchIn(lower)
                || rawText.contains("ध्यान केंद्रित") -> NexExpression.FOCUSED

            Regex("\\b(greeting\\s+(face|eyes|expression)|greet\\s+me|wave\\s+hello|namaste\\s+face)\\b").containsMatchIn(lower)
                || rawText.contains("नमस्ते चेहरा") -> NexExpression.GREETING

            else -> null
        }
        return expr?.let { it to null }
    }
}
