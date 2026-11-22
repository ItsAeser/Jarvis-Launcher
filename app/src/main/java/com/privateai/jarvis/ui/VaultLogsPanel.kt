package com.privateai.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privateai.jarvis.data.VaultDao
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun VaultLogsPanel(dao: VaultDao) {
    val logs by dao.observeLogs().collectAsState(initial = emptyList())
    val notes by dao.observeNotes().collectAsState(initial = emptyList())
    var subTab by remember { mutableIntStateOf(0) } // 0 = Action Log, 1 = Encrypted Notes
    val fmt = remember { SimpleDateFormat("dd MMM, HH:mm:ss", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FilterChip(
                selected = subTab == 0,
                onClick = { subTab = 0 },
                label = { Text("Action Log (${logs.size})") }
            )
            FilterChip(
                selected = subTab == 1,
                onClick = { subTab = 1 },
                label = { Text("Encrypted Notes (${notes.size})") }
            )
        }

        if (subTab == 0) {
            if (logs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No tool actions logged yet.", color = Color(0xFF64748B))
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(logs, key = { it.id }) { log ->
                        val statusColor = when (log.status) {
                            "EXECUTED" -> Color(0xFF34D399)
                            "DENIED" -> Color(0xFFFBBF24)
                            else -> Color(0xFFF87171)
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF111827), RoundedCornerShape(12.dp))
                                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = log.toolName.uppercase(Locale.ROOT),
                                    color = Color(0xFF38BDF8),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = log.status,
                                    color = statusColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = log.summary, color = Color.White, fontSize = 13.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = fmt.format(Date(log.timestamp)),
                                color = Color(0xFF64748B),
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        } else {
            if (notes.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Ask J.A.R.V.I.S. to save a note to see it here.", color = Color(0xFF64748B))
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(notes, key = { it.id }) { note ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF111827), RoundedCornerShape(12.dp))
                                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Text(
                                text = note.title,
                                color = Color(0xFF38BDF8),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = note.content, color = Color.White, fontSize = 13.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = fmt.format(Date(note.createdAt)),
                                color = Color(0xFF64748B),
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
