package com.beeper.mcp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.beeper.mcp.data.api.ElevenLabsStt
import com.beeper.mcp.data.api.ElevenLabsTts
import com.beeper.mcp.data.api.STT
import com.beeper.mcp.tools.getChatsFormatted
import com.beeper.mcp.tools.sendHardcodedMessageToRasums
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.concurrent.thread

class AudioRecorderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioRecordScreen(
    modifier: Modifier = Modifier,
    isDemo: Boolean = false,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var isRecording by remember { mutableStateOf(false) }
    var recordingJob: Job? by remember { mutableStateOf(null) }
    var outputFile: File? by remember { mutableStateOf(null) }

    // Function to call after recording
    fun processRecordedAudio(filePath: String) {
        val ApiKey = BuildConfig.TINFOIL_API_KEY
        val elevenApiKey = BuildConfig.ELEVENLABS_API_KEY
        if (context is ComponentActivity) {
            context.lifecycleScope.launch {
                try {
                    val transcription = STT.speechToText(ApiKey, File(filePath))
                    Log.d("AudioRecorder", "STT transcription: $transcription")
                    // ... (rest of the code remains the same: call getChatsFormatted, LLM, etc.)
                    var chatsResult = ""
                    try {
                        Log.d("AudioRecorder", "Calling getChatsFormatted for LLM context...")
                        val startTime = System.currentTimeMillis()

                        // Create args map with default parameters
                        val args = mapOf<String, Any?>(
                            "limit" to 35, // Get more chats for better context
                            "offset" to 0
                        )
                        chatsResult = if (isDemo) {
                            "Mock chats:\nChat1: Friend: Hello\nYou: Hi\nChat2: Group: Meeting at 5"
                        } else {
                            context.contentResolver.getChatsFormatted(args)
                        }
                        val duration = System.currentTimeMillis() - startTime
                        Log.d("AudioRecorder", "getChatsFormatted completed in ${duration}ms")
                        Log.d("AudioRecorder", "Chats result length: ${chatsResult.length} characters")
                        Log.d("AudioRecorder", "Chats result preview: ${chatsResult.take(500)}...")
                        if (chatsResult.length < 2000) {
                            Log.d("AudioRecorder", "Full chats result:\n$chatsResult")
                        }
                    } catch (chatsEx: Exception) {
                        Log.e("AudioRecorder", "getChatsFormatted failed: ${chatsEx.message}")
                    }
                    // Create enhanced transcript with chats context
                    val enhancedTranscript = buildString {
                        appendLine("User transcript: $transcription")
                        appendLine()
                        appendLine("Available chats context:")
                        appendLine(chatsResult)  // Include the actual fetched chats
                    }

                    // Send transcript to LLM with tools and chats context
                    try {
                        val tinfoilKey = try { BuildConfig.TINFOIL_API_KEY } catch (_: Exception) { "" }
                        if (tinfoilKey.isNullOrBlank()) {
                            Log.w("AudioRecorder", "TINFOIL_API_KEY is missing or blank")
                        } else {
                            val masked = if (tinfoilKey.length > 8) "${tinfoilKey.substring(0,4)}...${tinfoilKey.takeLast(4)}" else "****"
                            val sha256 = try {
                                MessageDigest.getInstance("SHA-256").digest(tinfoilKey.toByteArray()).joinToString("") { "%02x".format(it) }
                            } catch (e: Exception) { "<hash-error>" }
                            Log.d("AudioRecorder", "TINFOIL_API_KEY present: length=${tinfoilKey.length}, masked=$masked, sha256=$sha256")
                        }

                        // Create enhanced transcript with chats context
                        val enhancedTranscript = buildString {
                            appendLine("User transcript: $transcription")
                            appendLine()
                            appendLine("Available chats context:")
                        }

                        Log.d("AudioRecorder", "Sending enhanced transcript with chats context to LLM")
                        // Record user message into the LLM client's rolling history so the model
                        // can use the previous back-and-forth context (up to 5 exchanges).
                        try {
                            com.beeper.mcp.data.api.LLMClient.addUserMessage(enhancedTranscript)
                        } catch (_: Exception) {
                        }

                        var assistantText = com.beeper.mcp.data.api.LLMClient
                            .sendTranscriptWithTools(context, tinfoilKey, enhancedTranscript, context.contentResolver)

                        // Defensive normalization: sometimes the LLM client returns the
                        // literal string "null" (or a null reference). Normalize to empty
                        // string so downstream code and logs are unambiguous.
                        if (assistantText == null || assistantText == "null") {
                            Log.d("AudioRecorder", "LLM returned null content; normalizing to empty string")
                            assistantText = ""
                        }

                        Log.d("AudioRecorder", "Assistant reply: $assistantText")

                        // Save assistant response back into the LLM client's rolling history
                        try {
                            if (!assistantText.isNullOrBlank()) com.beeper.mcp.data.api.LLMClient.addAssistantMessage(assistantText)
                        } catch (_: Exception) {
                        }

                        // Track whether we've already played a TTS message so we don't duplicate playback
                        var playedTts = false
                        var sendResult = ""

                        // Instead of sending a hardcoded message, inspect the assistant reply.
                        // If it contains a function_call wrapper, execute the named tool using
                        // the project's OpenAI tool handler. Otherwise, fall back to treating
                        // the assistant text as a direct action/result string.
                        try {
                            val trimmedAssistant = assistantText.trim()
                            if (trimmedAssistant.startsWith("{")) {
                                try {
                                    val wrapper = org.json.JSONObject(trimmedAssistant)
                                    val fc = wrapper.optJSONObject("function_call")
                                    if (fc != null) {
                                        val fname = fc.optString("name", "")
                                        val fargs = fc.opt("arguments")
                                        val argsJsonString = when (fargs) {
                                            is org.json.JSONObject -> fargs.toString()
                                            is String -> fargs
                                            else -> "{}"
                                        }

                                        val toolCallMap = mapOf<String, Any>(
                                            "name" to fname,
                                            "arguments" to argsJsonString
                                        )

                                        // Execute the tool call using the ContentResolver helper.
                                        sendResult = if (isDemo) {
                                            "Mock tool call executed: $fname with args $argsJsonString"
                                        } else {
                                            context.contentResolver.handleOpenAIToolCall(toolCallMap)
                                        }
                                        Log.d("AudioRecorder", "Executed tool call: $fname -> result length=${sendResult.length}")
                                    } else {
                                        // Not a function_call JSON; use assistant text directly
                                        sendResult = assistantText
                                        Log.d("AudioRecorder", "Assistant reply contained JSON but no function_call; using plain text result")
                                    }
                                } catch (parseEx: Exception) {
                                    Log.e("AudioRecorder", "Failed to parse assistant JSON reply: ${parseEx.message}")
                                    // As a cautious fallback, attempt the legacy hardcoded send
                                    try {
                                        sendResult = if (isDemo) {
                                            "Mock hardcoded message sent"
                                        } else {
                                            context.contentResolver.sendHardcodedMessageToRasums()
                                        }
                                        Log.d("AudioRecorder", "Fallback hardcoded send result: $sendResult")
                                    } catch (sendEx: Exception) {
                                        Log.e("AudioRecorder", "Fallback hardcoded send failed: ${sendEx.message}")
                                    }
                                }
                            } else {
                                // Assistant returned plain text (no function_call) — treat it as the action/result
                                sendResult = assistantText
                                Log.d("AudioRecorder", "Assistant reply is plain text; using as action result")
                            }
                        } catch (e: Exception) {
                            Log.e("AudioRecorder", "Tool execution flow failed: ${e.message}")
                            // As a last resort, keep the previous hardcoded behavior so the app remains functional
                            try {
                                Log.d("AudioRecorder", "Last-resort hardcoded send result: $sendResult")
                            } catch (sendEx: Exception) {
                                Log.e("AudioRecorder", "Last-resort hardcoded send failed: ${sendEx.message}")
                            }
                        }

                        // After sending the hardcoded message, call the LLM again to produce
                        // a short message that should be sent to ElevenLabs TTS, then play it.
                        try {
                            if (elevenApiKey.isNullOrBlank()) {
                                Log.w("AudioRecorder", "ELEVENLABS_API_KEY missing; skipping LLM->TTS step")
                            } else {
                                val ttsPrompt = buildString {
                                    appendLine("You are a voice assistant confirming actions to the user.")
                                    appendLine("Based on the action result below, create a SHORT spoken confirmation message (1-2 sentences max).")
                                    appendLine("Tell the user exactly what was accomplished in a natural, conversational way.")
                                    appendLine("Examples:")
                                    appendLine("- If a message was sent: 'I sent your message to [person] saying [brief summary]'")
                                    appendLine("- If something failed: 'I wasn't able to send the message because [brief reason]'")
                                    appendLine("- If data was retrieved: 'I found [brief summary of what was found]'")
                                    appendLine("")
                                    appendLine("Action result to confirm:")
                                    appendLine(sendResult)
                                    appendLine("")
                                    appendLine("User's original request was: $transcription")
                                    appendLine("")
                                    appendLine("Return ONLY the spoken confirmation message, nothing else. No JSON, no explanations.")
                                }


                                // Record the TTS prompt as a user message so the LLM has the preceding
                                // context when generating the spoken confirmation.
                                try {
                                    com.beeper.mcp.data.api.LLMClient.addUserMessage(ttsPrompt)
                                } catch (_: Exception) {
                                }

                                var ttsMessage = com.beeper.mcp.data.api.LLMClient
                                    .sendTranscriptWithTools(context, tinfoilKey, ttsPrompt, context.contentResolver)

                                if (ttsMessage == null || ttsMessage == "null") ttsMessage = ""

                                // If the LLM returned a function_call wrapper JSON, try to extract a message
                                val trimmed = ttsMessage.trim()
                                if (trimmed.startsWith("{")) {
                                    try {
                                        val wrapper = org.json.JSONObject(trimmed)
                                        val fc = wrapper.optJSONObject("function_call")
                                        val args = fc?.optJSONObject("arguments")
                                        val candidate = args?.optString("text")
                                            ?: args?.optString("message")
                                            ?: args?.optString("content")
                                            ?: args?.optString("raw")
                                            ?: ""
                                        if (!candidate.isNullOrBlank()) {
                                            ttsMessage = candidate
                                        }

                                        // Save the assistant's TTS-generation reply into history as well
                                        try {
                                            if (!ttsMessage.isNullOrBlank()) com.beeper.mcp.data.api.LLMClient.addAssistantMessage(ttsMessage)
                                        } catch (_: Exception) {
                                        }
                                    } catch (_: Exception) {
                                        // ignore parse errors and fall back to raw text
                                    }
                                }

                                if (ttsMessage.isNotBlank()) {
                                    try {
                                        // Convert the LLM's TTS text into audio and play it.
                                        if (!elevenApiKey.isNullOrBlank()) {
                                            val voiceId = "5kMbtRSEKIkRZSdXxrZg"
                                            val ttsFile = ElevenLabsTts.textToSpeech(context, elevenApiKey, voiceId, ttsMessage)
                                            ElevenLabsTts.playFromFile(context, ttsFile)
                                            playedTts = true
                                            Log.d("AudioRecorder", "Played TTS for message (truncated): ${ttsMessage.take(120)}")
                                        } else {
                                            Log.w("AudioRecorder", "ELEVENLABS_API_KEY missing; cannot play ttsMessage")
                                        }
                                    } catch (ttsEx: Exception) {
                                        Log.e("AudioRecorder", "TTS generation/playback failed: ${ttsEx.message}")
                                    }
                                } else {
                                    Log.d("AudioRecorder", "LLM did not return a TTS message; nothing to play")
                                }
                            }
                        } catch (flowEx: Exception) {
                            Log.e("AudioRecorder", "LLM->TTS flow failed: ${flowEx.message}")
                        }

                        // Fallback: if LLM->TTS didn't play anything, use the assistantText directly
                        try {
                            // Avoid reading raw JSON blobs aloud. Prefer the executed tool result (sendResult)
                            // which should be a human-readable summary of the action. If that's not
                            // available, fall back to assistantText only when it is plain text.
                            if (!playedTts && !elevenApiKey.isNullOrBlank()) {
                                val candidate = when {
                                    !sendResult.isNullOrBlank() && !sendResult.trim().startsWith("{") -> sendResult
                                    !assistantText.isNullOrBlank() && !assistantText.trim().startsWith("{") -> assistantText
                                    else -> null
                                }

                                if (!candidate.isNullOrBlank()) {
                                    val voiceId = "5kMbtRSEKIkRZSdXxrZg"
                                    val ttsFile = ElevenLabsTts.textToSpeech(context, elevenApiKey, voiceId, candidate)
                                    ElevenLabsTts.playFromFile(context, ttsFile)
                                    Log.d("AudioRecorder", "Fallback: played text via TTS (truncated): ${candidate.take(120)}")
                                    playedTts = true
                                } else {
                                    Log.w("AudioRecorder", "No suitable human-readable text to TTS; skipping fallback to avoid vague outputs")
                                }
                            }
                        } catch (fbEx: Exception) {
                            Log.e("AudioRecorder", "Fallback TTS failed: ${fbEx.message}")
                        }

                    } catch (ex: Exception) {
                        Log.e("AudioRecorder", "LLM tool flow failed: ${ex.message}")
                    }
                } catch (e: Exception) {
                    Log.e("AudioRecorder", "STT call failed: ${e.message}")
                }
            }
        }
    }

    // Full-screen content
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (onBack != null) {
                TopAppBar(
                    title = { Text(if (isDemo) "Demo Mode" else "Real Mode") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                // Ensure we still have permission at the moment of starting
                                val hasPermission = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED

                                if (!hasPermission) {
                                    // Since MainActivity owns the permission launcher, just inform the user
                                    Toast.makeText(context, "Microphone permission required", Toast.LENGTH_SHORT).show()
                                } else {
                                    if (!isRecording) {
                                        try {
                                            outputFile = File.createTempFile("audio", ".wav", context.cacheDir)
                                            isRecording = true
                                            if (context is ComponentActivity) {
                                                recordingJob = context.lifecycleScope.launch(Dispatchers.IO) {
                                                    recordAudioToWav(context, outputFile!!) { isRecording }
                                                }
                                            }
                                        } catch (e: IOException) {
                                            Log.e("AudioRecorder", "Failed to start: ${e.message}")
                                            isRecording = false
                                        }
                                    }
                                }
                                tryAwaitRelease()
                                isRecording = false
                                recordingJob?.join() // Wait for recording to finish
                                outputFile?.let { file ->
                                    Log.d("AudioRecorder", "Recorded file size: ${file.length()} bytes")
                                    processRecordedAudio(file.absolutePath)
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isRecording) "Recording..." else "Hold anywhere to record",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

// Function to record raw PCM and save as WAV (runs on IO dispatcher)
suspend fun recordAudioToWav(context: Context, outputFile: File, isRecording: () -> Boolean) {
    val sampleRate = 16000
    val channelConfig = AudioFormat.CHANNEL_IN_MONO
    val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    var audioRecord: AudioRecord? = null
    var minBufferSize = 0
    try {
        minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e("AudioRecorder", "Invalid buffer size returned: $minBufferSize")
            return
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            minBufferSize * 2
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("AudioRecorder", "AudioRecord not initialized (state=${audioRecord.state})")
            audioRecord.release()
            return
        }

        val baos = ByteArrayOutputStream()

        try {
            audioRecord.startRecording()
        } catch (se: SecurityException) {
            Log.e("AudioRecorder", "startRecording denied by SecurityException: ${se.message}")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Microphone permission denied", Toast.LENGTH_SHORT).show()
            }
            return
        }

        while (isRecording()) {
            val buffer = ByteArray(minBufferSize)
            val read = try {
                audioRecord.read(buffer, 0, buffer.size)
            } catch (se: SecurityException) {
                Log.e("AudioRecorder", "read denied by SecurityException: ${se.message}")
                -1
            }
            if (read > 0) {
                baos.write(buffer, 0, read)
            }
        }

        try {
            audioRecord.stop()
        } catch (e: IllegalStateException) {
            Log.w("AudioRecorder", "stop() called in illegal state: ${e.message}")
        } catch (se: SecurityException) {
            Log.e("AudioRecorder", "stop denied by SecurityException: ${se.message}")
        }

        // Get raw PCM data
        val rawData = baos.toByteArray()

        // Create WAV header
        val header = createWavHeader(rawData.size, sampleRate, 1, 16) // mono, 16-bit

        // Write to file
        withContext(Dispatchers.IO) {
            FileOutputStream(outputFile).use { fos ->
                fos.write(header)
                fos.write(rawData)
            }
        }
    } catch (se: SecurityException) {
        Log.e("AudioRecorder", "Recording failed due to SecurityException: ${se.message}")
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Microphone permission denied", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Log.e("AudioRecorder", "Recording failed: ${e.message}")
    } finally {
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            // ignore
        }
    }
}

// Helper to create WAV header
fun createWavHeader(dataLength: Int, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
    val header = ByteArray(44)
    val totalDataLen = dataLength + 36
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8

    header[0] = 'R'.code.toByte()
    header[1] = 'I'.code.toByte()
    header[2] = 'F'.code.toByte()
    header[3] = 'F'.code.toByte()
    header[4] = (totalDataLen and 0xff).toByte()
    header[5] = ((totalDataLen shr 8) and 0xff).toByte()
    header[6] = ((totalDataLen shr 16) and 0xff).toByte()
    header[7] = ((totalDataLen shr 24) and 0xff).toByte()

    header[8] = 'W'.code.toByte()
    header[9] = 'A'.code.toByte()
    header[10] = 'V'.code.toByte()
    header[11] = 'E'.code.toByte()

    header[12] = 'f'.code.toByte()
    header[13] = 'm'.code.toByte()
    header[14] = 't'.code.toByte()
    header[15] = ' '.code.toByte()

    header[16] = 16.toByte() // Subchunk1Size for PCM
    header[17] = 0.toByte()
    header[18] = 0.toByte()
    header[19] = 0.toByte()

    header[20] = 1.toByte() // AudioFormat PCM
    header[21] = 0.toByte()

    header[22] = channels.toByte()
    header[23] = 0.toByte()

    header[24] = (sampleRate and 0xff).toByte()
    header[25] = ((sampleRate shr 8) and 0xff).toByte()
    header[26] = ((sampleRate shr 16) and 0xff).toByte()
    header[27] = ((sampleRate shr 24) and 0xff).toByte()

    header[28] = (byteRate and 0xff).toByte()
    header[29] = ((byteRate shr 8) and 0xff).toByte()
    header[30] = ((byteRate shr 16) and 0xff).toByte()
    header[31] = ((byteRate shr 24) and 0xff).toByte()

    header[32] = blockAlign.toByte()
    header[33] = 0.toByte()

    header[34] = bitsPerSample.toByte()
    header[35] = 0.toByte()

    header[36] = 'd'.code.toByte()
    header[37] = 'a'.code.toByte()
    header[38] = 't'.code.toByte()
    header[39] = 'a'.code.toByte()

    header[40] = (dataLength and 0xff).toByte()
    header[41] = ((dataLength shr 8) and 0xff).toByte()
    header[42] = ((dataLength shr 16) and 0xff).toByte()
    header[43] = ((dataLength shr 24) and 0xff).toByte()

    return header
}

@Preview(showBackground = true)
@Composable
fun AudioRecordScreenPreview() {
    TextAndDriveTheme {
        AudioRecordScreen()
    }
}

@Composable
fun TextAndDriveTheme(content: @Composable () -> Unit) {
    MaterialTheme {
        content()
    }
}
