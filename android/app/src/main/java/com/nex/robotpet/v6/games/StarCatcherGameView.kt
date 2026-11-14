package com.nex.robotpet.v6.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.random.Random

/**
 * 60 FPS landscape arcade view for Mini-Game #3: Catch the Falling Stars.
 */
class StarCatcherGameView(
    context: Context,
    private val onStatsUpdated: (score: Int, lives: Int, gameOver: Boolean) -> Unit
) : View(context) {

    private data class FallingItem(
        var x: Float,
        var y: Float,
        var speedPxPerSec: Float,
        var kind: Int // 0 = gold star (+10), 1 = cyan core (+25), 2 = red hazard (-1 life)
    )

    private val items = mutableListOf<FallingItem>()
    private var paddleCenterX: Float = -1f
    private var score: Int = 0
    private var lives: Int = 3
    private var isGameOver: Boolean = false
    private var lastFrameMs: Long = SystemClock.uptimeMillis()
    private var nextSpawnMs: Long = SystemClock.uptimeMillis() + 500L

    private val paddlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
    }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 36f
        isFakeBoldText = true
    }
    private val rect = RectF()

    fun resetGame() {
        items.clear()
        score = 0
        lives = 3
        isGameOver = false
        paddleCenterX = width * 0.5f
        lastFrameMs = SystemClock.uptimeMillis()
        nextSpawnMs = lastFrameMs + 450L
        onStatsUpdated(score, lives, isGameOver)
        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            if (isGameOver) {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    resetGame()
                }
            } else if (width > 0) {
                val halfPaddle = width * 0.10f
                paddleCenterX = event.x.coerceIn(halfPaddle, width - halfPaddle)
                postInvalidateOnAnimation()
            }
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#080E14"))

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 10f || h <= 10f) return

        if (paddleCenterX < 0f) {
            paddleCenterX = w * 0.5f
        }

        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastFrameMs).coerceIn(1L, 50L)) / 1000f
        lastFrameMs = now

        val paddleW = w * 0.19f
        val paddleH = maxOf(18f, h * 0.055f)
        val paddleTop = h - paddleH - 22f

        if (!isGameOver) {
            if (now >= nextSpawnMs) {
                val roll = Random.nextInt(100)
                val kind = when {
                    roll < 55 -> 0 // Gold Star
                    roll < 75 -> 1 // Cyan Core
                    else -> 2      // Red Glitch
                }
                val baseSpeed = h * (0.42f + (score / 450f).coerceAtMost(0.55f))
                items.add(
                    FallingItem(
                        x = Random.nextFloat() * (w - 80f) + 40f,
                        y = -20f,
                        speedPxPerSec = baseSpeed * (0.85f + Random.nextFloat() * 0.35f),
                        kind = kind
                    )
                )
                val interval = (680L - (score * 2L)).coerceIn(280L, 680L)
                nextSpawnMs = now + interval
            }

            val itemRadius = maxOf(16f, h * 0.048f)
            val iterator = items.iterator()
            while (iterator.hasNext()) {
                val it = iterator.next()
                it.y += it.speedPxPerSec * dt

                val hitHoriz = abs(it.x - paddleCenterX) <= (paddleW * 0.5f + itemRadius * 0.75f)
                val hitVert = (it.y + itemRadius >= paddleTop) && (it.y - itemRadius <= paddleTop + paddleH)

                if (hitHoriz && hitVert) {
                    iterator.remove()
                    when (it.kind) {
                        0 -> score += 10
                        1 -> score += 25
                        2 -> {
                            lives -= 1
                            if (lives <= 0) {
                                lives = 0
                                isGameOver = true
                            }
                        }
                    }
                    onStatsUpdated(score, lives, isGameOver)
                } else if (it.y - itemRadius > h) {
                    iterator.remove()
                }
            }
        }

        // Draw collector paddle
        rect.set(
            paddleCenterX - paddleW * 0.5f,
            paddleTop,
            paddleCenterX + paddleW * 0.5f,
            paddleTop + paddleH
        )
        canvas.drawRoundRect(rect, paddleH * 0.5f, paddleH * 0.5f, paddlePaint)

        // Draw falling items
        val itemR = maxOf(15f, h * 0.046f)
        for (it in items) {
            starPaint.color = when (it.kind) {
                0 -> Color.parseColor("#FFD740")
                1 -> Color.parseColor("#00E676")
                else -> Color.parseColor("#FF5252")
            }
            if (it.kind == 2) {
                rect.set(it.x - itemR, it.y - itemR, it.x + itemR, it.y + itemR)
                canvas.drawRoundRect(rect, 6f, 6f, starPaint)
            } else {
                canvas.drawCircle(it.x, it.y, itemR, starPaint)
            }
        }

        textPaint.textSize = maxOf(22f, h * 0.055f)
        textPaint.color = Color.parseColor("#B0ECF8FF")
        canvas.drawText("Lives: ${"♥".repeat(lives)}", w - 120f, 42f, textPaint)

        if (isGameOver) {
            starPaint.color = Color.argb(190, 0, 0, 0)
            canvas.drawRect(0f, 0f, w, h, starPaint)
            textPaint.color = Color.parseColor("#FF5252")
            textPaint.textSize = maxOf(32f, h * 0.09f)
            canvas.drawText("GAME OVER — Final Score: $score", w * 0.5f, h * 0.45f, textPaint)
            textPaint.color = Color.parseColor("#00E5FF")
            textPaint.textSize = maxOf(22f, h * 0.06f)
            canvas.drawText("Tap Arena or 'Restart' to play again!", w * 0.5f, h * 0.60f, textPaint)
        } else if (isShown) {
            postInvalidateOnAnimation()
        }
    }
}
