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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.beeper.mcp.ui.theme.BeeperMcpTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import androidx.compose.material3.ExperimentalMaterial3Api
const val BEEPER_AUTHORITY = "com.beeper.api"
// Hitchhiker's Guide to the Galaxy theme: Goofy green vibes, dark space background, with a touch of improbability
object HitchhikerColors {
    val Primary = Color(0xFF2E7D32) // Deep green like the Guide's cover
    val Background = Color(0xFF000000) // Vast emptiness of space
    val Surface = Color(0xFF1B1B1B) // Dim starlight panels
    val OnBackground = Color(0xFFFFFFFF) // Bright text to pierce the void
    val OnSurface = Color(0xFFBDBDBD) // Silvery text for that metallic spaceship feel
    val Success = Color(0xFF4CAF50) // Goofy green success, like finding the answer 42
    val Error = Color(0xFFFF5722) // Fiery error, Vogons would approve
}
class MainActivity : ComponentActivity() {
    private var beeperPermissionsGranted by mutableStateOf(false)
    private var micPermissionGranted by mutableStateOf(false)
    private var beeperEnabled by mutableStateOf(false)
    private var menuExpanded by mutableStateOf(false)
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
        beeperPermissionsGranted = permissions.values.all { it }
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
        checkPermissions()
        setContent {
            BeeperMcpTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = HitchhikerColors.Background
                ) {
                    @OptIn(ExperimentalMaterial3Api::class)
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        topBar = {
                            TopAppBar(
                                title = {
                                    Text(
                                        "DON'T PANIC! Voice Chat",
                                        style = MaterialTheme.typography.headlineSmall.copy(
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = HitchhikerColors.Primary
                                    )
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = HitchhikerColors.Surface,
                                    titleContentColor = HitchhikerColors.OnSurface,
                                    navigationIconContentColor = HitchhikerColors.OnSurface
                                ),
                                navigationIcon = {
                                    Box {
                                        IconButton(onClick = { menuExpanded = !menuExpanded }) {
                                            Icon(
                                                Icons.Default.Menu,
                                                contentDescription = "Menu"
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = menuExpanded,
                                            onDismissRequest = { menuExpanded = false },
                                            modifier = Modifier.background(HitchhikerColors.Surface)
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(16.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    "Enable Beeper Integration",
                                                    color = HitchhikerColors.OnSurface,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                                Switch(
                                                    checked = beeperEnabled,
                                                    onCheckedChange = { newValue ->
                                                        beeperEnabled = newValue
                                                        checkPermissions()
                                                        if (newValue) {
                                                            requestBeeperPermissions()
                                                        }
                                                        menuExpanded = false
                                                    },
                                                    colors = SwitchDefaults.colors(
                                                        checkedThumbColor = HitchhikerColors.Primary,
                                                        checkedTrackColor = HitchhikerColors.Primary.copy(alpha = 0.5f)
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    ) { innerPadding ->
                        if (micPermissionGranted && (!beeperEnabled || beeperPermissionsGranted)) {
                            AudioRecordScreen(
                                modifier = Modifier.padding(innerPadding),
                                isDemo = !beeperEnabled,
                                onBack = { }
                            )
                        } else {
                            PermissionStatus(
                                permissionsGranted = beeperPermissionsGranted,
                                micPermissionGranted = micPermissionGranted,
                                requireBeeper = beeperEnabled,
                                modifier = Modifier.padding(innerPadding),
                                onRequestPermissions = { requestBeeperPermissions() },
                                onRequestMic = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                            )
                        }
                    }
                }
            }
        }
        // Preserve the existing sample tool call behavior only for the Real flow when permissions are already granted.
        if (beeperEnabled && beeperPermissionsGranted && micPermissionGranted) {
            lifecycleScope.launch {
                // Sample tool call for "get_chats" (mimics OpenAI response format)
                val toolCall = mapOf(
                    "name" to "get_chats",
                    "arguments" to """{"limit":10}""" // Fetches up to 10 most recent chats (read or unread)
                )
                try {
                    val result = contentResolver.handleOpenAIToolCallMock(toolCall)
                    Log.d("ToolTest", "Get Chats Result:\n$result") // Inspect this in Logcat
                } catch (e: Exception) {
                    Log.e("ToolTest", "Error: ${e.message}", e)
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        checkPermissions() // Re-check on resume
    }
    private fun checkPermissions() {
        micPermissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        beeperPermissionsGranted = if (beeperEnabled) {
            beeperPermissions.all { permission ->
                ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            }
        } else {
            true
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
    micPermissionGranted: Boolean = false,
    requireBeeper: Boolean = true,
    onRequestPermissions: () -> Unit = {},
    onRequestMic: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .background(HitchhikerColors.Background)
            .padding(24.dp)
    ) {
        Text(
            text = "Permissions: Marvin Needs a Brain Upload",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp
            ),
            color = HitchhikerColors.OnBackground,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "To grumble-voice chat, we need these. (He's got a brain the size of a planet, but still... permissions.)",
            style = MaterialTheme.typography.bodyMedium,
            color = HitchhikerColors.OnSurface,
            modifier = Modifier.padding(bottom = 24.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = HitchhikerColors.Surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), // Flat like a Babel fish
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Beeper Permissions Row (only if required)
                if (requireBeeper) {
                    StatusRow(
                        label = "Beeper Access (For Real Chats)",
                        status = if (permissionsGranted) "Granted (Yay!)" else "Not Yet (Sigh)",
                        statusColor = if (permissionsGranted) HitchhikerColors.Success else HitchhikerColors.Error,
                        buttonText = "Grant It",
                        showButton = !permissionsGranted,
                        onButtonClick = onRequestPermissions
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
                // Microphone Permissions Row
                StatusRow(
                    label = "Microphone (For Marvin's Moans)",
                    status = if (micPermissionGranted) "Granted (Finally!)" else "Not Yet (Brain Ache)",
                    statusColor = if (micPermissionGranted) HitchhikerColors.Success else HitchhikerColors.Error,
                    buttonText = "Grant It",
                    showButton = !micPermissionGranted,
                    onButtonClick = onRequestMic
                )
            }
        }
        // Warning message (only if Beeper is required)
        if (requireBeeper) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = HitchhikerColors.Primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Make sure Beeper is installed—or Marvin will just stare at the wall.",
                    style = MaterialTheme.typography.bodySmall,
                    color = HitchhikerColors.Primary
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
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp
                ),
                color = HitchhikerColors.OnSurface
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = statusColor,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (showButton) {
            TextButton(
                onClick = onButtonClick,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = HitchhikerColors.Primary
                )
            ) {
                Text(buttonText)
            }
        } else {
            Spacer(modifier = Modifier.width(80.dp))
        }
    }
}
