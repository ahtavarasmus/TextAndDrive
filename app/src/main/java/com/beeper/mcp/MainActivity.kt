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
import androidx.core.content.ContextCompat
import com.beeper.mcp.ui.theme.BeeperMcpTheme

const val BEEPER_AUTHORITY = "com.beeper.api"

class MainActivity : ComponentActivity() {
    private var permissionsGranted by mutableStateOf(false)

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkBeeperPermissions()

        setContent {
            BeeperMcpTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (permissionsGranted) {
                        AudioRecordScreen(modifier = Modifier.padding(innerPadding))
                    } else {
                        PermissionStatus(
                            permissionsGranted = permissionsGranted,
                            modifier = Modifier.padding(innerPadding),
                            onRequestPermissions = { requestBeeperPermissions() }
                        )
                    }
                }
            }
        }
        if (permissionsGranted) {
            lifecycleScope.launch {
                // Sample tool call for "get_chats" (mimics OpenAI response format)
                val toolCall = mapOf(
                    "name" to "get_chats",
                    "arguments" to """{"limit":10}"""  // Fetches up to 10 most recent chats (read or unread)
                )

                try {
                    val result = contentResolver.handleOpenAIToolCallMock(toolCall)
                    Log.d("ToolTest", "Get Chats Result:\n$result")  // Inspect this in Logcat
                    // Optional: Toast.makeText(this@MainActivity, result.take(200), Toast.LENGTH_LONG).show()  // Show snippet on screen
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
    }

    private fun requestBeeperPermissions() {
        val permissionsToRequest = beeperPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest)
        }
    }
}

@Composable
fun PermissionStatus(
    modifier: Modifier = Modifier,
    permissionsGranted: Boolean = false,
    onRequestPermissions: () -> Unit = {}
) {
    Column(modifier = modifier.padding(16.dp)) {
        Text(
            text = "🤖 Beeper Tools",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 24.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Permissions Row
                StatusRow(
                    label = "Permissions",
                    status = if (permissionsGranted) "✅ Granted" else "❌ Not Granted",
                    statusColor = if (permissionsGranted) Color(0xFF4CAF50) else Color(0xFFF44336),
                    buttonText = "Fix",
                    showButton = !permissionsGranted,
                    onButtonClick = onRequestPermissions
                )
            }
        }
        // Warning message (optional, removed server-specific warning)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "⚠️ Ensure Beeper app is installed and running",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFFF9800),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
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
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = statusColor,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (showButton) {
            Button(
                onClick = onButtonClick,
                modifier = Modifier.width(100.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(buttonText)
            }
        } else {
            Spacer(modifier = Modifier.width(100.dp))
        }
    }
}
