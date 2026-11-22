package com.privateai.jarvis.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.privateai.jarvis.ai.CloudChatClient
import com.privateai.jarvis.ai.ToolRequest
import com.privateai.jarvis.data.ChatMessageEntity
import com.privateai.jarvis.data.VaultDao
import com.privateai.jarvis.security.SecurityManager
import com.privateai.jarvis.tools.PendingToolAction
import com.privateai.jarvis.tools.ToolExecutionOutcome
import com.privateai.jarvis.tools.ToolExecutor
import com.privateai.jarvis.voice.VoiceAssistantManager
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun ChatPanel(
    dao: VaultDao,
    securityManager: SecurityManager,
    chatClient: CloudChatClient,
    toolExecutor: ToolExecutor,
    voiceManager: VoiceAssistantManager,
    installedAppNames: List<String>,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages by dao.observeChat().collectAsState(initial = emptyList())
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var ttsEnabled by remember { mutableStateOf(securityManager.isTtsEnabled()) }
    var pendingConfirmation by remember { mutableStateOf<PendingToolAction?>(null) }
    var pendingPermissionTool by remember { mutableStateOf<ToolRequest?>(null) }

    val isListening by voiceManager.isListening.collectAsState()
    val partialVoice by voiceManager.partialText.collectAsState()
    val voiceError by voiceManager.voiceError.collectAsState()

    val submitPrompt: (String) -> Unit = { rawText ->
        val prompt = rawText.trim()
        if (prompt.isNotEmpty() && !isSending) {
            input = ""
            isSending = true
            scope.launch {
                dao.insertChat(ChatMessageEntity(role = "user", text = prompt))

                val localOpenMatch = Regex("^(?:open|launch|start)\\s+(.+)$", RegexOption.IGNORE_CASE)
                    .matchEntire(prompt)
                if (localOpenMatch != null) {
                    val targetApp = localOpenMatch.groupValues[1].trim()
                    val req = ToolRequest(
                        tool = "open_app",
                        args = JSONObject().put("app_name", targetApp)
                    )
                    val outcome = toolExecutor.handleToolRequest(req, confirmed = false)
                    handleOutcome(
                        outcome = outcome,
                        dao = dao,
                        speakReply = { if (ttsEnabled) voiceManager.speak(it) },
                        onNeedConfirm = { pendingConfirmation = it },
                        onNeedPermission = { _, _ -> }
                    )
                    isSending = false
                    return@launch
                }

                val result = chatClient.sendMessage(messages, prompt, installedAppNames)
                result.fold(
                    onSuccess = { turn ->
                        if (turn.toolRequest != null) {
                            val outcome = toolExecutor.handleToolRequest(
                                request = turn.toolRequest,
                                confirmed = false
                            )
                            handleOutcome(
                                outcome = outcome,
                                dao = dao,
                                speakReply = { if (ttsEnabled) voiceManager.speak(it) },
                                onNeedConfirm = { pendingConfirmation = it },
                                onNeedPermission = { perms, req ->
                                    pendingPermissionTool = req
                                }
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
                isSending = false
            }
        }
    }

    // Fallback system speech dialog launcher (guaranteed crash-free on HyperOS 3 / Android 16)
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
        voiceManager.startListening(
            onFinalResult = { spoken -> submitPrompt(spoken) },
            onFallbackToSystemDialog = {
                try {
                    systemSpeechDialogLauncher.launch(voiceManager.buildRecognizerIntent())
                } catch (_: Throwable) {
                    scope.launch {
                        dao.insertChat(
                            ChatMessageEntity(
                                role = "assistant",
                                text = "⚠️ No speech recognition app found. Please install or enable 'Speech Recognition and Synthesis from Google' in Play Store."
                            )
                        )
                    }
                }
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        val toolToRetry = pendingPermissionTool
        pendingPermissionTool = null
        if (toolToRetry != null) {
            scope.launch {
                if (allGranted) {
                    handleOutcome(
                        outcome = toolExecutor.handleToolRequest(toolToRetry, confirmed = false),
                        dao = dao,
                        speakReply = { if (ttsEnabled) voiceManager.speak(it) },
                        onNeedConfirm = { pendingConfirmation = it },
                        onNeedPermission = { _, _ -> }
                    )
                } else {
                    val deniedMsg = "⚠️ Permission denied. I cannot complete that action without Android's permission."
                    dao.insertChat(ChatMessageEntity(role = "assistant", text = deniedMsg))
                    if (ttsEnabled) voiceManager.speak(deniedMsg)
                }
            }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            beginVoiceCapture()
        } else {
            scope.launch {
                dao.insertChat(
                    ChatMessageEntity(
                        role = "assistant",
                        text = "⚠️ Microphone permission denied. Enable Microphone in App Info to use voice commands."
                    )
                )
            }
        }
    }

    val greetingText = remember(messages.size) { chatClient.buildGreeting() }

    LaunchedEffect(Unit) {
        if (ttsEnabled && messages.isEmpty()) {
            voiceManager.speak(greetingText)
        }
    }

    LaunchedEffect(messages.size, pendingConfirmation) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item(key = "greeting_header") {
                GreetingCard(
                    greeting = greetingText,
                    ttsEnabled = ttsEnabled,
                    hasHistory = messages.isNotEmpty(),
                    onToggleTts = {
                        ttsEnabled = !ttsEnabled
                        securityManager.setTtsEnabled(ttsEnabled)
                        if (!ttsEnabled) voiceManager.stopSpeaking()
                    },
                    onClearHistory = { scope.launch { dao.clearChat() } },
                    onOpenSettings = onOpenSettings
                )
            }

            items(messages, key = { it.id }) { msg ->
                ChatBubble(message = msg)
            }

            if (pendingConfirmation != null) {
                item(key = "confirmation_card") {
                    val pending = pendingConfirmation!!
                    ConfirmationCard(
                        action = pending,
                        onApprove = {
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
                        onDeny = {
                            pendingConfirmation = null
                            scope.launch {
                                toolExecutor.logDenied(pending)
                                val cancelMsg = "Action cancelled and logged."
                                dao.insertChat(ChatMessageEntity(role = "assistant", text = cancelMsg))
                                if (ttsEnabled) voiceManager.speak(cancelMsg)
                            }
                        }
                    )
                }
            }

            if (isSending) {
                item(key = "typing_indicator") {
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF38BDF8)
                        )
                        Text(
                            text = "J.A.R.V.I.S. is processing...",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        if (isListening || voiceError != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF172033), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isListening) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFFF87171)
                    )
                    Text(
                        text = if (partialVoice.isNotEmpty()) partialVoice else "Listening... speak your command",
                        color = Color(0xFF38BDF8),
                        fontSize = 13.sp
                    )
                } else if (voiceError != null) {
                    Text(
                        text = voiceError!!,
                        color = Color(0xFFF87171),
                        fontSize = 12.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask or tap mic...", color = Color(0xFF64748B)) },
                maxLines = 4,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF38BDF8),
                    unfocusedBorderColor = Color(0xFF1E293B),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color(0xFF111827),
                    unfocusedContainerColor = Color(0xFF111827)
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
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                },
                modifier = Modifier.size(50.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (isListening) Color(0xFFDC2626) else Color(0xFF1E293B),
                    contentColor = if (isListening) Color.White else Color(0xFF38BDF8)
                )
            ) {
                Icon(
                    imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = if (isListening) "Stop Listening" else "Voice Command"
                )
            }

            FilledIconButton(
                onClick = { submitPrompt(input) },
                enabled = input.isNotBlank() && !isSending,
                modifier = Modifier.size(50.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color(0xFF0284C7),
                    contentColor = Color.White
                )
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

private suspend fun handleOutcome(
    outcome: ToolExecutionOutcome,
    dao: VaultDao,
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
private fun ConfirmationCard(
    action: PendingToolAction,
    onApprove: () -> Unit,
    onDeny: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF172033), RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFFF59E0B), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.Security, contentDescription = null, tint = Color(0xFFF59E0B))
            Text(
                text = action.title,
                color = Color(0xFFFBBF24),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }

        Text(
            text = action.details,
            color = Color.White,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onDeny,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF87171))
            ) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Deny")
            }

            Button(
                onClick = onApprove,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Approve")
            }
        }
    }
}

