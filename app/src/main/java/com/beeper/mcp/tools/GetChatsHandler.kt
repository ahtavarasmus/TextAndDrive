// app/src/main/java/com/beeper/mcp/tools/GetChatsHandler.kt
// Changes from original:
// - Added getChatsFormattedMock below the original getChatsFormatted
// - Mock version ignores most filters for simplicity (e.g., roomIds, protocol) but respects limit/offset for pagination
// - Uses hard-coded example chats (5 total) with varied data
// - Formatting mirrors the original exactly, including using formatTimestamp
// - No actual queries or logging in mock - just builds the string from examples
// - To switch: in handleOpenAIToolCall, replace getChatsFormatted(argsMap) with getChatsFormattedMock(argsMap)
package com.beeper.mcp.tools
import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.beeper.mcp.BEEPER_AUTHORITY
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Utility function for timestamp (moved here for mock to use; or keep in main if preferred)
private fun formatTimestampInChats(timestamp: Long): String {
    val formatter = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    return formatter.format(Date(timestamp))
}

private const val TAG = "GetChatsHandler"
fun ContentResolver.getChatsFormatted(args: Map<String, Any?>): String {
    val startTime = System.currentTimeMillis()
    return try {
        val roomIds = args["roomIds"]?.toString()
        val isLowPriority = args["isLowPriority"]?.toString()?.toIntOrNull()
        val isArchived = args["isArchived"]?.toString()?.toIntOrNull()
        val isUnread = args["isUnread"]?.toString()?.toIntOrNull()
        val showInAllChats = args["showInAllChats"]?.toString()?.toIntOrNull()
        val protocol = args["protocol"]?.toString()
        val limit = args["limit"]?.toString()?.toIntOrNull() ?: 100
        val offset = args["offset"]?.toString()?.toIntOrNull() ?: 0
        Log.i(TAG, "=== REQUEST: get_chats ===")
        Log.i(TAG, "Parameters: roomIds=$roomIds, isLowPriority=$isLowPriority, isArchived=$isArchived, isUnread=$isUnread, showInAllChats=$showInAllChats, protocol=$protocol, limit=$limit, offset=$offset")
        Log.i(TAG, "Start time: $startTime")
        val params = buildString {
            roomIds?.let { append("roomIds=${Uri.encode(it)}&") }
            isLowPriority?.let { append("isLowPriority=$it&") }
            isArchived?.let { append("isArchived=$it&") }
            isUnread?.let { append("isUnread=$it&") }
            showInAllChats?.let { append("showInAllChats=$it&") }
            protocol?.let { append("protocol=${Uri.encode(it)}&") }
        }.trimEnd('&')
        val paginationParams = if (params.isNotEmpty()) {
            "$params&limit=$limit&offset=$offset"
        } else {
            "limit=$limit&offset=$offset"
        }
        val queryUri = "content://$BEEPER_AUTHORITY/chats?$paginationParams".toUri()
        val chats = mutableListOf<Map<String, Any?>>()
        query(queryUri, null, null, null, null)?.use { cursor ->
            val roomIdIdx = cursor.getColumnIndex("roomId")
            val titleIdx = cursor.getColumnIndex("title")
            val previewIdx = cursor.getColumnIndex("messagePreview")
            val senderEntityIdIdx = cursor.getColumnIndex("senderEntityId")
            val protocolIdx = cursor.getColumnIndex("protocol")
            val unreadIdx = cursor.getColumnIndex("unreadCount")
            val timestampIdx = cursor.getColumnIndex("timestamp")
            val oneToOneIdx = cursor.getColumnIndex("oneToOne")
            val isMutedIdx = cursor.getColumnIndex("isMuted")
            while (cursor.moveToNext()) {
                val chatData = mapOf(
                    "roomId" to cursor.getString(roomIdIdx),
                    "title" to cursor.getString(titleIdx),
                    "preview" to cursor.getString(previewIdx),
                    "senderEntityId" to cursor.getString(senderEntityIdIdx),
                    "protocol" to cursor.getString(protocolIdx),
                    "unreadCount" to cursor.getInt(unreadIdx),
                    "timestamp" to cursor.getLong(timestampIdx),
                    "isOneToOne" to (cursor.getInt(oneToOneIdx) == 1),
                    "isMuted" to (cursor.getInt(isMutedIdx) == 1)
                )
                chats.add(chatData)
            }
        }
        var totalCount: Int? = null
        if (chats.size == limit) {
            val countUri = "content://$BEEPER_AUTHORITY/chats/count".let { baseUri ->
                if (params.isNotEmpty()) "$baseUri?$params" else baseUri
            }.toUri()
            totalCount = query(countUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val countIdx = cursor.getColumnIndex("count")
                    if (countIdx >= 0) cursor.getInt(countIdx) else 0
                } else 0
            } ?: 0
            Log.i(TAG, "Total chats found: $totalCount")
        }
        val result = buildString {
            appendLine("Beeper Chats:")
            appendLine("=".repeat(50))
            if (chats.isNotEmpty()) {
                chats.forEachIndexed { index, chatData ->
                    val roomId = chatData["roomId"] as? String ?: "unknown"
                    val title = chatData["title"] as? String ?: "Untitled"
                    val preview = chatData["preview"] as? String ?: ""
                    val senderEntityId = chatData["senderEntityId"] as? String ?: ""
                    val protocol = chatData["protocol"] as? String ?: ""
                    val unreadCount = chatData["unreadCount"] as? Int ?: 0
                    val timestamp = chatData["timestamp"] as? Long ?: 0L
                    val isOneToOne = chatData["isOneToOne"] as? Boolean ?: false
                    val isMuted = chatData["isMuted"] as? Boolean ?: false
                    appendLine()
                    appendLine("Chat #${offset + index + 1}:")
                    appendLine(" Title: $title")
                    appendLine(" Room ID: $roomId")
                    appendLine(" Type: ${if (isOneToOne) "Direct Message" else "Group Chat"}")
                    appendLine(" Network: ${protocol.ifEmpty { "beeper" }}")
                    appendLine(" Unread: $unreadCount messages")
                    appendLine(" Muted: ${if (isMuted) "Yes" else "No"}")
                    appendLine(" Last Activity: ${formatTimestampInChats(timestamp)}")
                    if (preview.isNotEmpty()) {
                        appendLine(" Preview: ${preview.take(100)}${if (preview.length > 100) "..." else ""}")
                        if (senderEntityId.isNotEmpty()) {
                            appendLine(" Preview Sender: $senderEntityId")
                        }
                    }
                }
                appendLine()
                if (totalCount != null) {
                    appendLine("Showing ${offset + 1}-${offset + chats.size} of $totalCount total chats")
                    if (offset + chats.size < totalCount) {
                        appendLine("Use offset=${offset + chats.size} to get the next page")
                    }
                } else {
                    appendLine("Showing ${chats.size} chat${if (chats.size != 1) "s" else ""} (page complete)")
                }
            } else {
                appendLine("\nNo chats found matching the specified criteria.")
                if (offset > 0) {
                    appendLine("This page is empty - try a smaller offset value.")
                }
            }
        }
        val duration = System.currentTimeMillis() - startTime
        Log.i(TAG, "=== RESPONSE: get_chats ===")
        Log.i(TAG, "Duration: ${duration}ms")
        Log.i(TAG, "Total count: ${totalCount ?: "not fetched"}")
        Log.i(TAG, "Chats retrieved: ${chats.size}")
        Log.i(TAG, "Result length: ${result.length} characters")
        Log.i(TAG, "Status: SUCCESS")
        result
    } catch (e: Exception) {
        val duration = System.currentTimeMillis() - startTime
        Log.e(TAG, "=== ERROR: get_chats ===")
        Log.e(TAG, "Duration: ${duration}ms")
        Log.e(TAG, "Error: ${e.message}")
        Log.e(TAG, "Exception type: ${e.javaClass.simpleName}")
        Log.e(TAG, "Stack trace:", e)
        "Error retrieving chats: ${e.message}"
    }
}

