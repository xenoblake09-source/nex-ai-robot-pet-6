package com.nex.robotpet.v6.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.nex.robotpet.v6.data.CustomEyeStyle
import com.nex.robotpet.v6.data.NexExpression
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Full-screen EMO-inspired glowing square robot eyes view (Part B).
 * - Pure black (#000000) background.
 * - Smooth 60 FPS procedural physics & eyelid mask rendering across all 17 expressions.
 * - Works 100% offline with zero network dependency.
 */
class RobotEyesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var currentExpression: NexExpression = NexExpression.HAPPY
        private set

    var customStyle: CustomEyeStyle = CustomEyeStyle()
        set(value) {
            field = value
            invalidate()
        }

    var isSpeaking: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var micLevelNormalized: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var musicBeatLevel: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private var targetGazeX: Float = 0f
    private var targetGazeY: Float = 0f
    private var smoothGazeX: Float = 0f
    private var smoothGazeY: Float = 0f

    private var curLeftScaleY: Float = 1f
    private var curRightScaleY: Float = 1f
    private var curLeftScaleX: Float = 1f
    private var curRightScaleX: Float = 1f
    private var curLeftSlant: Float = 0f
    private var curRightSlant: Float = 0f
    private var curBottomHappyArc: Float = 0f

    private var blinkProgress: Float = 0f
    private var isBlinkingNow: Boolean = false
    private var blinkStartMs: Long = 0L
    private var nextAutoBlinkMs: Long = SystemClock.uptimeMillis() + 2600L
    private var nextMicroSaccadeMs: Long = SystemClock.uptimeMillis() + 1900L

    private var curColorR: Float = 0f
    private var curColorG: Float = 229f
    private var curColorB: Float = 255f

    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowMidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }
    private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val scanlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val rectTemp = RectF()
    private val pathTemp = Path()

    fun setExpression(expression: NexExpression) {
        currentExpression = expression
        if (expression == NexExpression.BLINK) {
            triggerBlink()
        }
        postInvalidateOnAnimation()
    }

    fun triggerBlink() {
        isBlinkingNow = true
        blinkStartMs = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    /**
     * Set normalized gaze target in [-1f..1f] (used for on-device face tracking & touch curiosity).
     */
    fun setGazeTarget(normX: Float, normY: Float) {
        targetGazeX = normX.coerceIn(-1f, 1f)
        targetGazeY = normY.coerceIn(-1f, 1f)
        nextMicroSaccadeMs = SystemClock.uptimeMillis() + 3500L
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 4f || h <= 4f) return

        val now = SystemClock.uptimeMillis()
        val tSec = now / 1000f

        updateBlinkAndSaccades(now)

        // Compute target geometric parameters for each of the 17 expressions
        var tgtLeftScaleY = 1.0f
        var tgtRightScaleY = 1.0f
        var tgtLeftScaleX = 1.0f
        var tgtRightScaleX = 1.0f
        var tgtLeftSlant = 0.0f   // >0 slopes down toward center (angry), <0 slopes down outward (sad)
        var tgtRightSlant = 0.0f
        var tgtHappyArc = 0.0f
        var targetColor = Color.parseColor("#00E5FF")
        var offsetYLeft = 0f
        var offsetYRight = 0f
        var offsetXBoth = 0f

        val baseSize = min(w * 0.255f, h * 0.45f)

        when (currentExpression) {
            NexExpression.HAPPY -> {
                tgtLeftScaleY = 0.86f
                tgtRightScaleY = 0.86f
                tgtHappyArc = 0.48f
                offsetYLeft = sin(tSec * 3.0f) * baseSize * 0.03f
                offsetYRight = offsetYLeft
                targetColor = Color.parseColor("#00F0FF")
            }
            NexExpression.SAD -> {
                tgtLeftScaleY = 0.76f
                tgtRightScaleY = 0.76f
                tgtLeftSlant = -0.38f
                tgtRightSlant = -0.38f
                offsetYLeft = baseSize * 0.08f
                offsetYRight = baseSize * 0.08f
                targetColor = Color.parseColor("#2979FF")
            }
            NexExpression.ANGRY -> {
                tgtLeftScaleY = 0.74f
                tgtRightScaleY = 0.74f
                tgtLeftSlant = 0.48f
                tgtRightSlant = 0.48f
                offsetXBoth = sin(tSec * 38f) * baseSize * 0.015f
                targetColor = Color.parseColor("#FF3D00")
            }
            NexExpression.SLEEP -> {
                val breath = 0.09f + 0.03f * sin(tSec * 1.8f)
                tgtLeftScaleY = breath
                tgtRightScaleY = breath
                tgtLeftScaleX = 0.94f
                tgtRightScaleX = 0.94f
                offsetYLeft = baseSize * 0.09f
                offsetYRight = baseSize * 0.09f
                targetColor = Color.parseColor("#00838F")
            }
            NexExpression.DANCE -> {
                val beat = maxOf(musicBeatLevel, (sin(tSec * 8.5f) * 0.5f + 0.5f))
                tgtLeftScaleY = 0.78f + 0.34f * beat
                tgtRightScaleY = 1.12f - 0.30f * beat
                tgtHappyArc = 0.35f
                offsetXBoth = sin(tSec * 6.5f) * baseSize * 0.14f
                offsetYLeft = -abs(sin(tSec * 8.5f)) * baseSize * 0.12f
                offsetYRight = -abs(cos(tSec * 8.5f)) * baseSize * 0.12f
                targetColor = if ((now / 320L) % 2L == 0L) {
                    Color.parseColor("#00E5FF")
                } else {
                    Color.parseColor("#EA80FC")
                }
            }
            NexExpression.BLINK -> {
                tgtLeftScaleY = 1.0f
                tgtRightScaleY = 1.0f
                targetColor = Color.parseColor("#00E5FF")
            }
            NexExpression.SURPRISED -> {
                val pop = 1.18f + 0.04f * sin(tSec * 9f)
                tgtLeftScaleY = pop
                tgtRightScaleY = pop
                tgtLeftScaleX = 1.12f
                tgtRightScaleX = 1.12f
                targetColor = Color.parseColor("#64FFDA")
            }
            NexExpression.THINKING -> {
                tgtLeftScaleY = 0.62f
                tgtRightScaleY = 1.04f
                tgtLeftSlant = 0.14f
                offsetXBoth = sin(tSec * 1.7f) * baseSize * 0.10f
                offsetYLeft = -baseSize * 0.07f
                offsetYRight = -baseSize * 0.09f
                targetColor = Color.parseColor("#40C4FF")
            }
            NexExpression.LISTENING -> {
                val audioBoost = micLevelNormalized * 0.22f
                tgtLeftScaleY = 1.06f + audioBoost
                tgtRightScaleY = 1.06f + audioBoost
                tgtLeftScaleX = 1.04f
                tgtRightScaleX = 1.04f
                targetColor = Color.parseColor("#00E676")
            }
            NexExpression.GAMING -> {
                tgtLeftScaleY = 0.68f
                tgtRightScaleY = 0.68f
                tgtLeftScaleX = 1.08f
                tgtRightScaleX = 1.08f
                tgtLeftSlant = 0.24f
                tgtRightSlant = 0.24f
                offsetXBoth = sin(tSec * 5.2f) * baseSize * 0.13f
                targetColor = Color.parseColor("#76FF03")
            }
            NexExpression.LAUGH -> {
                val bounce = abs(sin(tSec * 12f))
                tgtLeftScaleY = 0.68f + 0.16f * bounce
                tgtRightScaleY = 0.68f + 0.16f * bounce
                tgtHappyArc = 0.68f
                offsetYLeft = -bounce * baseSize * 0.11f
                offsetYRight = -bounce * baseSize * 0.11f
                targetColor = Color.parseColor("#18FFFF")
            }
            NexExpression.CRY -> {
                tgtLeftScaleY = 0.78f
                tgtRightScaleY = 0.78f
                tgtLeftSlant = -0.42f
                tgtRightSlant = -0.42f
                offsetXBoth = sin(tSec * 22f) * baseSize * 0.01f
                targetColor = Color.parseColor("#448AFF")
            }
            NexExpression.LOVE -> {
                val pulse = 1.0f + 0.12f * maxOf(0f, sin(tSec * 6.5f))
                tgtLeftScaleY = pulse
                tgtRightScaleY = pulse
                tgtLeftScaleX = pulse
                tgtRightScaleX = pulse
                targetColor = Color.parseColor("#FF4081")
            }
            NexExpression.CURIOUS -> {
                tgtLeftScaleY = 1.14f
                tgtRightScaleY = 0.76f
                tgtLeftScaleX = 1.08f
                tgtRightScaleX = 0.92f
                offsetYLeft = -baseSize * 0.05f
                offsetYRight = baseSize * 0.04f
                targetColor = Color.parseColor("#00E5FF")
            }
            NexExpression.EXCITED -> {
                val hop = abs(sin(tSec * 10f))
                tgtLeftScaleY = 1.12f + 0.08f * hop
                tgtRightScaleY = 1.12f + 0.08f * hop
                tgtHappyArc = 0.30f
                offsetYLeft = -hop * baseSize * 0.10f
                offsetYRight = -hop * baseSize * 0.10f
                targetColor = Color.parseColor("#FFD740")
            }
            NexExpression.CONFUSED -> {
                tgtLeftScaleY = 0.64f
                tgtRightScaleY = 1.10f
                tgtLeftSlant = -0.30f
                tgtRightSlant = 0.28f
                offsetXBoth = sin(tSec * 2.8f) * baseSize * 0.06f
                targetColor = Color.parseColor("#B388FF")
            }
            NexExpression.CUSTOM -> {
                tgtLeftScaleY = customStyle.heightScale
                tgtRightScaleY = customStyle.heightScale
                tgtLeftSlant = customStyle.browSlant
                tgtRightSlant = customStyle.browSlant
                targetColor = customStyle.colorArgb
            }
        }

        // Speaking pulse modulation (eye height lively voice rhythm)
        if (isSpeaking) {
            val voicePulse = 0.88f + 0.22f * abs(sin(tSec * 13.5f))
            tgtLeftScaleY *= voicePulse
            tgtRightScaleY *= voicePulse
        }

        // Apply active blink squash
        val blinkMultiplier = (1.0f - blinkProgress).coerceAtLeast(0.06f)
        tgtLeftScaleY *= blinkMultiplier
        tgtRightScaleY *= blinkMultiplier

        // Smooth exponential interpolation (60 FPS silky motion)
        val smooth = 0.24f
        curLeftScaleY += (tgtLeftScaleY - curLeftScaleY) * smooth
        curRightScaleY += (tgtRightScaleY - curRightScaleY) * smooth
        curLeftScaleX += (tgtLeftScaleX - curLeftScaleX) * smooth
        curRightScaleX += (tgtRightScaleX - curRightScaleX) * smooth
        curLeftSlant += (tgtLeftSlant - curLeftSlant) * smooth
        curRightSlant += (tgtRightSlant - curRightSlant) * smooth
        curBottomHappyArc += (tgtHappyArc - curBottomHappyArc) * smooth
        smoothGazeX += (targetGazeX - smoothGazeX) * 0.16f
        smoothGazeY += (targetGazeY - smoothGazeY) * 0.16f

        curColorR += (Color.red(targetColor) - curColorR) * 0.18f
        curColorG += (Color.green(targetColor) - curColorG) * 0.18f
        curColorB += (Color.blue(targetColor) - curColorB) * 0.18f

        val rInt = curColorR.toInt().coerceIn(0, 255)
        val gInt = curColorG.toInt().coerceIn(0, 255)
        val bInt = curColorB.toInt().coerceIn(0, 255)
        val mainColor = Color.rgb(rInt, gInt, bInt)

        val glowScale = if (currentExpression == NexExpression.CUSTOM) customStyle.glowStrength else 1.0f
        glowOuterPaint.color = Color.argb((42 * glowScale).toInt().coerceIn(0, 120), rInt, gInt, bInt)
        glowMidPaint.color = Color.argb((88 * glowScale).toInt().coerceIn(0, 180), rInt, gInt, bInt)
        eyePaint.color = mainColor
        glintPaint.color = Color.argb(
            215,
            min(255, rInt + 140),
            min(255, gInt + 140),
            min(255, bInt + 140)
        )

        val centerGap = baseSize * 0.58f
        val gazeShiftX = smoothGazeX * baseSize * 0.24f + offsetXBoth
        val gazeShiftY = smoothGazeY * baseSize * 0.18f

        val leftCx = w * 0.5f - (baseSize * 0.5f + centerGap * 0.5f) + gazeShiftX
        val rightCx = w * 0.5f + (baseSize * 0.5f + centerGap * 0.5f) + gazeShiftX
        val leftCy = h * 0.5f + gazeShiftY + offsetYLeft
        val rightCy = h * 0.5f + gazeShiftY + offsetYRight

        // Draw Left & Right Glowing Square Robot Eyes
        drawSingleRobotEye(
            canvas = canvas,
            cx = leftCx,
            cy = leftCy,
            baseW = baseSize * curLeftScaleX,
            baseH = baseSize * 1.06f * curLeftScaleY,
            slant = curLeftSlant,
            isLeftEye = true,
            happyArc = curBottomHappyArc,
            tSec = tSec
        )

        drawSingleRobotEye(
            canvas = canvas,
            cx = rightCx,
            cy = rightCy,
            baseW = baseSize * curRightScaleX,
            baseH = baseSize * 1.06f * curRightScaleY,
            slant = curRightSlant,
            isLeftEye = false,
            happyArc = curBottomHappyArc,
            tSec = tSec
        )

        // Special expression particle accents (drawn purely as robot face animation, zero text clutter)
        drawExpressionParticles(canvas, leftCx, rightCx, leftCy, rightCy, baseSize, tSec, mainColor)

        // Schedule next animation frame at 60 FPS while visible
        if (isShown) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawSingleRobotEye(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        baseW: Float,
        baseH: Float,
        slant: Float,
        isLeftEye: Boolean,
        happyArc: Float,
        tSec: Float
    ) {
        val corner = min(baseW, baseH) * 0.22f

        // Listening sonar halo
        if (currentExpression == NexExpression.LISTENING) {
            val ripple = (tSec * 1.6f) % 1.0f
            val extra = baseW * (0.14f + 0.34f * ripple + micLevelNormalized * 0.18f)
            ringPaint.strokeWidth = maxOf(2f, baseW * 0.03f)
            ringPaint.color = Color.argb(((1f - ripple) * 150).toInt(), 0, 230, 118)
            rectTemp.set(cx - baseW / 2f - extra, cy - baseH / 2f - extra, cx + baseW / 2f + extra, cy + baseH / 2f + extra)
            canvas.drawRoundRect(rectTemp, corner + extra * 0.5f, corner + extra * 0.5f, ringPaint)
        }

        // Love / Heart eye mode renders glowing twin robot hearts inside neon square aura
        if (currentExpression == NexExpression.LOVE && blinkProgress < 0.5f) {
            val padOuter = baseW * 0.16f
            rectTemp.set(cx - baseW / 2f - padOuter, cy - baseH / 2f - padOuter, cx + baseW / 2f + padOuter, cy + baseH / 2f + padOuter)
            canvas.drawRoundRect(rectTemp, corner * 1.4f, corner * 1.4f, glowOuterPaint)
            drawHeartShape(canvas, cx, cy, baseW * 0.96f, baseH * 0.92f, eyePaint)
            drawHeartShape(canvas, cx - baseW * 0.14f, cy - baseH * 0.14f, baseW * 0.22f, baseH * 0.22f, glintPaint)
            return
        }

        // Outer & Mid Neon Glow Layers
        val outerPad = baseW * 0.18f
        rectTemp.set(cx - baseW / 2f - outerPad, cy - baseH / 2f - outerPad, cx + baseW / 2f + outerPad, cy + baseH / 2f + outerPad)
        canvas.drawRoundRect(rectTemp, corner + outerPad * 0.6f, corner + outerPad * 0.6f, glowOuterPaint)

        val midPad = baseW * 0.08f
        rectTemp.set(cx - baseW / 2f - midPad, cy - baseH / 2f - midPad, cx + baseW / 2f + midPad, cy + baseH / 2f + midPad)
        canvas.drawRoundRect(rectTemp, corner + midPad * 0.5f, corner + midPad * 0.5f, glowMidPaint)

        // Core Glowing Square Robot Eye
        val left = cx - baseW / 2f
        val top = cy - baseH / 2f
        val right = cx + baseW / 2f
        val bottom = cy + baseH / 2f
        rectTemp.set(left, top, right, bottom)
        canvas.drawRoundRect(rectTemp, corner, corner, eyePaint)

        // Subtle OLED Robot Scanlines (gives authentic EMO/Cozmo screen depth)
        if (baseH > 28f) {
            scanlinePaint.color = Color.argb(38, 0, 0, 0)
            scanlinePaint.strokeWidth = maxOf(1.2f, baseH * 0.022f)
            val step = maxOf(4f, baseH * 0.095f)
            var yLine = top + step
            while (yLine < bottom - step * 0.5f) {
                canvas.drawLine(left + 2f, yLine, right - 2f, yLine, scanlinePaint)
                yLine += step
            }
        }

        // Specular cyber glint in upper-left quadrant (when eye is open)
        if (baseH > baseW * 0.32f) {
            val gw = baseW * 0.22f
            val gh = baseH * 0.16f
            val gx = left + baseW * 0.15f
            val gy = top + baseH * 0.14f
            rectTemp.set(gx, gy, gx + gw, gy + gh)
            canvas.drawRoundRect(rectTemp, gw * 0.35f, gw * 0.35f, glintPaint)
        }

        // Upper Brow Slant Cutout (Angry / Sad / Gaming / Confused)
        if (abs(slant) > 0.02f) {
            val drop = abs(slant) * baseH * 0.62f
            val dropInner = (slant > 0f)
            val leftDrop = if ((isLeftEye && dropInner) || (!isLeftEye && !dropInner)) 0f else drop
            val rightDrop = if ((isLeftEye && dropInner) || (!isLeftEye && !dropInner)) drop else 0f

            pathTemp.reset()
            pathTemp.moveTo(left - outerPad, top - outerPad)
            pathTemp.lineTo(right + outerPad, top - outerPad)
            pathTemp.lineTo(right + outerPad, top + rightDrop)
            pathTemp.lineTo(left - outerPad, top + leftDrop)
            pathTemp.close()
            canvas.drawPath(pathTemp, maskPaint)
        }

        // Lower Happy Cheek Arch Cutout (Happy / Laugh / Dance / Excited)
        if (happyArc > 0.03f) {
            val archHeight = baseH * 0.55f * happyArc
            rectTemp.set(
                left - baseW * 0.12f,
                bottom - archHeight,
                right + baseW * 0.12f,
                bottom + baseH * 0.75f
            )
            canvas.drawOval(rectTemp, maskPaint)
        }

        // Surprised bright inner focus ring
        if (currentExpression == NexExpression.SURPRISED) {
            ringPaint.color = Color.argb(190, 230, 255, 255)
            ringPaint.strokeWidth = maxOf(2f, baseW * 0.045f)
            val inset = baseW * 0.14f
            rectTemp.set(left + inset, top + inset, right - inset, bottom - inset)
            canvas.drawRoundRect(rectTemp, corner * 0.7f, corner * 0.7f, ringPaint)
        }
    }

    private fun drawHeartShape(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float, paint: Paint) {
        pathTemp.reset()
        val topCurveHeight = h * 0.32f
        pathTemp.moveTo(cx, cy + h * 0.42f)
        pathTemp.cubicTo(
            cx - w * 0.55f, cy + h * 0.10f,
            cx - w * 0.52f, cy - h * 0.42f,
            cx, cy - h * 0.42f + topCurveHeight
        )
        pathTemp.cubicTo(
            cx + w * 0.52f, cy - h * 0.42f,
            cx + w * 0.55f, cy + h * 0.10f,
            cx, cy + h * 0.42f
        )
        pathTemp.close()
        canvas.drawPath(pathTemp, paint)
    }

    private fun drawExpressionParticles(
        canvas: Canvas,
        leftCx: Float,
        rightCx: Float,
        leftCy: Float,
        rightCy: Float,
        baseSize: Float,
        tSec: Float,
        mainColor: Int
    ) {
        when (currentExpression) {
            NexExpression.CRY -> {
                // Falling glowing cyan teardrops below both eyes
                eyePaint.color = Color.parseColor("#80D8FF")
                val dropR = maxOf(3f, baseSize * 0.065f)
                val fallLeft = ((tSec * 1.4f) % 1.0f) * baseSize * 0.65f
                val fallRight = (((tSec * 1.4f) + 0.5f) % 1.0f) * baseSize * 0.65f
                canvas.drawCircle(leftCx - baseSize * 0.22f, leftCy + baseSize * 0.45f + fallLeft, dropR, eyePaint)
                canvas.drawCircle(rightCx + baseSize * 0.22f, rightCy + baseSize * 0.45f + fallRight, dropR, eyePaint)
            }
            NexExpression.SLEEP -> {
                // Rising tiny glowing sleep bubbles above right eye
                for (i in 0..2) {
                    val phase = ((tSec * 0.45f) + i * 0.33f) % 1.0f
                    val alpha = ((1f - phase) * 165).toInt().coerceIn(0, 255)
                    eyePaint.color = Color.argb(alpha, 0, 229, 255)
                    val sz = baseSize * (0.045f + 0.035f * phase)
                    val px = rightCx + baseSize * (0.35f + phase * 0.28f)
                    val py = rightCy - baseSize * (0.25f + phase * 0.65f)
                    rectTemp.set(px - sz, py - sz, px + sz, py + sz)
                    canvas.drawRoundRect(rectTemp, sz * 0.3f, sz * 0.3f, eyePaint)
                }
            }
            NexExpression.THINKING -> {
                // Orbiting pixel dots above raised right eye
                for (i in 0..2) {
                    val angle = tSec * 3.8f + i * 2.09f
                    val px = rightCx + cos(angle) * baseSize * 0.24f
                    val py = rightCy - baseSize * 0.72f + sin(angle) * baseSize * 0.08f
                    val r = maxOf(2.5f, baseSize * 0.04f)
                    eyePaint.color = mainColor
                    canvas.drawCircle(px, py, r, eyePaint)
                }
            }
            NexExpression.EXCITED -> {
                // Sparkling corner pixel glints
                val pulse = abs(sin(tSec * 9f))
                val sz = baseSize * 0.055f * (0.6f + pulse * 0.7f)
                eyePaint.color = Color.WHITE
                canvas.drawCircle(leftCx - baseSize * 0.58f, leftCy - baseSize * 0.52f, sz, eyePaint)
                canvas.drawCircle(rightCx + baseSize * 0.58f, rightCy - baseSize * 0.52f, sz, eyePaint)
            }
            else -> Unit
        }
    }

    private fun updateBlinkAndSaccades(nowMs: Long) {
        if (currentExpression != NexExpression.SLEEP && !isBlinkingNow && nowMs >= nextAutoBlinkMs) {
            isBlinkingNow = true
            blinkStartMs = nowMs
            nextAutoBlinkMs = nowMs + Random.nextLong(2900L, 5800L)
        }

        if (isBlinkingNow) {
            val elapsed = (nowMs - blinkStartMs).toFloat()
            val totalBlinkMs = 190f
            if (elapsed >= totalBlinkMs) {
                isBlinkingNow = false
                blinkProgress = 0f
                if (currentExpression == NexExpression.BLINK) {
                    currentExpression = NexExpression.HAPPY
                }
            } else {
                val half = totalBlinkMs * 0.5f
                blinkProgress = if (elapsed <= half) {
                    elapsed / half
                } else {
                    1f - ((elapsed - half) / half)
                }
            }
        }

        if (nowMs >= nextMicroSaccadeMs && currentExpression !in setOf(NexExpression.SLEEP, NexExpression.LISTENING)) {
            targetGazeX = Random.nextFloat() * 0.56f - 0.28f
            targetGazeY = Random.nextFloat() * 0.36f - 0.18f
            nextMicroSaccadeMs = nowMs + Random.nextLong(1800L, 4200L)
        }
    }
}