@Composable
private fun GreetingCard(
    greeting: String,
    ttsEnabled: Boolean,
    hasHistory: Boolean,
    onToggleTts: () -> Unit,
    onClearHistory: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF111827), RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFF1E3A5F), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "J.A.R.V.I.S. VOICE + CORE",
                    color = Color(0xFF38BDF8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.3.sp
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onToggleTts,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = if (ttsEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                        contentDescription = "Toggle Voice Replies",
                        tint = if (ttsEnabled) Color(0xFF38BDF8) else Color(0xFF64748B),
                        modifier = Modifier.size(18.dp)
                    )
                }

                if (hasHistory) {
                    IconButton(
                        onClick = onClearHistory,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Clear encrypted chat",
                            tint = Color(0xFF64748B),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        Text(
            text = greeting,
            color = Color(0xFFE2E8F0),
            fontSize = 14.sp,
            lineHeight = 20.sp
        )

        if (greeting.contains("configure your Cloud AI key")) {
            TextButton(
                onClick = onOpenSettings,
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("Configure Cloud API Key →", color = Color(0xFF38BDF8), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessageEntity) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 310.dp)
                .background(
                    color = if (isUser) Color(0xFF0369A1) else Color(0xFF1E293B),
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp
                    )
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = message.text,
                color = Color.White,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }
    }
}
