package com.nex.robotpet.v6.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.core.content.ContextCompat
import com.nex.robotpet.v6.util.CommandRouter
import com.nex.robotpet.v6.util.LanguageMode
import java.util.Locale
import java.util.UUID

/**
 * Complete Voice Interaction Manager for NEX 6.0 (Part C).
 *
 * Implements:
 * - Natural voice-first Speech-to-Text (STT) & Text-to-Speech (TTS).
 * - Ready to listen when the app is open in the foreground and microphone permission is granted,
 *   without requiring repeated microphone taps.
 * - English, Hindi (Devanagari), and Hinglish recognition & automatic natural TTS voice selection.
 * - Strict state machine preventing acoustic self-echo (never listens while TTS speaks, never speaks
 *   over the user) and bounded error cooldowns eliminating endless microphone restart loops.
 * - Honest microphone state tracking so NEX never claims to hear the user when the mic isn't working.
 */
class VoiceSystemManager(
    context: Context,
    private val callbacks: Listener
) {

    interface Listener {
        fun onListeningStateChanged(activeCommandListening: Boolean, wakeStandbyListening: Boolean)
        fun onMicRmsChanged(normalizedRms: Float)
        fun onSpeakingStateChanged(speaking: Boolean)
        fun onWakePhraseDetected()
        fun onUserSpeechRecognized(transcript: String)
        fun onVoiceDiagnosticError(safeMessage: String)
    }

    private enum class VoiceState {
        IDLE,
        WAKE_STANDBY,
        ACTIVE_COMMAND,
        SPEAKING,
        PAUSED
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady: Boolean = false

    private var state: VoiceState = VoiceState.IDLE
    private var foregroundWakeEnabled: Boolean = true
    private var consecutiveErrors: Int = 0
    private var activeNoMatchRetries: Int = 0
    private var pendingPostSpeechResumeWake: Boolean = false

    var isMicrophoneReady: Boolean = false
        private set

    var isUserCurrentlySpeaking: Boolean = false
        private set

    private val restartStandbyRunnable = Runnable {
        if (state != VoiceState.PAUSED && state != VoiceState.SPEAKING && foregroundWakeEnabled) {
            beginRecognitionInternal(isWakeStandby = true)
        }
    }

    init {
        initTextToSpeech()
    }

    private fun initTextToSpeech() {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.setPitch(1.12f) // Cute, sweet, friendly robot-pet voice pitch
                tts?.setSpeechRate(0.98f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        mainHandler.post {
                            state = VoiceState.SPEAKING
                            callbacks.onSpeakingStateChanged(true)
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        handleTtsFinished()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        handleTtsFinished()
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        handleTtsFinished()
                    }
                })
            }
        }
    }

    private fun handleTtsFinished() {
        mainHandler.postDelayed({
            if (state == VoiceState.PAUSED) return@postDelayed
            state = VoiceState.IDLE
            callbacks.onSpeakingStateChanged(false)
            if (pendingPostSpeechResumeWake && foregroundWakeEnabled && consecutiveErrors < MAX_HARD_ERRORS_BEFORE_PAUSE) {
                pendingPostSpeechResumeWake = false
                // Immediately ready for natural follow-up conversation without tapping
                beginRecognitionInternal(isWakeStandby = true)
            }
        }, 380L)
    }

    fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isMicrophoneActuallyListening(): Boolean {
        return hasMicPermission() && isMicrophoneReady &&
            (state == VoiceState.ACTIVE_COMMAND || state == VoiceState.WAKE_STANDBY)
    }

    fun setForegroundWakeEnabled(enabled: Boolean) {
        foregroundWakeEnabled = enabled
        if (!enabled && state == VoiceState.WAKE_STANDBY) {
            cancelRecognitionOnly()
            isMicrophoneReady = false
            state = VoiceState.IDLE
            callbacks.onListeningStateChanged(activeCommandListening = false, wakeStandbyListening = false)
        } else if (enabled && state == VoiceState.IDLE && hasMicPermission()) {
            consecutiveErrors = 0
            beginRecognitionInternal(isWakeStandby = true)
        }
    }

    /**
     * Trigger immediate active command listening (when user taps NEX's face or says "Hey NEX").
     */
    fun startActiveCommandListening() {
        if (!hasMicPermission()) {
            isMicrophoneReady = false
            callbacks.onVoiceDiagnosticError("Microphone permission is required so NEX can hear you.")
            return
        }
        mainHandler.removeCallbacks(restartStandbyRunnable)
        stopSpeaking()
        consecutiveErrors = 0
        activeNoMatchRetries = 0
        beginRecognitionInternal(isWakeStandby = false)
    }

    /**
     * Enter polite foreground listening standby if enabled and permitted.
     */
    fun resumeForegroundStandbyIfPermitted() {
        if (state == VoiceState.PAUSED) {
            state = VoiceState.IDLE
        }
        if (foregroundWakeEnabled && hasMicPermission() && state == VoiceState.IDLE) {
            consecutiveErrors = 0
            mainHandler.postDelayed(restartStandbyRunnable, 350L)
        }
    }

    fun speak(text: String, language: LanguageMode, resumeWakeAfter: Boolean = true) {
        val cleanText = cleanTextForNaturalSpeech(text)
        if (cleanText.isEmpty()) return

        mainHandler.removeCallbacks(restartStandbyRunnable)
        cancelRecognitionOnly()
        isMicrophoneReady = false
        isUserCurrentlySpeaking = false
        callbacks.onListeningStateChanged(activeCommandListening = false, wakeStandbyListening = false)

        if (!ttsReady || tts == null) {
            callbacks.onSpeakingStateChanged(false)
            return
        }

        val engine = tts ?: return
        val desiredLocale = language.preferredTtsLocale()
        val langResult = engine.setLanguage(desiredLocale)
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale("en", "IN"))
        }
        selectBestNaturalVoice(engine, desiredLocale)
        engine.setPitch(1.12f)
        engine.setSpeechRate(if (language == LanguageMode.ENGLISH) 0.98f else 0.96f)

        pendingPostSpeechResumeWake = resumeWakeAfter
        state = VoiceState.SPEAKING
        callbacks.onSpeakingStateChanged(true)
        val utteranceId = "nex6_${UUID.randomUUID()}"
        engine.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    private fun selectBestNaturalVoice(engine: TextToSpeech, desiredLocale: Locale) {
        try {
            val available = engine.voices ?: return
            val targetLang = desiredLocale.language.lowercase(Locale.ROOT)
            val targetCountry = desiredLocale.country.lowercase(Locale.ROOT)
            val best = available
                .filter { v ->
                    v.locale != null &&
                        v.locale.language.lowercase(Locale.ROOT) == targetLang &&
                        !v.features.orEmpty().contains("legacySetLanguageVoice")
                }
                .maxWithOrNull(
                    compareBy<Voice> { it.locale.country.lowercase(Locale.ROOT) == targetCountry }
                        .thenBy { it.quality }
                        .thenBy { !it.isNetworkConnectionRequired }
                )
            if (best != null) {
                engine.voice = best
            }
        } catch (_: Exception) {
            // Keep default engine voice if custom selection is unsupported on device
        }
    }

    fun stopSpeaking() {
        pendingPostSpeechResumeWake = false
        try {
            if (tts?.isSpeaking == true) {
                tts?.stop()
            }
        } catch (_: Exception) {
            // Safe stop
        }
        if (state == VoiceState.SPEAKING) {
            state = VoiceState.IDLE
        }
        callbacks.onSpeakingStateChanged(false)
    }

    fun pauseForLifecycle() {
        state = VoiceState.PAUSED
        isMicrophoneReady = false
        isUserCurrentlySpeaking = false
        mainHandler.removeCallbacksAndMessages(null)
        stopSpeaking()
        destroyRecognizerSafely()
        callbacks.onListeningStateChanged(activeCommandListening = false, wakeStandbyListening = false)
    }

    fun shutdown() {
        pauseForLifecycle()
        try {
            tts?.shutdown()
        } catch (_: Exception) {
            // Safe cleanup
        }
        tts = null
        ttsReady = false
    }

    private fun beginRecognitionInternal(isWakeStandby: Boolean) {
        if (!hasMicPermission() || state == VoiceState.PAUSED || state == VoiceState.SPEAKING) {
            isMicrophoneReady = false
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            isMicrophoneReady = false
            callbacks.onVoiceDiagnosticError("Android Speech Recognition service is unavailable on this device.")
            return
        }

        cancelRecognitionOnly()
        ensureRecognizerCreated()

        state = if (isWakeStandby) VoiceState.WAKE_STANDBY else VoiceState.ACTIVE_COMMAND

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // en-IN + additional hi-IN & en-US supports English, Hinglish, and Hindi cleanly
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putStringArrayListExtra(
                "android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES",
                arrayListOf("hi-IN", "en-US")
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 600L)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        }

        try {
            val recognizer = speechRecognizer
            if (recognizer == null) {
                isMicrophoneReady = false
                handleRecognitionError(SpeechRecognizer.ERROR_CLIENT)
                return
            }
            recognizer.startListening(intent)
        } catch (_: Exception) {
            isMicrophoneReady = false
            handleRecognitionError(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    private fun ensureRecognizerCreated() {
        if (speechRecognizer != null) return
        val recognizer = try {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        } catch (_: Exception) {
            isMicrophoneReady = false
            callbacks.onVoiceDiagnosticError("Speech recognition engine could not be initialized on this device.")
            return
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                consecutiveErrors = 0
                isMicrophoneReady = true
                callbacks.onListeningStateChanged(
                    activeCommandListening = (state == VoiceState.ACTIVE_COMMAND),
                    wakeStandbyListening = (state == VoiceState.WAKE_STANDBY)
                )
            }

            override fun onBeginningOfSpeech() {
                isUserCurrentlySpeaking = true
                if (state == VoiceState.WAKE_STANDBY) {
                    callbacks.onListeningStateChanged(activeCommandListening = true, wakeStandbyListening = true)
                }
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Ignore low ambient background hum below noise floor
                val norm = ((rmsdB - 1.0f) / 11f).coerceIn(0f, 1f)
                callbacks.onMicRmsChanged(norm)
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                isUserCurrentlySpeaking = false
                callbacks.onMicRmsChanged(0f)
            }

            override fun onError(error: Int) {
                isUserCurrentlySpeaking = false
                isMicrophoneReady = false
                callbacks.onMicRmsChanged(0f)
                handleRecognitionError(error)
            }

            override fun onResults(results: Bundle?) {
                isUserCurrentlySpeaking = false
                callbacks.onMicRmsChanged(0f)
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                handleTranscriptCandidates(matches)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (state == VoiceState.WAKE_STANDBY) {
                    val partials = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    if (partials.any { containsWakePhrase(it) }) {
                        callbacks.onWakePhraseDetected()
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        speechRecognizer = recognizer
    }

    private fun handleTranscriptCandidates(candidates: List<String>) {
        val top = candidates.firstOrNull { it.isNotBlank() }?.trim()
        if (top.isNullOrEmpty()) {
            handleRecognitionError(SpeechRecognizer.ERROR_NO_MATCH)
            return
        }

        consecutiveErrors = 0
        activeNoMatchRetries = 0
        val wasWakeStandby = (state == VoiceState.WAKE_STANDBY)
        state = VoiceState.IDLE
        isMicrophoneReady = false
        callbacks.onListeningStateChanged(activeCommandListening = false, wakeStandbyListening = false)

        if (wasWakeStandby) {
            val wakeCandidate = candidates.firstOrNull { containsWakePhrase(it) }
            if (wakeCandidate != null) {
                val (_, remainder) = CommandRouter.stripWakePhrase(normalizeWakeAliases(wakeCandidate))
                callbacks.onWakePhraseDetected()
                if (remainder.length >= 2) {
                    callbacks.onUserSpeechRecognized(remainder)
                } else {
                    mainHandler.postDelayed({ startActiveCommandListening() }, 180L)
                }
            } else if (isMeaningfulUtterance(top)) {
                // Voice-first natural conversation: accept clear speech while app is open in foreground
                val (_, stripped) = CommandRouter.stripWakePhrase(normalizeWakeAliases(top))
                callbacks.onUserSpeechRecognized(stripped.ifBlank { top })
            } else {
                // Background noise / non-speech syllable: return to calm standby
                scheduleCalmStandbyRestart(1400L)
            }
        } else {
            val (_, stripped) = CommandRouter.stripWakePhrase(normalizeWakeAliases(top))
            callbacks.onUserSpeechRecognized(stripped.ifBlank { top })
        }
    }

    private fun handleRecognitionError(errorCode: Int) {
        if (state == VoiceState.PAUSED || state == VoiceState.SPEAKING) return

        val wasActive = (state == VoiceState.ACTIVE_COMMAND)
        state = VoiceState.IDLE
        isMicrophoneReady = false
        callbacks.onListeningStateChanged(activeCommandListening = false, wakeStandbyListening = false)

        if (errorCode == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            callbacks.onVoiceDiagnosticError("Microphone permission denied. Please grant microphone access so NEX can hear you.")
            return
        }

        if (errorCode == SpeechRecognizer.ERROR_NO_MATCH || errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            if (wasActive && activeNoMatchRetries < 1) {
                activeNoMatchRetries++
                mainHandler.postDelayed({ beginRecognitionInternal(isWakeStandby = false) }, 650L)
                return
            }
            activeNoMatchRetries = 0
            if (foregroundWakeEnabled) {
                scheduleCalmStandbyRestart(2200L)
            }
            return
        }

        consecutiveErrors++
        if (errorCode == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || errorCode == SpeechRecognizer.ERROR_CLIENT) {
            destroyRecognizerSafely()
        }

        if (consecutiveErrors >= MAX_HARD_ERRORS_BEFORE_PAUSE) {
            callbacks.onVoiceDiagnosticError("Voice standby paused after repeated mic errors. Tap NEX's face to talk.")
            return
        }

        val backoffMs = (1800L * (1 shl (consecutiveErrors - 1))).coerceAtMost(8000L)
        if (foregroundWakeEnabled) {
            scheduleCalmStandbyRestart(backoffMs)
        }
    }

    private fun scheduleCalmStandbyRestart(delayMs: Long) {
        mainHandler.removeCallbacks(restartStandbyRunnable)
        if (foregroundWakeEnabled && state != VoiceState.PAUSED && state != VoiceState.SPEAKING) {
            mainHandler.postDelayed(restartStandbyRunnable, delayMs)
        }
    }

    private fun cancelRecognitionOnly() {
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {
            // Safe cancel
        }
    }

    private fun destroyRecognizerSafely() {
        val rec = speechRecognizer
        speechRecognizer = null
        if (rec != null) {
            try {
                rec.cancel()
            } catch (_: Exception) {
            }
            try {
                rec.destroy()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val MAX_HARD_ERRORS_BEFORE_PAUSE = 3

        private val NOISE_FILLERS = setOf(
            "uh", "um", "hmm", "hm", "ah", "eh", "oh", "mm", "huh"
        )

        fun containsWakePhrase(raw: String): Boolean {
            val lower = raw.lowercase().trim()
            val triggers = listOf(
                "hey nex", "hello nex", "hi nex", "ok nex", "okay nex",
                "hay nex", "hey next", "hello next", "हे नेक्स", "हेलो नेक्स"
            )
            return triggers.any { lower.contains(it) }
        }

        fun normalizeWakeAliases(raw: String): String {
            return raw
                .replace(Regex("^(hey|hello|hi)\\s+next\\b", RegexOption.IGNORE_CASE), "Hey NEX")
                .trim()
        }

        fun isMeaningfulUtterance(raw: String): Boolean {
            val cleaned = raw.trim()
            if (cleaned.length < 2) return false
            if (cleaned.lowercase(Locale.ROOT) in NOISE_FILLERS) return false
            return cleaned.any { it.isLetterOrDigit() }
        }

        fun cleanTextForNaturalSpeech(raw: String): String {
            return raw
                .replace(Regex("[*_#`~]+"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }
}
