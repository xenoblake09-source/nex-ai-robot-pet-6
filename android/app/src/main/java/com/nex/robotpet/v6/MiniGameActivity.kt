package com.nex.robotpet.v6

import android.graphics.Color
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.nex.robotpet.v6.games.MiniGameCatalog
import com.nex.robotpet.v6.games.MiniGameSpec
import com.nex.robotpet.v6.games.StarCatcherGameView
import com.nex.robotpet.v6.memory.MemoryManager
import com.nex.robotpet.v6.util.FullscreenHelper
import kotlin.random.Random

/**
 * Hosts all 7 complete, interactive landscape mini-games for NEX 6.0 (Part I).
 * Every game has functional gameplay, clear instructions, accurate score & best-score tracking,
 * and an instant Restart button.
 */
class MiniGameActivity : AppCompatActivity() {

    private lateinit var memoryManager: MemoryManager
    private lateinit var spec: MiniGameSpec

    private lateinit var tvTitle: TextView
    private lateinit var tvScore: TextView
    private lateinit var tvBest: TextView
    private lateinit var tvBanner: TextView
    private lateinit var arenaContainer: FrameLayout

    private val handler = Handler(Looper.getMainLooper())
    private var activeTimer: CountDownTimer? = null
    private var currentScore: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mini_game)
        FullscreenHelper.applyImmersiveLandscape(this)

        memoryManager = MemoryManager(this)
        val gameIdx = intent.getIntExtra(EXTRA_GAME_INDEX, 0)
            .coerceIn(0, MiniGameCatalog.ALL_GAMES.lastIndex)
        spec = MiniGameCatalog.ALL_GAMES[gameIdx]

        tvTitle = findViewById(R.id.tvMiniGameTitle)
        tvScore = findViewById(R.id.tvMiniGameScore)
        tvBest = findViewById(R.id.tvMiniGameBest)
        tvBanner = findViewById(R.id.tvGameStatusBanner)
        arenaContainer = findViewById(R.id.gameArenaContainer)

        tvTitle.text = spec.title

        findViewById<Button>(R.id.btnBackFromGame).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnGameInstructions).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(spec.title)
                .setMessage(spec.instructions)
                .setPositiveButton("Got It", null)
                .show()
        }
        findViewById<Button>(R.id.btnGameRestart).setOnClickListener {
            startSelectedGame()
        }

        startSelectedGame()
    }

    override fun onResume() {
        super.onResume()
        FullscreenHelper.applyImmersiveLandscape(this)
    }

    override fun onDestroy() {
        cleanupGameLoop()
        super.onDestroy()
    }

    private fun cleanupGameLoop() {
        activeTimer?.cancel()
        activeTimer = null
        handler.removeCallbacksAndMessages(null)
    }

    private fun updateScoreDisplay(newScore: Int) {
        currentScore = newScore
        val best = memoryManager.updateGameBestScore(spec.id, newScore)
        tvScore.text = "Score: $currentScore"
        tvBest.text = "Best: $best"
    }

    private fun startSelectedGame() {
        cleanupGameLoop()
        arenaContainer.removeAllViews()
        currentScore = 0
        tvScore.text = "Score: 0"
        tvBest.text = "Best: ${memoryManager.getGameBestScore(spec.id)}"
        tvBanner.text = spec.instructions

        when (spec.id) {
            "tic_tac_toe" -> buildTicTacToeGame()
            "memory_match" -> buildMemoryMatchGame()
            "star_catcher" -> buildStarCatcherGame()
            "simon_cyber" -> buildSimonCyberGame()
            "whack_glitch" -> buildWhackGlitchGame()
            "reaction_reflex" -> buildReactionReflexGame()
            "math_blaster" -> buildMathBlasterGame()
        }
    }

    // =========================================================================
    // GAME 1: TIC-TAC-TOE VS NEX
    // =========================================================================
    private fun buildTicTacToeGame() {
        val board = CharArray(9) { ' ' }
        val buttons = arrayOfNulls<Button>(9)
        var roundLocked = false
        var winsThisSession = 0

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        fun checkWinner(): Char {
            val lines = arrayOf(
                intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8),
                intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
                intArrayOf(0, 4, 8), intArrayOf(2, 4, 6)
            )
            for (l in lines) {
                val a = board[l[0]]
                if (a != ' ' && a == board[l[1]] && a == board[l[2]]) return a
            }
            return if (board.none { it == ' ' }) 'D' else ' '
        }

        fun resetRoundOnly() {
            for (i in 0..8) {
                board[i] = ' '
                buttons[i]?.text = ""
                buttons[i]?.isEnabled = true
                buttons[i]?.setBackgroundColor(Color.parseColor("#182B3A"))
            }
            roundLocked = false
            tvBanner.text = "Your turn (X)! Outsmart NEX (O)."
        }

        fun chooseNexMove(): Int {
            val lines = arrayOf(
                intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8),
                intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
                intArrayOf(0, 4, 8), intArrayOf(2, 4, 6)
            )
            // 1. Win if possible
            for (l in lines) {
                val vals = l.map { board[it] }
                if (vals.count { it == 'O' } == 2 && vals.count { it == ' ' } == 1) {
                    return l[vals.indexOf(' ')]
                }
            }
            // 2. Block player's immediate win
            for (l in lines) {
                val vals = l.map { board[it] }
                if (vals.count { it == 'X' } == 2 && vals.count { it == ' ' } == 1) {
                    return l[vals.indexOf(' ')]
                }
            }
            // 3. Center or available corner/side
            val empty = board.indices.filter { board[it] == ' ' }
            if (4 in empty && Random.nextInt(100) < 75) return 4
            val corners = listOf(0, 2, 6, 8).filter { it in empty }
            return if (corners.isNotEmpty()) corners.random() else empty.random()
        }

        fun finishOutcome(winner: Char) {
            roundLocked = true
            when (winner) {
                'X' -> {
                    winsThisSession += 10
                    updateScoreDisplay(winsThisSession)
                    tvBanner.text = "You beat NEX! (+10 pts) — Starting next board..."
                }
                'O' -> tvBanner.text = "NEX wins this round! — Starting next board..."
                else -> {
                    winsThisSession += 3
                    updateScoreDisplay(winsThisSession)
                    tvBanner.text = "Draw game! (+3 pts) — Starting next board..."
                }
            }
            handler.postDelayed({ resetRoundOnly() }, 1350L)
        }

        val density = resources.displayMetrics.density
        for (r in 0..2) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    0,
                    1f
                )
            }
            for (c in 0..2) {
                val idx = r * 3 + c
                val btn = Button(this).apply {
                    textSize = 22f
                    isAllCaps = false
                    paint.isFakeBoldText = true
                    setBackgroundColor(Color.parseColor("#182B3A"))
                    layoutParams = LinearLayout.LayoutParams(
                        (104 * density).toInt(),
                        LinearLayout.LayoutParams.MATCH_PARENT
                    ).apply {
                        val m = (4 * density).toInt()
                        setMargins(m, m, m, m)
                    }
                    setOnClickListener {
                        if (roundLocked || board[idx] != ' ') return@setOnClickListener
                        board[idx] = 'X'
                        text = "X"
                        setTextColor(Color.parseColor("#00E5FF"))
                        val w1 = checkWinner()
                        if (w1 != ' ') {
                            finishOutcome(w1)
                            return@setOnClickListener
                        }
                        roundLocked = true
                        tvBanner.text = "NEX is thinking..."
                        handler.postDelayed({
                            val aiIdx = chooseNexMove()
                            board[aiIdx] = 'O'
                            buttons[aiIdx]?.text = "O"
                            buttons[aiIdx]?.setTextColor(Color.parseColor("#FF4081"))
                            val w2 = checkWinner()
                            if (w2 != ' ') {
                                finishOutcome(w2)
                            } else {
                                roundLocked = false
                                tvBanner.text = "Your turn (X)!"
                            }
                        }, 320L)
                    }
                }
                buttons[idx] = btn
                row.addView(btn)
            }
            wrapper.addView(row)
        }

        arenaContainer.addView(wrapper)
        tvBanner.text = "Your turn (X)! Tap any square to challenge NEX."
    }

    // =========================================================================
    // GAME 2: MEMORY MATRIX MATCH
    // =========================================================================
    private fun buildMemoryMatchGame() {
        val symbols = listOf("⚡", "🤖", "🛰️", "🔋", "💎", "🌟")
        val deck = (symbols + symbols).shuffled()
        val matched = BooleanArray(12)
        val cardButtons = arrayOfNulls<Button>(12)
        var firstIdx: Int? = null
        var lockFlip = false
        var moves = 0
        var pairsFound = 0

        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val density = resources.displayMetrics.density
        for (r in 0..2) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }
            for (c in 0..3) {
                val idx = r * 4 + c
                val btn = Button(this).apply {
                    text = "?"
                    textSize = 22f
                    setTextColor(Color.parseColor("#00E5FF"))
                    setBackgroundColor(Color.parseColor("#172A3A"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                        val m = (4 * density).toInt()
                        setMargins(m, m, m, m)
                    }
                    setOnClickListener {
                        if (lockFlip || matched[idx] || firstIdx == idx) return@setOnClickListener
                        text = deck[idx]
                        setBackgroundColor(Color.parseColor("#234257"))
                        val prev = firstIdx
                        if (prev == null) {
                            firstIdx = idx
                        } else {
                            moves++
                            firstIdx = null
                            if (deck[prev] == deck[idx]) {
                                matched[prev] = true
                                matched[idx] = true
                                cardButtons[prev]?.setBackgroundColor(Color.parseColor("#1B5E20"))
                                setBackgroundColor(Color.parseColor("#1B5E20"))
                                pairsFound++
                                val pts = maxOf(20, 220 - moves * 10)
                                if (pairsFound == 6) {
                                    updateScoreDisplay(pts)
                                    tvBanner.text = "All 6 pairs matched in $moves moves! Final Score: $pts. Tap Restart to play again!"
                                } else {
                                    updateScoreDisplay(pairsFound * 20)
                                    tvBanner.text = "Pair matched! ($pairsFound/6 pairs, $moves moves)"
                                }
                            } else {
                                lockFlip = true
                                tvBanner.text = "Not a match ($moves moves) — remember their positions!"
                                handler.postDelayed({
                                    cardButtons[prev]?.text = "?"
                                    cardButtons[prev]?.setBackgroundColor(Color.parseColor("#172A3A"))
                                    text = "?"
                                    setBackgroundColor(Color.parseColor("#172A3A"))
                                    lockFlip = false
                                }, 650L)
                            }
                        }
                    }
                }
                cardButtons[idx] = btn
                row.addView(btn)
            }
            grid.addView(row)
        }

        arenaContainer.addView(grid)
    }

    // =========================================================================
    // GAME 3: CATCH THE FALLING STARS
    // =========================================================================
    private fun buildStarCatcherGame() {
        val starView = StarCatcherGameView(this) { score, lives, gameOver ->
            updateScoreDisplay(score)
            tvBanner.text = if (gameOver) {
                "Game Over! Final Score: $score — Tap Restart or tap the arena to play again."
            } else {
                "Lives remaining: $lives — Slide finger horizontally to catch stars!"
            }
        }
        arenaContainer.addView(
            starView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    // =========================================================================
    // GAME 4: NEX SIMON CYBER SEQUENCE
    // =========================================================================
    private fun buildSimonCyberGame() {
        val baseColors = intArrayOf(
            Color.parseColor("#006064"),
            Color.parseColor("#1B5E20"),
            Color.parseColor("#827717"),
            Color.parseColor("#4A148C")
        )
        val litColors = intArrayOf(
            Color.parseColor("#00E5FF"),
            Color.parseColor("#00E676"),
            Color.parseColor("#FFD740"),
            Color.parseColor("#EA80FC")
        )
        val labels = arrayOf("CYAN", "GREEN", "GOLD", "VIOLET")
        val pads = arrayOfNulls<Button>(4)
        val sequence = mutableListOf<Int>()
        var playerPos = 0
        var acceptingInput = false

        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        fun flashPad(padIdx: Int, durationMs: Long = 340L) {
            pads[padIdx]?.setBackgroundColor(litColors[padIdx])
            handler.postDelayed({
                pads[padIdx]?.setBackgroundColor(baseColors[padIdx])
            }, durationMs)
        }

        fun playNextRound() {
            acceptingInput = false
            playerPos = 0
            sequence.add(Random.nextInt(4))
            tvBanner.text = "Watch NEX closely! Sequence length: ${sequence.size}"
            sequence.forEachIndexed { step, padIdx ->
                handler.postDelayed({
                    flashPad(padIdx, 360L)
                }, 550L * (step + 1))
            }
            handler.postDelayed({
                acceptingInput = true
                tvBanner.text = "Your Turn! Repeat all ${sequence.size} flashes."
            }, 550L * sequence.size + 520L)
        }

        val density = resources.displayMetrics.density
        for (r in 0..1) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            for (c in 0..1) {
                val idx = r * 2 + c
                val btn = Button(this).apply {
                    text = labels[idx]
                    textSize = 18f
                    setTextColor(Color.WHITE)
                    setBackgroundColor(baseColors[idx])
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                        val m = (6 * density).toInt()
                        setMargins(m, m, m, m)
                    }
                    setOnClickListener {
                        if (!acceptingInput) return@setOnClickListener
                        flashPad(idx, 220L)
                        if (sequence[playerPos] == idx) {
                            playerPos++
                            if (playerPos == sequence.size) {
                                acceptingInput = false
                                updateScoreDisplay(sequence.size * 15)
                                tvBanner.text = "Awesome memory! Advancing to next level..."
                                handler.postDelayed({ playNextRound() }, 700L)
                            }
                        } else {
                            acceptingInput = false
                            tvBanner.text = "Wrong pad! You reached sequence length ${sequence.size - 1}. Tap Restart to try again!"
                        }
                    }
                }
                pads[idx] = btn
                row.addView(btn)
            }
            grid.addView(row)
        }

        arenaContainer.addView(grid)
        playNextRound()
    }

    // =========================================================================
    // GAME 5: WHACK-A-GLITCH (30 SECONDS)
    // =========================================================================
    private fun buildWhackGlitchGame() {
        val cells = arrayOfNulls<Button>(8)
        var activeCell = -1
        var isFriendlyDecoy = false
        var running = true

        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        fun clearAllCells() {
            for (i in 0..7) {
                cells[i]?.text = ""
                cells[i]?.setBackgroundColor(Color.parseColor("#142230"))
            }
            activeCell = -1
        }

        fun spawnTarget() {
            if (!running) return
            clearAllCells()
            activeCell = Random.nextInt(8)
            isFriendlyDecoy = Random.nextInt(100) < 25
            if (isFriendlyDecoy) {
                cells[activeCell]?.text = "🤖 NEX\n(Don't Tap)"
                cells[activeCell]?.setBackgroundColor(Color.parseColor("#006064"))
            } else {
                cells[activeCell]?.text = "👾 GLITCH!\n(+10)"
                cells[activeCell]?.setBackgroundColor(Color.parseColor("#C62828"))
            }
            handler.postDelayed({ spawnTarget() }, 780L)
        }

        val density = resources.displayMetrics.density
        for (r in 0..1) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            for (c in 0..3) {
                val idx = r * 4 + c
                val btn = Button(this).apply {
                    textSize = 14f
                    isAllCaps = false
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#142230"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                        val m = (5 * density).toInt()
                        setMargins(m, m, m, m)
                    }
                    setOnClickListener {
                        if (!running || idx != activeCell) return@setOnClickListener
                        if (isFriendlyDecoy) {
                            updateScoreDisplay(maxOf(0, currentScore - 5))
                        } else {
                            updateScoreDisplay(currentScore + 10)
                        }
                        clearAllCells()
                    }
                }
                cells[idx] = btn
                row.addView(btn)
            }
            grid.addView(row)
        }

        arenaContainer.addView(grid)
        spawnTarget()

        activeTimer = object : CountDownTimer(30_000L, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = millisUntilFinished / 1000L
                tvBanner.text = "Time Left: ${sec}s — Tap RED GLITCHES (+10), avoid CYAN NEX (-5)!"
            }

            override fun onFinish() {
                running = false
                handler.removeCallbacksAndMessages(null)
                clearAllCells()
                tvBanner.text = "Time's Up! Final Score: $currentScore — Tap Restart to play again!"
            }
        }.start()
    }

    // =========================================================================
    // GAME 6: REACTION SPEED REFLEX TEST
    // =========================================================================
    private fun buildReactionReflexGame() {
        var state = 0 // 0 = ready to arm, 1 = waiting red, 2 = green tap now, 3 = finished
        var greenShownAtMs = 0L
        val reactionTimes = mutableListOf<Long>()

        val pad = Button(this).apply {
            text = "TAP TO START ROUND 1 OF 5"
            textSize = 22f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#00695C"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        fun armNextRound() {
            state = 1
            pad.setBackgroundColor(Color.parseColor("#B71C1C"))
            pad.text = "WAIT FOR NEON GREEN...\n(Round ${reactionTimes.size + 1} / 5)"
            val delayMs = Random.nextLong(1400L, 3600L)
            handler.postDelayed({
                if (state == 1) {
                    state = 2
                    greenShownAtMs = SystemClock.elapsedRealtime()
                    pad.setBackgroundColor(Color.parseColor("#00C853"))
                    pad.text = "TAP NOW!"
                }
            }, delayMs)
        }

        pad.setOnClickListener {
            when (state) {
                0 -> armNextRound()
                1 -> {
                    handler.removeCallbacksAndMessages(null)
                    state = 0
                    pad.setBackgroundColor(Color.parseColor("#E65100"))
                    pad.text = "Too Early! Tap to re-arm Round ${reactionTimes.size + 1}."
                }
                2 -> {
                    val elapsed = (SystemClock.elapsedRealtime() - greenShownAtMs).coerceAtLeast(1L)
                    reactionTimes.add(elapsed)
                    val avg = reactionTimes.average().toInt()
                    val pts = maxOf(50, 1000 - avg)
                    updateScoreDisplay(pts)
                    if (reactionTimes.size >= 5) {
                        state = 3
                        pad.setBackgroundColor(Color.parseColor("#00838F"))
                        pad.text = "5 Rounds Complete!\nAverage Reflex: ${avg} ms  |  Score: $pts\n(Tap Restart to test again)"
                        tvBanner.text = "Completed 5 reflex rounds! Average reaction time: ${avg} ms."
                    } else {
                        state = 0
                        pad.setBackgroundColor(Color.parseColor("#172A3A"))
                        pad.text = "Reaction: ${elapsed} ms!\nTap for Round ${reactionTimes.size + 1} of 5"
                    }
                }
                3 -> startSelectedGame()
            }
        }

        arenaContainer.addView(pad)
    }

    // =========================================================================
    // GAME 7: MATH BLASTER QUIZ WITH NEX (45 SECONDS)
    // =========================================================================
    private fun buildMathBlasterGame() {
        var correctAnswer = 0
        var streak = 0
        var gameActive = true
        val optionButtons = arrayOfNulls<Button>(4)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val questionTv = TextView(this).apply {
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#00E5FF"))
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val optionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.1f
            )
        }

        fun generateQuestion() {
            if (!gameActive) return
            val op = Random.nextInt(3)
            val a: Int
            val b: Int
            val symbol: String
            when (op) {
                0 -> {
                    a = Random.nextInt(8, 55)
                    b = Random.nextInt(6, 45)
                    correctAnswer = a + b
                    symbol = "+"
                }
                1 -> {
                    a = Random.nextInt(20, 75)
                    b = Random.nextInt(5, a)
                    correctAnswer = a - b
                    symbol = "−"
                }
                else -> {
                    a = Random.nextInt(3, 13)
                    b = Random.nextInt(3, 13)
                    correctAnswer = a * b
                    symbol = "×"
                }
            }
            questionTv.text = "$a  $symbol  $b  =  ?"

            val choices = mutableSetOf(correctAnswer)
            while (choices.size < 4) {
                val delta = Random.nextInt(-12, 13)
                val candidate = correctAnswer + if (delta == 0) 3 else delta
                if (candidate >= 0) choices.add(candidate)
            }
            val shuffled = choices.shuffled()
            for (i in 0..3) {
                optionButtons[i]?.text = shuffled[i].toString()
                optionButtons[i]?.tag = shuffled[i]
            }
        }

        val density = resources.displayMetrics.density
        for (i in 0..3) {
            val btn = Button(this).apply {
                textSize = 20f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#172A3A"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    val m = (6 * density).toInt()
                    setMargins(m, m, m, m)
                }
                setOnClickListener {
                    if (!gameActive) return@setOnClickListener
                    val picked = tag as? Int ?: return@setOnClickListener
                    if (picked == correctAnswer) {
                        streak++
                        updateScoreDisplay(currentScore + 10 + streak * 2)
                    } else {
                        streak = 0
                    }
                    generateQuestion()
                }
            }
            optionButtons[i] = btn
            optionsRow.addView(btn)
        }

        root.addView(questionTv)
        root.addView(optionsRow)
        arenaContainer.addView(root)
        generateQuestion()

        activeTimer = object : CountDownTimer(45_000L, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = millisUntilFinished / 1000L
                tvBanner.text = "Time Left: ${sec}s  |  Streak: $streak — Pick the right answer fast!"
            }

            override fun onFinish() {
                gameActive = false
                questionTv.text = "Time's Up! Score: $currentScore"
                tvBanner.text = "Quiz complete! Final Score: $currentScore — Tap Restart to play again!"
            }
        }.start()
    }

    companion object {
        const val EXTRA_GAME_INDEX = "extra_game_index"
    }
}
