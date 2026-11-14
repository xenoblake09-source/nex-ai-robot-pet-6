package com.nex.robotpet.v6.util

import java.util.Locale

enum class LanguageMode(val apiCode: String) {
    ENGLISH("en"),
    HINDI("hi"),
    HINGLISH("hinglish");

    fun preferredTtsLocale(): Locale = when (this) {
        HINDI -> Locale("hi", "IN")
        HINGLISH -> Locale("en", "IN")
        ENGLISH -> Locale("en", "IN")
    }
}

object LanguageHelper {

    const val EXACT_CREATOR_RESPONSE = "ARNAV, DIVYA AND ARYAN created me."

    private val devanagariRegex = Regex("[\u0900-\u097F]")
    private val auraWordRegex = Regex("\\bAURA\\b", RegexOption.IGNORE_CASE)

    private val hinglishVocabulary = setOf(
        "kya", "kaise", "kaisa", "kaisi", "hai", "hain", "ho", "hoon", "hun",
        "mera", "meri", "mere", "tum", "tumhara", "tumhari", "aap", "aapka",
        "kaun", "kahan", "kab", "kyun", "kyon", "kisne", "banaya", "batao",
        "sunao", "dikhao", "mujhe", "hum", "nahi", "haan", "accha", "achha",
        "bahut", "namaste", "shukriya", "dhanyawad", "mausam", "samay", "waqt",
        "tarikh", "tareekh", "khel", "gaana", "naach", "pyaar", "dost", "yaar",
        "bataiye", "boliye", "bolo", "suno", "aaj", "abhi"
    )

    private val creatorPatterns = listOf(
        Regex("\\bwho\\s+(created|made|built|designed|developed|invented|programmed)\\s+(you|nex)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bwho\\s+(are|is)\\s+your\\s+(creator|creators|maker|makers|builder|builders|developer|developers)\\b", RegexOption.IGNORE_CASE),
        Regex("\\byour\\s+(creator|creators|maker|makers)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(tumhe|tumko|aapko|nex\\s+ko)\\s+kisne\\s+(banaya|create\\s+kiya|develop\\s+kiya)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bkisne\\s+banaya\\s+(hai\\s+)?(tumhe|aapko|nex)\\b", RegexOption.IGNORE_CASE),
        Regex("(तुम्हें|आपको|नेक्स\\s*को)\\s*किसने\\s*बनाया"),
        Regex("तुम्हारे\\s*(निर्माता|क्रिएटर)\\s*कौन")
    )

    fun detectLanguage(text: String): LanguageMode {
        if (devanagariRegex.containsMatchIn(text)) {
            return LanguageMode.HINDI
        }
        val words = Regex("[a-zA-Z']+").findAll(text.lowercase()).map { it.value }.toList()
        val hits = words.count { it in hinglishVocabulary }
        return if (hits >= 2 || (words.size in 1..4 && hits >= 1)) {
            LanguageMode.HINGLISH
        } else {
            LanguageMode.ENGLISH
        }
    }

    fun isCreatorQuestion(text: String): Boolean {
        val normalized = text.trim().replace(Regex("\\s+"), " ")
        return creatorPatterns.any { it.containsMatchIn(normalized) }
    }

    fun sanitizeIdentityOutput(text: String, creatorAsked: Boolean = false): String {
        if (creatorAsked) return EXACT_CREATOR_RESPONSE
        return auraWordRegex.replace(text, "NEX").trim()
    }
}
