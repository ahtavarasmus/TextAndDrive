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
    private var permissionsGranted by mutableStateOf(false)
    private var micPermissionGranted by mutableStateOf(false)
    // Tracks whether the user chose the demo flow or the real flow (or still choosing)
    private var appMode by mutableStateOf("choice") // values: "choice", "demo", "real", "interest"
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
        if (permissionsGranted) {
            appMode = "real"
        }
        setContent {
            BeeperMcpTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = HitchhikerColors.Background
                ) {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        // Initial choice screen: Demo vs Real vs Interest
                        when (appMode) {
                            "choice" -> ChoiceScreen(
                                modifier = Modifier.padding(innerPadding),
                                onDemo = { appMode = "demo" },
                                onReal = { appMode = "real" },
                                onInterest = { appMode = "interest" }
                            )
                            "interest" -> InterestScreen(
                                modifier = Modifier.padding(innerPadding),
                                onBack = { appMode = "choice" }
                            )
                            "demo" -> {
                                // Demo flow: require only microphone permissions, showcase Marvin's inbox
                                if (micPermissionGranted) {
                                    AudioRecordScreen(
                                        modifier = Modifier.padding(innerPadding),
                                        isDemo = true,
                                        onBack = { appMode = "choice" }
                                    )
                                } else {
                                    PermissionStatus(
                                        permissionsGranted = permissionsGranted,
                                        micPermissionGranted = micPermissionGranted,
                                        requireBeeper = false,
                                        modifier = Modifier.padding(innerPadding),
                                        onRequestPermissions = { },
                                        onRequestMic = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                                    )
                                }
                            }
                            "real" -> {
                                // Real flow: require both Beeper and microphone permissions
                                if (permissionsGranted && micPermissionGranted) {
                                    AudioRecordScreen(
                                        modifier = Modifier.padding(innerPadding),
                                        isDemo = false,
                                        onBack = { appMode = "choice" }
                                    )
                                } else {
                                    PermissionStatus(
                                        permissionsGranted = permissionsGranted,
                                        micPermissionGranted = micPermissionGranted,
                                        requireBeeper = true,
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
                                    onReal = { appMode = "real" },
                                    onInterest = { appMode = "interest" }
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
    onReal: () -> Unit = {},
    onInterest: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(HitchhikerColors.Background)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "DON'T PANIC!",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold
            ),
            color = HitchhikerColors.Primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "Voice-Command Your Messages on WhatsApp, iMessage & More – Hands-Free Chat via Beeper (Install & Connect Your Networks for Galactic Convenience)",
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 16.sp
            ),
            color = HitchhikerColors.OnSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 32.dp)
        )
        Button(
            onClick = onDemo,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(bottom = 16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = HitchhikerColors.Primary
            )
        ) {
            Text("Try Demo: Chat with Marvin's Inbox (No Setup Needed)")
        }
        Button(
            onClick = onReal,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(bottom = 16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = HitchhikerColors.Primary.copy(alpha = 0.7f)
            )
        ) {
            Text("Real Mode: Your Actual Chats (Needs Beeper, Poor Marvin)")
        }
        OutlinedButton(
            onClick = onInterest,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = HitchhikerColors.Primary
            )
        ) {
            Text("Sign Up for Launch (We'll Towel You In)")
        }
        // Funny small print legal disclaimer
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "* Not for real-world driving—brains the size of planets shouldn't be wasted on that. Only for video games or improbably flying the Heart of Gold. Vogon poetry responses not guaranteed.",
            style = MaterialTheme.typography.bodySmall,
            color = HitchhikerColors.OnSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}
@Composable
fun InterestScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }
    val context = LocalContext.current
    if (submitted) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(HitchhikerColors.Background)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Thanks! We'll notify you when it's improbably ready. (Don't forget your towel.)",
                style = MaterialTheme.typography.headlineSmall,
                color = HitchhikerColors.Success,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = HitchhikerColors.Primary
                )
            ) {
                Text("Back to the Pantry")
            }
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(HitchhikerColors.Background)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Want to Hitch a Ride on This App?",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = HitchhikerColors.OnBackground,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "It'll be 100% private—like the Ultimate Question hidden in a teapot. (Demo's private now, but verifiability is still orbiting.)",
                style = MaterialTheme.typography.bodyMedium,
                color = HitchhikerColors.OnSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 32.dp)
            )
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Your email (no spam, promise)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = HitchhikerColors.Primary,
                    unfocusedBorderColor = HitchhikerColors.OnSurface.copy(alpha = 0.5f)
                )
            )
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                label = { Text("Willing to pay (e.g., $5/month for infinite improbability)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                singleLine = true,
                prefix = { Text("$") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = HitchhikerColors.Primary,
                    unfocusedBorderColor = HitchhikerColors.OnSurface.copy(alpha = 0.5f)
                )
            )
            Button(
                onClick = {
                    if (email.isNotBlank() && amount.isNotBlank()) {
                        isSubmitting = true
                        (context as? ComponentActivity)?.lifecycleScope?.launch {
                            try {
                                addToResendAudience(email, amount)
                                submitted = true
                            } catch (e: Exception) {
                                Log.e("InterestScreen", "Submit failed: ${e.message}")
                            } finally {
                                isSubmitting = false
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSubmitting && email.isNotBlank() && amount.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = HitchhikerColors.Primary
                )
            ) {
                if (isSubmitting) {
                    Text("Beaming Up...")
                } else {
                    Text("Submit & Mostly Harmless Pay")
                }
            }
            TextButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = HitchhikerColors.Primary
                )
            ) {
                Text("Back (Or Hitch Another Ride)")
            }
        }
    }
}
private suspend fun addToResendAudience(email: String, amount: String) {
    withContext(Dispatchers.IO) {
        val resendApiKey = BuildConfig.RESEND_API_KEY // Assume added to BuildConfig
        val audienceId = "your_audience_id_here" // Hardcode or from props
        val client = OkHttpClient()
        val json = JSONObject().apply {
            put("email", email)
            put("name", amount) // Use amount as 'name' field
        }
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://api.resend.com/audiences/$audienceId/contacts")
            .addHeader("Authorization", "Bearer $resendApiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Resend API failed: ${response.code} - ${response.body?.string()}")
            }
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
