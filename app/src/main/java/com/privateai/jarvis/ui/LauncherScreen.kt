package com.privateai.jarvis.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.net.Uri
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.core.content.ContextCompat
import com.privateai.jarvis.MainActivity
import com.privateai.jarvis.ai.CloudChatClient
import com.privateai.jarvis.ai.HybridRouter
import com.privateai.jarvis.ai.LocalLlmEngine
import com.privateai.jarvis.ai.RouteDecision
import com.privateai.jarvis.ai.ToolRequest
import com.privateai.jarvis.data.ActionLogEntity
import com.privateai.jarvis.data.ChatMessageEntity
import com.privateai.jarvis.data.PinnedAppEntity
import com.privateai.jarvis.data.VaultDatabase
import com.privateai.jarvis.launcher.AppRepository
import com.privateai.jarvis.launcher.InstalledApp
import com.privateai.jarvis.security.SecurityManager
import com.privateai.jarvis.tools.PendingToolAction
import com.privateai.jarvis.tools.ToolExecutionOutcome
import com.privateai.jarvis.tools.ToolExecutor
import com.privateai.jarvis.voice.VoiceAssistantManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun LauncherScreen(
    securityManager: SecurityManager,
    database: VaultDatabase
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appRepo = remember { AppRepository(context) }
    val dao = remember { database.vaultDao() }
    val chatClient = remember { CloudChatClient(context, securityManager) }
    val localEngine = remember { LocalLlmEngine(context, securityManager) }
    val hybridRouter = remember { HybridRouter(chatClient, localEngine) }
    val toolExecutor = remember { ToolExecutor(context, appRepo, dao) }
    val voiceManager = remember { VoiceAssistantManager(context) }
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    DisposableEffect(Unit) {
        onDispose {
            voiceManager.release()
            localEngine.close()
        }
    }

    var allApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var showAppDrawerSheet by remember { mutableStateOf(false) }
    var showLogsSheet by remember { mutableStateOf(false) }
    var showSetupSheet by remember { mutableStateOf(false) }
    var isJarvisExpanded by remember { mutableStateOf(false) }
    var monochromeIcons by remember { mutableStateOf(securityManager.isMonochromeIconsEnabled()) }

    // Live Clock State
    var currentTimeStr by remember { mutableStateOf("") }
    var currentDateStr by remember { mutableStateOf("") }
    var isMediaPlaying by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val timeFmt = SimpleDateFormat("HH : mm", Locale.getDefault())
        val dateFmt = SimpleDateFormat("dd MMM, EEEE", Locale.getDefault())
        while (true) {
            val now = Date()
            currentTimeStr = timeFmt.format(now)
            currentDateStr = dateFmt.format(now)
            isMediaPlaying = audioManager.isMusicActive
            delay(1000L)
        }
    }

    val pinnedEntities by dao.observePinnedApps().collectAsState(initial = emptyList())
    val pinnedPackages = remember(pinnedEntities) { pinnedEntities.map { it.packageName }.toSet() }

    LaunchedEffect(Unit) {
        allApps = appRepo.getInstalledApps()
    }

    // Chat & Agent State
    val messages by dao.observeChat().collectAsState(initial = emptyList())
    val chatListState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var ttsEnabled by remember { mutableStateOf(securityManager.isTtsEnabled()) }
    var wakeEnabled by remember { mutableStateOf(securityManager.isWakeWordEnabled()) }
    var pendingConfirmation by remember { mutableStateOf<PendingToolAction?>(null) }
    var pendingCloudPrivacyPrompt by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingPermissionTool by remember { mutableStateOf<ToolRequest?>(null) }

    val isListening by voiceManager.isListening.collectAsState()
    val isWakeActive by voiceManager.isWakeWordActive.collectAsState()
    val partialVoice by voiceManager.partialText.collectAsState()
    val voiceError by voiceManager.voiceError.collectAsState()
    val isSpeaking by voiceManager.isSpeaking.collectAsState()
    val voiceAmplitude by voiceManager.voiceAmplitude.collectAsState()

    val runCloudQuery: suspend (String) -> Unit = { prompt ->
        val result = chatClient.sendMessage(messages, prompt, allApps.map { it.label })
        result.fold(
            onSuccess = { turn ->
                if (turn.toolRequest != null) {
                    val outcome = toolExecutor.handleToolRequest(turn.toolRequest, confirmed = false)
                    handleOutcome(
                        outcome = outcome,
                        dao = dao,
                        speakReply = { if (ttsEnabled) voiceManager.speak(it) },
                        onNeedConfirm = { pendingConfirmation = it },
                        onNeedPermission = { _, req -> pendingPermissionTool = req }
                    )
                } else {
                    dao.insertChat(ChatMessageEntity(role = "assistant", text = turn.replyText))
                    if (ttsEnabled) voiceManager.speak(turn.replyText)
                }
            },
            onFailure = { err ->
                val msg = "⚠️ ${err.message ?: "Request failed."}"
                dao.insertChat(ChatMessageEntity(role = "assistant", text = msg))
                if (ttsEnabled) voiceManager.speak(msg)
            }
        )
    }

    LaunchedEffect(isSending, input) {
        voiceManager.isBusyProcessing = isSending || input.isNotBlank()
    }

    val submitPrompt: (String) -> Unit = { rawText ->
        val prompt = rawText.trim()
        if (prompt.isNotEmpty() && !isSending) {
            input = ""
            isJarvisExpanded = true
            isSending = true
            voiceManager.isBusyProcessing = true
            scope.launch {
                try {
                    dao.insertChat(ChatMessageEntity(role = "user", text = prompt))
                    val appLabels = allApps.map { it.label }

                    when (val route = hybridRouter.decideRoute(prompt)) {
                        is RouteDecision.DirectTool -> {
                            val outcome = toolExecutor.handleToolRequest(route.request, confirmed = false)
                            handleOutcome(
                                outcome = outcome,
                                dao = dao,
                                speakReply = { if (ttsEnabled) voiceManager.speak(it) },
                                onNeedConfirm = { pendingConfirmation = it },
                                onNeedPermission = { _, req -> pendingPermissionTool = req }
                            )
                        }
                        is RouteDecision.Cloud -> {
                            if (route.requiresPrivacyApproval) {
                                pendingCloudPrivacyPrompt = Pair(prompt, route.privacySummary)
                                if (ttsEnabled) {
                                    voiceManager.speak("Approval required before sending personal data online.")
                                }
                            } else {
                                runCloudQuery(prompt)
                            }
                        }
                        is RouteDecision.LocalFirst -> {
                            val hybridRes = withTimeoutOrNull(45_000L) {
                                hybridRouter.executeHybrid(prompt, messages, appLabels)
                            } ?: Result.failure(IllegalStateException("Inference timed out after 45s. Check your local model or API settings."))
                            hybridRes.fold(
                                onSuccess = { (turn, _) ->
                                    if (turn.toolRequest != null) {
                                        val outcome = toolExecutor.handleToolRequest(turn.toolRequest, confirmed = false)
                                        handleOutcome(
                                            outcome = outcome,
                                            dao = dao,
                                            speakReply = { if (ttsEnabled) voiceManager.speak(it) },
                                            onNeedConfirm = { pendingConfirmation = it },
                                            onNeedPermission = { _, req -> pendingPermissionTool = req }
                                        )
                                    } else {
                                        dao.insertChat(ChatMessageEntity(role = "assistant", text = turn.replyText))
                                        if (ttsEnabled) voiceManager.speak(turn.replyText)
                                    }
                                },
                                onFailure = { err ->
                                    val msg = "⚠️ ${err.message ?: "Local/Hybrid inference failed."}"
                                    dao.insertChat(ChatMessageEntity(role = "assistant", text = msg))
                                    if (ttsEnabled) voiceManager.speak(msg)
                                }
                            )
                        }
                    }
                } catch (e: Throwable) {
                    val msg = "⚠️ Error: ${e.message ?: "Unexpected failure"}"
                    dao.insertChat(ChatMessageEntity(role = "assistant", text = msg))
                } finally {
                    isSending = false
                    voiceManager.isBusyProcessing = false
                }
            }
        }
    }

    val systemSpeechDialogLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (spoken.isNotEmpty()) {
                submitPrompt(spoken)
            }
        }
    }

    val beginVoiceCapture: () -> Unit = {
        isJarvisExpanded = true
        try {
            voiceManager.startListening(
                onFinalResult = { spoken -> submitPrompt(spoken) },
                onFallbackToSystemDialog = {
                    try {
                        securityManager.suppressNextAutoLock()
                        systemSpeechDialogLauncher.launch(voiceManager.buildRecognizerIntent())
                    } catch (_: Throwable) {
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        val toolToRetry = pendingPermissionTool
        pendingPermissionTool = null
        if (toolToRetry != null && allGranted) {
            scope.launch {
                handleOutcome(
                    outcome = toolExecutor.handleToolRequest(toolToRetry, confirmed = false),
                    dao = dao,
                    speakReply = { if (ttsEnabled) voiceManager.speak(it) },
                    onNeedConfirm = { pendingConfirmation = it },
                    onNeedPermission = { _, _ -> }
                )
            }
        }
    }

    // Always-on silent wake-word listener ("Jarvis")
    LaunchedEffect(wakeEnabled) {
        val hasMic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (wakeEnabled && hasMic) {
            voiceManager.startWakeWordDetection { trailingCommand ->
                if (!trailingCommand.isNullOrBlank()) {
                    submitPrompt(trailingCommand)
                } else {
                    beginVoiceCapture()
                }
            }
        } else {
            voiceManager.stopWakeWordDetection()
        }
    }

    LaunchedEffect(messages.size, isJarvisExpanded) {
        if (messages.isNotEmpty() && isJarvisExpanded) {
            chatListState.animateScrollToItem(messages.lastIndex)
        }
    }

    // Select 8 home-screen apps (pinned first) + 4 bottom dock apps
    val homeGridApps = remember(allApps, pinnedPackages) {
        val sorted = allApps.sortedWith(
            compareByDescending<InstalledApp> { pinnedPackages.contains(it.packageName) }
                .thenBy { it.label.lowercase() }
        )
        sorted.take(8)
    }

    val dockApps = remember(allApps) {
        val preferredKeywords = listOf("phone", "dialer", "contacts", "settings", "camera", "instagram", "messages", "whatsapp")
        val picked = mutableListOf<InstalledApp>()
        for (kw in preferredKeywords) {
            val match = allApps.firstOrNull {
                it !in picked && (it.label.contains(kw, ignoreCase = true) || it.packageName.contains(kw, ignoreCase = true))
            }
            if (match != null && picked.size < 4) {
                picked.add(match)
            }
        }
        if (picked.size < 4) {
            allApps.filter { it !in picked }.take(4 - picked.size).forEach { picked.add(it) }
        }
        picked
    }

    // Main Wallpaper-Transparent Home Screen
    Box(modifier = Modifier.fillMaxSize()) {
        // Soft scrim so the clock, orb and dock stay readable over any wallpaper
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0x8A050B16),
                            Color(0x22050B16),
                            Color(0x99050B16)
                        )
                    )
                )
        )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // ============================================================
        // TOP ZONE: "JARVIS HERE" (Big Clock + Always-Ready Jarvis Hub)
        // ============================================================
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(if (isJarvisExpanded) 1f else 0.44f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Subtle top utility strip (Logs, Wake-Word Status, Settings, Lock)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .background(Color(0x880B1221), CircleShape)
                        .clickable {
                            val hasMic = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (!hasMic) {
                                (context as? Activity)?.let { act ->
                                    securityManager.suppressNextAutoLock()
                                    ActivityCompat.requestPermissions(act, arrayOf(Manifest.permission.RECORD_AUDIO), 1002)
                                }
                            } else {
                                wakeEnabled = !wakeEnabled
                                securityManager.setWakeWordEnabled(wakeEnabled)
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Hearing,
                        contentDescription = "Wake Word",
                        tint = if (isWakeActive) Color(0xFF34D399) else Color(0xFF94A3B8),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = if (isWakeActive) "\"JARVIS\" LISTENING" else "WAKE-WORD OFF",
                        color = if (isWakeActive) Color(0xFF34D399) else Color(0xFF94A3B8),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconButton(onClick = { showLogsSheet = true }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.History, contentDescription = "Logs & Vault", tint = Color(0xFFCBD5E1), modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = { showSetupSheet = true }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color(0xFFCBD5E1), modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = { securityManager.lock() }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.Lock, contentDescription = "Lock", tint = Color(0xFFF87171), modifier = Modifier.size(18.dp))
                    }
                }
            }

            if (!isJarvisExpanded) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = currentTimeStr,
                    color = Color.White,
                    fontSize = 54.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 1.sp,
                    style = TextStyle(
                        shadow = Shadow(
                            color = Color(0xCC0284C7),
                            offset = Offset(0f, 0f),
                            blurRadius = 28f
                        )
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = currentDateStr.uppercase(Locale.getDefault()),
                    color = Color(0xFF7DD3FC),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.6.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Voice-reactive Jarvis orb — the centrepiece of the home screen
                JarvisReactiveOrb(
                    isListening = isListening,
                    isSpeaking = isSpeaking,
                    isProcessing = isSending,
                    amplitude = voiceAmplitude,
                    sizeDp = 132f,
                    onClick = {
                        if (isListening) {
                            voiceManager.stopListening()
                        } else {
                            val hasMic = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasMic) {
                                beginVoiceCapture()
                            } else {
                                (context as? Activity)?.let { act ->
                                    securityManager.suppressNextAutoLock()
                                    ActivityCompat.requestPermissions(act, arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
                                }
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = when {
                        isListening -> "LISTENING"
                        isSpeaking -> "SPEAKING"
                        isSending -> "THINKING"
                        else -> "TAP TO SPEAK"
                    },
                    color = if (isListening) Color(0xFF34D399) else Color(0xFF7DD3FC),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.4.sp
                )
                Spacer(modifier = Modifier.height(10.dp))

                Surface(
                    onClick = { isJarvisExpanded = true },
                    shape = CircleShape,
                    color = Color(0x990B1221),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x5538BDF8)),
                    modifier = Modifier.height(34.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Keyboard,
                            contentDescription = "Type to J.A.R.V.I.S.",
                            tint = Color(0xFF7DD3FC),
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "TYPE",
                            color = Color(0xFF7DD3FC),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.8.sp
                        )
                    }
                }

                val lastAssistant = messages.lastOrNull { it.role == "assistant" }?.text
                if (isListening || !lastAssistant.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0x660B1221))
                            .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(14.dp))
                            .clickable { isJarvisExpanded = true }
                            .padding(horizontal = 14.dp, vertical = 9.dp)
                    ) {
                        Text(
                            text = if (isListening) (partialVoice.ifEmpty { "Listening..." }) else lastAssistant.orEmpty(),
                            color = Color(0xFFE2E8F0),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            } else {
                JarvisReactiveOrb(
                    isListening = isListening,
                    isSpeaking = isSpeaking,
                    isProcessing = isSending,
                    amplitude = voiceAmplitude,
                    sizeDp = 76f,
                    onClick = {
                        if (isListening) voiceManager.stopListening() else beginVoiceCapture()
                    }
                )
                Spacer(modifier = Modifier.height(6.dp))

                // Expanded Glassmorphic J.A.R.V.I.S. Chat Drawer
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color(0xD90D1526), RoundedCornerShape(22.dp))
                        .border(1.dp, Color(0x6638BDF8), RoundedCornerShape(22.dp))
                        .padding(12.dp)
                ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                        Text(
                            text = "J.A.R.V.I.S.",
                            color = Color(0xFF38BDF8),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                ttsEnabled = !ttsEnabled
                                securityManager.setTtsEnabled(ttsEnabled)
                                if (!ttsEnabled) voiceManager.stopSpeaking()
                            },
                            modifier = Modifier.size(26.dp)
                        ) {
                            Icon(
                                imageVector = if (ttsEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                                contentDescription = "Voice Toggle",
                                tint = if (ttsEnabled) Color(0xFF38BDF8) else Color(0xFF64748B),
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        if (true) {
                            IconButton(
                                onClick = { isJarvisExpanded = !isJarvisExpanded },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(
                                    imageVector = if (isJarvisExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = "Expand/Collapse Chat",
                                    tint = Color(0xFF94A3B8),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                // Latest reply preview when collapsed, or full scrollable chat when expanded
                if (!isJarvisExpanded) {
                    val lastAssistant = messages.lastOrNull { it.role == "assistant" }?.text
                        ?: "Say \"Jarvis\" or type a command below..."
                    Text(
                        text = lastAssistant,
                        color = Color(0xFFE2E8F0),
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { if (messages.isNotEmpty()) isJarvisExpanded = true }
                            .padding(vertical = 4.dp)
                    )
                } else {
                    LazyColumn(
                        state = chatListState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(messages, key = { it.id }) { msg ->
                            val isUser = msg.role == "user"
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                            ) {
                                Box(
                                    modifier = Modifier
                                        .widthIn(max = 280.dp)
                                        .background(
                                            if (isUser) Color(0xFF0284C7) else Color(0xFF1E293B),
                                            RoundedCornerShape(14.dp)
                                        )
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(text = msg.text, color = Color.White, fontSize = 13.sp)
                                }
                            }
                        }

                        if (isSending) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .background(Color(0xFF1E293B), RoundedCornerShape(14.dp))
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = Color(0xFF38BDF8)
                                    )
                                    Text(
                                        text = "J.A.R.V.I.S. is processing...",
                                        color = Color(0xFF38BDF8),
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }

                        if (pendingCloudPrivacyPrompt != null) {
                            item {
                                val (promptToSend, summary) = pendingCloudPrivacyPrompt!!
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF1E1B2E), RoundedCornerShape(12.dp))
                                        .border(1.dp, Color(0xFFA855F7), RoundedCornerShape(12.dp))
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text("Cloud Privacy Check", color = Color(0xFFD8B4FE), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Text(summary, color = Color.White, fontSize = 12.sp)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { pendingCloudPrivacyPrompt = null }, modifier = Modifier.weight(1f)) {
                                            Text("Deny", fontSize = 12.sp)
                                        }
                                        Button(
                                            onClick = {
                                                pendingCloudPrivacyPrompt = null
                                                isSending = true
                                                scope.launch {
                                                    runCloudQuery(promptToSend)
                                                    isSending = false
                                                }
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Approve", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }

                        if (pendingConfirmation != null) {
                            item {
                                val pending = pendingConfirmation!!
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF172033), RoundedCornerShape(12.dp))
                                        .border(1.dp, Color(0xFFF59E0B), RoundedCornerShape(12.dp))
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(pending.title, color = Color(0xFFFBBF24), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Text(pending.details, color = Color.White, fontSize = 12.sp)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = {
                                                pendingConfirmation = null
                                                scope.launch { toolExecutor.logDenied(pending) }
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Deny", fontSize = 12.sp)
                                        }
                                        Button(
                                            onClick = {
                                                pendingConfirmation = null
                                                scope.launch {
                                                    handleOutcome(
                                                        outcome = toolExecutor.handleToolRequest(pending.request, confirmed = true),
                                                        dao = dao,
                                                        speakReply = { if (ttsEnabled) voiceManager.speak(it) },
                                                        onNeedConfirm = { pendingConfirmation = it },
                                                        onNeedPermission = { perms, req ->
                                                            pendingPermissionTool = req
                                                            permissionLauncher.launch(perms.toTypedArray())
                                                        }
                                                    )
                                                }
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Approve", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (isListening || voiceError != null) {
                    Text(
                        text = if (isListening) (partialVoice.ifEmpty { "Listening..." }) else voiceError.orEmpty(),
                        color = if (isListening) Color(0xFF38BDF8) else Color(0xFFF87171),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                // Embedded Prompt + Mic Bar inside the top Jarvis Hub
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Command J.A.R.V.I.S...", color = Color(0xFF94A3B8), fontSize = 13.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF38BDF8),
                            unfocusedBorderColor = Color(0xFF334155),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0xAA0F172A),
                            unfocusedContainerColor = Color(0xAA0F172A)
                        )
                    )

                    FilledIconButton(
                        onClick = {
                            if (isListening) {
                                voiceManager.stopListening()
                            } else {
                                val hasMic = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasMic) {
                                    beginVoiceCapture()
                                } else {
                                    (context as? Activity)?.let { act ->
                                        securityManager.suppressNextAutoLock()
                                        ActivityCompat.requestPermissions(act, arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
                                    }
                                }
                            }
                        },
                        modifier = Modifier.size(44.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (isListening) Color(0xFFDC2626) else Color(0xFF1E293B),
                            contentColor = if (isListening) Color.White else Color(0xFF38BDF8)
                        )
                    ) {
                        Icon(
                            imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = "Mic",
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    FilledIconButton(
                        onClick = { submitPrompt(input) },
                        enabled = input.isNotBlank() && !isSending,
                        modifier = Modifier.size(44.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color(0xFF0284C7),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", modifier = Modifier.size(18.dp))
                    }
                }
                }
            }
        }

        // ============================================================
        // BOTTOM ZONE: "EVERYTHING ELSE SAME" (Media Card + Grid + Search + Dock)
        // ============================================================
        if (!isJarvisExpanded) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Teal/Cyan Glass Media Widget matching your screenshot
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xE60D9488), Color(0xE62DD4BF), Color(0xD90F766E))
                            ),
                            RoundedCornerShape(22.dp)
                        )
                        .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(22.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0x66FFFFFF), Color(0x22FFFFFF))
                                ),
                                RoundedCornerShape(14.dp)
                            )
                            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.White)
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isMediaPlaying) "Now Playing" else "Media",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            letterSpacing = 0.4.sp
                        )
                        Text(
                            text = if (isMediaPlaying) "Tap play, pause or skip" else "Nothing is playing",
                            color = Color(0xE6ECFEFF),
                            fontSize = 10.sp
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Surface(
                                onClick = {
                                    audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                                    audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                                    isMediaPlaying = !isMediaPlaying
                                },
                                shape = CircleShape,
                                color = Color.White
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isMediaPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = "Play/Pause",
                                        tint = Color(0xFF0F172A),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = if (isMediaPlaying) "Pause" else "Play",
                                        color = Color(0xFF0F172A),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Surface(
                                onClick = {
                                    audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT))
                                    audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT))
                                },
                                shape = CircleShape,
                                color = Color.White
                            ) {
                                Box(modifier = Modifier.padding(6.dp)) {
                                    Icon(
                                        imageVector = Icons.Default.FastForward,
                                        contentDescription = "Next",
                                        tint = Color(0xFF0F172A),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 4x2 Monochrome/Space-Framed Pinned App Grid
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    homeGridApps.chunked(4).forEach { rowApps ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            rowApps.forEach { app ->
                                SpaceAppIcon(
                                    app = app,
                                    monochrome = monochromeIcons,
                                    label = app.label,
                                    onLaunch = { appRepo.launchApp(app.packageName) },
                                    onLongPress = { showAppDrawerSheet = true }
                                )
                            }
                        }
                    }
                }

                // Center Dark "Search" Pill (Opens Full App Drawer)
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.64f)
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xE60F172A), Color(0xE6111C2E))
                            )
                        )
                        .border(1.dp, Color(0x33FFFFFF), CircleShape)
                        .clickable { showAppDrawerSheet = true }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Search", tint = Color(0xFF7DD3FC), modifier = Modifier.size(15.dp))
                        Text("Search apps", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp)
                    }
                }

                // 4-Icon Bottom Dock
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    dockApps.forEach { app ->
                        SpaceAppIcon(
                            app = app,
                            monochrome = monochromeIcons,
                            onLaunch = { appRepo.launchApp(app.packageName) },
                            onLongPress = { showAppDrawerSheet = true }
                        )
                    }
                }
            }
        }
    }
    }

    // Full App Drawer Modal Sheet (Opened by tapping Search Pill)
    if (showAppDrawerSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAppDrawerSheet = false },
            containerColor = Color(0xFF0B1220),
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(top = 10.dp, bottom = 4.dp)
                        .size(width = 44.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(Color(0x33FFFFFF))
                )
            }
        ) {
            val query = searchQuery.trim()
            val filtered = remember(allApps, query) {
                (if (query.isEmpty()) allApps else allApps.filter {
                    it.label.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
                }).sortedBy { it.label.lowercase() }
            }
            val pinnedApps = remember(filtered, pinnedPackages, query) {
                if (query.isEmpty()) filtered.filter { pinnedPackages.contains(it.packageName) } else emptyList()
            }
            val sectionApps = remember(filtered, pinnedPackages, query) {
                if (query.isEmpty()) filtered.filterNot { pinnedPackages.contains(it.packageName) } else filtered
            }
            val sections = remember(sectionApps) {
                sectionApps
                    .groupBy { app ->
                        app.label.trim().firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() }?.toString() ?: "#"
                    }
                    .toSortedMap()
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
                    .padding(horizontal = 18.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("All Apps", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0x1F38BDF8))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "${filtered.size} APPS",
                            color = Color(0xFF7DD3FC),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search apps", color = Color(0xFF64748B), fontSize = 14.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search", tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF38BDF8),
                        unfocusedBorderColor = Color(0xFF1E293B),
                        focusedContainerColor = Color(0xFF111C2E),
                        unfocusedContainerColor = Color(0xFF111C2E),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                if (filtered.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No apps match \"$query\"", color = Color(0xFF64748B), fontSize = 13.sp)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (pinnedApps.isNotEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "header_pinned") {
                                AppDrawerSectionLabel("PINNED")
                            }
                            items(pinnedApps, key = { "pinned_${it.packageName}" }) { app ->
                                DrawerAppTile(
                                    app = app,
                                    isPinned = true,
                                    monochrome = monochromeIcons,
                                    onLaunch = {
                                        showAppDrawerSheet = false
                                        appRepo.launchApp(app.packageName)
                                    },
                                    onTogglePin = {
                                        scope.launch { dao.unpinApp(app.packageName) }
                                    }
                                )
                            }
                        }

                        sections.forEach { (letter, apps) ->
                            item(span = { GridItemSpan(maxLineSpan) }, key = "header_$letter") {
                                AppDrawerSectionLabel(letter)
                            }
                            items(apps, key = { it.packageName }) { app ->
                                val isPinned = pinnedPackages.contains(app.packageName)
                                DrawerAppTile(
                                    app = app,
                                    isPinned = isPinned,
                                    monochrome = monochromeIcons,
                                    onLaunch = {
                                        showAppDrawerSheet = false
                                        appRepo.launchApp(app.packageName)
                                    },
                                    onTogglePin = {
                                        scope.launch {
                                            if (isPinned) dao.unpinApp(app.packageName)
                                            else dao.pinApp(PinnedAppEntity(app.packageName))
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLogsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showLogsSheet = false },
            containerColor = Color(0xFF111827)
        ) {
            Box(modifier = Modifier.fillMaxHeight(0.8f)) {
                VaultLogsPanel(dao = dao)
            }
        }
    }

    if (showSetupSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSetupSheet = false },
            containerColor = Color(0xFF111827)
        ) {
            SetupAndHyperOsSheet(
                context = context,
                securityManager = securityManager,
                localEngine = localEngine,
                monochromeIcons = monochromeIcons,
                onMonochromeChanged = {
                    monochromeIcons = it
                    securityManager.setMonochromeIconsEnabled(it)
                },
                onSaved = { showSetupSheet = false }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpaceAppIcon(
    app: InstalledApp,
    monochrome: Boolean,
    size: Dp = 56.dp,
    label: String? = null,
    onLaunch: () -> Unit,
    onLongPress: () -> Unit
) {
    val bitmap = remember(app.packageName) { drawableToBitmap(app.icon) }
    val grayscaleFilter = remember {
        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "app_press"
    )
    val shape = RoundedCornerShape(if (size > 70.dp) 20.dp else 17.dp)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(size)
                .scale(pressScale)
                .clip(shape)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xE6142137), Color(0xE6091120))
                    )
                )
                .border(1.dp, Color(0x44CBD5E1), shape)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    onClick = onLaunch,
                    onLongClick = onLongPress
                )
                .padding(size * 0.2f),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = app.label,
                colorFilter = if (monochrome) grayscaleFilter else null,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (label != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                color = Color(0xFFCBD5E1),
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(size + 10.dp)
            )
        }
    }
}

