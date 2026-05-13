package com.example.android_screen_relay

import android.content.Context
import android.os.Environment
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object LogRepository {
    // New Structured Log Entry
    data class LogEntry(
        val timestamp: Long,
        val level: String,     // INFO, WARN, ERROR
        val component: String, // WebSocketServer, RelayService, etc.
        val event: String,     // heartbeat, client_connected, etc.
        val data: Map<String, Any>, // Flexible data payload
        val type: LogType // Keeping UI type distinct for color coding
    )

    enum class LogType {
        INFO, ERROR, OUTGOING, INCOMING
    }

    private val _logs = mutableStateListOf<LogEntry>()
    val logs: List<LogEntry> get() = _logs

    // ISO 8601 Format for JSON logs (Using Local Time for user readability)
    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
    
    // UI Display Format
    private val uiDateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun addLog(
        component: String,
        event: String,
        data: Map<String, Any> = emptyMap(),
        level: String = "INFO",
        type: LogType = LogType.INFO
    ) {
        synchronized(_logs) {
            // Increase buffer size to prevent losing important logs quickly
            if (_logs.size > 2000) {
                _logs.removeAt(0)
            }
            // Ensure map values are serializable or strings
            val safeData = data.mapValues { (_, value) -> 
                 value.toString() 
            }
            
            _logs.add(
                LogEntry(
                    timestamp = System.currentTimeMillis(),
                    level = level,
                    component = component,
                    event = event,
                    data = safeData,
                    type = type
                )
            )
        }
    }
    
    // Legacy support wrapper (optional, helps migrate existing calls)
    fun addLegacyLog(message: String, type: LogType = LogType.INFO) {
        addLog(
            component = "Legacy",
            event = "message",
            data = mapOf("msg" to message),
            level = if (type == LogType.ERROR) "ERROR" else "INFO",
            type = type
        )
    }

    fun clearLogs() {
        _logs.clear()
    }

    fun getFormattedTimestamp(timestamp: Long): String {
        return uiDateFormat.format(Date(timestamp))
    }

    // Export to JSON file in Downloads
    fun exportLogsToDownloads(context: Context): String? {
        return try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
            val fileName = "relay_logs_$timestamp.json"
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            // Ensure directory exists
            downloadsDir.mkdirs()
            val file = File(downloadsDir, fileName)

            FileWriter(file).use { writer ->
                val jsonArray = org.json.JSONArray()
                
                // Copy list to avoid concurrent modification during iteration
                val logsCopy = synchronized(_logs) { _logs.toList() }
                
                logsCopy.forEach { log ->
                    val json = JSONObject()
                    json.put("timestamp", isoDateFormat.format(Date(log.timestamp)))
                    json.put("level", log.level)
                    json.put("component", log.component)
                    json.put("event", log.event)
                    
                    // Handle data map carefully
                    val dataJson = JSONObject()
                    log.data.forEach { (key, value) ->
                        dataJson.put(key, value)
                    }
                    json.put("data", dataJson)
                    
                    jsonArray.put(json)
                }
                
                // Write formatted JSON with indentation level 2
                writer.write(jsonArray.toString(2))
            }
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
