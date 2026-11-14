package com.nex.robotpet.v6

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.nex.robotpet.v6.data.NexExpression
import com.nex.robotpet.v6.games.MiniGameCatalog
import com.nex.robotpet.v6.memory.MemoryManager
import com.nex.robotpet.v6.ui.RobotEyesView
import com.nex.robotpet.v6.util.FullscreenHelper

/**
 * Landscape Arcade Hub displaying all 7 functional NEX 6.0 Mini-Games (Part I).
 */
class GamesHubActivity : AppCompatActivity() {

    private lateinit var memoryManager: MemoryManager
    private lateinit var listContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_games_hub)
        FullscreenHelper.applyImmersiveLandscape(this)

        memoryManager = MemoryManager(this)
        listContainer = findViewById(R.id.gamesHubList)

        findViewById<RobotEyesView>(R.id.gamesHubEyes).setExpression(NexExpression.GAMING)
        findViewById<Button>(R.id.btnBackFromGamesHub).setOnClickListener { finish() }

        populateGames()
    }

    override fun onResume() {
        super.onResume()
        FullscreenHelper.applyImmersiveLandscape(this)
        populateGames()
    }

    private fun populateGames() {
        listContainer.removeAllViews()
        val density = resources.displayMetrics.density

        // Arrange 7 games in clean 2-column landscape rows
        val games = MiniGameCatalog.ALL_GAMES
        var i = 0
        while (i < games.size) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * density).toInt()
                }
            }

            for (col in 0..1) {
                val idx = i + col
                if (idx < games.size) {
                    val spec = games[idx]
                    val best = memoryManager.getGameBestScore(spec.id)
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setBackgroundResource(R.drawable.bg_cyber_card)
                        val pad = (12 * density).toInt()
                        setPadding(pad, pad, pad, pad)
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            1f
                        ).apply {
                            if (col == 0) marginEnd = (8 * density).toInt()
                        }
                    }

                    val titleTv = TextView(this).apply {
                        text = spec.title
                        textSize = 14f
                        setTextColor(Color.parseColor("#00E5FF"))
                        paint.isFakeBoldText = true
                    }

                    val descTv = TextView(this).apply {
                        text = spec.shortSummary
                        textSize = 12f
                        setTextColor(Color.parseColor("#B0ECF8FF"))
                        setPadding(0, (4 * density).toInt(), 0, (8 * density).toInt())
                    }

                    val bottomRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }

                    val bestTv = TextView(this).apply {
                        text = "Best Score: $best"
                        textSize = 12f
                        setTextColor(Color.parseColor("#FFD740"))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }

                    val playBtn = Button(this).apply {
                        text = "Play Now"
                        isAllCaps = false
                        textSize = 12f
                        setTextColor(Color.WHITE)
                        setBackgroundColor(Color.parseColor("#00838F"))
                        setOnClickListener {
                            startActivity(
                                Intent(this@GamesHubActivity, MiniGameActivity::class.java)
                                    .putExtra(MiniGameActivity.EXTRA_GAME_INDEX, spec.index)
                            )
                        }
                    }

                    bottomRow.addView(bestTv)
                    bottomRow.addView(playBtn)
                    card.addView(titleTv)
                    card.addView(descTv)
                    card.addView(bottomRow)
                    row.addView(card)
                } else {
                    val spacer = LinearLayout(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                    }
                    row.addView(spacer)
                }
            }
            listContainer.addView(row)
            i += 2
        }
    }
}