@Composable
private fun DrawerAppTile(
    app: InstalledApp,
    isPinned: Boolean,
    monochrome: Boolean,
    onLaunch: () -> Unit,
    onTogglePin: () -> Unit
) {
    val haptics = LocalHapticFeedback.current

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            SpaceAppIcon(
                app = app,
                monochrome = monochrome,
                size = 58.dp,
                onLaunch = onLaunch,
                onLongPress = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onTogglePin()
                }
            )
            if (isPinned) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 3.dp, y = (-3).dp)
                        .size(17.dp)
                        .background(Color(0xFF0284C7), CircleShape)
                        .border(1.dp, Color(0xFF0B1220), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = "Pinned",
                        tint = Color.White,
                        modifier = Modifier.size(9.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = app.label,
            color = Color(0xFFE2E8F0),
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun AppDrawerSectionLabel(text: String) {
    Text(
        text = text,
        color = Color(0xFF64748B),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.6.sp,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
}

private suspend fun handleOutcome(
    outcome: ToolExecutionOutcome,
    dao: com.privateai.jarvis.data.VaultDao,
    speakReply: (String) -> Unit,
    onNeedConfirm: (PendingToolAction) -> Unit,
    onNeedPermission: (List<String>, ToolRequest) -> Unit
) {
    when (outcome) {
        is ToolExecutionOutcome.Completed -> {
            dao.insertChat(ChatMessageEntity(role = "assistant", text = outcome.message))
            speakReply(outcome.message)
        }
        is ToolExecutionOutcome.NeedsConfirmation -> {
            speakReply("Confirmation required: ${outcome.pendingAction.title}")
            onNeedConfirm(outcome.pendingAction)
        }
        is ToolExecutionOutcome.NeedsPermission -> {
            onNeedPermission(outcome.permissions, outcome.retryRequest)
        }
    }
}

@Composable
private fun SetupAndHyperOsSheet(
    context: Context,
    securityManager: SecurityManager,
    localEngine: LocalLlmEngine,
    monochromeIcons: Boolean,
    onMonochromeChanged: (Boolean) -> Unit,
    onSaved: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val isBatteryExempt = pm.isIgnoringBatteryOptimizations(context.packageName)
    val hasAllFiles = Environment.isExternalStorageManager()

    var userName by remember { mutableStateOf(securityManager.getUserName()) }
    var baseUrl by remember { mutableStateOf(securityManager.getApiBaseUrl()) }
    var modelName by remember { mutableStateOf(securityManager.getApiModel()) }
    var apiKey by remember { mutableStateOf(securityManager.getApiKey()) }
    var localPath by remember { mutableStateOf(securityManager.getLocalModelPath()) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    val detectedModels = remember { localEngine.findCandidateModels() }

    val pickedUri by MainActivity.pickedModelUri.collectAsState()
    LaunchedEffect(pickedUri) {
        val uri = pickedUri ?: return@LaunchedEffect
        MainActivity.consumePickedModelUri()
        importStatus = "Copying model to private vault (may take ~10s)..."
        val res = localEngine.importModelFromUri(uri)
        res.fold(
            onSuccess = { copiedPath ->
                localPath = copiedPath
                importStatus = "Imported successfully!"
            },
            onFailure = { err ->
                importStatus = "Import failed: ${err.message}"
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Home Screen & Voice Style", color = Color(0xFF38BDF8), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Monochrome Space App Icons", color = Color.White, fontSize = 14.sp)
            Switch(checked = monochromeIcons, onCheckedChange = onMonochromeChanged)
        }

        HorizontalDivider(color = Color(0xFF1E293B))

        Text("Local Offline Model (MediaPipe .task / .bin)", color = Color(0xFF34D399), fontSize = 17.sp, fontWeight = FontWeight.Bold)

        Button(
            onClick = {
                val activity = context as? Activity
                if (activity != null) {
                    securityManager.suppressNextAutoLock()
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    @Suppress("DEPRECATION")
                    activity.startActivityForResult(intent, MainActivity.REQ_CODE_PICK_MODEL)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
        ) {
            Text("Pick & Import .task / .bin Model File")
        }

        if (importStatus != null) {
            Text(text = importStatus!!, color = Color(0xFF38BDF8), fontSize = 12.sp)
        }

        detectedModels.forEach { file ->
            OutlinedButton(onClick = { localPath = file.absolutePath }, modifier = Modifier.fillMaxWidth()) {
                Text("Use: ${file.name} (${file.length() / (1024 * 1024)} MB)")
            }
        }

        OutlinedTextField(
            value = localPath,
            onValueChange = { localPath = it },
            label = { Text("Local Model File Path") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        HorizontalDivider(color = Color(0xFF1E293B))

        Text("Cloud Brain (Keystore Encrypted)", color = Color(0xFF38BDF8), fontSize = 17.sp, fontWeight = FontWeight.Bold)

        OutlinedTextField(value = userName, onValueChange = { userName = it }, label = { Text("Your Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("API Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = modelName, onValueChange = { modelName = it }, label = { Text("Model ID (e.g. gemini-3.5-flash-lite)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())

        Button(
            onClick = {
                securityManager.setLocalModelPath(localPath)
                securityManager.setUserName(userName)
                securityManager.setApiBaseUrl(baseUrl)
                securityManager.setApiModel(modelName)
                securityManager.setApiKey(apiKey)
                onSaved()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
        ) {
            Text("Save All Settings")
        }

        HorizontalDivider(color = Color(0xFF1E293B))

        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) {
            Text("1. Set Default Home Launcher")
        }
        OutlinedButton(
            onClick = {
                context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                })
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isBatteryExempt) "2. Battery Unrestricted (Done)" else "2. Disable Battery Optimization")
        }
        OutlinedButton(
            onClick = {
                try {
                    context.startActivity(Intent().apply {
                        component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                    })
                } catch (_: Exception) {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:${context.packageName}") })
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("3. Enable HyperOS Autostart")
        }
        OutlinedButton(
            onClick = {
                try {
                    context.startActivity(Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                        setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                        putExtra("extra_pkgname", context.packageName)
                    })
                } catch (_: Exception) {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:${context.packageName}") })
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("4. HyperOS Background Pop-up & App Permissions")
        }
        OutlinedButton(
            onClick = {
                try {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply { data = Uri.parse("package:${context.packageName}") })
                } catch (_: Exception) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (hasAllFiles) "5. All Files Access Granted (Done)" else "5. Grant All Files Access")
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

private fun drawableToBitmap(drawable: Drawable): Bitmap {
    val width = drawable.intrinsicWidth.coerceAtLeast(1).coerceAtMost(144)
    val height = drawable.intrinsicHeight.coerceAtLeast(1).coerceAtMost(144)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}


@Composable
private fun JarvisReactiveOrb(
    isListening: Boolean,
    isSpeaking: Boolean,
    isProcessing: Boolean,
    amplitude: Float,
    sizeDp: Float = 84f,
    onClick: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "jarvis_orb")
    val breath by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath"
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spin"
    )
    val counterSpin by transition.animateFloat(
        initialValue = 0f,
        targetValue = -360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "counter_spin"
    )
    val shimmer by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmer"
    )

    val active = isListening || isSpeaking || isProcessing
    val accent = when {
        isListening -> Color(0xFF34D399)
        isSpeaking -> Color(0xFF38BDF8)
        isProcessing -> Color(0xFFA78BFA)
        else -> Color(0xFF38BDF8)
    }

    // Live mic amplitude is the heartbeat of the whole orb
    val energyTarget = when {
        isListening -> (0.3f + amplitude).coerceIn(0.3f, 1.25f)
        isSpeaking -> 0.55f + shimmer * 0.4f
        isProcessing -> 0.5f
        else -> 0f
    }
    val energy by animateFloatAsState(
        targetValue = energyTarget,
        animationSpec = tween(durationMillis = 110),
        label = "energy"
    )
    val amplitudeSmooth by animateFloatAsState(
        targetValue = amplitude.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 90),
        label = "amplitude_smooth"
    )

    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val minDim = size.minDimension
            val base = minDim * 0.27f
            val pulse = if (active) 1f + energy * 0.16f else breath

            // Ambient bloom
            val haloRadius = base * 1.9f * pulse
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.34f),
                        accent.copy(alpha = 0.10f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = haloRadius
                ),
                radius = haloRadius,
                center = center
            )

            // Counter-rotating orbit rings while the assistant is active
            if (active) {
                val ringRadius = base * 1.62f
                val strokeWidth = minDim * 0.012f
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(Color.Transparent, accent.copy(alpha = 0.55f), Color.Transparent),
                        center
                    ),
                    startAngle = spin,
                    sweepAngle = 110f,
                    useCenter = false,
                    topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
                    size = Size(ringRadius * 2, ringRadius * 2),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(Color.Transparent, Color(0xFF7DD3FC).copy(alpha = 0.45f), Color.Transparent),
                        center
                    ),
                    startAngle = counterSpin,
                    sweepAngle = 70f,
                    useCenter = false,
                    topLeft = Offset(center.x - ringRadius * 0.86f, center.y - ringRadius * 0.86f),
                    size = Size(ringRadius * 1.72f, ringRadius * 1.72f),
                    style = Stroke(width = strokeWidth * 0.8f, cap = StrokeCap.Round)
                )
            }

            // Iridescent lobes orbiting the core
            val lobeRadius = base * 0.42f * (1f + energy * 0.5f)
            val angle1 = spin * (PI / 180f)
            val lobe1 = Offset(
                center.x + cos(angle1) * base * 0.5f,
                center.y + sin(angle1) * base * 0.5f
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xCC22D3EE), Color(0x553B82F6), Color.Transparent),
                    center = lobe1,
                    radius = lobeRadius
                ),
                radius = lobeRadius,
                center = lobe1
            )

            val angle2 = counterSpin * (PI / 180f)
            val lobe2 = Offset(
                center.x + cos(angle2) * base * 0.42f,
                center.y + sin(angle2) * base * 0.42f
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.75f),
                        Color(0x446366F1),
                        Color.Transparent
                    ),
                    center = lobe2,
                    radius = lobeRadius * 0.9f
                ),
                radius = lobeRadius * 0.9f,
                center = lobe2
            )

            // Diffusion shell
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0x55FFFFFF),
                        accent.copy(alpha = 0.35f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = base * 0.95f * pulse
                ),
                radius = base * 0.95f * pulse,
                center = center
            )

            // Luminous core, swelling with your voice
            val coreRadius = base * (0.62f + energy * 0.18f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White,
                        Color(0xFFBFE9FF),
                        accent,
                        accent.copy(alpha = 0f)
                    ),
                    center = center,
                    radius = coreRadius
                ),
                radius = coreRadius,
                center = center
            )

            // Specular highlight
            drawCircle(
                color = Color(0x66FFFFFF),
                radius = coreRadius * 0.22f,
                center = Offset(
                    center.x - coreRadius * 0.3f,
                    center.y - coreRadius * 0.32f
                )
            )

            // Amplitude ring — expands and brightens with your speech
            if (isListening) {
                drawCircle(
                    color = accent.copy(alpha = 0.35f + amplitudeSmooth * 0.45f),
                    radius = base * (1.12f + amplitudeSmooth * 0.5f),
                    center = center,
                    style = Stroke(width = minDim * 0.008f)
                )
            }
        }
    }
}
