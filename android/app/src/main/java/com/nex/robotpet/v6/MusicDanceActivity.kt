package com.nex.robotpet.v6

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nex.robotpet.v6.data.NexExpression
import com.nex.robotpet.v6.ui.RobotEyesView
import com.nex.robotpet.v6.util.FullscreenHelper
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Local Music & Dance Studio for NEX 6.0 (Part H).
 * - Plays user-selected local audio files via Android Storage Access Framework (no copyrighted music bundled).
 * - Synchronizes dance eye animations via Android Visualizer waveform energy when supported/permitted,
 *   with smooth fallback rhythm modulation.
 * - Triggers joyful random dance expressions & manages AudioFocus + lifecycle cleanly.
 */
class MusicDanceActivity : AppCompatActivity() {

    private lateinit var danceEyes: RobotEyesView
    private lateinit var tvReactionBadge: TextView
    private lateinit var tvTrackTitle: TextView
    private lateinit var tvMusicTime: TextView
    private lateinit var seekProgress: SeekBar
    private lateinit var btnPlayPause: Button

    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var mediaPlayer: MediaPlayer? = null
    private var visualizer: Visualizer? = null

    private var selectedUri: Uri? = null
    private var currentVolume: Float = 0.85f
    private var userDraggingSeek: Boolean = false

