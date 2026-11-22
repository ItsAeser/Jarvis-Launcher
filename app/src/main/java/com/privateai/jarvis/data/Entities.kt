package com.privateai.jarvis.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String, // "user" or "assistant"
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "action_logs")
data class ActionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val toolName: String,
    val summary: String,
    val status: String, // "EXECUTED", "DENIED", "FAILED"
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "pinned_apps")
data class PinnedAppEntity(
    @PrimaryKey val packageName: String,
    val pinnedAt: Long = System.currentTimeMillis()
)
