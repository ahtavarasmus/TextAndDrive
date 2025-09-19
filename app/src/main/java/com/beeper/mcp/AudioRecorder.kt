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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.beeper.mcp.data.api.ElevenLabsStt
import com.beeper.mcp.data.api.ElevenLabsTts
import com.beeper.mcp.data.api.STT
import com.beeper.mcp.tools.getChatsFormatted
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

@Composable
fun AudioRecordScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var isRecording by remember { mutableStateOf(false) }
    var recordingJob: Job? by remember { mutableStateOf(null) }
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

    // Function to call after recording
    fun processRecordedAudio(filePath: String) {
        val ApiKey = BuildConfig.TINFOIL_API_KEY
        if (context is ComponentActivity) {
            context.lifecycleScope.launch {
                try {
                    val transcription = STT.speechToText(ApiKey, File(filePath))
                    Log.d("AudioRecorder", "STT transcription: $transcription")
                    // ... (rest of the code remains the same: call getChatsFormatted, LLM, etc.)
                    try {
                        Log.d("AudioRecorder", "Calling getChatsFormatted...")
                        val startTime = System.currentTimeMillis()
                        val args = mapOf<String, Any?>(
                            "limit" to 10,
                            "offset" to 0
                        )
                        val chatsResult = context.contentResolver.getChatsFormatted(args)
                        val duration = System.currentTimeMillis() - startTime
                        Log.d("AudioRecorder", "getChatsFormatted completed in ${duration}ms")
                        Log.d("AudioRecorder", "Chats result length: ${chatsResult.length} characters")
                        Log.d("AudioRecorder", "Chats result preview: ${chatsResult.take(500)}...")
                        if (chatsResult.length < 2000) {
                            Log.d("AudioRecorder", "Full chats result:\n$chatsResult")
                        }
                    } catch (chatsEx: Exception) {
                        Log.e("AudioRecorder", "getChatsFormatted failed: ${chatsEx.message}")
                        Log.e("AudioRecorder", "getChatsFormatted exception type: ${chatsEx.javaClass.simpleName}")
                        chatsEx.printStackTrace()
                    }

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
                        val assistantText = com.beeper.mcp.data.api.LLMClient
                            .sendTranscriptWithTools(context, tinfoilKey, transcription, context.contentResolver)
                        Log.d("AudioRecorder", "Assistant reply: $assistantText")
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
            if (!micPermissionGranted) {
                // Permission request UI (unchanged)
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
}

// Function to record raw PCM and save as WAV (runs on IO dispatcher)
suspend fun recordAudioToWav(context: Context, outputFile: File, isRecording: () -> Boolean) {
    val sampleRate = 16000
    val channelConfig = AudioFormat.CHANNEL_IN_MONO
    val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    val audioRecord = AudioRecord(
        MediaRecorder.AudioSource.MIC,
        sampleRate,
        channelConfig,
        audioFormat,
        minBufferSize * 2
    )

    try {
        val baos = ByteArrayOutputStream()
        audioRecord.startRecording()
        while (isRecording()) {
            val buffer = ByteArray(minBufferSize)
            val read = audioRecord.read(buffer, 0, minBufferSize)
            if (read > 0) {
                baos.write(buffer, 0, read)
            }
        }
        audioRecord.stop()

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
    } catch (e: Exception) {
        Log.e("AudioRecorder", "Recording failed: ${e.message}")
    } finally {
        audioRecord.release()
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