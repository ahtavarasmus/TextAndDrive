package com.beeper.mcp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import com.beeper.mcp.data.api.ElevenLabsStt
import com.beeper.mcp.data.api.ElevenLabsTts
import com.beeper.mcp.tools.getChatsFormatted
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class AudioRecorderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
    }
}

@Composable
fun AudioRecordScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var isRecording by remember { mutableStateOf(false) }
    var recorder: MediaRecorder? by remember { mutableStateOf(null) }
    var outputFile: File? by remember { mutableStateOf(null) }
    // Permission launcher and local permission state
    var micPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        micPermissionGranted = isGranted
        if (!isGranted) {
            Toast.makeText(context, "Audio permission denied", Toast.LENGTH_SHORT).show()
        }
    }
    // Function to call after recording (empty for now, add API later)
    // Function to call after recording (updated to call getChatsFormatted and provide context to LLM)
    fun processRecordedAudio(filePath: String) {
        val elevenApiKey = BuildConfig.ELEVENLABS_API_KEY
        Log.d("AudioRecorder", "ELEVENLABS_API_KEY: ${if (elevenApiKey.isNullOrBlank()) "<missing>" else "<redacted>"}") // avoid logging key
        // Removed user-facing toast for recorded file path to avoid alert after recording
        if (context is ComponentActivity) {
            context.lifecycleScope.launch {
                try {
                    val transcription = ElevenLabsStt.speechToText(context, elevenApiKey, File(filePath))
                    Log.d("AudioRecorder", "STT transcription: $transcription")

                    // Get chats data to provide context to LLM
                    var chatsContext = ""
                    try {
                        Log.d("AudioRecorder", "Calling getChatsFormatted for LLM context...")
                        val startTime = System.currentTimeMillis()

                        // Create args map with default parameters
                        val args = mapOf<String, Any?>(
                            "limit" to 20, // Get more chats for better context
                            "offset" to 0
                        )

                        chatsContext = context.contentResolver.getChatsFormatted(args)
                        val duration = System.currentTimeMillis() - startTime

                        Log.d("AudioRecorder", "getChatsFormatted completed in ${duration}ms")
                        Log.d("AudioRecorder", "Chats result length: ${chatsContext.length} characters")
                        Log.d("AudioRecorder", "Chats result preview: ${chatsContext.take(500)}...")

                    } catch (chatsEx: Exception) {
                        Log.e("AudioRecorder", "getChatsFormatted failed: ${chatsEx.message}")
                        chatsContext = "Error retrieving chats: ${chatsEx.message}"
                    }

                    // Send transcript to LLM with tools and chats context
                    try {
                        val tinfoilKey = try { BuildConfig.TINFOIL_API_KEY } catch (_: Exception) { "" }
                        // Safer debug output: do NOT log the full API key. Log presence, length, a masked snippet, and a SHA-256 fingerprint.
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
                            append(chatsContext)
                        }

                        Log.d("AudioRecorder", "Sending enhanced transcript with chats context to LLM")
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

//                    if (assistantText.isNotBlank()) {
//                        try {
//                            val voiceId = "5kMbtRSEKIkRZSdXxrZg"
//                            val ttsFile = ElevenLabsTts.textToSpeech(context, elevenApiKey, voiceId, assistantText)
//                            ElevenLabsTts.playFromFile(context, ttsFile)
//                        } catch (ttsEx: Exception) {
//                            Log.e("AudioRecorder", "TTS call/playback failed: ${ttsEx.message}")
//                        }
//                    }

                    } catch (ex: Exception) {
                        Log.e("AudioRecorder", "LLM tool flow failed: ${ex.message}")
                    }

                } catch (e: Exception) {
                    Log.e("AudioRecorder", "STT call failed: ${e.message}")
                }
            }
        }
    }
    // Full-screen content: either a permission request UI or the recorder
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!micPermissionGranted) {
                // Show an explicit UI asking the user to grant microphone permission
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Microphone permission required",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    Text(
                        text = "This screen needs access to your microphone so you can record audio.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 20.dp)
                    )
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                        Text(text = "Grant microphone permission")
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    // Start recording on press down
                                    if (!isRecording) {
                                        try {
                                            outputFile = File.createTempFile("audio", ".mp4", context.cacheDir)
                                            recorder = MediaRecorder().apply {
                                                setAudioSource(MediaRecorder.AudioSource.MIC)
                                                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                                                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                                                setOutputFile(outputFile!!.absolutePath)
                                                prepare()
                                                start()
                                            }
                                            isRecording = true
                                            // Removed user-facing toast for recording start
                                        } catch (e: IOException) {
                                            Toast.makeText(context, "Failed to start recording", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    // Wait for release
                                    tryAwaitRelease()
                                    // Stop recording on release
                                    if (isRecording) {
                                        recorder?.apply {
                                            stop()
                                            release()
                                        }
                                        recorder = null
                                        isRecording = false
                                        outputFile?.let { file ->
                                            processRecordedAudio(file.absolutePath)
                                        }
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
    // Minimal theme so previews and composables have a Material3 theme wrapper.
    MaterialTheme {
        content()
    }
}
