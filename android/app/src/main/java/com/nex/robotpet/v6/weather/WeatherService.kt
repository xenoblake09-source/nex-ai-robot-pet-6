package com.nex.robotpet.v6.weather

import com.nex.robotpet.v6.util.LanguageMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

data class LiveWeatherReport(
    val locationLabel: String,
    val temperatureC: Double,
    val feelsLikeC: Double,
    val humidityPercent: Int,
    val windKmh: Double,
    val conditionText: String,
    val providerName: String = "MET Norway / Open-Meteo Live HTTPS"
) {
    fun toSpokenSummary(lang: LanguageMode): String {
        val temp = temperatureC.roundToInt()
        val feels = feelsLikeC.roundToInt()
        val wind = windKmh.roundToInt()
        return when (lang) {
            LanguageMode.HINDI ->
                "$locationLabel में अभी तापमान $temp डिग्री सेल्सियस है, मौसम $conditionText है, नमी $humidityPercent प्रतिशत और हवा की गति $wind किलोमीटर प्रति घंटा है।"
            LanguageMode.HINGLISH ->
                "$locationLabel mein abhi temperature $temp degree Celsius hai (feels like $feels degree), mausam $conditionText hai, aur humidity $humidityPercent percent hai."
            LanguageMode.ENGLISH ->
                "Right now in $locationLabel, it is $temp degrees Celsius with $conditionText, feels like $feels degrees, humidity $humidityPercent percent, and wind $wind kilometers per hour."
        }
    }

    fun toCardDetails(): String = buildString {
        appendLine("Location: $locationLabel")
        appendLine("Temperature: ${String.format(Locale.US, "%.1f", temperatureC)} °C  (Feels like ${String.format(Locale.US, "%.1f", feelsLikeC)} °C)")
        appendLine("Condition: $conditionText")
        appendLine("Humidity: $humidityPercent%")
        appendLine("Wind Speed: ${String.format(Locale.US, "%.1f", windKmh)} km/h")
        append("Source: $providerName")
    }
}

sealed class WeatherResult {
    data class Success(val report: LiveWeatherReport) : WeatherResult()
    data class Failure(val reason: String) : WeatherResult()
}

/**
 * Real live meteorological weather service over HTTPS (Part J).
 * Primary: Official Norwegian Meteorological Institute Global API (api.met.no) + OpenStreetMap Nominatim.
 * Secondary Fallback: Open-Meteo Global Forecast API (api.open-meteo.com).
 * Never displays invented, cached, or placeholder weather when offline.
 */
class WeatherService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun fetchByCityName(cityQuery: String): WeatherResult = withContext(Dispatchers.IO) {
        val cleanCity = cityQuery.trim().ifBlank { "Bagaha" }
        val known = KNOWN_CITY_COORDS[cleanCity.lowercase(Locale.US)]
        if (known != null) {
            return@withContext fetchByCoordinates(known.first, known.second, known.third)
        }

        val geocoded = geocodeCityHttps(cleanCity)
            ?: return@withContext WeatherResult.Failure(
                "Could not resolve location '$cleanCity'—check city spelling or internet connection."
            )

