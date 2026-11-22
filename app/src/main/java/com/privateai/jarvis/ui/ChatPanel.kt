package com.privateai.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privateai.jarvis.ai.CloudChatClient
import com.privateai.jarvis.data.ChatMessageEntity
import com.privateai.jarvis.data.VaultDao
import kotlinx.coroutines.launch

@Composable
fun ChatPanel(
    dao: VaultDao,
    chatClient: CloudChatClient,
    onOpenSettings: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val messages by dao.observeChat().collectAsState(initial = emptyList())
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }

    val greetingText = remember(messages.size) { chatClient.buildGreeting() }

    LaunchedEffect(messages.size) {
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
                    hasHistory = messages.isNotEmpty(),
                    onClearHistory = {
                        scope.launch { dao.clearChat() }
                    },
                    onOpenSettings = onOpenSettings
                )
            }

            items(messages, key = { it.id }) { msg ->
                ChatBubble(message = msg)
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
                            text = "J.A.R.V.I.S. is thinking...",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask J.A.R.V.I.S. anything...", color = Color(0xFF64748B)) },
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
                    val prompt = input.trim()
                    if (prompt.isNotEmpty() && !isSending) {
                        input = ""
                        isSending = true
                        scope.launch {
                            dao.insertChat(ChatMessageEntity(role = "user", text = prompt))
                            val result = chatClient.sendMessage(messages, prompt)
                            val replyText = result.getOrElse { err ->
                                "⚠️ ${err.message ?: "Request failed."}"
                            }
                            dao.insertChat(ChatMessageEntity(role = "assistant", text = replyText))
                            isSending = false
                        }
                    }
                },
                enabled = input.isNotBlank() && !isSending,
                modifier = Modifier.size(52.dp),
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

@Composable
private fun GreetingCard(
    greeting: String,
    hasHistory: Boolean,
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
                    text = "J.A.R.V.I.S. CORE",
                    color = Color(0xFF38BDF8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
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

        Text(
            text = greeting,
            color = Color(0xFFE2E8F0),
            fontSize = 14.sp,
            lineHeight = 20.sp
        )

        if (greeting.contains("isn't configured")) {
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
