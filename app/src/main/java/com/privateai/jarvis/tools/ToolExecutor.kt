package com.privateai.jarvis.tools

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.privateai.jarvis.ai.ToolRequest
import com.privateai.jarvis.data.ActionLogEntity
import com.privateai.jarvis.data.NoteEntity
import com.privateai.jarvis.data.VaultDao
import com.privateai.jarvis.launcher.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

sealed class ToolExecutionOutcome {
    data class Completed(val message: String) : ToolExecutionOutcome()
    data class NeedsConfirmation(val pendingAction: PendingToolAction) : ToolExecutionOutcome()
    data class NeedsPermission(val permissions: List<String>, val retryRequest: ToolRequest) : ToolExecutionOutcome()
}

data class PendingToolAction(
    val toolName: String,
    val title: String,
    val details: String,
    val request: ToolRequest
)

class ToolExecutor(
    private val context: Context,
    private val appRepository: AppRepository,
    private val dao: VaultDao
) {
    companion object {
        val WHITELIST = setOf(
            "open_app",
            "set_alarm",
            "create_note",
            "calendar",
            "send_message",
            "search_files"
        )
    }

    suspend fun handleToolRequest(
        request: ToolRequest,
        confirmed: Boolean = false
    ): ToolExecutionOutcome {
        val tool = request.tool.lowercase(Locale.ROOT).trim()

        if (tool !in WHITELIST) {
            val msg = "Blocked: '$tool' is outside the permitted tool whitelist."
            dao.insertLog(ActionLogEntity(toolName = tool, summary = msg, status = "BLOCKED"))
            return ToolExecutionOutcome.Completed("🛡️ $msg")
        }

        return when (tool) {
            "open_app" -> executeOpenApp(request)
            "set_alarm" -> executeSetAlarm(request)
            "create_note" -> executeCreateNote(request)
            "calendar" -> executeCalendar(request, confirmed)
            "send_message" -> executeSendMessage(request, confirmed)
            "search_files" -> executeSearchFiles(request)
            else -> ToolExecutionOutcome.Completed("Blocked unknown tool.")
        }
    }

    private suspend fun executeOpenApp(request: ToolRequest): ToolExecutionOutcome {
        val query = request.args.optString("app_name").trim()
        if (query.isEmpty()) {
            return ToolExecutionOutcome.Completed("Please specify which app to open.")
        }

        val apps = appRepository.getInstalledApps()
        val exact = apps.firstOrNull { it.label.equals(query, ignoreCase = true) }
        val partial = exact ?: apps.firstOrNull {
            it.label.contains(query, ignoreCase = true) ||
                query.contains(it.label, ignoreCase = true)
        }

        if (partial == null) {
            dao.insertLog(ActionLogEntity(toolName = "open_app", summary = "App not found: $query", status = "FAILED"))
            return ToolExecutionOutcome.Completed("I couldn't find an installed app matching \"$query\".")
        }

        val launched = appRepository.launchApp(partial.packageName)
        return if (launched) {
            dao.insertLog(ActionLogEntity(toolName = "open_app", summary = "Opened ${partial.label}", status = "EXECUTED"))
            ToolExecutionOutcome.Completed("Opened ${partial.label}.")
        } else {
            dao.insertLog(ActionLogEntity(toolName = "open_app", summary = "Failed to launch ${partial.label}", status = "FAILED"))
            ToolExecutionOutcome.Completed("Found ${partial.label}, but Android prevented launching it.")
        }
    }

    private suspend fun executeSetAlarm(request: ToolRequest): ToolExecutionOutcome {
        val hour = request.args.optInt("hour", -1)
        val minute = request.args.optInt("minute", 0)
        val label = request.args.optString("label", "J.A.R.V.I.S. Alarm")

        if (hour !in 0..23 || minute !in 0..59) {
            return ToolExecutionOutcome.Completed("Invalid alarm time requested.")
        }

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            val formattedTime = String.format(Locale.ROOT, "%02d:%02d", hour, minute)
            dao.insertLog(ActionLogEntity(toolName = "set_alarm", summary = "Set alarm for $formattedTime ($label)", status = "EXECUTED"))
            ToolExecutionOutcome.Completed("⏰ Alarm set for $formattedTime ($label).")
        } catch (e: Exception) {
            dao.insertLog(ActionLogEntity(toolName = "set_alarm", summary = "Failed to set alarm: ${e.message}", status = "FAILED"))
            ToolExecutionOutcome.Completed("Could not set alarm: ${e.message}")
        }
    }

    private suspend fun executeCreateNote(request: ToolRequest): ToolExecutionOutcome {
        val title = request.args.optString("title", "Quick Note").trim().ifEmpty { "Quick Note" }
        val content = request.args.optString("content").trim()
        if (content.isEmpty()) {
            return ToolExecutionOutcome.Completed("Note content was empty; nothing saved.")
        }

        dao.insertNote(NoteEntity(title = title, content = content))
        dao.insertLog(ActionLogEntity(toolName = "create_note", summary = "Saved encrypted note: $title", status = "EXECUTED"))
        return ToolExecutionOutcome.Completed("📝 Saved encrypted note \"$title\" to your vault.")
    }

    private suspend fun executeSearchFiles(request: ToolRequest): ToolExecutionOutcome = withContext(Dispatchers.IO) {
        val query = request.args.optString("query").trim()
        if (query.isEmpty()) {
            return@withContext ToolExecutionOutcome.Completed("Please provide a filename or keyword to search.")
        }

        val matches = mutableListOf<String>()
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.RELATIVE_PATH
        )

        try {
            context.contentResolver.query(
                uri,
                projection,
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("%$query%"),
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                while (cursor.moveToNext() && matches.size < 10) {
                    val name = cursor.getString(0) ?: continue
                    val rel = cursor.getString(1).orEmpty()
                    matches.add("• $rel$name")
                }
            }
        } catch (_: Throwable) {
        }

        // Also scan Downloads directory directly
        val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (dlDir.exists()) {
            dlDir.listFiles()?.forEach { f ->
                if (f.name.contains(query, ignoreCase = true) && matches.size < 12) {
                    val entry = "• Download/${f.name}"
                    if (entry !in matches) matches.add(entry)
                }
            }
        }

        dao.insertLog(
            ActionLogEntity(
                toolName = "search_files",
                summary = "Searched files for '$query' (${matches.size} matches)",
                status = "EXECUTED"
            )
        )

        if (matches.isEmpty()) {
            ToolExecutionOutcome.Completed("📂 No files matching \"$query\" found on device.")
        } else {
            ToolExecutionOutcome.Completed("📂 Found ${matches.size} file(s):\n" + matches.joinToString("\n"))
        }
    }

    private suspend fun executeCalendar(
        request: ToolRequest,
        confirmed: Boolean
    ): ToolExecutionOutcome = withContext(Dispatchers.IO) {
        val action = request.args.optString("action", "read").lowercase(Locale.ROOT)
        val neededPerms = if (action == "add") {
            listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        } else {
            listOf(Manifest.permission.READ_CALENDAR)
        }

        val missing = neededPerms.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            return@withContext ToolExecutionOutcome.NeedsPermission(missing, request)
        }

        if (action == "add") {
            val title = request.args.optString("title", "New Event").trim()
            val dateTimeStr = request.args.optString("date_time").trim()

            if (!confirmed) {
                return@withContext ToolExecutionOutcome.NeedsConfirmation(
                    PendingToolAction(
                        toolName = "calendar",
                        title = "Confirm Calendar Event",
                        details = "Event: $title\nWhen: ${dateTimeStr.ifEmpty { "Next hour" }}",
                        request = request
                    )
                )
            }

            val startMillis = parseDateTimeMillis(dateTimeStr)
            val endMillis = startMillis + 60 * 60 * 1000L

            val calId = getPrimaryCalendarId() ?: 1L
            val values = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, startMillis)
                put(CalendarContract.Events.DTEND, endMillis)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            return@withContext if (uri != null) {
                dao.insertLog(ActionLogEntity(toolName = "calendar", summary = "Added calendar event: $title", status = "EXECUTED"))
                ToolExecutionOutcome.Completed("📅 Added \"$title\" to your calendar.")
            } else {
                ToolExecutionOutcome.Completed("Failed to insert calendar event.")
            }
        } else {
            val now = System.currentTimeMillis()
            val weekLater = now + 7L * 24 * 60 * 60 * 1000
            val projection = arrayOf(CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART)
            val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
            val selectionArgs = arrayOf(now.toString(), weekLater.toString())
            val events = mutableListOf<String>()
            val fmt = SimpleDateFormat("EEE, dd MMM hh:mm a", Locale.getDefault())

            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Events.DTSTART} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext() && events.size < 8) {
                    val evTitle = cursor.getString(0) ?: "(Untitled)"
                    val start = cursor.getLong(1)
                    events.add("• ${fmt.format(Date(start))}: $evTitle")
                }
            }

            dao.insertLog(ActionLogEntity(toolName = "calendar", summary = "Read ${events.size} calendar events", status = "EXECUTED"))
            val reply = if (events.isEmpty()) {
                "📅 You have no calendar events scheduled for the next 7 days."
            } else {
                "📅 Upcoming events:\n" + events.joinToString("\n")
            }
            ToolExecutionOutcome.Completed(reply)
        }
    }

    private suspend fun executeSendMessage(
        request: ToolRequest,
        confirmed: Boolean
    ): ToolExecutionOutcome = withContext(Dispatchers.IO) {
        val recipientRaw = request.args.optString("recipient").trim()
        val messageBody = request.args.optString("message").trim()

        if (recipientRaw.isEmpty() || messageBody.isEmpty()) {
            return@withContext ToolExecutionOutcome.Completed("Recipient and message text are both required.")
        }

        val isDigits = recipientRaw.replace("+", "").replace(" ", "").replace("-", "").all { it.isDigit() }
        val neededPerms = mutableListOf(Manifest.permission.SEND_SMS)
        if (!isDigits) {
            neededPerms.add(Manifest.permission.READ_CONTACTS)
        }

        val missing = neededPerms.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            return@withContext ToolExecutionOutcome.NeedsPermission(missing, request)
        }

        val resolvedNumber = if (isDigits) recipientRaw else lookupContactPhone(recipientRaw)
        if (resolvedNumber.isNullOrBlank()) {
            return@withContext ToolExecutionOutcome.Completed("Could not find a phone number for \"$recipientRaw\" in your contacts.")
        }

        if (!confirmed) {
            return@withContext ToolExecutionOutcome.NeedsConfirmation(
                PendingToolAction(
                    toolName = "send_message",
                    title = "Confirm SMS Message",
                    details = "To: $recipientRaw ($resolvedNumber)\nMessage: \"$messageBody\"",
                    request = request
                )
            )
        }

        return@withContext try {
            val smsManager = context.getSystemService(SmsManager::class.java)
            smsManager.sendTextMessage(resolvedNumber, null, messageBody, null, null)
            dao.insertLog(ActionLogEntity(toolName = "send_message", summary = "Sent SMS to $recipientRaw", status = "EXECUTED"))
            ToolExecutionOutcome.Completed("✉️ SMS sent to $recipientRaw ($resolvedNumber).")
        } catch (e: Exception) {
            val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$resolvedNumber")).apply {
                putExtra("sms_body", messageBody)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(smsIntent)
            dao.insertLog(ActionLogEntity(toolName = "send_message", summary = "Opened SMS app for $recipientRaw", status = "EXECUTED"))
            ToolExecutionOutcome.Completed("Opened Messages with your pre-filled text to $recipientRaw.")
        }
    }

    private fun lookupContactPhone(nameQuery: String): String? {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        context.contentResolver.query(
            uri,
            projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$nameQuery%"),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(1)
            }
        }
        return null
    }

    private fun getPrimaryCalendarId(): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.VISIBLE} = 1",
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        }
        return null
    }

    private fun parseDateTimeMillis(input: String): Long {
        if (input.isBlank()) return System.currentTimeMillis() + 3600_000L
        val formats = listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm", "dd-MM-yyyy HH:mm")
        for (pattern in formats) {
            try {
                val parsed = SimpleDateFormat(pattern, Locale.ROOT).parse(input)
                if (parsed != null) return parsed.time
            } catch (_: Exception) {
            }
        }
        return Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1) }.timeInMillis
    }

    suspend fun logDenied(action: PendingToolAction) {
        dao.insertLog(
            ActionLogEntity(
                toolName = action.toolName,
                summary = "User denied: ${action.title}",
                status = "DENIED"
            )
        )
    }
}
