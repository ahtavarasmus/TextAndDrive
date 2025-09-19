package com.beeper.mcp.data.api

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.io.File

/**
 * Minimal Tinfoil STT helper using OkHttp.
 * Usage:
 *   val text = TinfoilStt.speechToText(context, apiKey, audioFile)
 */
object TinfoilStt {
    private val client = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .build()

    suspend fun speechToText(
        context: Context,
        apiKey: String,
        audioFile: File,
        model: String = "whisper-large-v3-turbo",
        language: String? = null,
        prompt: String? = null
    ): String = withContext(Dispatchers.IO) {
        val url = "https://inference.tinfoil.sh/v1/audio/transcriptions"
        val mediaType = "audio/mpeg".toMediaType()
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody(mediaType))
            .addFormDataPart("model", model)
            .apply {
                language?.let { addFormDataPart("language", it) }
                prompt?.let { addFormDataPart("prompt", it) }
            }
            .build()

        val request = Request.Builder()
            .url(url)
            // DO NOT log the actual API key. We add it to the request but will redact it in logs.
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        // Detailed logging: request metadata (redacting sensitive headers) and response body on failure
        try {
            client.newCall(request).execute().use { resp ->
                val responseBodyString = resp.body?.string()

                if (!resp.isSuccessful) {
                    // Try to extract helpful error details from JSON response if present
                    var errorDetails = responseBodyString ?: "(empty response body)"
                    try {
                        responseBodyString?.let {
                            val json = JSONObject(it)
                            // Common keys for errors: "error", "detail", "message"
                            val extracted = when {
                                json.has("error") -> json.optString("error")
                                json.has("detail") -> json.optString("detail")
                                json.has("message") -> json.optString("message")
                                else -> null
                            }
                            if (!extracted.isNullOrEmpty()) {
                                errorDetails = extracted
                            }
                        }
                    } catch (je: Exception) {
                        // ignore JSON parse errors; keep raw body
                    }

                    Log.e("TinfoilStt", "Request to $url failed: ${resp.code} ${resp.message}. Error: $errorDetails")
                    Log.d("TinfoilStt", "Request info: url=${request.url}, method=${request.method}, fileName=${audioFile.name}, fileSize=${audioFile.length()} bytes")

                    throw Exception("Tinfoil STT failed: ${resp.code} ${resp.message}. Response body: $errorDetails")
                }

                // Success path: return parsed text
                val body = responseBodyString ?: throw Exception("Empty response body from Tinfoil STT")
                try {
                    val json = JSONObject(body)
                    json.optString("text", "")
                } catch (je: Exception) {
                    // If response isn't JSON, return raw body but log warning
                    Log.w("TinfoilStt", "Could not parse response JSON, returning raw body. Body=${body}")
                    body
                }
            }
        } catch (e: Exception) {
            Log.e("TinfoilStt", "Network call to Tinfoil failed: ${e.message}", e)
            throw e
        }
    }
}