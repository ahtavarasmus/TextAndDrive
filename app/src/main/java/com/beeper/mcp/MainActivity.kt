package com.beeper.mcp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.material3.*
import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import com.beeper.mcp.ui.theme.BeeperMcpTheme

const val BEEPER_AUTHORITY = "com.beeper.api"

// Modern Color Palette inspired by Claude
object ModernColors {
    val Primary = Color(0xFFE97C4C) // Warm orange-red (Claude inspired)
    val PrimaryVariant = Color(0xFFD4663A)
    val Secondary = Color(0xFF4A5568) // Sophisticated gray-blue
    val Background = Color(0xFFF8FAFC) // Clean light background
    val Surface = Color(0xFFFFFFFF)
    val OnPrimary = Color(0xFFFFFFFF)
    val OnBackground = Color(0xFF1A202C) // Deep text color
    val OnSurface = Color(0xFF2D3748)
    val Success = Color(0xFF10B981) // Modern green
    val Error = Color(0xFFEF4444) // Modern red
    val Warning = Color(0xFFF59E0B) // Modern amber
}

class MainActivity : ComponentActivity() {
    private var permissionsGranted by mutableStateOf(false)
    private var micPermissionGranted by mutableStateOf(false)
    // Tracks whether the user chose the demo flow or the real flow (or still choosing)
    private var appMode by mutableStateOf("choice") // values: "choice", "demo", "real"

    private val beeperPermissions = mutableListOf(
        "com.beeper.android.permission.READ_PERMISSION",
        "com.beeper.android.permission.SEND_PERMISSION"
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissionsGranted = permissions.values.all { it }
    }

    // Microphone permission launcher
    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        micPermissionGranted = granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkBeeperPermissions()

