package com.nex.robotpet.v6

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.nex.robotpet.v6.data.NexExpression
import com.nex.robotpet.v6.network.NetworkStatusMonitor
import com.nex.robotpet.v6.ui.RobotEyesView
import com.nex.robotpet.v6.util.FullscreenHelper
import com.nex.robotpet.v6.util.LanguageMode
import com.nex.robotpet.v6.voice.VoiceSystemManager
import com.nex.robotpet.v6.weather.LiveWeatherReport
import com.nex.robotpet.v6.weather.WeatherResult
import com.nex.robotpet.v6.weather.WeatherService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live Time, Date, Internet Status & Real Meteorological Weather Station (Part J).
 * Never displays invented weather or fake online status.
 */
class WeatherTimeActivity : AppCompatActivity() {

    private lateinit var tvClock: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvNetStatus: TextView
    private lateinit var tvWeatherDetails: TextView
    private lateinit var etCity: EditText
    private lateinit var miniEyes: RobotEyesView

    private lateinit var networkMonitor: NetworkStatusMonitor
    private lateinit var weatherService: WeatherService
    private lateinit var voiceManager: VoiceSystemManager

    private var latestReport: LiveWeatherReport? = null
    private val clockHandler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("hh:mm:ss a", Locale.US)
    private val dateFmt = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.US)

    private val clockTicker = object : Runnable {
        override fun run() {
            val now = Date()
            tvClock.text = timeFmt.format(now)
            tvDate.text = dateFmt.format(now)
            clockHandler.postDelayed(this, 1000L)
        }
    }

    private val requestLocationPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                fetchGpsWeather()
            } else {
                tvWeatherDetails.text =
                    "Location permission was not granted. You can check any city directly (e.g. Bagaha, Patna, New Delhi)."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_weather_time)
        FullscreenHelper.applyImmersiveLandscape(this)

        weatherService = WeatherService()

        tvClock = findViewById(R.id.tvLiveClock)
        tvDate = findViewById(R.id.tvLiveDate)
        tvNetStatus = findViewById(R.id.tvInternetDiagnostic)
        tvWeatherDetails = findViewById(R.id.tvWeatherDetails)
        etCity = findViewById(R.id.etWeatherCity)
        miniEyes = findViewById(R.id.weatherMiniEyes)

        networkMonitor = NetworkStatusMonitor(this) { online ->
            tvNetStatus.text = if (online) {
                "Internet Status: ONLINE (Validated)"
            } else {
                "Internet Status: OFFLINE"
            }
            tvNetStatus.setTextColor(
                if (online) Color.parseColor("#00E676") else Color.parseColor("#FF5252")
            )
        }

        voiceManager = VoiceSystemManager(this, object : VoiceSystemManager.Listener {
            override fun onListeningStateChanged(activeCommandListening: Boolean, wakeStandbyListening: Boolean) = Unit
            override fun onMicRmsChanged(normalizedRms: Float) = Unit
            override fun onSpeakingStateChanged(speaking: Boolean) {
                miniEyes.isSpeaking = speaking
            }
            override fun onWakePhraseDetected() = Unit
            override fun onUserSpeechRecognized(transcript: String) = Unit
            override fun onVoiceDiagnosticError(safeMessage: String) = Unit
        })
        voiceManager.setForegroundWakeEnabled(false)

        findViewById<Button>(R.id.btnBackFromWeather).setOnClickListener { finish() }

        findViewById<Button>(R.id.btnFetchCityWeather).setOnClickListener {
            fetchCityWeather(etCity.text?.toString().orEmpty())
        }

        findViewById<Button>(R.id.btnQuickBagaha).setOnClickListener {
            etCity.setText("Bagaha")
            fetchCityWeather("Bagaha")
        }
        findViewById<Button>(R.id.btnQuickPatna).setOnClickListener {
            etCity.setText("Patna")
            fetchCityWeather("Patna")
        }
        findViewById<Button>(R.id.btnQuickDelhi).setOnClickListener {
            etCity.setText("New Delhi")
            fetchCityWeather("New Delhi")
        }

        findViewById<Button>(R.id.btnFetchGpsWeather).setOnClickListener {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                fetchGpsWeather()
            } else {
                requestLocationPermLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }

        findViewById<Button>(R.id.btnSpeakTimeWeather).setOnClickListener {
            val now = Date()
            val spokenTime = SimpleDateFormat("hh:mm a", Locale.US).format(now)
            val spokenDate = dateFmt.format(now)
            val weatherPart = latestReport?.toSpokenSummary(LanguageMode.ENGLISH)
                ?: if (networkMonitor.isCurrentlyOnline()) {
                    "Tap Check to load live weather."
                } else {
                    "Internet is currently offline, so live weather is unavailable."
                }
            voiceManager.speak(
                "The time is $spokenTime on $spokenDate. $weatherPart",
                LanguageMode.ENGLISH,
                resumeWakeAfter = false
            )
        }

        fetchCityWeather("Bagaha")
    }

    override fun onResume() {
        super.onResume()
        FullscreenHelper.applyImmersiveLandscape(this)
        networkMonitor.start()
        clockHandler.post(clockTicker)
    }

    override fun onPause() {
        clockHandler.removeCallbacks(clockTicker)
        networkMonitor.stop()
        voiceManager.pauseForLifecycle()
        super.onPause()
    }

    override fun onDestroy() {
        voiceManager.shutdown()
        super.onDestroy()
    }

    private fun fetchCityWeather(city: String) {
        if (!networkMonitor.hasNetworkTransport()) {
            latestReport = null
            miniEyes.setExpression(NexExpression.SAD)
            tvWeatherDetails.text =
                "Device is currently OFFLINE.\nNEX never displays invented weather—connect to Wi-Fi or mobile data and tap Check."
            return
        }

        miniEyes.setExpression(NexExpression.THINKING)
        tvWeatherDetails.text = "Fetching live meteorological weather for '${city.ifBlank { "Bagaha" }}'..."

        lifecycleScope.launch {
            when (val res = weatherService.fetchByCityName(city)) {
                is WeatherResult.Success -> {
                    networkMonitor.confirmHttpsReachable()
                    latestReport = res.report
                    miniEyes.setExpression(NexExpression.HAPPY)
                    tvWeatherDetails.text = res.report.toCardDetails()
                }
                is WeatherResult.Failure -> {
                    latestReport = null
                    miniEyes.setExpression(NexExpression.CONFUSED)
                    tvWeatherDetails.text = res.reason
                }
            }
        }
    }

    private fun fetchGpsWeather() {
        if (!networkMonitor.hasNetworkTransport()) {
            tvWeatherDetails.text = "Offline — connect to the internet to fetch live GPS weather."
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = try {
            lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (_: SecurityException) {
            null
        }
        if (loc == null) {
            tvWeatherDetails.text =
                "No cached GPS fix available right now. Use city search (Bagaha, Patna, New Delhi, etc.) above!"
            return
        }

        miniEyes.setExpression(NexExpression.THINKING)
        tvWeatherDetails.text = "Fetching live weather for current coordinates..."
        lifecycleScope.launch {
            when (val res = weatherService.fetchByCoordinates(loc.latitude, loc.longitude, "Current Location")) {
                is WeatherResult.Success -> {
                    networkMonitor.confirmHttpsReachable()
                    latestReport = res.report
                    miniEyes.setExpression(NexExpression.HAPPY)
                    tvWeatherDetails.text = res.report.toCardDetails()
                }
                is WeatherResult.Failure -> {
                    latestReport = null
                    miniEyes.setExpression(NexExpression.CONFUSED)
                    tvWeatherDetails.text = res.reason
                }
            }
        }
    }
}
