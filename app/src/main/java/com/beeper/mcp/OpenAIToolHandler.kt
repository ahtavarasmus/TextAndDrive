package com.beeper.mcp

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject  // For parsing OpenAI tool call args (which come as JSON string)
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.beeper.mcp.tools.getChatsFormatted
import com.beeper.mcp.tools.getContactsFormatted
import com.beeper.mcp.tools.getMessagesFormatted
import com.beeper.mcp.tools.sendMessageFormatted
import kotlinx.coroutines.withContext

// Extension to convert JSONObject to Map<String, Any?>
fun JSONObject.toMap(): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    keys().forEach { key ->
        map[key] = when (val value = get(key)) {
            is JSONObject -> value.toMap()
            is org.json.JSONArray -> value.toList()
            JSONObject.NULL -> null
            else -> value
        }
    }
    return map
}

// Extension for JSONArray to List<Any?>
fun org.json.JSONArray.toList(): List<Any?> {
    val list = mutableListOf<Any?>()
    for (i in 0 until length()) {
        list.add(when (val value = get(i)) {
            is JSONObject -> value.toMap()
            is org.json.JSONArray -> value.toList()
            JSONObject.NULL -> null
            else -> value
        })
    }
    return list
}

// Utility functions (copied from tools/Utils.kt)
private fun formatTimestamp(timestamp: Long): String {
    val formatter = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    return formatter.format(Date(timestamp))
}

// Hardcoded OpenAI-compatible tool definitions (mirroring MCP tools)
fun getOpenAITools(): List<Map<String, Any>> {
    return listOf(
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "get_chats",
                "description" to "Retrieves chats/conversations with optional filtering.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "roomIds" to mapOf("type" to "string", "description" to "Optional comma-separated room IDs"),
                        "isLowPriority" to mapOf("type" to "integer", "description" to "Optional 0/1"),
                        "isArchived" to mapOf("type" to "integer", "description" to "Optional 0/1"),
                        "isUnread" to mapOf("type" to "integer", "description" to "Optional 0/1"),
                        "showInAllChats" to mapOf("type" to "integer", "description" to "Optional 0/1"),
                        "protocol" to mapOf("type" to "string", "description" to "Optional network filter"),
                        "limit" to mapOf("type" to "integer", "description" to "Default 100"),
                        "offset" to mapOf("type" to "integer", "description" to "Default 0")
                    ),
                    "required" to emptyList<String>()
                )
            )
        ),
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "get_contacts",
                "description" to "Retrieves contacts/senders with optional filtering.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "senderIds" to mapOf("type" to "string", "description" to "Optional comma-separated sender IDs"),
                        "roomIds" to mapOf("type" to "string", "description" to "Optional comma-separated room IDs"),
                        "query" to mapOf("type" to "string", "description" to "Optional full-text search"),
                        "protocol" to mapOf("type" to "string", "description" to "Optional network filter"),
                        "limit" to mapOf("type" to "integer", "description" to "Default 100"),
                        "offset" to mapOf("type" to "integer", "description" to "Default 0")
                    ),
                    "required" to emptyList<String>()
                )
            )
        ),
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "get_messages",
                "description" to "Get messages from chats with optional filtering.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "roomIds" to mapOf("type" to "string", "description" to "Optional comma-separated room IDs"),
                        "senderId" to mapOf("type" to "string", "description" to "Optional sender filter"),
                        "query" to mapOf("type" to "string", "description" to "Optional full-text search"),
                        "contextBefore" to mapOf("type" to "integer", "description" to "Optional number"),
                        "contextAfter" to mapOf("type" to "integer", "description" to "Optional number"),
                        "openAtUnread" to mapOf("type" to "boolean", "description" to "Optional boolean"),
                        "limit" to mapOf("type" to "integer", "description" to "Default 100"),
                        "offset" to mapOf("type" to "integer", "description" to "Default 0")
                    ),
                    "required" to emptyList<String>()
                )
            )
        ),
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to "send_message",
                "description" to "Send a text message to a specific chat room.",
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "room_id" to mapOf("type" to "string", "description" to "Required Matrix room ID"),
                        "text" to mapOf("type" to "string", "description" to "Required message content")
                    ),
                    "required" to listOf("room_id", "text")
                )
            )
        )
    )
}

// Suspend function to handle an OpenAI tool call (call this when OpenAI responds with a tool_call)
suspend fun ContentResolver.handleOpenAIToolCall(toolCall: Map<String, Any>): String = withContext(Dispatchers.IO) {
    val functionName = toolCall["name"] as? String ?: return@withContext "Error: Invalid tool name"
    val argsJson = toolCall["arguments"] as? String ?: return@withContext "Error: Invalid arguments"
    val argsObj = JSONObject(argsJson)
    val argsMap = argsObj.toMap()

    when (functionName) {
        "get_chats" -> getChatsFormatted(argsMap)
        "get_contacts" -> getContactsFormatted(argsMap)
        "get_messages" -> getMessagesFormatted(argsMap)
        "send_message" -> sendMessageFormatted(argsMap)
        else -> "Error: Unknown tool $functionName"
    }
}