package com.nex.robotpet.v6.network

import android.util.Base64
import com.nex.robotpet.v6.data.NexExpression
import com.nex.robotpet.v6.memory.MemoryManager
import com.nex.robotpet.v6.memory.StoredTurn
import com.nex.robotpet.v6.util.LanguageHelper
import com.nex.robotpet.v6.util.LanguageMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.TimeZone
import java.util.concurrent.TimeUnit

sealed class BackendResult<out T> {
    data class Success<T>(val data: T) : BackendResult<T>()
    data class Error(val httpCode: Int?, val code: String, val safeMessage: String) : BackendResult<Nothing>()
}

data class HealthStatus(
    val ok: Boolean,
    val geminiConfigured: Boolean,
    val configuredModel: String
)

data class ChatReplyData(
    val reply: String,
    val expression: NexExpression,
    val language: LanguageMode,
    val rememberedName: String?,
    val modelUsed: String,
    val source: String = "gemini"
)

data class SearchReplyData(
    val reply: String,
    val expression: NexExpression,
    val language: LanguageMode,
    val sources: List<String>,
    val modelUsed: String
)

data class VisionReplyData(
    val reply: String,
    val expression: NexExpression,
    val objectsDetected: List<String>,
    val language: LanguageMode,
    val modelUsed: String
)

/**
 * Secure HTTPS-only client for the NEX 6.0 FastAPI + Google GenAI backend
 * (`https://nex-ai-robot-pet-6-backend.onrender.com`).
 *
 * - Zero API keys are stored or transmitted from the phone.
 * - Tuned for Render.com Free-Tier cold starts (~35–50s container wake + ~2–6s Gemini inference).
 * - Strictly validates JSON response fields and never fabricates AI replies on failure.
 */
