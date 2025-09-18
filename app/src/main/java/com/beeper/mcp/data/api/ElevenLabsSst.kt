package com.beeper.mcp.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File

object ElevenLabsSst {
    private val client = OkHttpClient.Builder().build()

    /**
     * Uploads an audio file to ElevenLabs STT endpoint and returns the transcript string.
     * This uses the `v1/speech-to-text` endpoint with multipart/form-data.
     */
    suspend fun speechToTextFile(apiKey: String, audioFile: File, model: String = "eleven_speech_recognition_v1"): String =
        withContext(Dispatchers.IO) {
            val url = "https://api.elevenlabs.io/v1/speech-to-text"

            val mediaType = "audio/mpeg".toMediaTypeOrNull() // adapt if you have wav/aac
            val fileBody = audioFile.asRequestBody(mediaType)

            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", audioFile.name, fileBody)
                .addFormDataPart("model", model)
                .build()

            val request = Request.Builder()
                .url(url)
                .addHeader("xi-api-key", apiKey)
                .post(multipart)
                .build()

            client.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: throw Exception("Empty response from ElevenLabs SST")
                if (!resp.isSuccessful) throw Exception("ElevenLabs SST failed: ${resp.code} - $bodyStr")

                // Try to extract the text field from returned JSON; ElevenLabs may vary fields
                try {
                    val json = JSONObject(bodyStr)
                    // Common key names: 'text' or 'transcript' or nested structure
                    when {
                        json.has("text") -> json.getString("text")
                        json.has("transcript") -> json.getString("transcript")
                        json.has("data") && json.get("data") is JSONObject && (json.getJSONObject("data").has("text")) -> json.getJSONObject("data").getString("text")
                        else -> bodyStr
                    }
                } catch (e: Exception) {
                    // If JSON parsing fails, return raw body
                    bodyStr
                }
            }
        }

    /**
     * Upload audio bytes with filename to ElevenLabs SST and return the raw JSON response string.
     */
    suspend fun speechToTextBytes(apiKey: String, bytes: ByteArray, filename: String = "audio.mp3", model: String = "eleven_speech_recognition_v1"): String =
        withContext(Dispatchers.IO) {
            val url = "https://api.elevenlabs.io/v1/speech-to-text"

            val mediaType = "audio/mpeg".toMediaTypeOrNull()
            val fileBody = bytes.toRequestBody(mediaType)

            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", filename, fileBody)
                .addFormDataPart("model", model)
                .build()

            val request = Request.Builder()
                .url(url)
                .addHeader("xi-api-key", apiKey)
                .post(multipart)
                .build()

            client.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: throw Exception("Empty response from ElevenLabs SST")
                if (!resp.isSuccessful) throw Exception("ElevenLabs SST failed: ${resp.code} - $bodyStr")
                bodyStr
            }
        }
}