        setContent {
            BeeperMcpTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = ModernColors.Background
                ) {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        // Initial choice screen: Demo vs Real
                        when (appMode) {
                            "choice" -> ChoiceScreen(
                                modifier = Modifier.padding(innerPadding),
                                onDemo = { appMode = "demo" },
                                onReal = { appMode = "real" }
                            )
                            "demo" -> {
                                // Demo flow: require both Beeper and microphone permissions
                                if (permissionsGranted && micPermissionGranted) {
                                    DemoAudioRecordScreen(modifier = Modifier.padding(innerPadding))
                                } else {
                                    PermissionStatus(
                                        permissionsGranted = permissionsGranted,
                                        micPermissionGranted = micPermissionGranted,
                                        modifier = Modifier.padding(innerPadding),
                                        onRequestPermissions = { requestBeeperPermissions() },
                                        onRequestMic = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                                    )
                                }
                            }
                            "real" -> {
                                // Real flow: require both Beeper and microphone permissions
                                if (permissionsGranted && micPermissionGranted) {
                                    AudioRecordScreen(modifier = Modifier.padding(innerPadding))
                                } else {
                                    PermissionStatus(
                                        permissionsGranted = permissionsGranted,
                                        micPermissionGranted = micPermissionGranted,
                                        modifier = Modifier.padding(innerPadding),
                                        onRequestPermissions = { requestBeeperPermissions() },
                                        onRequestMic = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                                    )
                                }
                            }
                            else -> {
                                // fallback to choice
                                ChoiceScreen(
                                    modifier = Modifier.padding(innerPadding),
                                    onDemo = { appMode = "demo" },
                                    onReal = { appMode = "real" }
                                )
                            }
                        }
                    }
                }
            }
        }
        // Preserve the existing sample tool call behavior only for the Real flow when permissions are already granted.
        if (appMode == "real" && permissionsGranted && micPermissionGranted) {
            lifecycleScope.launch {
                // Sample tool call for "get_chats" (mimics OpenAI response format)
                val toolCall = mapOf(
                    "name" to "get_chats",
                    "arguments" to """{"limit":10}"""  // Fetches up to 10 most recent chats (read or unread)
                )

                try {
                    val result = contentResolver.handleOpenAIToolCallMock(toolCall)
                    Log.d("ToolTest", "Get Chats Result:\n$result")  // Inspect this in Logcat
                } catch (e: Exception) {
                    Log.e("ToolTest", "Error: ${e.message}", e)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkBeeperPermissions() // Re-check on resume
    }

    private fun checkBeeperPermissions() {
        permissionsGranted = beeperPermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
        micPermissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestBeeperPermissions() {
        val permissionsToRequest = beeperPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest)
        }
        // Also request microphone if missing
        if (!micPermissionGranted) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

@Composable
fun ChoiceScreen(
    modifier: Modifier = Modifier,
    onDemo: () -> Unit = {},
    onReal: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ModernColors.Background)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Choose Mode",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp
            ),
            color = ModernColors.OnBackground,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Text(
            text = "Select how you'd like to experience the app",
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 16.sp
            ),
            color = ModernColors.Secondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 48.dp)
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = ModernColors.Surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Text(
                        text = "🚀",
                        style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp),
                        modifier = Modifier.padding(end = 12.dp)
                    )
                    Text(
                        text = "Demo App",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp
                        ),
                        color = ModernColors.OnSurface
                    )
                }
                Text(
                    text = "Has premade data so you don't need to sync your WhatsApp",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 15.sp,
                        lineHeight = 20.sp
                    ),
                    color = ModernColors.Secondary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Button(
                    onClick = onDemo,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ModernColors.Primary
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        "Try Demo",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = ModernColors.Surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Text(
                        text = "⚡",
                        style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp),
                        modifier = Modifier.padding(end = 12.dp)
                    )
                    Text(
                        text = "Real App",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp
                        ),
                        color = ModernColors.OnSurface
                    )
                }
                Text(
                    text = "Real data, you would have to sync our App with your WhatsApp to work",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 15.sp,
                        lineHeight = 20.sp
                    ),
                    color = ModernColors.Secondary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Button(
                    onClick = onReal,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ModernColors.Primary
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        "Use Real Data",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionStatus(
    modifier: Modifier = Modifier,
    permissionsGranted: Boolean = false,
    micPermissionGranted: Boolean = false,
    onRequestPermissions: () -> Unit = {},
    onRequestMic: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .background(ModernColors.Background)
            .padding(24.dp)
    ) {
        Text(
            text = "Permissions Required",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp
            ),
            color = ModernColors.OnBackground,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = "In order for our app to work smoothly, we would need these permissions from you:",
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 16.sp
            ),
            color = ModernColors.Secondary,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = ModernColors.Surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                // Beeper Permissions Row
                StatusRow(
                    label = "Beeper permissions",
                    status = if (permissionsGranted) "✅ Granted" else "❌ Not Granted",
                    statusColor = if (permissionsGranted) ModernColors.Success else ModernColors.Error,
                    buttonText = "Grant",
                    showButton = !permissionsGranted,
                    onButtonClick = onRequestPermissions
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Microphone Permissions Row
                StatusRow(
                    label = "Microphone",
                    status = if (micPermissionGranted) "✅ Granted" else "❌ Not Granted",
                    statusColor = if (micPermissionGranted) ModernColors.Success else ModernColors.Error,
                    buttonText = "Grant",
                    showButton = !micPermissionGranted,
                    onButtonClick = onRequestMic
                )
            }
        }
        // Warning message
        Spacer(modifier = Modifier.height(24.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = ModernColors.Warning.copy(alpha = 0.1f)
            )
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "⚠️",
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp),
                    modifier = Modifier.padding(end = 12.dp)
                )
                Text(
                    text = "Ensure Beeper app is installed",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp
                    ),
                    color = ModernColors.Warning
                )
            }
        }
    }
}

@Composable
fun StatusRow(
    label: String,
    status: String,
    statusColor: Color,
    buttonText: String,
    showButton: Boolean,
    onButtonClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp
                ),
                color = ModernColors.OnSurface
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = statusColor,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (showButton) {
            Button(
                onClick = onButtonClick,
                modifier = Modifier.width(100.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ModernColors.Primary,
                    contentColor = ModernColors.OnPrimary
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    buttonText,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        } else {
            Spacer(modifier = Modifier.width(100.dp))
        }
    }
}