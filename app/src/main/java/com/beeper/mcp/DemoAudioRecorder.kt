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
import com.beeper.mcp.tools.getChatsFormattedMock
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

class DemoAudioRecorderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TextAndDriveTheme {
                DemoAudioRecordScreen()
            }
        }
    }
}

@Composable
fun DemoAudioRecordScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var isRecording by remember { mutableStateOf(false) }
    var recordingJob: Job? by remember { mutableStateOf(null) }
    var outputFile: File? by remember { mutableStateOf(null) }

    // Function to call after recording
    fun processRecordedAudio(filePath: String) {
        val apiKey = BuildConfig.TINFOIL_API_KEY
        val elevenApiKey = BuildConfig.ELEVENLABS_API_KEY
        if (context is ComponentActivity) {
            context.lifecycleScope.launch {
                try {
                    val transcription = STT.speechToText(apiKey, File(filePath))
                    Log.d("AudioRecorder", "STT transcription: $transcription")
                    // ... (rest of the code remains the same: call getChatsFormatted, LLM, etc.)
                    try {
                        Log.d("AudioRecorder", "Calling getChatsFormatted for LLM context...")
                        val startTime = System.currentTimeMillis()

                        // Create args map with default parameters
                        val args = mapOf<String, Any?>(
                            "limit" to 35, // Get more chats for better context
                            "offset" to 0
                        )
                        val chatsResult = context.contentResolver.getChatsFormattedMock(args)
                        val duration = System.currentTimeMillis() - startTime
                        Log.d("AudioRecorder", "getChatsFormatted completed in ${duration}ms")
                        Log.d("AudioRecorder", "Chats result length: ${chatsResult.length} characters")
                        Log.d("AudioRecorder", "Chats result preview: ${chatsResult.take(500)}...")
                        if (chatsResult.length < 2000) {
                            Log.d("AudioRecorder", "Full chats result:\n$chatsResult")
                        }

                        // DEBUG: quick sanity-check — directly invoke the mock handler to ensure it's reachable
                        try {
                            Log.d("DemoAudioRecorder", "DEBUG: invoking handleOpenAIToolCallMock test...")
                            val testCall = mapOf("name" to "get_chats", "arguments" to "{}")
                            val testResult = context.contentResolver.handleOpenAIToolCallMock(testCall)
                            Log.d("DemoAudioRecorder", "DEBUG: mock handler test result length=${testResult.length}")
                        } catch (dbgEx: Exception) {
                            Log.e("DemoAudioRecorder", "DEBUG: mock handler test failed: ${dbgEx.message}")
                        }

                        // Convert the mock chats result into the same system-message form LLMClient expects
                        val chatMsg = org.json.JSONObject().put("role", "system").put("content", "Available chats context:\n$chatsResult")
                        val chatsExtraForLLM = listOf(chatMsg)

                        // Send transcript to LLM with tools and chats context
                        try {
                            val tinfoilKey = try {
                                BuildConfig.TINFOIL_API_KEY
                            } catch (_: Exception) {
                                ""
                            }
                            if (tinfoilKey.isNullOrBlank()) {
                                Log.w("AudioRecorder", "TINFOIL_API_KEY is missing or blank")
                            } else {
                                val masked = if (tinfoilKey.length > 8) "${tinfoilKey.substring(0,4)}...${tinfoilKey.takeLast(4)}" else "****"
                                val sha256 = try {
                                    MessageDigest.getInstance("SHA-256").digest(tinfoilKey.toByteArray()).joinToString("") { "%02x".format(it) }
                                } catch (e: Exception) {
                                    "<hash-error>"
                                }
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
                                // Handle exception silently
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
                                // Handle exception silently
                            }

                            // Track whether we've already played a TTS message so we don't duplicate playback
                            var playedTts = false
                            var sendResult = ""

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
                                            // Demo flow: use the mock handler so demo uses mock tool implementations only.
                                            Log.d("DemoAudioRecorder", "Invoking mock OpenAI tool handler for $fname")
                                            sendResult = context.contentResolver.handleOpenAIToolCallMock(toolCallMap)
                                            Log.d("AudioRecorder", "Executed tool call (mock): $fname -> result length=${sendResult.length}")
                                        } else {
                                            // Not a function_call JSON; use assistant text directly
                                            sendResult = assistantText
                                            Log.d("AudioRecorder", "Assistant reply contained JSON but no function_call; using plain text result")
                                        }
                                    } catch (parseEx: Exception) {
                                        Log.e("AudioRecorder", "Failed to parse assistant JSON reply: ${parseEx.message}")
                                        // As a cautious fallback, attempt the legacy hardcoded send
                                        try {
                                            sendResult = context.contentResolver.sendHardcodedMessageToRasums()
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
                                    sendResult = context.contentResolver.sendHardcodedMessageToRasums()
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
                                        // Handle exception silently
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
                                                // Handle exception silently
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
                                        Log.w("AudioRecorder", "No suitable human-readable text to TTS; skipping fallback to avoid speaking raw JSON")
                                    }
                                }
                            } catch (fbEx: Exception) {
                                Log.e("AudioRecorder", "Fallback TTS failed: ${fbEx.message}")
                            }

                        } catch (ex: Exception) {
                            Log.e("AudioRecorder", "LLM tool flow failed: ${ex.message}")
                        }
                    } catch (ex: Exception) {
                        Log.e("AudioRecorder", "getChatsFormatted failed: ${ex.message}")
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

@Preview(showBackground = true)
@Composable
fun DemoAudioRecordScreenPreview() {
    TextAndDriveTheme {
        DemoAudioRecordScreen()
    }
}