package com.nex.robotpet.v6

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nex.robotpet.v6.data.CustomEyeStyle
import com.nex.robotpet.v6.memory.MemoryManager
import com.nex.robotpet.v6.network.BackendResult
import com.nex.robotpet.v6.network.NexBackendClient
import com.nex.robotpet.v6.util.FullscreenHelper
import kotlinx.coroutines.launch

/**
 * Standalone External Configuration Activity for NEX 6.0 (Parts B, L, M, N).
 *
 * Intentionally SEPARATE from [MainActivity] so NEX's main robot face contains ONLY:
 * - Pure black (#000000) background
 * - Two large glowing square robot eyes
 * - Small passive ONLINE/OFFLINE corner indicator
 *
 * Launched externally via:
 * 1. Pressing & holding the NEX icon on the Android Home Screen -> "Backend Setup" shortcut.
 * 2. Android System Settings -> Apps -> NEX -> "Additional settings in the app".
 * 3. Browser deep link: nex6://setup or nex6://setup?url=https://your-service.onrender.com
 */
class BackendSetupActivity : AppCompatActivity() {

    private lateinit var memoryManager: MemoryManager
    private lateinit var backendClient: NexBackendClient

    private lateinit var etBackendUrl: EditText
    private lateinit var tvHealthStatus: TextView
    private lateinit var tvMemorySummary: TextView
    private lateinit var chkAllowMemory: CheckBox
    private lateinit var chkWakeStandby: CheckBox
    private lateinit var chkAutonomousIdle: CheckBox
    private lateinit var seekOpenness: SeekBar
    private lateinit var seekSlant: SeekBar