class NexBackendClient {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(65, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun checkHealth(baseUrlRaw: String): BackendResult<HealthStatus> = withContext(Dispatchers.IO) {
        val baseUrl = MemoryManager.validateHttpsBackendUrl(baseUrlRaw)
            ?: return@withContext BackendResult.Error(
                null,
                "BACKEND_NOT_CONFIGURED",
                "HTTPS Backend URL is not configured yet. Press and hold the NEX app icon on your Home Screen and tap Backend Setup."
            )

        var lastError: BackendResult.Error? = null
        for (attempt in 1..2) {
            val request = Request.Builder()
                .url("$baseUrl/health")
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            try {
                val outcome = httpClient.newCall(request).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        parseErrorResponse(resp, raw)
                    } else {
                        val json = JSONObject(raw)
                        val status = json.optString("status")
                        val configured = json.optBoolean("gemini_configured", false)
                        val model = json.optString("configured_model", "unknown")
                        BackendResult.Success(
                            HealthStatus(
                                ok = (status == "ok"),
                                geminiConfigured = configured,
                                configuredModel = model
                            )
                        )
                    }
                }
                if (outcome is BackendResult.Success) return@withContext outcome
                val err = outcome as BackendResult.Error
                lastError = err
                if (attempt < 2 && err.httpCode in listOf(502, 503, 504)) {
                    delay(1000L)
                    continue
                }
                return@withContext err
            } catch (e: SocketTimeoutException) {
                lastError = BackendResult.Error(
                    null,
                    "BACKEND_TIMEOUT",
                    "Backend timed out while waking up on Render. Please try again in a few seconds."
                )
                if (attempt < 2) {
                    delay(1000L)
                    continue
                }
            } catch (e: IOException) {
                lastError = BackendResult.Error(
                    null,
                    "NETWORK_UNREACHABLE",
                    "Could not reach NEX backend server (${e.javaClass.simpleName}). Check your internet connection."
                )
                if (attempt < 2) {
                    delay(900L)
                    continue
                }
            } catch (_: Exception) {
                return@withContext BackendResult.Error(
                    null,
                    "INVALID_HEALTH_PAYLOAD",
                    "Backend returned an unexpected health payload."
                )
            }
        }
        lastError ?: BackendResult.Error(null, "NETWORK_UNREACHABLE", "Could not reach backend server.")
    }

    suspend fun sendChat(
        baseUrlRaw: String,
        message: String,
        languageHint: LanguageMode,
        userName: String?,
        allowMemory: Boolean,
        history: List<StoredTurn>,
        userPreferences: String? = null
    ): BackendResult<ChatReplyData> = withContext(Dispatchers.IO) {
        val baseUrl = MemoryManager.validateHttpsBackendUrl(baseUrlRaw)
            ?: return@withContext BackendResult.Error(
                null,
                "BACKEND_NOT_CONFIGURED",
                "Backend HTTPS URL is not set yet. Press and hold the NEX app icon on your Home Screen and tap Backend Setup."
            )

        val historyArr = JSONArray()
        if (allowMemory && !userPreferences.isNullOrBlank()) {
            historyArr.put(
                JSONObject()
                    .put("role", "user")
                    .put("content", "User preferences to remember: $userPreferences")
            )
        }
        for (turn in history.takeLast(9)) {
            historyArr.put(JSONObject().put("role", turn.role).put("content", turn.content))
        }

        val payload = JSONObject()
            .put("message", message)
            .put("language_hint", languageHint.apiCode)
            .put("allow_memory", allowMemory)
            .put("timezone", TimeZone.getDefault().id)
            .put("history", historyArr)
        if (!userName.isNullOrBlank() && allowMemory) {
            payload.put("user_name", userName)
        }

        executeWithBoundedRetry("$baseUrl/chat", payload.toString()) { json ->
            val rawReply = json.optString("reply", "").trim()
            if (rawReply.isEmpty()) {
                throw EmptyBackendReplyException()
            }
            val cleanReply = LanguageHelper.sanitizeIdentityOutput(
                rawReply,
                creatorAsked = LanguageHelper.isCreatorQuestion(message)
            )
            val expr = NexExpression.fromBackendTag(json.optString("expression", "HAPPY"))
            val outLang = parseLanguageMode(json.optString("language", languageHint.apiCode), languageHint)
            val remName = if (json.isNull("remembered_name")) {
                null
            } else {
                json.optString("remembered_name").takeIf { it.isNotBlank() }
            }
            ChatReplyData(
                reply = cleanReply,
                expression = expr,
                language = outLang,
                rememberedName = remName,
                modelUsed = json.optString("model_used", ""),
                source = json.optString("source", "gemini")
            )
        }
    }

    suspend fun sendSearch(
        baseUrlRaw: String,
        query: String,
        languageHint: LanguageMode,
        userName: String? = null,
        allowMemory: Boolean = true
    ): BackendResult<SearchReplyData> = withContext(Dispatchers.IO) {
        val baseUrl = MemoryManager.validateHttpsBackendUrl(baseUrlRaw)
            ?: return@withContext BackendResult.Error(
                null,
                "BACKEND_NOT_CONFIGURED",
                "Backend HTTPS URL is not set yet. Press and hold the NEX app icon on your Home Screen and tap Backend Setup."
            )

        val payload = JSONObject()
            .put("query", query)
            .put("language_hint", languageHint.apiCode)
            .put("allow_memory", allowMemory)
        if (!userName.isNullOrBlank() && allowMemory) {
            payload.put("user_name", userName)
        }

        executeWithBoundedRetry("$baseUrl/search", payload.toString()) { json ->
            val rawReply = json.optString("reply", "").trim()
            if (rawReply.isEmpty()) {
                throw EmptyBackendReplyException()
            }
            val cleanReply = LanguageHelper.sanitizeIdentityOutput(
                rawReply,
                creatorAsked = LanguageHelper.isCreatorQuestion(query)
            )
            val expr = NexExpression.fromBackendTag(json.optString("expression", "CURIOUS"))
            val outLang = parseLanguageMode(json.optString("language", languageHint.apiCode), languageHint)
            val sources = mutableListOf<String>()
            val arr = json.optJSONArray("sources")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i).trim()
                    if (s.isNotEmpty()) sources.add(s)
                }
            }
            SearchReplyData(
                reply = cleanReply,
                expression = expr,
                language = outLang,
                sources = sources,
                modelUsed = json.optString("model_used", "")
            )
        }
    }

    suspend fun sendVision(
        baseUrlRaw: String,
        prompt: String,
        jpegBytes: ByteArray,
        languageHint: LanguageMode,
        mimeType: String = "image/jpeg"
    ): BackendResult<VisionReplyData> = withContext(Dispatchers.IO) {
        val baseUrl = MemoryManager.validateHttpsBackendUrl(baseUrlRaw)
            ?: return@withContext BackendResult.Error(
                null,
                "BACKEND_NOT_CONFIGURED",
                "Online Gemini Vision requires a configured HTTPS Backend URL."
            )

        val b64 = encodeBase64NoWrap(jpegBytes)
        val payload = JSONObject()
            .put("prompt", prompt.ifBlank { "What is this?" })
            .put("image_base64", b64)
            .put("mime_type", mimeType)
            .put("language_hint", languageHint.apiCode)

        executeWithBoundedRetry("$baseUrl/vision", payload.toString()) { json ->
            val rawReply = json.optString("reply", "").trim()
            if (rawReply.isEmpty()) {
                throw EmptyBackendReplyException()
            }
            val cleanReply = LanguageHelper.sanitizeIdentityOutput(
                rawReply,
                creatorAsked = LanguageHelper.isCreatorQuestion(prompt)
            )
            val expr = NexExpression.fromBackendTag(json.optString("expression", "CURIOUS"))
            val objs = mutableListOf<String>()
            val arr = json.optJSONArray("objects_detected")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optString(i).trim()
                    if (item.isNotEmpty()) objs.add(item)
                }
            }
            val outLang = parseLanguageMode(json.optString("language", languageHint.apiCode), languageHint)
            VisionReplyData(
                reply = cleanReply,
                expression = expr,
                objectsDetected = objs,
                language = outLang,
                modelUsed = json.optString("model_used", "")
            )
        }
    }

    private suspend fun <T> executeWithBoundedRetry(
        url: String,
        jsonBody: String,
        parser: (JSONObject) -> T
    ): BackendResult<T> {
        val maxAttempts = 2
        var lastError: BackendResult.Error? = null

        for (attempt in 1..maxAttempts) {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .post(jsonBody.toRequestBody(jsonMediaType))
                .build()

            try {
                val outcome = httpClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        BackendResult.Success(parser(JSONObject(raw)))
                    } else {
                        parseErrorResponse(resp, raw)
                    }
                }
                if (outcome is BackendResult.Success) return outcome
                val err = outcome as BackendResult.Error
                lastError = err
                val retryable = err.httpCode in listOf(502, 503, 504) &&
                    err.code !in setOf("GEMINI_KEY_MISSING", "RENDER_SERVICE_NOT_FOUND")
                if (attempt < maxAttempts && retryable) {
                    delay(900L * attempt)
                    continue
                }
                return err
            } catch (_: EmptyBackendReplyException) {
                return BackendResult.Error(
                    200,
                    "EMPTY_BACKEND_REPLY",
                    "NEX backend returned an empty AI response. Please try asking again."
                )
            } catch (_: SocketTimeoutException) {
                lastError = BackendResult.Error(
                    null,
                    "BACKEND_TIMEOUT",
                    "NEX backend took too long to respond (Render free tier may still be waking up). Please try once more."
                )
                if (attempt < maxAttempts) {
                    delay(1000L * attempt)
                    continue
                }
            } catch (_: IOException) {
                lastError = BackendResult.Error(
                    null,
                    "NETWORK_ERROR",
                    "Could not connect to NEX backend. Please check internet connectivity and try again."
                )
                if (attempt < maxAttempts) {
                    delay(900L * attempt)
                    continue
                }
            } catch (_: Exception) {
                return BackendResult.Error(
                    null,
                    "PARSE_ERROR",
                    "Received an invalid response format from the backend server."
                )
            }
        }
        return lastError ?: BackendResult.Error(null, "UNKNOWN_ERROR", "Request failed.")
    }

    private fun parseErrorResponse(resp: Response, rawBody: String): BackendResult.Error {
        val httpCode = resp.code
        val renderRouting = resp.header("x-render-routing").orEmpty()
        if (httpCode == 404 && renderRouting.equals("no-server", ignoreCase = true)) {
            return BackendResult.Error(
                404,
                "RENDER_SERVICE_NOT_FOUND",
                "Render reported no service at this URL. Verify Backend Setup uses https://nex-ai-robot-pet-6-backend.onrender.com."
            )
        }

        return try {
            val root = JSONObject(rawBody)
            val detail = root.optJSONObject("detail")
            val code = detail?.optString("code")?.takeIf { it.isNotBlank() } ?: "HTTP_$httpCode"
            val msg = detail?.optString("message")?.takeIf { it.isNotBlank() }
                ?: defaultHttpStatusMessage(httpCode)
            BackendResult.Error(httpCode, code, msg)
        } catch (_: Exception) {
            BackendResult.Error(httpCode, "HTTP_$httpCode", defaultHttpStatusMessage(httpCode))
        }
    }

    private fun defaultHttpStatusMessage(httpCode: Int): String = when (httpCode) {
        401 -> "Gemini API key on the server was rejected (401). Check GEMINI_API_KEY in Render Environment Variables."
        403 -> "Google Gemini denied access (403). Check GEMINI_API_KEY permissions in Google AI Studio."
        404 -> "Backend endpoint not found (404). Ensure Backend URL is https://nex-ai-robot-pet-6-backend.onrender.com."
        413 -> "Request payload was too large for the backend server (413)."
        429 -> "Gemini API rate limit reached (429). Please wait a few seconds and try again."
        502, 503 -> "NEX cloud backend is waking up or Gemini is temporarily busy ($httpCode). Please try again in a moment."
        504 -> "Gemini request timed out on the server (504). Please try asking again."
        else -> "Backend returned HTTP $httpCode."
    }

    private fun parseLanguageMode(langStr: String?, fallback: LanguageMode): LanguageMode {
        return when (langStr?.trim()?.lowercase()) {
            "hi" -> LanguageMode.HINDI
            "hinglish" -> LanguageMode.HINGLISH
            "en" -> LanguageMode.ENGLISH
            else -> fallback
        }
    }

    private fun encodeBase64NoWrap(bytes: ByteArray): String {
        val androidEncoded = try {
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Throwable) {
            null
        }
        if (!androidEncoded.isNullOrEmpty()) return androidEncoded
        return encodeBase64PureKotlin(bytes)
    }

    private class EmptyBackendReplyException : Exception()

    companion object {
        private const val USER_AGENT = "NEX-6.0-Android/6.0.0 (com.nex.robotpet.v6)"
        private const val B64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

        internal fun encodeBase64PureKotlin(data: ByteArray): String {
            if (data.isEmpty()) return ""
            val sb = StringBuilder(((data.size + 2) / 3) * 4)
            var i = 0
            while (i < data.size) {
                val b0 = data[i].toInt() and 0xFF
                val rem = data.size - i
                val b1 = if (rem > 1) data[i + 1].toInt() and 0xFF else 0
                val b2 = if (rem > 2) data[i + 2].toInt() and 0xFF else 0
                sb.append(B64_ALPHABET[b0 ushr 2])
                sb.append(B64_ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
                sb.append(if (rem > 1) B64_ALPHABET[((b1 and 0x0F) shl 2) or (b2 ushr 6)] else '=')
                sb.append(if (rem > 2) B64_ALPHABET[b2 and 0x3F] else '=')
                i += 3
            }
            return sb.toString()
        }
    }
}
