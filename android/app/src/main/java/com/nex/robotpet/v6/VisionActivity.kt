package com.nex.robotpet.v6

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.nex.robotpet.v6.data.NexExpression
import com.nex.robotpet.v6.memory.MemoryManager
import com.nex.robotpet.v6.network.BackendResult
import com.nex.robotpet.v6.network.NetworkStatusMonitor
import com.nex.robotpet.v6.network.NexBackendClient
import com.nex.robotpet.v6.ui.RobotEyesView
import com.nex.robotpet.v6.util.FullscreenHelper
import com.nex.robotpet.v6.util.LanguageHelper
import com.nex.robotpet.v6.util.LanguageMode
import com.nex.robotpet.v6.vision.FaceTrackingOverlayView
import com.nex.robotpet.v6.voice.VoiceSystemManager
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Camera & Computer Vision Activity for NEX 6.0 (Part G).
 * - Front camera by default + Flip Camera toggle.
 * - On-device real-time Face Detection, Face Presence, and Eye Gaze Tracking via ML Kit (works offline).
 * - Online Gemini AI Object Recognition ("What is this?") via secure HTTPS backend.
 * - Never activates camera without explicit user permission & screen open.
 * - Explicitly distinguishes offline face tracking from online Gemini object recognition.
 */
class VisionActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var faceOverlayView: FaceTrackingOverlayView
    private lateinit var tvFaceBadge: TextView
    private lateinit var tvVisionResult: TextView
    private lateinit var etPrompt: EditText
    private lateinit var miniEyes: RobotEyesView

    private lateinit var memoryManager: MemoryManager
    private lateinit var networkMonitor: NetworkStatusMonitor
    private lateinit var backendClient: NexBackendClient
    private lateinit var voiceManager: VoiceSystemManager
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var faceDetector: FaceDetector

    private var lensFacing: Int = CameraSelector.LENS_FACING_FRONT
    private var cameraProvider: ProcessCameraProvider? = null

    private val requestCameraPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCameraLifecycle()
            } else {
                tvFaceBadge.text = "Camera Permission Denied"
                tvVisionResult.text =
                    "Camera permission was not granted. NEX never activates the camera without your permission."
            }
        }

    private val requestMicPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                voiceManager.startActiveCommandListening()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vision)
        FullscreenHelper.applyImmersiveLandscape(this)

        memoryManager = MemoryManager(this)
        backendClient = NexBackendClient()
        cameraExecutor = Executors.newSingleThreadExecutor()

        val detectorOptions = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .enableTracking()
            .build()
        faceDetector = FaceDetection.getClient(detectorOptions)

        previewView = findViewById(R.id.cameraPreviewView)
        faceOverlayView = findViewById(R.id.faceOverlayView)
        tvFaceBadge = findViewById(R.id.tvFaceStatusBadge)
        tvVisionResult = findViewById(R.id.tvVisionResult)
        etPrompt = findViewById(R.id.etVisionPrompt)
        miniEyes = findViewById(R.id.visionMiniEyes)

        intent.getStringExtra(EXTRA_INITIAL_PROMPT)?.takeIf { it.isNotBlank() }?.let {
            etPrompt.setText(it)
        }

        networkMonitor = NetworkStatusMonitor(this) { online ->
            findViewById<TextView>(R.id.tvVisionModeNote).text = if (online) {
                "Internet ONLINE — On-device Face Tracking + Online Gemini Vision active."
            } else {
                "Internet OFFLINE — Face Tracking works offline; Gemini AI Object Recognition requires Internet."
            }
        }

        voiceManager = VoiceSystemManager(this, object : VoiceSystemManager.Listener {
            override fun onListeningStateChanged(activeCommandListening: Boolean, wakeStandbyListening: Boolean) {
                if (activeCommandListening) miniEyes.setExpression(NexExpression.LISTENING)
            }

            override fun onMicRmsChanged(normalizedRms: Float) {
                miniEyes.micLevelNormalized = normalizedRms
            }

            override fun onSpeakingStateChanged(speaking: Boolean) {
                miniEyes.isSpeaking = speaking
            }

            override fun onWakePhraseDetected() = Unit

            override fun onUserSpeechRecognized(transcript: String) {
                etPrompt.setText(transcript)
                analyzeCurrentCameraFrame(transcript)
            }

            override fun onVoiceDiagnosticError(safeMessage: String) {
                tvVisionResult.text = safeMessage
            }
        })
        voiceManager.setForegroundWakeEnabled(false)

        findViewById<Button>(R.id.btnBackFromVision).setOnClickListener { finish() }

        findViewById<Button>(R.id.btnSwitchCamera).setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                CameraSelector.LENS_FACING_BACK
            } else {
                CameraSelector.LENS_FACING_FRONT
            }
            startCameraLifecycle()
        }

        findViewById<Button>(R.id.btnAskWhatIsThis).setOnClickListener {
            val prompt = etPrompt.text?.toString()?.trim().orEmpty().ifBlank { "What is this?" }
            analyzeCurrentCameraFrame(prompt)
        }

        findViewById<Button>(R.id.btnVoiceVisionAsk).setOnClickListener {
            if (voiceManager.hasMicPermission()) {
                voiceManager.startActiveCommandListening()
            } else {
                requestMicPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        checkAndStartCamera()
    }

    override fun onResume() {
        super.onResume()
        FullscreenHelper.applyImmersiveLandscape(this)
        networkMonitor.start()
    }

    override fun onPause() {
        networkMonitor.stop()
        voiceManager.pauseForLifecycle()
        super.onPause()
    }

    override fun onStop() {
        cameraProvider?.unbindAll()
        super.onStop()
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        faceDetector.close()
        cameraExecutor.shutdown()
        voiceManager.shutdown()
        super.onDestroy()
    }

    private fun checkAndStartCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraLifecycle()
        } else {
            requestCameraPermLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCameraLifecycle() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        processFaceFrame(imageProxy)
                    }
                }

            val selector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, analyzer)
                tvFaceBadge.text = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                    "Front Camera — Scanning Faces"
                } else {
                    "Rear Camera — Ready"
                }
            } catch (e: Exception) {
                tvFaceBadge.text = "Camera Unavailable"
                tvVisionResult.text = "Unable to open selected camera lens on this device."
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processFaceFrame(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val imgW = if (rotation == 90 || rotation == 270) imageProxy.height.toFloat() else imageProxy.width.toFloat()
        val imgH = if (rotation == 90 || rotation == 270) imageProxy.width.toFloat() else imageProxy.height.toFloat()

        try {
            val inputImage = InputImage.fromMediaImage(mediaImage, rotation)
            faceDetector.process(inputImage)
                .addOnSuccessListener { faces ->
                    if (faces.isEmpty()) {
                        faceOverlayView.updateNormalizedFaces(emptyList())
                        tvFaceBadge.text = "No Face Detected"
                    } else {
                        val boxes = faces.map { face ->
                            val b = face.boundingBox
                            val rawLeft = (b.left / imgW).coerceIn(0f, 1f)
                            val rawRight = (b.right / imgW).coerceIn(0f, 1f)
                            val left = if (lensFacing == CameraSelector.LENS_FACING_FRONT) 1f - rawRight else rawLeft
                            val right = if (lensFacing == CameraSelector.LENS_FACING_FRONT) 1f - rawLeft else rawRight
                            val top = (b.top / imgH).coerceIn(0f, 1f)
                            val bottom = (b.bottom / imgH).coerceIn(0f, 1f)
                            RectF(left, top, right, bottom)
                        }
                        faceOverlayView.updateNormalizedFaces(boxes)

                        val primary = boxes.first()
                        val cxNorm = ((primary.left + primary.right) * 0.5f - 0.5f) * 2f
                        val cyNorm = ((primary.top + primary.bottom) * 0.5f - 0.5f) * 2f
                        miniEyes.setGazeTarget(cxNorm, cyNorm)

                        val smiling = (faces.first().smilingProbability ?: 0f) > 0.65f
                        if (smiling && !miniEyes.isSpeaking) {
                            miniEyes.setExpression(NexExpression.HAPPY)
                        }
                        tvFaceBadge.text = "Face Present (${faces.size}) — Tracking Active"
                    }
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } catch (_: Exception) {
            imageProxy.close()
        }
    }

    private fun analyzeCurrentCameraFrame(prompt: String) {
        val lang = LanguageHelper.detectLanguage(prompt)

        if (!networkMonitor.hasNetworkTransport()) {
            val msg = "Online internet connection is required for Gemini AI object recognition. Face detection and tracking continue to work offline."
            tvVisionResult.text = msg
            miniEyes.setExpression(NexExpression.CONFUSED)
            voiceManager.speak(msg, lang, resumeWakeAfter = false)
            return
        }

        val baseUrl = memoryManager.backendBaseUrl
        if (MemoryManager.validateHttpsBackendUrl(baseUrl) == null) {
            val msg = "Please configure your Render HTTPS Backend URL via the NEX Home Screen icon shortcut (Backend Setup) so Gemini Vision can analyze images securely."
            tvVisionResult.text = msg
            miniEyes.setExpression(NexExpression.CONFUSED)
            voiceManager.speak(msg, lang, resumeWakeAfter = false)
            return
        }

        val bitmap = previewView.bitmap
        if (bitmap == null) {
            tvVisionResult.text = "Camera preview is still warming up—please try again in a moment."
            return
        }

        val scaled = scaleBitmapMaxEdge(bitmap, 640)
        val jpegBytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
            out.toByteArray()
        }

        miniEyes.setExpression(NexExpression.THINKING)
        tvVisionResult.text = "Sending camera frame securely to Gemini Vision..."

        lifecycleScope.launch {
            when (val res = backendClient.sendVision(baseUrl, prompt, jpegBytes, lang)) {
                is BackendResult.Success -> {
                    networkMonitor.confirmHttpsReachable()
                    val data = res.data
                    miniEyes.setExpression(data.expression)
                    val objLine = if (data.objectsDetected.isNotEmpty()) {
                        "\n\nDetected Objects: ${data.objectsDetected.joinToString(", ")}"
                    } else {
                        ""
                    }
                    tvVisionResult.text = "${data.reply}$objLine"
                    voiceManager.speak(data.reply, data.language, resumeWakeAfter = false)
                }
                is BackendResult.Error -> {
                    miniEyes.setExpression(NexExpression.SAD)
                    tvVisionResult.text = "Vision Error (${res.code}): ${res.safeMessage}"
                    voiceManager.speak(res.safeMessage, LanguageMode.ENGLISH, resumeWakeAfter = false)
                }
            }
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

    companion object {
        const val EXTRA_INITIAL_PROMPT = "extra_initial_vision_prompt"
    }
}
