package com.privateai.jarvis.ai

import com.privateai.jarvis.data.ChatMessageEntity
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

sealed class RouteDecision {
    data class DirectTool(val request: ToolRequest) : RouteDecision()
    data class Cloud(val reason: String, val requiresPrivacyApproval: Boolean, val privacySummary: String) : RouteDecision()
    data class LocalFirst(val fallbackNotice: Boolean) : RouteDecision()
}

class HybridRouter(
    private val cloudClient: CloudChatClient,
    private val localEngine: LocalLlmEngine
) {
    /**
     * Rule-first deterministic router:
     * 1. Instant local command rules (open app, set alarm, quick note)
     * 2. Privacy gate before sending anything referencing contacts, messages, or files online
     * 3. Complexity / length / code / web rules -> Cloud
     * 4. Offline -> Local model with explicit reduced-quality notice
     * 5. Short simple queries -> Local model first, escalating to Cloud only if local signals [UNSURE]
     */
    fun decideRoute(prompt: String): RouteDecision {
        val trimmed = prompt.trim()
        val lower = trimmed.lowercase()

        // Rule 1A: Direct app launch ("open youtube", "launch whatsapp")
        Regex("^(?:open|launch|start)\\s+(.+)$", RegexOption.IGNORE_CASE)
            .matchEntire(trimmed)
            ?.let { match ->
                return RouteDecision.DirectTool(
                    ToolRequest("open_app", JSONObject().put("app_name", match.groupValues[1].trim()))
                )
            }

        // Rule 1B: Direct alarm ("set alarm for 7:30" or "wake me up at 06:45")
        Regex("^(?:set\\s+(?:an\\s+)?alarm\\s+(?:for\\s+)?|wake\\s+me\\s+(?:up\\s+)?at\\s+)(\\d{1,2}):(\\d{2})(?:\\s*(am|pm))?.*$", RegexOption.IGNORE_CASE)
            .matchEntire(trimmed)
            ?.let { match ->
                var hour = match.groupValues[1].toIntOrNull() ?: 7
                val minute = match.groupValues[2].toIntOrNull() ?: 0
                val ampm = match.groupValues[3].lowercase()
                if (ampm == "pm" && hour in 1..11) hour += 12
                if (ampm == "am" && hour == 12) hour = 0
                if (hour in 0..23 && minute in 0..59) {
                    return RouteDecision.DirectTool(
                        ToolRequest(
                            "set_alarm",
                            JSONObject().put("hour", hour).put("minute", minute).put("label", "J.A.R.V.I.S. Alarm")
                        )
                    )
                }
            }

        // Rule 1C: Direct quick note ("note: buy batteries" or "save note buy batteries")
        Regex("^(?:save\\s+(?:a\\s+)?note|take\\s+(?:a\\s+)?note|note)\\s*:?\\s+(.+)$", RegexOption.IGNORE_CASE)
            .matchEntire(trimmed)
            ?.let { match ->
                val content = match.groupValues[1].trim()
                if (content.isNotEmpty()) {
                    return RouteDecision.DirectTool(
                        ToolRequest(
                            "create_note",
                            JSONObject().put("title", "Quick Note").put("content", content)
                        )
                    )
                }
            }

        // Rule 1D: Direct local file search ("find file report" or "search files invoice")
        Regex("^(?:find|search)\\s+(?:my\\s+)?files?\\s+(?:for\\s+|named\\s+)?(.+)$", RegexOption.IGNORE_CASE)
            .matchEntire(trimmed)
            ?.let { match ->
                val query = match.groupValues[1].trim()
                if (query.isNotEmpty()) {
                    return RouteDecision.DirectTool(
                        ToolRequest("search_files", JSONObject().put("query", query))
                    )
                }
            }

        val online = cloudClient.isOnline()

        // Rule 2: Offline fallback with explicit reduced-quality notice
        if (!online) {
            return RouteDecision.LocalFirst(fallbackNotice = true)
        }

        // Privacy check: does the prompt reference contacts, SMS/messages, or personal files?
        val touchesSensitiveData = listOf(
            "contact", "contacts", "sms", "message", "messages", "text ",
            "file", "files", "document", "pdf", "folder", "calendar", "schedule"
        ).any { lower.contains(it) }

        // Rule 3: Complex, long, coding, planning, or multi-step queries -> Cloud
        val isComplex = trimmed.length > 140 ||
            listOf(
                "code", "kotlin", "python", "script", "debug", "plan", "step by step",
                "explain in detail", "compare", "summarize", "search web", "latest",
                "news", "weather", "send ", "calendar", "event", "meeting"
            ).any { lower.contains(it) }

        if (isComplex || touchesSensitiveData || !localEngine.isModelFileAvailable()) {
            return RouteDecision.Cloud(
                reason = if (isComplex) "Complex / multi-step task" else "Cloud reasoning",
                requiresPrivacyApproval = touchesSensitiveData,
                privacySummary = "Prompt may reference your contacts, messages, calendar, or files:\n\"$trimmed\""
            )
        }

        // Rule 4: Short general question & local model is available -> Try Local first, secondary [UNSURE] escalation
        return RouteDecision.LocalFirst(fallbackNotice = false)
    }

    suspend fun executeHybrid(
        prompt: String,
        history: List<ChatMessageEntity>,
        installedAppNames: List<String>
    ): Result<Pair<AgentTurnResponse, String>> {
        val online = cloudClient.isOnline()

        if (!online) {
            val localRes = localEngine.generateLocalReply(prompt, installedAppNames)
            return localRes.map { resp ->
                val clean = if (resp.replyText == "[UNSURE]") {
                    "I'm offline and my local model isn't confident about that answer."
                } else {
                    resp.replyText
                }
                Pair(
                    resp.copy(replyText = "📴 Offline mode (local model — reduced quality):\n$clean"),
                    "LOCAL_OFFLINE"
                )
            }
        }

        // Try local model first for short queries
        val localAttempt = localEngine.generateLocalReply(prompt, installedAppNames)
        if (localAttempt.isSuccess) {
            val localTurn = localAttempt.getOrThrow()
            // Secondary uncertainty escalation rule: only escalate to cloud if local outputs [UNSURE]
            if (localTurn.replyText != "[UNSURE]") {
                return Result.success(Pair(localTurn, "LOCAL_FAST"))
            }
        }

        // Escalate to Cloud
        return cloudClient.sendMessage(history, prompt, installedAppNames).map { cloudTurn ->
            Pair(cloudTurn, "CLOUD_ESCALATED")
        }
    }
}
