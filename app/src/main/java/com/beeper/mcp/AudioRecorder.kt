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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.beeper.mcp.data.api.ElevenLabsStt
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

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
    fun processRecordedAudio(filePath: String) {
        val apiKey = BuildConfig.ELEVENLABS_API_KEY
        Log.d("AudioRecorder", "ELEVENLABS_API_KEY: $apiKey") // DEBUG: Log the API key
        Toast.makeText(context, "Recorded file: $filePath", Toast.LENGTH_SHORT).show()
        if (context is ComponentActivity) {
            context.lifecycleScope.launch {
                try {
                    val transcription = ElevenLabsStt.speechToText(context, apiKey, File(filePath))
                    Log.d("AudioRecorder", "STT transcription: $transcription")
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
        if (!micPermissionGranted) {
            // Show an explicit UI asking the user to grant microphone permission
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
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
                                        Toast.makeText(context, "Recording started", Toast.LENGTH_SHORT).show()
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
                                    Toast.makeText(context, "Recording stopped", Toast.LENGTH_SHORT).show()
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

