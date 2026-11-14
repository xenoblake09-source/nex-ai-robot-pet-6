package com.nex.robotpet.v6.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.annotation.RawRes

/**
 * Clean, reliable audio player for NEX 6.0's 20 real animal sound assets (Part F).
 * Handles Play, Stop, Volume scaling, and lifecycle cleanup with zero leaked MediaPlayers.
 */
class AnimalSoundPlayer(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private var volume: Float = 0.85f

    fun setVolumeLevel(normalized: Float) {
        volume = normalized.coerceIn(0.0f, 1.0f)
        try {
            mediaPlayer?.setVolume(volume, volume)
        } catch (_: IllegalStateException) {
            // Ignore if player is resetting
        }
    }

    fun getVolumeLevel(): Float = volume

    fun play(@RawRes resId: Int, onCompleted: (() -> Unit)? = null): Boolean {
        stop()
        return try {
            val afd = context.resources.openRawResourceFd(resId) ?: return false
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.prepare()
            mp.setVolume(volume, volume)
            mp.setOnCompletionListener {
                stop()
                onCompleted?.invoke()
            }
            mp.setOnErrorListener { _, _, _ ->
                stop()
                onCompleted?.invoke()
                true
            }
            mediaPlayer = mp
            mp.start()
            true
        } catch (_: Exception) {
            stop()
            false
        }
    }

    fun isPlaying(): Boolean = try {
        mediaPlayer?.isPlaying == true
    } catch (_: Exception) {
        false
    }

    fun stop() {
        val existing = mediaPlayer
        mediaPlayer = null
        if (existing != null) {
            try {
                if (existing.isPlaying) {
                    existing.stop()
                }
            } catch (_: Exception) {
                // Safe stop
            } finally {
                try {
                    existing.release()
                } catch (_: Exception) {
                    // Safe release
                }
            }
        }
    }
}
