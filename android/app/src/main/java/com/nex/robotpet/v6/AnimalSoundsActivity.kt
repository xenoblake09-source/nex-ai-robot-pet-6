package com.nex.robotpet.v6

import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.nex.robotpet.v6.audio.AnimalSoundPlayer
import com.nex.robotpet.v6.data.AnimalSoundCatalog
import com.nex.robotpet.v6.data.AnimalSoundItem
import com.nex.robotpet.v6.data.NexExpression
import com.nex.robotpet.v6.ui.RobotEyesView
import com.nex.robotpet.v6.util.FullscreenHelper

/**
 * Landscape Soundboard for all 20 verified real animal audio files (Part F).
 * Includes Play, Stop, Volume SeekBar, and expressive NEX eye reactions.
 */
class AnimalSoundsActivity : AppCompatActivity() {

    private lateinit var soundPlayer: AnimalSoundPlayer
    private lateinit var miniEyes: RobotEyesView
    private lateinit var tvNowPlaying: TextView
    private lateinit var gridRows: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_animal_sounds)
        FullscreenHelper.applyImmersiveLandscape(this)

        soundPlayer = AnimalSoundPlayer(this)
        miniEyes = findViewById(R.id.miniAnimalEyes)
        tvNowPlaying = findViewById(R.id.tvAnimalNowPlaying)
        gridRows = findViewById(R.id.animalGridRows)

        findViewById<Button>(R.id.btnBackFromAnimals).setOnClickListener { finish() }

        findViewById<Button>(R.id.btnStopAnimalSound).setOnClickListener {
            soundPlayer.stop()
            miniEyes.setExpression(NexExpression.HAPPY)
            tvNowPlaying.text = "Stopped — Tap any of the 20 animals to play"
        }

        findViewById<SeekBar>(R.id.seekAnimalVolume).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    soundPlayer.setVolumeLevel(progress / 100f)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            }
        )

        build20AnimalButtons()
    }

    override fun onResume() {
        super.onResume()
        FullscreenHelper.applyImmersiveLandscape(this)
    }

    override fun onStop() {
        soundPlayer.stop()
        super.onStop()
    }

    override fun onDestroy() {
        soundPlayer.stop()
        super.onDestroy()
    }

    private fun build20AnimalButtons() {
        gridRows.removeAllViews()
        val animals = AnimalSoundCatalog.ALL_ANIMALS
        val density = resources.displayMetrics.density

        // 4 rows × 5 columns = 20 animals cleanly arranged in landscape
        for (rowIdx in 0 until 4) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (6 * density).toInt()
                }
            }

            for (colIdx in 0 until 5) {
                val item: AnimalSoundItem = animals[rowIdx * 5 + colIdx]
                val btn = Button(this).apply {
                    text = "${item.index}. ${item.displayName}"
                    isAllCaps = false
                    textSize = 13f
                    setTextColor(Color.parseColor("#ECF8FF"))
                    setBackgroundColor(Color.parseColor("#162838"))
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        (58 * density).toInt(),
                        1f
                    ).apply {
                        val m = (4 * density).toInt()
                        setMargins(m, m, m, m)
                    }
                    setOnClickListener {
                        playAnimalItem(item)
                    }
                }
                row.addView(btn)
            }
            gridRows.addView(row)
        }
    }

    private fun playAnimalItem(item: AnimalSoundItem) {
        miniEyes.setExpression(item.reactionExpression)
        val started = soundPlayer.play(item.rawResId) {
            runOnUiThread {
                miniEyes.setExpression(NexExpression.HAPPY)
                tvNowPlaying.text = "Finished: ${item.displayName} — Tap another animal"
            }
        }
        tvNowPlaying.text = if (started) {
            "Playing #${item.index}: ${item.displayName}"
        } else {
            "Audio error loading ${item.displayName}"
        }
    }
}
