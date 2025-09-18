// Refactored app/src/main/java/com/beeper/mcp/tools/SendMessageHandler.kt
// Changes: Take Map<String, Any?>, return formatted success/error String
// Removed suspend if not needed, but kept for consistency if calling from coroutine

package com.beeper.mcp.tools

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.beeper.mcp.BEEPER_AUTHORITY

private const val TAG = "SendMessageHandler"

fun ContentResolver.sendMessageFormatted(args: Map<String, Any?>): String {
    val startTime = System.currentTimeMillis()
    return try {
        val roomId = args["room_id"]?.toString()
        val text = args["text"]?.toString()

        if (roomId == null || text == null) {
            return "Error: Both 'room_id' and 'text' parameters are required".also {
                Log.e(TAG, "=== ERROR: send_message ===")
                Log.e(TAG, "Error: Missing required parameters - need both room_id and text")
            }
        }

        Log.i(TAG, "=== REQUEST: send_message ===")
        Log.i(TAG, "Parameters: roomId=$roomId, text length=${text.length}")
        Log.i(TAG, "Start time: $startTime")

        val uriBuilder = "content://$BEEPER_AUTHORITY/messages".toUri().buildUpon()
        uriBuilder.appendQueryParameter("roomId", roomId)
        uriBuilder.appendQueryParameter("text", text)

        val uri = uriBuilder.build()
        val contentValues = ContentValues()
        val result = insert(uri, contentValues)

        val responseText = if (result != null) {
            "Message sent successfully to room: $roomId\n\nMessage content: $text"
        } else {
            "Failed to send message to room: $roomId\n\nThis could be due to:\n - Invalid room ID\n - Network connectivity issues\n - Insufficient permissions\n - Beeper app not running"
        }

        val duration = System.currentTimeMillis() - startTime
        Log.i(TAG, "=== RESPONSE: send_message ===")
        Log.i(TAG, "Duration: ${duration}ms")
        Log.i(TAG, "Success: ${result != null}")
        Log.i(TAG, "Status: ${if (result != null) "SUCCESS" else "FAILED"}")

        responseText

    } catch (e: Exception) {
        val duration = System.currentTimeMillis() - startTime
        Log.e(TAG, "=== ERROR: send_message ===")
        Log.e(TAG, "Duration: ${duration}ms")
        Log.e(TAG, "Error: ${e.message}")
        Log.e(TAG, "Exception type: ${e.javaClass.simpleName}")
        Log.e(TAG, "Stack trace:", e)
        "Error sending message: ${e.message}"
    }
}