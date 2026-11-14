package com.nex.robotpet.v6

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nex.robotpet.v6.audio.AnimalSoundPlayer
import com.nex.robotpet.v6.data.AnimalSoundItem
import com.nex.robotpet.v6.data.NexExpression
import com.nex.robotpet.v6.memory.MemoryManager
import com.nex.robotpet.v6.network.BackendResult
import com.nex.robotpet.v6.network.NetworkStatusMonitor
import com.nex.robotpet.v6.network.NexBackendClient
import com.nex.robotpet.v6.ui.RobotEyesView
import com.nex.robotpet.v6.util.CommandRouter
import com.nex.robotpet.v6.util.FullscreenHelper
import com.nex.robotpet.v6.util.LanguageMode
import com.nex.robotpet.v6.util.RoutedAction
import com.nex.robotpet.v6.util.ScreenTarget
import com.nex.robotpet.v6.voice.VoiceSystemManager
import com.nex.robotpet.v6.weather.WeatherResult
import com.nex.robotpet.v6.weather.WeatherService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Pure Full-Screen Interactive Robot Face Activity for NEX 6.0 (Parts A, B, C, D, E, K, L, M).
 *
 * Strict Main Screen Design:
 * - Pure black (#000000) background.
 * - Two large glowing square EMO-inspired robot eyes ([RobotEyesView]).
 * - Single passive small ONLINE/OFFLINE indicator in the top-right corner.
 * - Zero long-press menus, zero hidden hubs, zero tap-on-status-pill panels, zero buttons,
 *   zero headers/subtitles, and zero extra text on the main screen.
 *
 * External Configuration (Does Not Touch Main Screen):
 * - Default Render URL (`https://nex-ai-robot-pet-6-backend.onrender.com`) works automatically.
 * - Custom HTTPS URL, Privacy & Custom Eye presets live in [BackendSetupActivity], opened ONLY from
 *   the Android Home Screen app-icon shortcut ("Backend Setup"), Android System Settings -> Apps -> NEX ->
 *   "Additional settings in the app", or `nex6://setup`.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var robotEyesView: RobotEyesView
    private lateinit var statusDot: View
    private lateinit var statusText: TextView

    private lateinit var memoryManager: MemoryManager
    private lateinit var networkMonitor: NetworkStatusMonitor
    private lateinit var backendClient: NexBackendClient
    private lateinit var weatherService: WeatherService
    private lateinit var animalSoundPlayer: AnimalSoundPlayer
    private lateinit var voiceManager: VoiceSystemManager
    private lateinit var gestureDetector: GestureDetector

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastUserInteractionMs: Long = SystemClock.elapsedRealtime()
    private var lastAutonomousSpeechMs: Long = 0L
    private var isBusyThinking: Boolean = false

    private val requestMicPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                voiceManager.setForegroundWakeEnabled(memoryManager.isWakeWordForegroundEnabled)
                voiceManager.startActiveCommandListening()
            } else {
                Toast.makeText(
                    this,
                    "Microphone permission is required so NEX can hear your voice.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    // Gentle non-intrusive idle robot-pet behavior when quiet for >55s
    private val autonomousPetLoop = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val idleMs = now - lastUserInteractionMs
            if (memoryManager.isAutonomousIdleEnabled &&
                !isBusyThinking &&
                !robotEyesView.isSpeaking &&
                robotEyesView.currentExpression != NexExpression.LISTENING &&
                idleMs > 55_000L
            ) {
                val idleExpressions = listOf(
                    NexExpression.CURIOUS,
                    NexExpression.HAPPY,
                    NexExpression.BLINK,
                    NexExpression.SLEEP
                )
                robotEyesView.setExpression(idleExpressions.random())

                if (now - lastAutonomousSpeechMs > 240_000L) {
                    lastAutonomousSpeechMs = now
                    val name = memoryManager.userName
                    val line = if (!name.isNullOrBlank()) {
                        "Hey $name, say Hey NEX or tap my eyes anytime!"
                    } else {
                        "Say Hey NEX or tap my eyes whenever you want to talk or play!"
                    }
                    voiceManager.speak(line, LanguageMode.ENGLISH)
                }
            }
            mainHandler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        FullscreenHelper.applyImmersiveLandscape(this)

        memoryManager = MemoryManager(this)
        backendClient = NexBackendClient()
        weatherService = WeatherService()
        animalSoundPlayer = AnimalSoundPlayer(this)

        robotEyesView = findViewById(R.id.robotEyesView)
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)

        robotEyesView.customStyle = memoryManager.getCustomEyeStyle()
        robotEyesView.setExpression(NexExpression.HAPPY)

        networkMonitor = NetworkStatusMonitor(this) { isOnline ->
            statusDot.setBackgroundResource(if (isOnline) R.drawable.dot_online else R.drawable.dot_offline)
            statusText.text = getString(if (isOnline) R.string.status_online else R.string.status_offline)
        }

        voiceManager = VoiceSystemManager(this, object : VoiceSystemManager.Listener {
            override fun onListeningStateChanged(activeCommandListening: Boolean, wakeStandbyListening: Boolean) {
                if (activeCommandListening) {
                    robotEyesView.setExpression(NexExpression.LISTENING)
                } else if (!isBusyThinking && !robotEyesView.isSpeaking &&
                    robotEyesView.currentExpression == NexExpression.LISTENING
                ) {
                    robotEyesView.setExpression(NexExpression.HAPPY)
                }
            }

            override fun onMicRmsChanged(normalizedRms: Float) {
                robotEyesView.micLevelNormalized = normalizedRms
            }

            override fun onSpeakingStateChanged(speaking: Boolean) {
                robotEyesView.isSpeaking = speaking
                if (!speaking && !isBusyThinking && robotEyesView.currentExpression == NexExpression.THINKING) {
                    robotEyesView.setExpression(NexExpression.HAPPY)
                }
            }

            override fun onWakePhraseDetected() {
                markInteraction()
                animalSoundPlayer.stop()
                robotEyesView.setExpression(NexExpression.LISTENING)
            }

            override fun onUserSpeechRecognized(transcript: String) {
                markInteraction()
                handleUserUtterance(transcript)
            }

            override fun onVoiceDiagnosticError(safeMessage: String) {
                if (!safeMessage.contains("standby paused", ignoreCase = true)) {
                    Toast.makeText(this@MainActivity, safeMessage, Toast.LENGTH_SHORT).show()
                }
            }
        })

        setupPurePetTouchInteractions()

        if (!voiceManager.hasMicPermission()) {
            requestMicPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onResume() {
        super.onResume()
        FullscreenHelper.applyImmersiveLandscape(this)
        robotEyesView.customStyle = memoryManager.getCustomEyeStyle()
        networkMonitor.start()
        voiceManager.setForegroundWakeEnabled(memoryManager.isWakeWordForegroundEnabled)
        if (voiceManager.hasMicPermission()) {
            voiceManager.resumeForegroundStandbyIfPermitted()
        }
        mainHandler.postDelayed(autonomousPetLoop, 30_000L)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(autonomousPetLoop)
        animalSoundPlayer.stop()
        networkMonitor.stop()
        voiceManager.pauseForLifecycle()
        super.onPause()
    }

    override fun onDestroy() {
        animalSoundPlayer.stop()
        voiceManager.shutdown()
        super.onDestroy()
    }

    private fun markInteraction() {
        lastUserInteractionMs = SystemClock.elapsedRealtime()
    }

    /**
     * Pure robot-pet touch interactions ONLY (zero menus, zero long-press dialogs):
     * - Single tap: wake NEX to listen (or stop current speech/animal audio).
     * - Double tap: playful robot eye giggle/affection reaction.
     * - Smooth drag/petting: NEX's pupils follow your finger and react affectionately.
     */
    private fun setupPurePetTouchInteractions() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                markInteraction()
                if (animalSoundPlayer.isPlaying()) {
                    animalSoundPlayer.stop()
                    robotEyesView.setExpression(NexExpression.HAPPY)
                    return true
                }
                if (robotEyesView.isSpeaking) {
                    voiceManager.stopSpeaking()
                    robotEyesView.setExpression(NexExpression.HAPPY)
                    return true
                }
                if (voiceManager.hasMicPermission()) {
                    voiceManager.startActiveCommandListening()
                } else {
                    requestMicPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                markInteraction()
                val playful = listOf(
                    NexExpression.LOVE,
                    NexExpression.LAUGH,
                    NexExpression.EXCITED
                ).random()
                robotEyesView.setExpression(playful)
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                markInteraction()
                if (robotEyesView.width > 0 && robotEyesView.height > 0) {
                    val nx = ((e2.x / robotEyesView.width) - 0.5f) * 2f
                    val ny = ((e2.y / robotEyesView.height) - 0.5f) * 2f
                    robotEyesView.setGazeTarget(nx, ny)
                    if (abs(distanceX) > 22f) {
                        robotEyesView.setExpression(NexExpression.LOVE)
                    }
                }
                return true
            }
        }).apply {
            // Explicitly disable long-press so holding the screen NEVER opens any menu or vibrates
            setIsLongpressEnabled(false)
        }

        robotEyesView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }
    }

    private fun handleUserUtterance(utterance: String) {
        when (val action = CommandRouter.route(utterance)) {
            is RoutedAction.SpeakImmediate -> {
                robotEyesView.setExpression(action.expression)
                voiceManager.speak(action.speech, action.language)
            }

            is RoutedAction.PlayAnimal -> {
                playAnimalDirectlyOnFace(action.animal)
            }

            is RoutedAction.SetExpression -> {
                if (action.customColorOverride != null) {
                    val updated = memoryManager.getCustomEyeStyle().copy(colorArgb = action.customColorOverride)
                    memoryManager.saveCustomEyeStyle(updated)
                    robotEyesView.customStyle = updated
                }
                robotEyesView.setExpression(action.expression)
                val ack = when (action.language) {
                    LanguageMode.HINDI -> "यह देखो मेरा ${action.expression.displayName} एक्सप्रेशन!"
                    LanguageMode.HINGLISH -> "Yeh dekho mera ${action.expression.displayName} expression!"
                    LanguageMode.ENGLISH -> "Showing my ${action.expression.displayName} expression!"
                }
                voiceManager.speak(ack, action.language)
            }

            is RoutedAction.OpenScreen -> {
                navigateToScreen(action.screen, action.gameIndex, action.initialVisionPrompt)
            }

            is RoutedAction.SpeakTimeOrDate -> {
                val now = Date()
                val reply = if (action.askDateOnly) {
                    val d = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.US).format(now)
                    when (action.language) {
                        LanguageMode.HINDI -> "आज की तारीख $d है।"
                        LanguageMode.HINGLISH -> "Aaj ki tarikh $d hai."
                        LanguageMode.ENGLISH -> "Today is $d."
                    }
                } else {
                    val t = SimpleDateFormat("hh:mm a", Locale.US).format(now)
                    val d = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.US).format(now)
                    when (action.language) {
                        LanguageMode.HINDI -> "अभी समय $t हुआ है, और आज $d है।"
                        LanguageMode.HINGLISH -> "Abhi samay $t hua hai, aur aaj $d hai."
                        LanguageMode.ENGLISH -> "It is currently $t on $d."
                    }
                }
                robotEyesView.setExpression(NexExpression.HAPPY)
                voiceManager.speak(reply, action.language)
            }

            is RoutedAction.FetchWeather -> {
                speakLiveWeather(action.cityHint ?: "Bagaha", action.language)
            }

            is RoutedAction.SaveUserName -> {
                if (memoryManager.isMemoryEnabled) {
                    memoryManager.userName = action.name
                    robotEyesView.setExpression(NexExpression.LOVE)
                    val msg = when (action.language) {
                        LanguageMode.HINDI -> "आपसे मिलकर बहुत खुशी हुई, ${action.name}! मैं आपका नाम याद रखूँगा।"
                        LanguageMode.HINGLISH -> "Tumse milkar bahut khushi hui, ${action.name}! Main tumhara naam yaad rakhunga."
                        LanguageMode.ENGLISH -> "Nice to meet you, ${action.name}! I will remember your name."
                    }
                    voiceManager.speak(msg, action.language)
                } else {
                    robotEyesView.setExpression(NexExpression.CURIOUS)
                    voiceManager.speak(
                        "Hello ${action.name}! Conversation memory is currently switched off.",
                        action.language
                    )
                }
            }

            is RoutedAction.SetMemoryEnabled -> {
                memoryManager.isMemoryEnabled = action.enabled
                robotEyesView.setExpression(NexExpression.HAPPY)
                val msg = if (action.enabled) {
                    "Conversation memory is now enabled."
                } else {
                    "Conversation memory has been disabled and stored context cleared."
                }
                voiceManager.speak(msg, action.language)
            }

            is RoutedAction.ClearMemory -> {
                memoryManager.clearAllUserData(includeBackendUrl = false)
                robotEyesView.setExpression(NexExpression.HAPPY)
                val msg = when (action.language) {
                    LanguageMode.HINDI -> "आपका सहेजा गया नाम और बातचीत का डेटा साफ़ कर दिया गया है।"
                    LanguageMode.HINGLISH -> "Tumhara saved naam aur conversation history clear kar diya gaya hai."
                    LanguageMode.ENGLISH -> "All stored user memory and conversation history have been cleared."
                }
                voiceManager.speak(msg, action.language)
            }

            is RoutedAction.AskGeminiChat -> {
                askGeminiBackend(action.cleanText, action.language)
            }
        }
    }

    private fun playAnimalDirectlyOnFace(item: AnimalSoundItem) {
        voiceManager.stopSpeaking()
        robotEyesView.setExpression(item.reactionExpression)
        animalSoundPlayer.play(item.rawResId) {
            runOnUiThread {
                robotEyesView.setExpression(NexExpression.HAPPY)
                voiceManager.resumeForegroundStandbyIfPermitted()
            }
        }
    }

    private fun speakLiveWeather(city: String, lang: LanguageMode) {
        if (!networkMonitor.isCurrentlyOnline()) {
            robotEyesView.setExpression(NexExpression.CONFUSED)
            val offlineMsg = when (lang) {
                LanguageMode.HINDI -> "इंटरनेट अभी ऑफ़लाइन है, इसलिए मैं लाइव मौसम नहीं देख सकता।"
                LanguageMode.HINGLISH -> "Internet abhi offline hai, isliye main live mausam nahi check kar sakta."
                LanguageMode.ENGLISH -> "I am offline right now, so I cannot fetch live weather."
            }
            voiceManager.speak(offlineMsg, lang)
            return
        }

        isBusyThinking = true
        robotEyesView.setExpression(NexExpression.THINKING)
        lifecycleScope.launch {
            val res = weatherService.fetchByCityName(city)
            isBusyThinking = false
            when (res) {
                is WeatherResult.Success -> {
                    robotEyesView.setExpression(NexExpression.HAPPY)
                    voiceManager.speak(res.report.toSpokenSummary(lang), lang)
                }
                is WeatherResult.Failure -> {
                    robotEyesView.setExpression(NexExpression.SAD)
                    voiceManager.speak(res.reason, LanguageMode.ENGLISH)
                }
            }
        }
    }

    private fun askGeminiBackend(userText: String, lang: LanguageMode) {
        if (!networkMonitor.isCurrentlyOnline()) {
            robotEyesView.setExpression(NexExpression.SAD)
            val msg = when (lang) {
                LanguageMode.HINDI -> "इंटरनेट ऑफ़लाइन है। ऑनलाइन AI बातचीत के लिए कृपया इंटरनेट से जुड़ें, या ऑफ़लाइन कमांड और गेम्स का उपयोग करें।"
                LanguageMode.HINGLISH -> "Internet abhi offline hai. Gemini AI se baat karne ke liye internet on karein, ya mere offline games aur animal sounds try karein!"
                LanguageMode.ENGLISH -> "Internet is currently offline. Connect to the internet for Gemini AI answers, or enjoy my offline expressions, animal sounds, and mini-games!"
            }
            voiceManager.speak(msg, lang)
            return
        }

        val baseUrl = memoryManager.backendBaseUrl
        if (MemoryManager.validateHttpsBackendUrl(baseUrl) == null) {
            robotEyesView.setExpression(NexExpression.CURIOUS)
            voiceManager.speak(
                "Please set your HTTPS Render backend URL using the Backend Setup shortcut on the NEX app icon.",
                LanguageMode.ENGLISH
            )
            return
        }

        isBusyThinking = true
        robotEyesView.setExpression(NexExpression.THINKING)

        lifecycleScope.launch {
            val history = memoryManager.getConversationHistory()
            val result = backendClient.sendChat(
                baseUrlRaw = baseUrl,
                message = userText,
                languageHint = lang,
                userName = memoryManager.userName,
                allowMemory = memoryManager.isMemoryEnabled,
                history = history
            )
            isBusyThinking = false

            when (result) {
                is BackendResult.Success -> {
                    val data = result.data
                    if (memoryManager.isMemoryEnabled) {
                        if (!data.rememberedName.isNullOrBlank()) {
                            memoryManager.userName = data.rememberedName
                        }
                        memoryManager.appendConversationExchange(userText, data.reply)
                    }
                    robotEyesView.setExpression(data.expression)
                    voiceManager.speak(data.reply, data.language)
                }
                is BackendResult.Error -> {
                    robotEyesView.setExpression(NexExpression.SAD)
                    voiceManager.speak(result.safeMessage, LanguageMode.ENGLISH)
                }
            }
        }
    }

    private fun navigateToScreen(
        target: ScreenTarget,
        gameIndex: Int? = null,
        initialVisionPrompt: String? = null
    ) {
        when (target) {
            ScreenTarget.ANIMALS -> startActivity(Intent(this, AnimalSoundsActivity::class.java))
            ScreenTarget.VISION -> {
                val intent = Intent(this, VisionActivity::class.java)
                if (!initialVisionPrompt.isNullOrBlank()) {
                    intent.putExtra(VisionActivity.EXTRA_INITIAL_PROMPT, initialVisionPrompt)
                }
                startActivity(intent)
            }
            ScreenTarget.MUSIC_DANCE -> startActivity(Intent(this, MusicDanceActivity::class.java))
            ScreenTarget.GAMES_HUB -> startActivity(Intent(this, GamesHubActivity::class.java))
            ScreenTarget.MINI_GAME -> {
                startActivity(
                    Intent(this, MiniGameActivity::class.java)
                        .putExtra(MiniGameActivity.EXTRA_GAME_INDEX, gameIndex ?: 0)
                )
            }
            ScreenTarget.WEATHER_TIME -> startActivity(Intent(this, WeatherTimeActivity::class.java))
        }
    }
}