        fetchByCoordinates(geocoded.first, geocoded.second, geocoded.third)
    }

    suspend fun fetchByCoordinates(
        latitude: Double,
        longitude: Double,
        locationLabel: String
    ): WeatherResult = withContext(Dispatchers.IO) {
        // 1. Primary: Official MET Norway Global Locationforecast 2.0 (HTTPS)
        fetchFromMetNorway(latitude, longitude, locationLabel)?.let {
            return@withContext WeatherResult.Success(it)
        }

        // 2. Secondary: Open-Meteo Global Forecast (HTTPS)
        fetchFromOpenMeteo(latitude, longitude, locationLabel)?.let {
            return@withContext WeatherResult.Success(it)
        }

        WeatherResult.Failure("Unable to retrieve live weather right now. Check your internet connection.")
    }

    private fun geocodeCityHttps(city: String): Triple<Double, Double, String>? {
        val encoded = URLEncoder.encode(city, "UTF-8")

        // 1. OpenStreetMap Nominatim HTTPS Geocoder
        try {
            val nomUrl = "https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=1&accept-language=en"
            val req = Request.Builder()
                .url(nomUrl)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val arr = JSONArray(resp.body?.string().orEmpty())
                    if (arr.length() > 0) {
                        val obj = arr.getJSONObject(0)
                        val lat = obj.getString("lat").toDouble()
                        val lon = obj.getString("lon").toDouble()
                        val display = obj.optString("display_name", city)
                        val shortLabel = display.split(",").take(2).joinToString(",") { it.trim() }.ifBlank { city }
                        return Triple(lat, lon, shortLabel)
                    }
                }
            }
        } catch (_: Exception) {
        }

        // 2. Fallback: Open-Meteo Geocoding HTTPS
        try {
            val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=en&format=json"
            val req = Request.Builder().url(geoUrl).header("User-Agent", USER_AGENT).get().build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val results = JSONObject(resp.body?.string().orEmpty()).optJSONArray("results")
                    if (results != null && results.length() > 0) {
                        val item = results.getJSONObject(0)
                        val name = item.optString("name", city)
                        val admin1 = item.optString("admin1", "")
                        val label = if (admin1.isNotBlank()) "$name, $admin1" else name
                        return Triple(item.getDouble("latitude"), item.getDouble("longitude"), label)
                    }
                }
            }
        } catch (_: Exception) {
        }

        return null
    }

    private fun fetchFromMetNorway(lat: Double, lon: Double, label: String): LiveWeatherReport? {
        val latStr = String.format(Locale.US, "%.4f", lat)
        val lonStr = String.format(Locale.US, "%.4f", lon)
        val url = "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=$latStr&lon=$lonStr"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val root = JSONObject(resp.body?.string().orEmpty())
                val timeseries = root.optJSONObject("properties")?.optJSONArray("timeseries") ?: return null
                if (timeseries.length() == 0) return null
                val dataObj = timeseries.getJSONObject(0).getJSONObject("data")
                val details = dataObj.getJSONObject("instant").getJSONObject("details")

                val tempC = details.getDouble("air_temperature")
                val humidity = details.optDouble("relative_humidity", 0.0).roundToInt()
                val windMs = details.optDouble("wind_speed", 0.0)
                val windKmh = windMs * 3.6
                val symbol = dataObj.optJSONObject("next_1_hours")
                    ?.optJSONObject("summary")
                    ?.optString("symbol_code", "")
                    .orEmpty()

                LiveWeatherReport(
                    locationLabel = label,
                    temperatureC = tempC,
                    feelsLikeC = tempC,
                    humidityPercent = humidity,
                    windKmh = windKmh,
                    conditionText = describeMetSymbol(symbol),
                    providerName = "MET Norway Global Forecast API (HTTPS)"
                )
            }
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchFromOpenMeteo(lat: Double, lon: Double, label: String): LiveWeatherReport? {
        val url =
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m" +
                "&timezone=auto"
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val current = JSONObject(resp.body?.string().orEmpty()).optJSONObject("current") ?: return null
                val temp = current.getDouble("temperature_2m")
                val feels = current.optDouble("apparent_temperature", temp)
                val humidity = current.optInt("relative_humidity_2m", 0)
                val wind = current.optDouble("wind_speed_10m", 0.0)
                val wmoCode = current.optInt("weather_code", 0)

                LiveWeatherReport(
                    locationLabel = label,
                    temperatureC = temp,
                    feelsLikeC = feels,
                    humidityPercent = humidity,
                    windKmh = wind,
                    conditionText = describeWmoCode(wmoCode),
                    providerName = "Open-Meteo Live Meteorological API (HTTPS)"
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val USER_AGENT = "NEX6RobotPet/6.0 (https://montfortbagaha.org; school-exhibition)"

        private val KNOWN_CITY_COORDS: Map<String, Triple<Double, Double, String>> = mapOf(
            "bagaha" to Triple(27.1003, 84.0886, "Bagaha, Bihar"),
            "bettiah" to Triple(26.8028, 84.5028, "Bettiah, Bihar"),
            "motihari" to Triple(26.6471, 84.9182, "Motihari, Bihar"),
            "patna" to Triple(25.5941, 85.1376, "Patna, Bihar"),
            "ranchi" to Triple(23.3441, 85.3096, "Ranchi, Jharkhand"),
            "gorakhpur" to Triple(26.7606, 83.3732, "Gorakhpur, UP"),
            "new delhi" to Triple(28.6139, 77.2090, "New Delhi"),
            "delhi" to Triple(28.6139, 77.2090, "New Delhi"),
            "mumbai" to Triple(19.0760, 72.8777, "Mumbai, Maharashtra"),
            "kolkata" to Triple(22.5726, 88.3639, "Kolkata, West Bengal"),
            "bengaluru" to Triple(12.9716, 77.5946, "Bengaluru, Karnataka")
        )

        fun describeMetSymbol(symbolCode: String): String {
            val s = symbolCode.lowercase(Locale.US)
            return when {
                s.startsWith("clearsky") -> "Clear sky"
                s.startsWith("fair") -> "Mostly clear"
                s.startsWith("partlycloudy") -> "Partly cloudy"
                s.startsWith("cloudy") -> "Cloudy"
                s.contains("thunder") -> "Thunderstorm"
                s.contains("heavyrain") -> "Heavy rain"
                s.contains("lightrain") || s.contains("drizzle") -> "Light rain"
                s.contains("rain") -> "Rain"
                s.contains("snow") -> "Snowfall"
                s.contains("fog") -> "Foggy"
                else -> "Clear / Mild"
            }
        }

        fun describeWmoCode(code: Int): String = when (code) {
            0 -> "Clear sky"
            1 -> "Mainly clear"
            2 -> "Partly cloudy"
            3 -> "Overcast"
            45, 48 -> "Foggy"
            51, 53, 55 -> "Light drizzle"
            61, 63, 65 -> "Rain"
            71, 73, 75 -> "Snowfall"
            80, 81, 82 -> "Rain showers"
            95, 96, 99 -> "Thunderstorm"
            else -> "Cloudy"
        }
    }
}