// Mock version with hard-coded example data
fun getChatsFormattedMockImpl(args: Map<String, Any?>): String {
    val limit = args["limit"]?.toString()?.toIntOrNull() ?: 100
    val offset = args["offset"]?.toString()?.toIntOrNull() ?: 0

    // Hard-coded example chats (ignoring filters like roomIds/protocol for mock simplicity)
    val exampleChats = listOf(
        mapOf(
            "roomId" to "!abc123:example.com",
            "title" to "Chat with Alice",
            "preview" to "Hey, how's it going? Let's catch up soon!",
            "senderEntityId" to "alice@example.com",
            "protocol" to "matrix",
            "unreadCount" to 2,
            "timestamp" to 1726662000000L,  // Sep 18, 2024 ~10:00 AM (adjust based on locale)
            "isOneToOne" to true,
            "isMuted" to false
        ),
        mapOf(
            "roomId" to "!def456:example.com",
            "title" to "Family Group",
            "preview" to "Dinner at 7 PM?",
            "senderEntityId" to "mom@example.com",
            "protocol" to "whatsapp",
            "unreadCount" to 0,
            "timestamp" to 1726665600000L,  // Sep 18, 2024 ~11:00 AM
            "isOneToOne" to false,
            "isMuted" to true
        ),
        mapOf(
            "roomId" to "!ghi789:example.com",
            "title" to "Bob DM",
            "preview" to "",
            "senderEntityId" to "",
            "protocol" to "sms",
            "unreadCount" to 1,
            "timestamp" to 1726669200000L,  // Sep 18, 2024 ~12:00 PM
            "isOneToOne" to true,
            "isMuted" to false
        ),
        mapOf(
            "roomId" to "!jkl012:example.com",
            "title" to "Work Team",
            "preview" to "Meeting notes attached.",
            "senderEntityId" to "boss@example.com",
            "protocol" to "slack",
            "unreadCount" to 5,
            "timestamp" to 1726672800000L,  // Sep 18, 2024 ~1:00 PM
            "isOneToOne" to false,
            "isMuted" to false
        ),
        mapOf(
            "roomId" to "!mno345:example.com",
            "title" to "Friend Chat",
            "preview" to "Check this out!",
            "senderEntityId" to "friend@example.com",
            "protocol" to "telegram",
            "unreadCount" to 0,
            "timestamp" to 1726676400000L,  // Sep 18, 2024 ~2:00 PM
            "isOneToOne" to true,
            "isMuted" to true
        )
    )

    val totalCount = exampleChats.size
    val paginatedChats = exampleChats.drop(offset).take(limit)

    return buildString {
        appendLine("Beeper Chats:")
        appendLine("=".repeat(50))
        if (paginatedChats.isNotEmpty()) {
            paginatedChats.forEachIndexed { index, chatData ->
                val roomId = chatData["roomId"] as? String ?: "unknown"
                val title = chatData["title"] as? String ?: "Untitled"
                val preview = chatData["preview"] as? String ?: ""
                val senderEntityId = chatData["senderEntityId"] as? String ?: ""
                val protocol = chatData["protocol"] as? String ?: ""
                val unreadCount = chatData["unreadCount"] as? Int ?: 0
                val timestamp = chatData["timestamp"] as? Long ?: 0L
                val isOneToOne = chatData["isOneToOne"] as? Boolean ?: false
                val isMuted = chatData["isMuted"] as? Boolean ?: false
                appendLine()
                appendLine("Chat #${offset + index + 1}:")
                appendLine(" Title: $title")
                appendLine(" Room ID: $roomId")
                appendLine(" Type: ${if (isOneToOne) "Direct Message" else "Group Chat"}")
                appendLine(" Network: ${protocol.ifEmpty { "beeper" }}")
                appendLine(" Unread: $unreadCount messages")
                appendLine(" Muted: ${if (isMuted) "Yes" else "No"}")
                appendLine(" Last Activity: ${formatTimestampInChats(timestamp)}")
                if (preview.isNotEmpty()) {
                    appendLine(" Preview: ${preview.take(100)}${if (preview.length > 100) "..." else ""}")
                    if (senderEntityId.isNotEmpty()) {
                        appendLine(" Preview Sender: $senderEntityId")
                    }
                }
            }
            appendLine()
            appendLine("Showing ${offset + 1}-${offset + paginatedChats.size} of $totalCount total chats")
            if (offset + paginatedChats.size < totalCount) {
                appendLine("Use offset=${offset + paginatedChats.size} to get the next page")
            }
        } else {
            appendLine("\nNo chats found matching the specified criteria.")
            if (offset > 0) {
                appendLine("This page is empty - try a smaller offset value.")
            }
        }
    }
}

// Extension so callers using ContentResolver.getChatsFormattedMock(...) will compile
fun ContentResolver.getChatsFormattedMock(args: Map<String, Any?>): String = getChatsFormattedMockImpl(args)
