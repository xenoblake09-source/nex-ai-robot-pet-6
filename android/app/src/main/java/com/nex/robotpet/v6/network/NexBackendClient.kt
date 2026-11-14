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
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
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
    val modelUsed: String
)

data class VisionReplyData(
    val reply: String,
    val expression: NexExpression,
    val objectsDetected: List<String>,
    val modelUsed: String
)

/**
 * Secure HTTPS-only client for the NEX 6.0 FastAPI + Google GenAI backend.
 * Zero API keys are stored or transmitted from the phone.
 */
class NexBackendClient {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(28, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun checkHealth(baseUrlRaw: String): BackendResult<HealthStatus> = withContext(Dispatchers.IO) {
        val baseUrl = MemoryManager.validateHttpsBackendUrl(baseUrlRaw)
            ?: return@withContext BackendResult.Error(
                null,
                "BACKEND_NOT_CONFIGURED",
                "HTTPS Backend URL is not configured yet. Press and hold the NEX app icon on your Home Screen and tap Backend Setup."
            )

        val request = Request.Builder()
            .url("$baseUrl/health")
            .get()
            .build()

        try {
            httpClient.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext parseErrorResponse(resp.code, raw)
                }
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
        } catch (e: IOException) {
            BackendResult.Error(null, "NETWORK_UNREACHABLE", "Could not reach backend server (${e.javaClass.simpleName}).")
        } catch (_: Exception) {
            BackendResult.Error(null, "INVALID_HEALTH_PAYLOAD", "Backend returned an unexpected health payload.")
        }
    }

    suspend fun sendChat(
        baseUrlRaw: String,
        message: String,
        languageHint: LanguageMode,
        userName: String?,
        allowMemory: Boolean,
        history: List<StoredTurn>
    ): BackendResult<ChatReplyData> = withContext(Dispatchers.IO) {
        val baseUrl = MemoryManager.validateHttpsBackendUrl(baseUrlRaw)
            ?: return@withContext BackendResult.Error(
                null,
                "BACKEND_NOT_CONFIGURED",
                "Backend HTTPS URL is not set yet. Press and hold the NEX app icon on your Home Screen and tap Backend Setup."
            )

        val historyArr = JSONArray()
        for (turn in history.takeLast(10)) {
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
            val rawReply = json.optString("reply", "")
            val cleanReply = LanguageHelper.sanitizeIdentityOutput(
                rawReply,
                creatorAsked = LanguageHelper.isCreatorQuestion(message)
            )
            val expr = NexExpression.fromBackendTag(json.optString("expression", "HAPPY"))
            val langStr = json.optString("language", languageHint.apiCode)
            val outLang = when (langStr) {
                "hi" -> LanguageMode.HINDI
                "hinglish" -> LanguageMode.HINGLISH
                else -> LanguageMode.ENGLISH
            }
            val remName = if (json.isNull("remembered_name")) null else json.optString("remembered_name").takeIf { it.isNotBlank() }
            ChatReplyData(
                reply = cleanReply,
                expression = expr,
                language = outLang,
                rememberedName = remName,
                modelUsed = json.optString("model_used", "")
            )
        }
    }

    suspend fun sendVision(
        baseUrlRaw: String,
        prompt: String,
        jpegBytes: ByteArray,
        languageHint: LanguageMode
    ): BackendResult<VisionReplyData> = withContext(Dispatchers.IO) {
        val baseUrl = MemoryManager.validateHttpsBackendUrl(baseUrlRaw)
            ?: return@withContext BackendResult.Error(
                null,
                "BACKEND_NOT_CONFIGURED",
                "Online Gemini Vision requires a configured HTTPS Backend URL."
            )

        val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val payload = JSONObject()
            .put("prompt", prompt.ifBlank { "What is this?" })
            .put("image_base64", b64)
            .put("mime_type", "image/jpeg")
            .put("language_hint", languageHint.apiCode)

        executeWithBoundedRetry("$baseUrl/vision", payload.toString()) { json ->
            val rawReply = json.optString("reply", "")
            val cleanReply = LanguageHelper.sanitizeIdentityOutput(rawReply)
            val expr = NexExpression.fromBackendTag(json.optString("expression", "CURIOUS"))
            val objs = mutableListOf<String>()
            val arr = json.optJSONArray("objects_detected")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optString(i).trim()
                    if (item.isNotEmpty()) objs.add(item)
                }
            }
            VisionReplyData(
                reply = cleanReply,
                expression = expr,
                objectsDetected = objs,
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
                .post(jsonBody.toRequestBody(jsonMediaType))
                .build()

            try {
                val outcome = httpClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        BackendResult.Success(parser(JSONObject(raw)))
                    } else {
                        parseErrorResponse(resp.code, raw)
                    }
                }
                if (outcome is BackendResult.Success) return outcome
                val err = outcome as BackendResult.Error
                lastError = err
                val retryable = err.httpCode in listOf(502, 503, 504) && err.code != "GEMINI_KEY_MISSING"
                if (attempt < maxAttempts && retryable) {
                    delay(700L * attempt)
                    continue
                }
                return err
            } catch (e: IOException) {
                lastError = BackendResult.Error(
                    null,
                    "NETWORK_ERROR",
                    "Could not connect to NEX backend (Render free tier may be waking up or internet is unstable)."
                )
                if (attempt < maxAttempts) {
                    delay(900L * attempt)
                    continue
                }
            } catch (_: Exception) {
                return BackendResult.Error(null, "PARSE_ERROR", "Received malformed JSON from backend.")
            }
        }
        return lastError ?: BackendResult.Error(null, "UNKNOWN_ERROR", "Request failed.")
    }

    private fun parseErrorResponse(httpCode: Int, rawBody: String): BackendResult.Error {
        return try {
            val root = JSONObject(rawBody)
            val detail = root.optJSONObject("detail")
            val code = detail?.optString("code")?.takeIf { it.isNotBlank() } ?: "HTTP_$httpCode"
            val msg = detail?.optString("message")?.takeIf { it.isNotBlank() }
                ?: "Backend returned HTTP $httpCode."
            BackendResult.Error(httpCode, code, msg)
        } catch (_: Exception) {
            BackendResult.Error(httpCode, "HTTP_$httpCode", "Backend returned HTTP $httpCode.")
        }
    }
}
