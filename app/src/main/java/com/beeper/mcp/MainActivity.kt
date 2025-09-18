package com.beeper.mcp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.beeper.mcp.ui.theme.BeeperMcpTheme
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {
    private var mcpServer: McpServer? = null
    private var permissionsGranted by mutableStateOf(false)
    private var localIpAddress by mutableStateOf("Getting IP address...")
    private var serviceStatus by mutableStateOf(ServiceStatus(
        isRunning = false,
        serviceName = "beeper-mcp-server",
        localIpAddress = "Getting IP address..."
    ))

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
        if (permissionsGranted) {
            startMcpServer() // Start server immediately after granting permissions
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkBeeperPermissions()
        getLocalIpAddress()

        setContent {
            BeeperMcpTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (permissionsGranted) {
                        AudioRecordScreen(modifier = Modifier.padding(innerPadding))
                    } else {
                        McpServerStatus(
                            permissionsGranted = permissionsGranted,
                            serviceStatus = serviceStatus,
                            modifier = Modifier.padding(innerPadding),
                            onRequestPermissions = { requestBeeperPermissions() }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (permissionsGranted) {
            startMcpServer()
        }
        checkBeeperPermissions() // Re-check on resume
    }

    override fun onPause() {
        super.onPause()
        stopMcpServer()
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

    private fun startMcpServer() {
        if (mcpServer == null) {
            mcpServer = McpServer(this)
            mcpServer?.start()
            updateServiceStatus(true)
        }
    }

    private fun stopMcpServer() {
        mcpServer?.stop()
        mcpServer = null
        updateServiceStatus(false)
    }

    private fun updateServiceStatus(isRunning: Boolean) {
        serviceStatus = ServiceStatus(
            isRunning = isRunning,
            serviceName = "beeper-mcp-server",
            localIpAddress = localIpAddress
        )
    }

    private fun getLocalIpAddress() {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isLoopback && networkInterface.isUp) {
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (!address.isLoopbackAddress && address.hostAddress?.contains(':') == false) {
                            localIpAddress = address.hostAddress ?: "unknown"
                            return
                        }
                    }
                }
            }
        } catch (e: Exception) {
            localIpAddress = "127.0.0.1"
        }
    }
}

data class ServiceStatus(
    val isRunning: Boolean,
    val serviceName: String,
    val localIpAddress: String
)

@Composable
fun McpServerStatus(
    modifier: Modifier = Modifier,
    permissionsGranted: Boolean = false,
    serviceStatus: ServiceStatus = ServiceStatus(false, "beeper-mcp-server", "Getting IP address..."),
    onRequestPermissions: () -> Unit = {}
) {
    Column(modifier = modifier.padding(16.dp)) {
        Text(
            text = "🤖 Beeper MCP Server",
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
        // Connection Info (show always, but indicate if running)
        Spacer(modifier = Modifier.height(24.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Connection Info",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                Text(
                    text = "Add to Claude:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFB0B0B0),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = "$ claude mcp add --transport sse \\\n beeper-android \\\n http://${serviceStatus.localIpAddress}:8081",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF00FF00),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(color = Color(0xFF0D0D0D), shape = RoundedCornerShape(4.dp))
                        .padding(12.dp)
                )
            }
        }
        // Warning message
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "⚠️ Authentication not implemented",
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