    private val handler = Handler(Looper.getMainLooper())
    private val danceExpressions = listOf(
        NexExpression.DANCE,
        NexExpression.EXCITED,
        NexExpression.HAPPY,
        NexExpression.LAUGH,
        NexExpression.LOVE
    )

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pausePlayback()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> setPlayerVolume(currentVolume * 0.3f)
            AudioManager.AUDIOFOCUS_GAIN -> setPlayerVolume(currentVolume)
        }
    }

    private val pickAudioLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                    // Temporary read permission is already granted for current session
                }
                loadSelectedTrack(uri)
            }
        }

    private val progressLoop = object : Runnable {
        override fun run() {
            val mp = mediaPlayer
            if (mp != null && isPlayingSafe(mp)) {
                val pos = mp.currentPosition
                val dur = mp.duration.coerceAtLeast(1)
                if (!userDraggingSeek) {
                    seekProgress.progress = ((pos.toLong() * 1000L) / dur).toInt().coerceIn(0, 1000)
                }
                tvMusicTime.text = "${formatMs(pos)} / ${formatMs(dur)}"

                // Fallback beat pulse if hardware Visualizer is inactive
                if (visualizer == null) {
                    val syntheticBeat = abs(sin(pos / 190.0)).toFloat()
                    danceEyes.musicBeatLevel = syntheticBeat
                }
            }
            handler.postDelayed(this, 60L)
        }
    }

    private val randomDanceReactionLoop = object : Runnable {
        override fun run() {
            val mp = mediaPlayer
            if (mp != null && isPlayingSafe(mp)) {
                triggerRandomDanceMove()
            }
            handler.postDelayed(this, 5200L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_music_dance)
        FullscreenHelper.applyImmersiveLandscape(this)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        danceEyes = findViewById(R.id.musicDanceEyes)
        tvReactionBadge = findViewById(R.id.tvDanceReactionBadge)
        tvTrackTitle = findViewById(R.id.tvTrackTitle)
        tvMusicTime = findViewById(R.id.tvMusicTime)
        seekProgress = findViewById(R.id.seekMusicProgress)
        btnPlayPause = findViewById(R.id.btnMusicPlayPause)

        danceEyes.setExpression(NexExpression.HAPPY)

        findViewById<Button>(R.id.btnBackFromMusic).setOnClickListener { finish() }

        findViewById<Button>(R.id.btnPickMusicFile).setOnClickListener {
            pickAudioLauncher.launch(arrayOf("audio/*"))
        }

        btnPlayPause.setOnClickListener {
            val mp = mediaPlayer
            if (mp == null) {
                if (selectedUri != null) {
                    loadSelectedTrack(selectedUri!!)
                } else {
                    pickAudioLauncher.launch(arrayOf("audio/*"))
                }
            } else if (isPlayingSafe(mp)) {
                pausePlayback()
            } else {
                startPlayback()
            }
        }

        findViewById<Button>(R.id.btnMusicStop).setOnClickListener {
            stopAndResetPlayback()
        }

        findViewById<Button>(R.id.btnFreestyleDance).setOnClickListener {
            triggerRandomDanceMove()
        }

        seekProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val mp = mediaPlayer ?: return
                    val target = ((mp.duration.toLong() * progress) / 1000L).toInt()
                    tvMusicTime.text = "${formatMs(target)} / ${formatMs(mp.duration)}"
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userDraggingSeek = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                userDraggingSeek = false
                val mp = mediaPlayer ?: return
                val target = ((mp.duration.toLong() * (seekBar?.progress ?: 0)) / 1000L).toInt()
                try {
                    mp.seekTo(target)
                } catch (_: Exception) {
                }
            }
        })

        findViewById<SeekBar>(R.id.seekMusicVolume).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    currentVolume = (progress / 100f).coerceIn(0f, 1f)
                    setPlayerVolume(currentVolume)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            }
        )
    }

    override fun onResume() {
        super.onResume()
        FullscreenHelper.applyImmersiveLandscape(this)
        handler.post(progressLoop)
        handler.postDelayed(randomDanceReactionLoop, 4000L)
    }

    override fun onPause() {
        handler.removeCallbacksAndMessages(null)
        pausePlayback()
        super.onPause()
    }

    override fun onDestroy() {
        releasePlayerAndVisualizer()
        abandonAudioFocus()
        super.onDestroy()
    }

    private fun loadSelectedTrack(uri: Uri) {
        selectedUri = uri
        releasePlayerAndVisualizer()

        val displayName = resolveDisplayName(uri)
        tvTrackTitle.text = "Track: $displayName"

        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(this@MusicDanceActivity, uri)
                prepare()
                setVolume(currentVolume, currentVolume)
                setOnCompletionListener {
                    btnPlayPause.text = "Play"
                    danceEyes.musicBeatLevel = 0f
                    danceEyes.setExpression(NexExpression.HAPPY)
                    tvReactionBadge.text = "Track Finished!"
                }
            }
            mediaPlayer = mp
            startPlayback()
        } catch (e: Exception) {
            tvTrackTitle.text = "Unable to play selected audio file (${e.javaClass.simpleName})."
        }
    }

    private fun startPlayback() {
        val mp = mediaPlayer ?: return
        if (!requestAudioFocus()) return
        mp.start()
        btnPlayPause.text = "Pause"
        danceEyes.setExpression(NexExpression.DANCE)
        tvReactionBadge.text = "NEX Dancing: Beat Sync"
        attachVisualizerIfPermitted(mp.audioSessionId)
    }

    private fun pausePlayback() {
        val mp = mediaPlayer ?: return
        if (isPlayingSafe(mp)) {
            mp.pause()
        }
        btnPlayPause.text = "Play"
        danceEyes.musicBeatLevel = 0f
        danceEyes.setExpression(NexExpression.HAPPY)
        tvReactionBadge.text = "Music Paused"
    }

    private fun stopAndResetPlayback() {
        val mp = mediaPlayer
        if (mp != null) {
            try {
                if (mp.isPlaying) mp.stop()
                mp.prepare()
                mp.seekTo(0)
            } catch (_: Exception) {
                releasePlayerAndVisualizer()
            }
        }
        seekProgress.progress = 0
        btnPlayPause.text = "Play"
        danceEyes.musicBeatLevel = 0f
        danceEyes.setExpression(NexExpression.HAPPY)
        tvReactionBadge.text = "Stopped"
    }

    private fun triggerRandomDanceMove() {
        val pick = if (Random.nextInt(100) < 55) {
            NexExpression.DANCE
        } else {
            danceExpressions.random()
        }
        danceEyes.setExpression(pick)
        tvReactionBadge.text = "Dance Reaction: ${pick.displayName}"
    }

    private fun attachVisualizerIfPermitted(audioSessionId: Int) {
        releaseVisualizerOnly()
        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic || audioSessionId == 0) return

        try {
            val vis = Visualizer(audioSessionId).apply {
                captureSize = Visualizer.getCaptureSizeRange()[0]
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            visualizer: Visualizer?,
                            waveform: ByteArray?,
                            samplingRate: Int
                        ) {
                            if (waveform == null || waveform.isEmpty()) return
                            var sumSq = 0.0
                            for (b in waveform) {
                                val centered = ((b.toInt() and 0xFF) - 128) / 128.0
                                sumSq += centered * centered
                            }
                            val rms = sqrt(sumSq / waveform.size).toFloat()
                            val level = (rms * 3.2f).coerceIn(0f, 1f)
                            runOnUiThread { danceEyes.musicBeatLevel = level }
                        }

                        override fun onFftDataCapture(
                            visualizer: Visualizer?,
                            fft: ByteArray?,
                            samplingRate: Int
                        ) = Unit
                    },
                    Visualizer.getMaxCaptureRate() / 2,
                    true,
                    false
                )
                enabled = true
            }
            visualizer = vis
        } catch (_: Throwable) {
            // Some Android OEM HALs restrict Visualizer; synthetic beat rhythm handles it gracefully
            visualizer = null
        }
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusListener)
        }
    }

    private fun setPlayerVolume(vol: Float) {
        val clamped = vol.coerceIn(0f, 1f)
        try {
            mediaPlayer?.setVolume(clamped, clamped)
        } catch (_: Exception) {
        }
    }

    private fun isPlayingSafe(mp: MediaPlayer): Boolean = try {
        mp.isPlaying
    } catch (_: Exception) {
        false
    }

    private fun releaseVisualizerOnly() {
        val v = visualizer
        visualizer = null
        if (v != null) {
            try {
                v.enabled = false
                v.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun releasePlayerAndVisualizer() {
        releaseVisualizerOnly()
        val mp = mediaPlayer
        mediaPlayer = null
        if (mp != null) {
            try {
                if (mp.isPlaying) mp.stop()
            } catch (_: Exception) {
            }
            try {
                mp.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            } ?: uri.lastPathSegment ?: "Selected Audio"
        } catch (_: Exception) {
            "Selected Audio"
        }
    }

    private fun formatMs(ms: Int): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val m = totalSec / 60
        val s = totalSec % 60
        return "%02d:%02d".format(m, s)
    }
}
