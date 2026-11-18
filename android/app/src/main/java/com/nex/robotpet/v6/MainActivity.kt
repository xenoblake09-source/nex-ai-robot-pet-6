package com.nex.robotpet.v6

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
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
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Pure Full-Screen Interactive Robot Face Activity for NEX 6.0 (Parts A, B, C, D, E, G, K, L, M).
 *
 * Strict Main Screen Design Lock:
 * - Pure black (#000000) background.
 * - Two large glowing square EMO-inspired robot eyes ([RobotEyesView]) across 32 expressions.
 * - Single passive small ONLINE/OFFLINE indicator in the top-right corner.
 * - Zero long-press menus, zero hidden hubs, zero tap-on-status-pill panels, zero buttons,
 *   zero headers/subtitles, and zero extra text on the main screen.
 * - Background CameraX + ML Kit support for voice-triggered object recognition ("What is this?")
 *   and face detection/tracking right on the pure robot face without altering the layout or
 *   secretly recording/uploading images.
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
    private var lastBackendWarmupMs: Long = 0L
    private var isBusyThinking: Boolean = false

    // Minimal permission-gated background camera intelligence (active ONLY when requested by user)
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService? = null
    private var faceDetector: FaceDetector? = null
    private var isFaceTrackingActiveOnFace: Boolean = false
    private var pendingCameraAction: (() -> Unit)? = null
    private var pendingCameraLang: LanguageMode = LanguageMode.ENGLISH

    private val requestMicPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                voiceManager.setForegroundWakeEnabled(memoryManager.isWakeWordForegroundEnabled)
                voiceManager.startActiveCommandListening()
            } else {
                robotEyesView.setExpression(NexExpression.SAD)
                Toast.makeText(
                    this,
                    "Microphone permission is required so NEX can hear your voice.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    private val requestCameraPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingCameraAction
            pendingCameraAction = null
            if (granted) {
                action?.invoke()
            } else {
                robotEyesView.setExpression(NexExpression.SAD)
                val msg = when (pendingCameraLang) {
                    LanguageMode.HINDI -> "कैमरा अनुमति नहीं दी गई है, इसलिए मैं अभी कैमरे से नहीं देख सकता।"
                    LanguageMode.HINGLISH -> "Camera permission nahi mili, isliye main abhi camera se nahi dekh sakta."
                    LanguageMode.ENGLISH -> "Camera permission was not granted, so I cannot use the camera right now."
                }
                voiceManager.speak(msg, pendingCameraLang)
            }
        }

    // Calm, non-random idle pet behavior: avoids unnecessary random expression changes
    // and occasionally initiates friendly conversation when quiet for a long time.
    private val autonomousPetLoop = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val idleMs = now - lastUserInteractionMs
            if (memoryManager.isAutonomousIdleEnabled &&
                !isBusyThinking &&
                !robotEyesView.isSpeaking &&
                !voiceManager.isUserCurrentlySpeaking &&
                robotEyesView.currentExpression != NexExpression.LISTENING &&
                idleMs > 90_000L
            ) {
                if (idleMs > 180_000L && robotEyesView.currentExpression == NexExpression.NORMAL) {
                    robotEyesView.setExpression(NexExpression.SLEEPY)
                }

                if (now - lastAutonomousSpeechMs > 240_000L) {
                    lastAutonomousSpeechMs = now
                    robotEyesView.setExpression(NexExpression.GREETING)
                    val name = memoryManager.userName
                    val line = if (!name.isNullOrBlank()) {
                        "Hey $name, I am right here whenever you want to chat, hear a joke, or play a game!"
                    } else {
                        "Hi friend! Just talk to me anytime if you want a fun fact, a joke, live news, or a game!"
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
        // Requirement 1 & 3: Start with familiar normal square eyes on the black screen
        robotEyesView.setExpression(NexExpression.NORMAL)

        networkMonitor = NetworkStatusMonitor(this) { isOnline ->
            statusDot.setBackgroundResource(if (isOnline) R.drawable.dot_online else R.drawable.dot_offline)
            statusText.text = getString(if (isOnline) R.string.status_online else R.string.status_offline)
        }

        voiceManager = VoiceSystemManager(this, object : VoiceSystemManager.Listener {
            override fun onListeningStateChanged(activeCommandListening: Boolean, wakeStandbyListening: Boolean) {
                if (activeCommandListening && voiceManager.isMicrophoneActuallyListening()) {
                    robotEyesView.setExpression(NexExpression.LISTENING)
                } else if (!isBusyThinking && !robotEyesView.isSpeaking &&
                    robotEyesView.currentExpression == NexExpression.LISTENING
                ) {
                    robotEyesView.setExpression(NexExpression.NORMAL)
                }
            }

            override fun onMicRmsChanged(normalizedRms: Float) {
                robotEyesView.micLevelNormalized = normalizedRms
            }

            override fun onSpeakingStateChanged(speaking: Boolean) {
                robotEyesView.isSpeaking = speaking
                if (speaking) {
                    if (robotEyesView.currentExpression in setOf(
                            NexExpression.NORMAL,
                            NexExpression.THINKING,
                            NexExpression.LISTENING
                        )
                    ) {
                        robotEyesView.setExpression(NexExpression.SPEAKING)
                    }
                } else if (!isBusyThinking) {
                    if (robotEyesView.currentExpression in setOf(
                            NexExpression.SPEAKING,
                            NexExpression.THINKING
                        )
                    ) {
                        robotEyesView.setExpression(NexExpression.NORMAL)
                    }
                }
            }

            override fun onWakePhraseDetected() {
                markInteraction()
                animalSoundPlayer.stop()
                if (voiceManager.hasMicPermission()) {
                    robotEyesView.setExpression(NexExpression.LISTENING)
                }
            }

            override fun onUserSpeechRecognized(transcript: String) {
                markInteraction()
                handleUserUtterance(transcript)
            }

            override fun onVoiceDiagnosticError(safeMessage: String) {
                if (robotEyesView.currentExpression == NexExpression.LISTENING) {
                    robotEyesView.setExpression(NexExpression.NORMAL)
                }
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
        warmUpBackendSilentlyIfNeeded()
        voiceManager.setForegroundWakeEnabled(memoryManager.isWakeWordForegroundEnabled)
        if (voiceManager.hasMicPermission()) {
            voiceManager.resumeForegroundStandbyIfPermitted()
        }
        mainHandler.postDelayed(autonomousPetLoop, 30_000L)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(autonomousPetLoop)
        stopBackgroundCameraOnFace()
        animalSoundPlayer.stop()
        networkMonitor.stop()
        voiceManager.pauseForLifecycle()
        super.onPause()
    }

    override fun onDestroy() {
        stopBackgroundCameraOnFace()
        faceDetector?.close()
        cameraExecutor?.shutdown()
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
                    robotEyesView.setExpression(NexExpression.NORMAL)
                    return true
                }
                if (robotEyesView.isSpeaking) {
                    voiceManager.stopSpeaking()
                    robotEyesView.setExpression(NexExpression.NORMAL)
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
                    NexExpression.HEART,
                    NexExpression.LAUGH,
                    NexExpression.VERY_HAPPY,
                    NexExpression.WINKING,
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

            is RoutedAction.AnalyzeCameraOnFace -> {
                runCameraPermissionGated(action.language) {
                    captureSingleFrameAndRecognizeOnFace(action.prompt, action.useFrontCamera, action.language)
                }
            }

            is RoutedAction.SetFaceTrackingOnFace -> {
                if (!action.enabled) {
                    stopBackgroundCameraOnFace()
                    robotEyesView.setGazeTarget(0f, 0f)
                    robotEyesView.setExpression(NexExpression.NORMAL)
                    val msg = when (action.language) {
                        LanguageMode.HINDI -> "फेस ट्रैकिंग बंद कर दी गई है।"
                        LanguageMode.HINGLISH -> "Face tracking band kar di gayi hai."
                        LanguageMode.ENGLISH -> "Face tracking is now turned off."
                    }
                    voiceManager.speak(msg, action.language)
                } else {
                    runCameraPermissionGated(action.language) {
                        startFaceDetectionOnFace(action.checkPresenceOnly, action.language)
                    }
                }
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

            is RoutedAction.SaveUserPreference -> {
                if (memoryManager.isMemoryEnabled) {
                    memoryManager.rememberPreference(action.preference)
                    robotEyesView.setExpression(NexExpression.VERY_HAPPY)
                    val msg = when (action.language) {
                        LanguageMode.HINDI -> "मैंने आपकी पसंद याद रख ली है!"
                        LanguageMode.HINGLISH -> "Maine tumhari pasand yaad rakh li hai!"
                        LanguageMode.ENGLISH -> "Got it! I will remember that preference for our chats."
                    }
                    voiceManager.speak(msg, action.language)
                } else {
                    robotEyesView.setExpression(NexExpression.CURIOUS)
                    voiceManager.speak(
                        "Conversation memory is currently switched off, so I did not save that preference.",
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
        // Requirement 8: Never play sounds over the user's speech
        if (voiceManager.isUserCurrentlySpeaking) return
        voiceManager.stopSpeaking()
        robotEyesView.setExpression(item.reactionExpression)
        animalSoundPlayer.play(item.rawResId) {
            runOnUiThread {
                robotEyesView.setExpression(NexExpression.HAPPY)
                voiceManager.resumeForegroundStandbyIfPermitted()
            }
        }
    }

    private fun runCameraPermissionGated(lang: LanguageMode, action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingCameraLang = lang
            pendingCameraAction = action
            requestCameraPermLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun ensureCameraHelpers() {
        if (cameraExecutor == null || cameraExecutor?.isShutdown == true) {
            cameraExecutor = Executors.newSingleThreadExecutor()
        }
        if (faceDetector == null) {
            val opts = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .enableTracking()
                .build()
            faceDetector = FaceDetection.getClient(opts)
        }
    }

    /**
     * Captures ONE single camera frame in the background without altering the pure black NEX face,
     * unbinds the camera immediately so nothing is recorded in the background, and asks Gemini Vision.
     */
    private fun captureSingleFrameAndRecognizeOnFace(
        prompt: String,
        useFrontCamera: Boolean,
        lang: LanguageMode
    ) {
        if (!networkMonitor.hasNetworkTransport()) {
            robotEyesView.setExpression(NexExpression.CONFUSED)
            val msg = when (lang) {
                LanguageMode.HINDI -> "कैमरा ऑब्जेक्ट पहचान के लिए इंटरनेट आवश्यक है, और अभी इंटरनेट ऑफ़लाइन है।"
                LanguageMode.HINGLISH -> "Camera object recognition ke liye internet chahiye, aur abhi internet offline hai."
                LanguageMode.ENGLISH -> "Internet connection is required for AI object recognition, and I am offline right now."
            }
            voiceManager.speak(msg, lang)
            return
        }

        val baseUrl = memoryManager.backendBaseUrl
        if (MemoryManager.validateHttpsBackendUrl(baseUrl) == null) {
            robotEyesView.setExpression(NexExpression.CONFUSED)
            voiceManager.speak(
                "Please configure your HTTPS Backend URL in Backend Setup so Gemini Vision can analyze what I see.",
                LanguageMode.ENGLISH
            )
            return
        }

        ensureCameraHelpers()
        val exec = cameraExecutor ?: return
        isBusyThinking = true
        robotEyesView.setExpression(NexExpression.FOCUSED)

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = try {
                future.get()
            } catch (_: Exception) {
                isBusyThinking = false
                robotEyesView.setExpression(NexExpression.SAD)
                voiceManager.speak("Camera is unavailable on this device right now.", lang)
                return@addListener
            }
            cameraProvider = provider

            val capturedOnce = AtomicBoolean(false)
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(exec) { imageProxy ->
                if (!capturedOnce.compareAndSet(false, true)) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                val jpegBytes = try {
                    val bmp = imageProxy.toBitmap()
                    val scaled = scaleBitmapMaxEdge(bmp, 640)
                    ByteArrayOutputStream().use { out ->
                        scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
                        out.toByteArray()
                    }
                } catch (_: Exception) {
                    null
                } finally {
                    imageProxy.close()
                }

                runOnUiThread {
                    if (!isFaceTrackingActiveOnFace) {
                        stopBackgroundCameraOnFace()
                    }
                    if (jpegBytes == null || jpegBytes.isEmpty()) {
                        isBusyThinking = false
                        robotEyesView.setExpression(NexExpression.SAD)
                        voiceManager.speak("I could not capture a clear camera image right now.", lang)
                        return@runOnUiThread
                    }
                    robotEyesView.setExpression(NexExpression.THINKING)
                    lifecycleScope.launch {
                        val res = backendClient.sendVision(baseUrl, prompt, jpegBytes, lang)
                        isBusyThinking = false
                        when (res) {
                            is BackendResult.Success -> {
                                networkMonitor.confirmHttpsReachable()
                                robotEyesView.setExpression(res.data.expression)
                                voiceManager.speak(res.data.reply, res.data.language)
                            }
                            is BackendResult.Error -> {
                                robotEyesView.setExpression(NexExpression.SAD)
                                voiceManager.speak(res.safeMessage, LanguageMode.ENGLISH)
                            }
                        }
                    }
                }
            }

            val preferredLens = if (useFrontCamera) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            try {
                provider.unbindAll()
                val selector = if (provider.hasCamera(CameraSelector.Builder().requireLensFacing(preferredLens).build())) {
                    CameraSelector.Builder().requireLensFacing(preferredLens).build()
                } else {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }
                provider.bindToLifecycle(this, selector, analysis)
            } catch (_: Exception) {
                isBusyThinking = false
                robotEyesView.setExpression(NexExpression.SAD)
                voiceManager.speak("Camera lens is currently unavailable on this device.", lang)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * On-device ML Kit face detection & gaze tracking directly on the pure black NEX robot face.
     * Zero images are uploaded or stored.
     */
    @OptIn(ExperimentalGetImage::class)
    private fun startFaceDetectionOnFace(checkPresenceOnly: Boolean, lang: LanguageMode) {
        ensureCameraHelpers()
        val exec = cameraExecutor ?: return
        val detector = faceDetector ?: return
        isFaceTrackingActiveOnFace = !checkPresenceOnly

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = try {
                future.get()
            } catch (_: Exception) {
                robotEyesView.setExpression(NexExpression.SAD)
                voiceManager.speak("Camera is unavailable on this device right now.", lang)
                return@addListener
            }
            cameraProvider = provider

            val reportedPresence = AtomicBoolean(false)
            val startMs = SystemClock.elapsedRealtime()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(exec) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage == null) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                val rotation = imageProxy.imageInfo.rotationDegrees
                val imgW = if (rotation == 90 || rotation == 270) imageProxy.height.toFloat() else imageProxy.width.toFloat()
                val imgH = if (rotation == 90 || rotation == 270) imageProxy.width.toFloat() else imageProxy.height.toFloat()

                try {
                    val input = InputImage.fromMediaImage(mediaImage, rotation)
                    detector.process(input)
                        .addOnSuccessListener { faces ->
                            if (faces.isNotEmpty()) {
                                val f = faces.first()
                                val b = f.boundingBox
                                val rawCx = ((b.centerX() / imgW).coerceIn(0f, 1f))
                                val mirroredCx = 1f - rawCx
                                val normX = (mirroredCx - 0.5f) * 2f
                                val normY = ((b.centerY() / imgH).coerceIn(0f, 1f) - 0.5f) * 2f
                                val smiling = (f.smilingProbability ?: 0f) > 0.65f

                                runOnUiThread {
                                    robotEyesView.setGazeTarget(normX, normY)
                                    if (smiling && !robotEyesView.isSpeaking) {
                                        robotEyesView.setExpression(NexExpression.VERY_HAPPY)
                                    }
                                    if (checkPresenceOnly && reportedPresence.compareAndSet(false, true)) {
                                        stopBackgroundCameraOnFace()
                                        robotEyesView.setExpression(if (smiling) NexExpression.VERY_HAPPY else NexExpression.HAPPY)
                                        val msg = when (lang) {
                                            LanguageMode.HINDI -> if (smiling) "हाँ, मैं आपको देख सकता हूँ और आप मुस्कुरा रहे हैं!" else "हाँ, मैं आपको अपने सामने साफ़ देख सकता हूँ!"
                                            LanguageMode.HINGLISH -> if (smiling) "Haan, main tumhe dekh sakta hoon aur tum smile kar rahe ho!" else "Haan, main tumhe apne samne dekh sakta hoon!"
                                            LanguageMode.ENGLISH -> if (smiling) "Yes, I can see you right in front of me, and you are smiling!" else "Yes, I can see a person right in front of me!"
                                        }
                                        voiceManager.speak(msg, lang)
                                    }
                                }
                            } else if (checkPresenceOnly &&
                                SystemClock.elapsedRealtime() - startMs > 1800L &&
                                reportedPresence.compareAndSet(false, true)
                            ) {
                                runOnUiThread {
                                    stopBackgroundCameraOnFace()
                                    robotEyesView.setExpression(NexExpression.CURIOUS)
                                    val msg = when (lang) {
                                        LanguageMode.HINDI -> "अभी कैमरे के सामने कोई चेहरा दिखाई नहीं दे रहा है।"
                                        LanguageMode.HINGLISH -> "Abhi camera ke samne koi face dikhai nahi de raha hai."
                                        LanguageMode.ENGLISH -> "I do not see anyone in front of the camera right now."
                                    }
                                    voiceManager.speak(msg, lang)
                                }
                            }
                        }
                        .addOnCompleteListener {
                            imageProxy.close()
                        }
                } catch (_: Exception) {
                    imageProxy.close()
                }
            }

            try {
                provider.unbindAll()
                val selector = if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                provider.bindToLifecycle(this, selector, analysis)
                if (!checkPresenceOnly) {
                    robotEyesView.setExpression(NexExpression.FOCUSED)
                    val msg = when (lang) {
                        LanguageMode.HINDI -> "फेस ट्रैकिंग चालू है! मेरी आँखें अब आपको फॉलो करेंगी।"
                        LanguageMode.HINGLISH -> "Face tracking chalu hai! Meri aankhein ab tumhe follow karengi."
                        LanguageMode.ENGLISH -> "Face tracking is active! My eyes will follow you now."
                    }
                    voiceManager.speak(msg, lang)
                }
            } catch (_: Exception) {
                isFaceTrackingActiveOnFace = false
                robotEyesView.setExpression(NexExpression.SAD)
                voiceManager.speak("Unable to start camera face detection on this device.", lang)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopBackgroundCameraOnFace() {
        isFaceTrackingActiveOnFace = false
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
    }

    private fun scaleBitmapMaxEdge(src: Bitmap, maxEdge: Int): Bitmap {
        val w = src.width
        val h = src.height
        val longest = maxOf(w, h)
        if (longest <= maxEdge) return src
        val ratio = maxEdge.toFloat() / longest.toFloat()
        val nw = (w * ratio).toInt().coerceAtLeast(1)
        val nh = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    private fun warmUpBackendSilentlyIfNeeded() {
        val now = SystemClock.elapsedRealtime()
        if (lastBackendWarmupMs != 0L && now - lastBackendWarmupMs < 600_000L) return
        if (!networkMonitor.hasNetworkTransport()) return
        val baseUrl = memoryManager.backendBaseUrl
        if (MemoryManager.validateHttpsBackendUrl(baseUrl) == null) return
        lastBackendWarmupMs = now
        lifecycleScope.launch {
            val res = backendClient.checkHealth(baseUrl)
            if (res is BackendResult.Success) {
                networkMonitor.confirmHttpsReachable()
            }
        }
    }

    private fun speakLiveWeather(city: String, lang: LanguageMode) {
        if (!networkMonitor.hasNetworkTransport()) {
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
                    networkMonitor.confirmHttpsReachable()
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
        if (!networkMonitor.hasNetworkTransport()) {
            robotEyesView.setExpression(NexExpression.SAD)
            val msg = when (lang) {
                LanguageMode.HINDI -> "इंटरनेट अभी ऑफ़लाइन है, इसलिए लाइव जानकारी और ऑनलाइन AI अभी उपलब्ध नहीं हैं।"
                LanguageMode.HINGLISH -> "Internet abhi offline hai, isliye live information aur online AI abhi temporarily unavailable hain."
                LanguageMode.ENGLISH -> "Internet is currently offline, so live information and online AI answers are temporarily unavailable."
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
                history = history,
                userPreferences = memoryManager.userPreferences
            )
            isBusyThinking = false

            when (result) {
                is BackendResult.Success -> {
                    networkMonitor.confirmHttpsReachable()
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
