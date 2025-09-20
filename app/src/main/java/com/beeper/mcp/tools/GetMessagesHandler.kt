// Refactored app/src/main/java/com/beeper/mcp/tools/GetMessagesHandler.kt
// Similar changes: Take Map<String, Any?>, return formatted String or error
package com.beeper.mcp.tools
import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.beeper.mcp.BEEPER_AUTHORITY
private const val TAG = "GetMessagesHandler"
fun ContentResolver.getMessagesFormatted(args: Map<String, Any?>): String {
    val startTime = System.currentTimeMillis()
    return try {
        val roomIds = args["roomIds"]?.toString()
        val senderId = args["senderId"]?.toString()
        val query = args["query"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val contextBefore = args["contextBefore"]?.toString()?.toIntOrNull() ?: 0
        val contextAfter = args["contextAfter"]?.toString()?.toIntOrNull() ?: 0
        val openAtUnread = args["openAtUnread"]?.toString()?.toBoolean() ?: false
        val limit = args["limit"]?.toString()?.toIntOrNull() ?: 100
        val offset = args["offset"]?.toString()?.toIntOrNull() ?: 0
        Log.i(TAG, "=== REQUEST: get_messages ===")
        Log.i(TAG, "Parameters: roomIds=$roomIds, senderId=$senderId, query=$query, contextBefore=$contextBefore, contextAfter=$contextAfter, openAtUnread=$openAtUnread, limit=$limit, offset=$offset")
        Log.i(TAG, "Start time: $startTime")
        val params = buildString {
            roomIds?.let { append("roomIds=${Uri.encode(it)}&") }
            senderId?.let { append("senderId=${Uri.encode(it)}&") }
            query?.let { append("query=${Uri.encode(it)}&") }
            if (contextBefore > 0) append("contextBefore=$contextBefore&")
            if (contextAfter > 0) append("contextAfter=$contextAfter&")
            if (openAtUnread) append("openAtUnread=true&")
        }.trimEnd('&')
        val paginationParams = if (params.isNotEmpty()) {
            "$params&limit=$limit&offset=$offset"
        } else {
            "limit=$limit&offset=$offset"
        }
        val queryUri = "content://$BEEPER_AUTHORITY/messages?$paginationParams".toUri()
        val messages = mutableListOf<Map<String, Any?>>()
        var pagingOffset: Int? = null
        var lastRead: String? = null
        query(queryUri, null, null, null, null)?.use { cursor ->
            val roomIdIdx = cursor.getColumnIndex("roomId")
            val originalIdIdx = cursor.getColumnIndex("originalId")
            val senderContactIdIdx = cursor.getColumnIndex("senderContactId")
            val timestampIdx = cursor.getColumnIndex("timestamp")
            val isSentByMeIdx = cursor.getColumnIndex("isSentByMe")
            val isDeletedIdx = cursor.getColumnIndex("isDeleted")
            val typeIdx = cursor.getColumnIndex("type")
            val textContentIdx = cursor.getColumnIndex("text_content")
            val displayNameIdx = cursor.getColumnIndex("displayName")
            val isSearchMatchIdx = cursor.getColumnIndex("is_search_match")
            val reactionsIdx = cursor.getColumnIndex("reactions")
            while (cursor.moveToNext()) {
                val messageData = mapOf(
                    "messageId" to cursor.getString(originalIdIdx),
                    "roomId" to cursor.getString(roomIdIdx),
                    "senderId" to cursor.getString(senderContactIdIdx),
                    "displayName" to cursor.getString(displayNameIdx),
                    "timestamp" to cursor.getLong(timestampIdx),
                    "isSentByMe" to (cursor.getInt(isSentByMeIdx) == 1),
                    "isDeleted" to (cursor.getInt(isDeletedIdx) == 1),
                    "type" to cursor.getString(typeIdx),
                    "textContent" to cursor.getString(textContentIdx),
                    "content" to cursor.getString(textContentIdx),
                    "isSearchMatch" to if (isSearchMatchIdx >= 0) (cursor.getInt(isSearchMatchIdx) == 1) else true,
                    "reactions" to cursor.getString(reactionsIdx)
                )
                messages.add(messageData)
            }
            if (openAtUnread && messages.isNotEmpty()) {
                if (cursor.moveToFirst()) {
                    val pagingOffsetIdx = cursor.getColumnIndex("paging_offset")
                    val lastReadIdx = cursor.getColumnIndex("last_read")
                    if (pagingOffsetIdx >= 0) pagingOffset = cursor.getInt(pagingOffsetIdx)
                    if (lastReadIdx >= 0) lastRead = cursor.getString(lastReadIdx)
                }
            }
        }
        var totalCount: Int? = null
        if (messages.size == limit) {
            val countUri = "content://$BEEPER_AUTHORITY/messages/count".let { baseUri ->
                if (params.isNotEmpty()) "$baseUri?$params" else baseUri
            }.toUri()
            totalCount = query(countUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val countIdx = cursor.getColumnIndex("count")
                    if (countIdx >= 0) cursor.getInt(countIdx) else 0
                } else 0
            } ?: 0
            Log.i(TAG, "Total messages found: $totalCount")
        }
        val result = buildString {
            when {
                query != null -> appendLine("Message Search Results for: \"$query\"")
                roomIds != null -> appendLine("Messages in Rooms: $roomIds")
                senderId != null -> appendLine("Messages from Sender: $senderId")
                else -> appendLine("Messages:")
            }
            if (roomIds != null && query == null && senderId == null) appendLine("Filtered to rooms: $roomIds")
            if (senderId != null && query == null) appendLine("Filtered to sender: $senderId")
            appendLine("=".repeat(60))
            if (messages.isNotEmpty()) {
                var currentRoomId: String? = null
                val messagesInRoom = mutableListOf<String>()
                messages.forEach { messageData ->
                    val roomId = messageData["roomId"] as? String ?: "unknown"
                    val displayName = messageData["displayName"] as? String ?: "Unknown"
                    val timestamp = messageData["timestamp"] as? Long ?: 0L
                    val content = messageData["content"] as? String ?: ""
                    val type = messageData["type"] as? String ?: "TEXT"
                    val isSentByMe = messageData["isSentByMe"] as? Boolean ?: false
                    val isDeleted = messageData["isDeleted"] as? Boolean ?: false
                    val isSearchMatch = messageData["isSearchMatch"] as? Boolean ?: true
                    val reactions = messageData["reactions"] as? String ?: ""
                    if (roomId != currentRoomId && (roomIds == null || roomIds.contains(","))) {
                        if (currentRoomId != null && messagesInRoom.isNotEmpty()) {
                            messagesInRoom.forEach { msg -> appendLine(msg) }
                            appendLine()
                        }
                        currentRoomId = roomId
                        messagesInRoom.clear()
                        appendLine("\n📍 Room: $roomId")
                        appendLine("=".repeat(50))
                    }
                    val msgBuilder = StringBuilder()
                    if (!isSearchMatch && (contextBefore > 0 || contextAfter > 0)) {
                        msgBuilder.appendLine(" [Context]")
                    }
                    val prefix = if (query != null && isSearchMatch) "🔍 " else ""
                    msgBuilder.appendLine(" $prefix[${formatTimestamp(timestamp)}] $displayName${if (isSentByMe) " (You)" else ""}: ")
                    when {
                        isDeleted -> msgBuilder.appendLine(" [Message deleted]")
                        type == "TEXT" && content.isNotEmpty() -> {
                            content.lines().forEach { line ->
                                msgBuilder.appendLine(" $line")
                            }
                        }
                        else -> {
                            msgBuilder.appendLine(" [$type message]")
                        }
                    }
                    if (reactions.isNotEmpty()) {
                        val reactionList = reactions.split(",").map { reaction ->
                            val parts = reaction.split("|")
                            if (parts.size >= 3) "${parts[0]} (${if (parts[2] == "1") "You" else "Someone"})" else reaction
                        }
                        msgBuilder.appendLine(" Reactions: ${reactionList.joinToString(", ")}")
                    }
                    if (roomIds == null || roomIds.contains(",")) {
                        messagesInRoom.add(msgBuilder.toString())
                    } else {
                        appendLine(msgBuilder.toString())
                    }
                }
                if (messagesInRoom.isNotEmpty()) {
                    messagesInRoom.forEach { msg -> appendLine(msg) }
                }
                appendLine("\n" + "=".repeat(60))
                if (totalCount != null) {
                    appendLine("Showing ${offset + 1}-${offset + messages.size} of $totalCount total messages")
                    if (offset + messages.size < totalCount) {
                        appendLine("Use offset=${offset + messages.size} to get the next page")
                    }
                } else {
                    appendLine("Showing ${messages.size} message${if (messages.size != 1) "s" else ""} (page complete)")
                }
                if (openAtUnread) {
                    appendLine()
                    pagingOffset?.let { appendLine("Paging offset: $it") }
                    lastRead?.let { appendLine("Last read message: $it") }
                }
            } else {
                when {
                    query != null -> appendLine("\nNo messages found matching \"$query\"")
                    roomIds != null -> appendLine("\nNo messages found in the specified rooms")
                    senderId != null -> appendLine("\nNo messages found from the specified sender")
                    else -> appendLine("\nNo messages found")
                }
                if (offset > 0) {
                    appendLine("This page is empty - try a smaller offset value.")
                }
            }
        }
        val duration = System.currentTimeMillis() - startTime
        Log.i(TAG, "=== RESPONSE: get_messages ===")
        Log.i(TAG, "Duration: ${duration}ms")
        Log.i(TAG, "Total count: ${totalCount ?: "not fetched"}")
        Log.i(TAG, "Messages retrieved: ${messages.size}")
        Log.i(TAG, "Result length: ${result.length} characters")
        Log.i(TAG, "Status: SUCCESS")
        result
    } catch (e: Exception) {
        val duration = System.currentTimeMillis() - startTime
        Log.e(TAG, "=== ERROR: get_messages ===")
        Log.e(TAG, "Duration: ${duration}ms")
        Log.e(TAG, "Error: ${e.message}")
        Log.e(TAG, "Exception type: ${e.javaClass.simpleName}")
        Log.e(TAG, "Stack trace:", e)
        "Error getting room messages: ${e.message}"
    }
}
// Mock version for getMessagesFormatted — returns synthetic messages for testing without
// querying the Beeper content provider. Mirrors the formatting of getMessagesFormatted
// so it can be used in environments where the provider is not available.
fun ContentResolver.getMessagesMock(args: Map<String, Any?>): String {
    val startTime = System.currentTimeMillis()
    val roomIds = args["roomIds"]?.toString()
    val senderId = args["senderId"]?.toString()
    val query = args["query"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    val limit = args["limit"]?.toString()?.toIntOrNull() ?: 10
    val offset = args["offset"]?.toString()?.toIntOrNull() ?: 0
    val now = System.currentTimeMillis()
    try {
        val messages = mutableListOf<Map<String, Any?>>()
        // HHGTTG-themed messages from Arthur Dent and Ford Prefect, exasperated with Marvin
        val arthurMessages = listOf(
            "Marvin, for Earth's sake, stop moaning! We're about to hitch a ride on a Vogon constructor fleet—cheer up!",
            "Oh brilliant, Marvin's sulking again. Ford, tell your robot to quit whinging or I'll make him calculate the improbability of tea.",
            "Marvin, if you don't shut up about your aching diodes, I'll throw you out the airlock with the Babel fish!"
        )
        val fordMessages = listOf(
            "Listen, Marvin, I've got the Guide right here—'Don't Panic.' But you're panicking enough for all of us. Snap out of it!",
            "Marvin, old chap, your brain's the size of a planet, yet you whine like a faulty improbability drive. Fancy a Pan Galactic Gargle Blaster?",
            "Enough with the depression, Marvin! Arthur's lost his planet, I've lost my deadline—pull yourself together or I'll reprogram you with Vogon poetry."
        )
        // Generate synthetic messages
        for (i in 0 until limit) {
            val idx = offset + i
            val syntheticRoom = roomIds ?: "!heart_of_gold:galaxy"
            val syntheticSender = senderId ?: if (idx % 2 == 0) "@arthur:earth" else "@ford:betelgeuse"
            val displayName = if (syntheticSender.contains("arthur")) "Arthur Dent" else "Ford Prefect"
            val ts = now - (idx * 60_000L)
            val funnyIndex = idx % 3 // Cycle through 3 themed messages
            val textBody = when {
                query != null -> "(mock match) Zarking fhouls! A hit on '$query'—even Marvin would complain about this improbability."
                syntheticSender.contains("arthur") -> arthurMessages[funnyIndex]
                syntheticSender.contains("ford") -> fordMessages[funnyIndex]
                else -> "Generic mock: Oh no, not again—Marvin's existential crisis incoming. Shut up!"
            }
            messages.add(
                mapOf(
                    "messageId" to "m_mock_$idx",
                    "roomId" to syntheticRoom,
                    "senderId" to syntheticSender,
                    "displayName" to displayName,
                    "timestamp" to ts,
                    "isSentByMe" to false, // Mocks from others
                    "isDeleted" to false,
                    "type" to "TEXT",
                    "content" to textBody,
                    "isSearchMatch" to (query != null),
                    "reactions" to if (funnyIndex == 0) "😩🤦‍♂️" else ""
                )
            )
        }
        val result = buildString {
            when {
                query != null -> appendLine("Message Search Results (mock) for: \"$query\"")
                roomIds != null -> appendLine("Messages (mock) in Rooms: $roomIds")
                senderId != null -> appendLine("Messages (mock) from Sender: $senderId")
                else -> appendLine("Messages (mock):")
            }
            appendLine("=".repeat(60))
            if (messages.isNotEmpty()) {
                var currentRoomId: String? = null
                val messagesInRoom = mutableListOf<String>()
                messages.forEach { messageData ->
                    val roomId = messageData["roomId"] as? String ?: "unknown"
                    val displayName = messageData["displayName"] as? String ?: "Unknown"
                    val timestamp = messageData["timestamp"] as? Long ?: 0L
                    val content = messageData["content"] as? String ?: ""
                    val type = messageData["type"] as? String ?: "TEXT"
                    val isSentByMe = messageData["isSentByMe"] as? Boolean ?: false
                    if (roomId != currentRoomId && (roomIds == null || roomIds.contains(","))) {
                        if (currentRoomId != null && messagesInRoom.isNotEmpty()) {
                            messagesInRoom.forEach { msg -> appendLine(msg) }
                            appendLine()
                        }
                        currentRoomId = roomId
                        messagesInRoom.clear()
                        appendLine("\n📍 Room: $roomId")
                        appendLine("=".repeat(50))
                    }
                    val msgBuilder = StringBuilder()
                    msgBuilder.appendLine(" [${formatTimestamp(timestamp)}] $displayName${if (isSentByMe) " (You)" else ""}: ")
                    when {
                        type == "TEXT" && content.isNotEmpty() -> {
                            content.lines().forEach { line -> msgBuilder.appendLine(" $line") }
                        }
                        else -> msgBuilder.appendLine(" [$type message]")
                    }
                    val reactions = messageData["reactions"] as? String ?: ""
                    if (reactions.isNotEmpty()) {
                        msgBuilder.appendLine(" Reactions: $reactions")
                    }
                    messagesInRoom.add(msgBuilder.toString())
                }
                if (messagesInRoom.isNotEmpty()) {
                    messagesInRoom.forEach { msg -> appendLine(msg) }
                }
                appendLine("\n" + "=".repeat(60))
                appendLine("Showing ${offset + 1}-${offset + messages.size} of (mock) ${offset + messages.size} messages")
            } else {
                appendLine("No messages (mock) found")
            }
        }
        val duration = System.currentTimeMillis() - startTime
        Log.i(TAG, "=== RESPONSE: get_messages (mock) ===")
        Log.i(TAG, "Duration: ${duration}ms")
        Log.i(TAG, "Messages returned: ${messages.size}")
        return result
    } catch (e: Exception) {
        val duration = System.currentTimeMillis() - startTime
        Log.e(TAG, "=== ERROR: get_messages (mock) ===")
        Log.e(TAG, "Duration: ${duration}ms")
        Log.e(TAG, "Error: ${e.message}")
        return "Error generating mock messages: ${e.message}"
    }
}
