package com.privateai.jarvis.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.privateai.jarvis.security.SecurityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class LocalLlmEngine(
    private val context: Context,
    private val securityManager: SecurityManager
) {
    private var llmInference: LlmInference? = null
    private var loadedPath: String? = null
    private val mutex = Mutex()

    private val _modelStatus = MutableStateFlow("Not loaded")
    val modelStatus: StateFlow<String> = _modelStatus.asStateFlow()

    fun findCandidateModels(): List<File> {
        val dirs = listOf(
            File("/sdcard/Download"),
            File("/sdcard/jarvis launcher"),
            context.getExternalFilesDir(null)
        ).filterNotNull()

        val found = mutableListOf<File>()
        for (dir in dirs) {
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { f ->
                    val lower = f.name.lowercase()
                    if (f.isFile && (lower.endsWith(".bin") || lower.endsWith(".task") || lower.endsWith(".tflite"))) {
                        if (f.length() > 50_000_000L) { // > 50 MB
                            found.add(f)
                        }
                    }
                }
            }
        }
        return found.distinctBy { it.absolutePath }
    }

    fun isModelFileAvailable(): Boolean {
        val configured = securityManager.getLocalModelPath()
        if (configured.isNotBlank() && File(configured).exists()) return true
        return findCandidateModels().isNotEmpty()
    }

    suspend fun ensureLoaded(): Result<LlmInference> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val path = securityManager.getLocalModelPath().ifBlank {
                findCandidateModels().firstOrNull()?.absolutePath.orEmpty()
            }
            if (path.isBlank() || !File(path).exists()) {
                _modelStatus.value = "No local model file found"
                return@withLock Result.failure(
                    IllegalStateException("No local model (.bin/.task) found in Downloads. Place Gemma-2-2B-it int4 in /sdcard/Download and select it in Settings.")
                )
            }

            if (llmInference != null && loadedPath == path) {
                return@withLock Result.success(llmInference!!)
            }

            try {
                _modelStatus.value = "Loading ${File(path).name}..."
                llmInference?.close()
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(path)
                    .setMaxTokens(512)
                    .setTopK(40)
                    .setTemperature(0.3f)
                    .build()

                val instance = LlmInference.createFromOptions(context, options)
                llmInference = instance
                loadedPath = path
                _modelStatus.value = "Loaded (${File(path).name})"
                Result.success(instance)
            } catch (e: Throwable) {
                _modelStatus.value = "Load error: ${e.message}"
                Result.failure(e)
            }
        }
    }

    suspend fun generateLocalReply(prompt: String, installedApps: List<String>): Result<AgentTurnResponse> =
        withContext(Dispatchers.IO) {
            val loaded = ensureLoaded().getOrElse { return@withContext Result.failure(it) }
            try {
                val appListShort = installedApps.take(40).joinToString(", ")
                val fullPrompt = """
                    <start_of_turn>user
                    You are J.A.R.V.I.S. running offline on the user's phone. Keep replies under 3 sentences.
                    If you cannot answer reliably without internet or deep reasoning, output ONLY: [UNSURE]
                    Available tools (output JSON ONLY if requested):
                    - open_app: {"tool":"open_app","args":{"app_name":"name"},"say":"Opening..."} (Apps: $appListShort)
                    - set_alarm: {"tool":"set_alarm","args":{"hour":7,"minute":30,"label":"Alarm"},"say":"Setting alarm..."}
                    - create_note: {"tool":"create_note","args":{"title":"Note","content":"text"},"say":"Saving note..."}

                    User request: $prompt<end_of_turn>
                    <start_of_turn>model
                """.trimIndent()

                val raw = mutex.withLock {
                    loaded.generateResponse(fullPrompt).trim()
                }

                if (raw.contains("[UNSURE]", ignoreCase = true)) {
                    return@withContext Result.success(AgentTurnResponse(replyText = "[UNSURE]"))
                }

                val startIdx = raw.indexOf('{')
                val endIdx = raw.lastIndexOf('}')
                if (startIdx != -1 && endIdx > startIdx) {
                    try {
                        val obj = org.json.JSONObject(raw.substring(startIdx, endIdx + 1))
                        if (obj.has("tool")) {
                            return@withContext Result.success(
                                AgentTurnResponse(
                                    replyText = obj.optString("say", "Executing..."),
                                    toolRequest = ToolRequest(
                                        tool = obj.getString("tool"),
                                        args = obj.optJSONObject("args") ?: org.json.JSONObject()
                                    )
                                )
                            )
                        }
                    } catch (_: Exception) {
                    }
                }

                Result.success(AgentTurnResponse(replyText = raw))
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }

    fun close() {
        try {
            llmInference?.close()
        } catch (_: Throwable) {
        }
        llmInference = null
        loadedPath = null
    }
}
