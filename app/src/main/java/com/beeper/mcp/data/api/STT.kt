package com.beeper.mcp.data.api

import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.IOException
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.FileInputStream
import java.io.OutputStreamWriter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody


/**
 * Minimal Tinfoil STT helper using built-in HttpURLConnection.
 * Usage:
 * val text = TinfoilStt.speechToText(apiKey, audioFile)
 */
object STT {


suspend fun speechToText(
    apiKey: String,
    audioFile: File,
    model: String = "whisper-large-v3-turbo",
    language: String? = "en"
): String = withContext(Dispatchers.IO) {
    val client = OkHttpClient()
    val requestBody = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/m4a".toMediaType()))
        .addFormDataPart("model", model)
        .apply { if (language != null) addFormDataPart("language", language) }
        .build()

    val request = Request.Builder()
        .url("https://inference.tinfoil.sh/v1/audio/transcriptions")
        .header("Authorization", "Bearer $apiKey")
        .post(requestBody)
        .build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw Exception("Tinfoil STT failed: ${response.code} - ${response.body?.string() ?: ""}")
        }
        val body = response.body?.string() ?: ""
        try {
            JSONObject(body).optString("text", "")
        } catch (e: Exception) {
            body
        }
    }
}
    

}
