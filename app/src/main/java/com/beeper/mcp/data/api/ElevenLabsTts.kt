package com.beeper.mcp.data.api

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream

/**
 * Minimal ElevenLabs TTS helper using OkHttp.
 *
 * Usage:
 *   val file = ElevenLabsTts.textToSpeech(context, apiKey, voiceId, text)
 *   ElevenLabsTts.playFromFile(context, file)
 */
object ElevenLabsTts {
    private val client = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .build()

    suspend fun textToSpeech(
        context: Context,
        apiKey: String,
        voiceId: String,
        text: String,
        modelId: String = "eleven_multilingual_v2",
        outputFormat: String = "mp3_44100_128"
    ): File = withContext(Dispatchers.IO) {
        val url = "https://api.elevenlabs.io/v1/text-to-speech/$voiceId"

        val json = buildJsonPayload(text, modelId, outputFormat)

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .addHeader("xi-api-key", apiKey)
            .addHeader("Accept", "audio/mpeg")
            .post(body)
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw Exception("ElevenLabs TTS failed: ${resp.code} ${resp.message}")
            }

            val bytes = resp.body?.bytes() ?: throw Exception("Empty response body from ElevenLabs")

            val outFile = File.createTempFile("eleven_tts_", ".mp3", context.cacheDir)
            FileOutputStream(outFile).use { it.write(bytes) }
            outFile
        }
    }

    private fun buildJsonPayload(text: String, modelId: String, outputFormat: String): String {
        // ElevenLabs expects something like {"text":"...","model_id":"...","output_format":"..."}
        return "{\"text\":${jsonEscape(text)},\"model_id\":\"$modelId\",\"output_format\":\"$outputFormat\"}"
    }

    private fun jsonEscape(s: String): String {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    }

    suspend fun playFromFile(context: Context, file: File) = withContext(Dispatchers.Main) {
        val mp = MediaPlayer()
        mp.setDataSource(file.absolutePath)
        mp.prepare()
        mp.setOnCompletionListener { it.release() }
        mp.start()
    }
}