    private var selectedCustomColor: Int = Color.parseColor("#00E5FF")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backend_setup)
        FullscreenHelper.applyImmersiveLandscape(this)

        memoryManager = MemoryManager(this)
        backendClient = NexBackendClient()

        etBackendUrl = findViewById(R.id.etSetupBackendUrl)
        tvHealthStatus = findViewById(R.id.tvSetupHealthStatus)
        tvMemorySummary = findViewById(R.id.tvStoredMemorySummary)
        chkAllowMemory = findViewById(R.id.chkSetupAllowMemory)
        chkWakeStandby = findViewById(R.id.chkSetupWakeStandby)
        chkAutonomousIdle = findViewById(R.id.chkSetupAutonomousIdle)
        seekOpenness = findViewById(R.id.seekCustomOpenness)
        seekSlant = findViewById(R.id.seekCustomSlant)

        // Populate current values (plus optional deep-link ?url=https://... prefill)
        val deepLinkUrl = intent?.data?.getQueryParameter("url")?.trim()
        val initialUrl = if (!deepLinkUrl.isNullOrEmpty() && MemoryManager.validateHttpsBackendUrl(deepLinkUrl) != null) {
            deepLinkUrl
        } else {
            memoryManager.backendBaseUrl
        }
        etBackendUrl.setText(initialUrl)

        chkAllowMemory.isChecked = memoryManager.isMemoryEnabled
        chkWakeStandby.isChecked = memoryManager.isWakeWordForegroundEnabled
        chkAutonomousIdle.isChecked = memoryManager.isAutonomousIdleEnabled
        refreshMemorySummary()

        setupCustomEyeControls()

        findViewById<Button>(R.id.btnResetDefaultUrl).setOnClickListener {
            memoryManager.resetBackendUrlToDefault()
            etBackendUrl.setText(MemoryManager.DEFAULT_NEX6_BACKEND_URL)
            tvHealthStatus.text = "Reset to default URL: ${MemoryManager.DEFAULT_NEX6_BACKEND_URL}"
        }

        findViewById<Button>(R.id.btnVerifyHealth).setOnClickListener {
            val raw = etBackendUrl.text?.toString()?.trim().orEmpty()
            val valid = MemoryManager.validateHttpsBackendUrl(raw)
            if (valid == null) {
                tvHealthStatus.setTextColor(Color.parseColor("#FF5252"))
                tvHealthStatus.text =
                    "Invalid URL: Must begin with https://, have a real domain, contain no API keys, and never use old NEX 5 URLs."
                return@setOnClickListener
            }
            tvHealthStatus.setTextColor(Color.parseColor("#80D8FF"))
            tvHealthStatus.text = "Checking $valid/health ..."
            lifecycleScope.launch {
                when (val res = backendClient.checkHealth(valid)) {
                    is BackendResult.Success -> {
                        val h = res.data
                        tvHealthStatus.setTextColor(Color.parseColor("#00E676"))
                        tvHealthStatus.text =
                            "Connected! Server OK=${h.ok} | Gemini Configured=${h.geminiConfigured} | Model=${h.configuredModel}"
                    }
                    is BackendResult.Error -> {
                        tvHealthStatus.setTextColor(Color.parseColor("#FF5252"))
                        tvHealthStatus.text = "Health Check Failed (${res.code}): ${res.safeMessage}"
                    }
                }
            }
        }

        findViewById<Button>(R.id.btnWipeUserData).setOnClickListener {
            memoryManager.clearAllUserData(includeBackendUrl = false)
            refreshMemorySummary()
            Toast.makeText(this, "All stored user memory & conversation history cleared.", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnCloseSetup).setOnClickListener {
            if (saveSettings()) {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        FullscreenHelper.applyImmersiveLandscape(this)
    }

    private fun refreshMemorySummary() {
        val name = memoryManager.userName ?: "(none)"
        val turns = memoryManager.getConversationHistory().size
        tvMemorySummary.text = "Stored Preferred Name: $name   |   Recent Context Turns: $turns"
    }

    private fun setupCustomEyeControls() {
        val current = memoryManager.getCustomEyeStyle()
        selectedCustomColor = current.colorArgb
        seekOpenness.progress = ((current.heightScale - 0.55f) * 100f).toInt().coerceIn(0, 80)
        seekSlant.progress = ((current.browSlant + 0.6f) * 100f).toInt().coerceIn(0, 120)

        val row = findViewById<LinearLayout>(R.id.customColorRow)
        val palette = listOf(
            "Cyan" to Color.parseColor("#00E5FF"),
            "Green" to Color.parseColor("#00E676"),
            "Gold" to Color.parseColor("#FFD740"),
            "Pink" to Color.parseColor("#FF4081"),
            "Violet" to Color.parseColor("#B388FF")
        )
        val density = resources.displayMetrics.density
        palette.forEach { (label, colorVal) ->
            val btn = Button(this).apply {
                text = label
                textSize = 11f
                isAllCaps = false
                setTextColor(Color.BLACK)
                setBackgroundColor(colorVal)
                layoutParams = LinearLayout.LayoutParams(0, (36 * density).toInt(), 1f).apply {
                    val m = (3 * density).toInt()
                    setMargins(m, 0, m, 0)
                }
                setOnClickListener {
                    selectedCustomColor = colorVal
                    Toast.makeText(this@BackendSetupActivity, "Selected $label custom eye glow", Toast.LENGTH_SHORT).show()
                }
            }
            row.addView(btn)
        }
    }

    private fun saveSettings(): Boolean {
        val candidateUrl = etBackendUrl.text?.toString()?.trim().orEmpty()
        if (candidateUrl.isEmpty()) {
            memoryManager.resetBackendUrlToDefault()
        } else {
            val valid = MemoryManager.validateHttpsBackendUrl(candidateUrl)
            if (valid == null) {
                Toast.makeText(
                    this,
                    "Please enter a valid https:// Render URL (no http:// and no old NEX 5 URLs).",
                    Toast.LENGTH_LONG
                ).show()
                return false
            }
            memoryManager.backendBaseUrl = valid
        }

        memoryManager.isMemoryEnabled = chkAllowMemory.isChecked
        memoryManager.isWakeWordForegroundEnabled = chkWakeStandby.isChecked
        memoryManager.isAutonomousIdleEnabled = chkAutonomousIdle.isChecked

        memoryManager.saveCustomEyeStyle(
            CustomEyeStyle(
                colorArgb = selectedCustomColor,
                heightScale = 0.55f + (seekOpenness.progress / 100f),
                browSlant = (seekSlant.progress / 100f) - 0.6f,
                glowStrength = 1.15f
            )
        )
        Toast.makeText(this, "NEX 6.0 setup saved.", Toast.LENGTH_SHORT).show()
        return true
    }
}
