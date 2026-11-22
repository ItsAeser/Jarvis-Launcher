package com.privateai.jarvis.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
import java.io.FileOutputStream

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
            context.filesDir,
            context.getExternalFilesDir(null),
            File("/sdcard/jarvis launcher"),
            File("/sdcard/Download")
        ).filterNotNull()

        val found = mutableListOf<File>()
        for (dir in dirs) {
            try {
                if (dir.exists() && dir.isDirectory && dir.canRead()) {
                    dir.listFiles()?.forEach { f ->
                        val lower = f.name.lowercase()
                        if (f.isFile && f.canRead() && (lower.endsWith(".bin") || lower.endsWith(".task") || lower.endsWith(".tflite"))) {
                            if (f.length() > 50_000_000L) {
                                found.add(f)
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return found.distinctBy { it.absolutePath }
    }

    suspend fun importModelFromUri(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            var fileName = "local_model.task"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx != -1 && cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIdx) ?: fileName
                }
            }

            val destFile = File(context.filesDir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            } ?: return@withContext Result.failure(IllegalStateException("Unable to open selected file stream."))

            securityManager.setLocalModelPath(destFile.absolutePath)
            Result.success(destFile.absolutePath)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    fun isModelFileAvailable(): Boolean {
        val configured = securityManager.getLocalModelPath()
        if (configured.isNotBlank()) {
            val f = File(configured)
            if (f.exists() && f.canRead()) return true
        }
        return findCandidateModels().isNotEmpty()
    }

    suspend fun ensureLoaded(): Result<LlmInference> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val path = securityManager.getLocalModelPath().ifBlank {
                findCandidateModels().firstOrNull()?.absolutePath.orEmpty()
            }
            val file = File(path)
            if (path.isBlank() || !file.exists()) {
                _modelStatus.value = "No local model file found"
                return@withLock Result.failure(
                    IllegalStateException("No local model (.bin/.task) found. Tap the Gear icon -> 'Import Model File' or enable All Files Access.")
                )
            }
            if (!file.canRead()) {
                return@withLock Result.failure(
                    IllegalStateException("Android 16 blocked reading $path. Open Settings (Gear icon) -> '5. Grant All Files Access' or use 'Import Model File'.")
                )
            }

            if (llmInference != null && loadedPath == path) {
                return@withLock Result.success(llmInference!!)
            }

            try {
                _modelStatus.value = "Loading ${file.name}..."
                llmInference?.close()
                val instance = try {
                    val cpuOptions = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(path)
                        .setMaxTokens(512)
                        .setPreferredBackend(LlmInference.Backend.CPU)
                        .build()
                    LlmInference.createFromOptions(context, cpuOptions)
                } catch (_: Throwable) {
                    val gpuOptions = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(path)
                        .setMaxTokens(512)
                        .setPreferredBackend(LlmInference.Backend.GPU)
                        .build()
                    LlmInference.createFromOptions(context, gpuOptions)
                }
                llmInference = instance
                loadedPath = path
                _modelStatus.value = "Loaded (${file.name})"
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
