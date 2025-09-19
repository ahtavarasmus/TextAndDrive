package com.beeper.mcp.data.api

import android.content.ContentResolver
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import com.beeper.mcp.handleOpenAIToolCall

object LLMClient {
    private val client = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Sends the transcript to a generic LLM inference endpoint supporting chat/completions
     * with function definitions. Uses the project's tools and ContentResolver handler for
     * executing function calls.
     */
    suspend fun sendTranscriptWithTools(
        context: Context,
        apiKey: String,
        transcript: String,
        contentResolver: ContentResolver
    ): String = withContext(Dispatchers.IO) {
        val model = "qwen3-coder-480b" // adjust if needed
        val endpoint = "https://inference.tinfoil.sh/v1/chat/completions"

        val tools = com.beeper.mcp.getOpenAITools()


        fun buildMessages(userMsg: String, extra: List<JSONObject>? = null): JSONArray {
            val msgs = JSONArray()
            // Stronger system instruction: when a tool can be used, respond ONLY with a function_call
            // object (name + JSON arguments). Do NOT emit assistant text if a function_call is possible.
            // Provide well-formed JSON arguments that match the tool parameter names. If no tool is
            // applicable, respond with a plain assistant text answer only.
            //
            // Include an explicit example and strict rules so the model prefers function_call output:
            // Example (when sending a message):
            // {"function_call": {"name": "send_message", "arguments": {"room_id": "!abc:matrix.org", "text": "Hi"}}}
            // Rules:
            // 1) If any available tool can satisfy the user's request, output ONLY the function_call JSON
            //    (no surrounding explanatory text). Use the `function_call` field at top-level.
            // 2) Arguments must be valid JSON and include required fields from the tool signature.
            // 3) If multiple tools could apply, pick the single best-matching tool. Do not output multiple
            //    function_call objects.
            // 4) If the required argument values are ambiguous or missing, populate them with the best
            //    guess you can and prefer returning a function_call rather than asking follow-up questions.
            // 5) Only use plain assistant text when no tool applies.
            msgs.put(
                JSONObject().put("role", "system").put(
                    "content",
                    "You are a helpful assistant. When the user's request can be fulfilled using available functions, call the appropriate function(s). Always respond directly to function calls without additional explanation unless specifically asked."
                )
            )
            msgs.put(JSONObject().put("role", "user").put("content", userMsg))
            extra?.forEach { msgs.put(it) }
            return msgs
        }

        suspend fun callLLM(messages: JSONArray, tools: List<Map<String, Any>>): JSONObject {
            val payload = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("tools", JSONArray(tools))
                .put("temperature", 0.0)


            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = payload.toString().toRequestBody(mediaType)
            val req = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            // Log the full outgoing payload for debugging (avoid logging API keys elsewhere)
            try { Log.d("LLMClient", "Outgoing LLM payload: ${payload}") } catch (_: Exception) {}

            client.newCall(req).execute().use { resp ->
                val b = resp.body?.string() ?: throw Exception("Empty LLM response")
                if (!resp.isSuccessful) throw Exception("LLM error: ${resp.code} ${resp.message}. Body=$b")
                return JSONObject(b)
            }
        }

        val initialMessages = buildMessages(transcript)
        val initialResp = callLLM(initialMessages, tools)
        val choice = initialResp.getJSONArray("choices").getJSONObject(0)
        val message = choice.getJSONObject("message")

        // Defensive handling for function_call: it may be missing, null, a JSONObject, or a string
        val funcObj: JSONObject? = when {
            !message.isNull("function_call") -> {
                val raw = message.opt("function_call")
                when (raw) {
                    is JSONObject -> raw
                    is String -> try { JSONObject(raw) } catch (_: Exception) { null }
                    else -> null
                }
            }
            !message.isNull("tool_calls") -> {
                val toolCalls = message.optJSONArray("tool_calls")
                if (toolCalls != null && toolCalls.length() > 0) {
                    val firstTool = toolCalls.getJSONObject(0).optJSONObject("function")
                    firstTool
                } else null
            }
            else -> null
        }


        if (funcObj != null) {
            // When the model indicates a function_call, return the function_call JSON to the caller
            // instead of executing the tool locally. The caller (higher layer) can then decide
            // to run the tool and provide the result back to the model if desired.
            val fname = funcObj.optString("name", "")
            val fargsRaw = when (val a = funcObj.opt("arguments")) {
                is String -> a
                is JSONObject -> a.toString()
                null -> "{}"
                else -> a.toString()
            }

            // Try to parse arguments into JSON if possible, otherwise keep as raw string under "raw".
            val argsJson = try {
                JSONObject(fargsRaw)
            } catch (e: Exception) {
                JSONObject().put("raw", fargsRaw)
            }

            val funcCall = JSONObject()
                .put("name", fname)
                .put("arguments", argsJson)

            val wrapper = JSONObject().put("function_call", funcCall)
            Log.d("LLMClient", "LLM requested function call: $wrapper")

            // Return the structured function_call JSON string so the caller can handle it.
            return@withContext wrapper.toString()
        }

        // The model may explicitly set `content` to null (JSON null). org.json's
        // optString() can return the literal string "null" when the value is
        // JSONObject.NULL, which then shows up in logs as Assistant reply: null.
        // Normalize that to an empty string so callers don't misinterpret it.
        val content = if (!message.isNull("content")) {
            val c = message.optString("content", "")
            if (c == "null") "" else c
        } else {
            ""
        }

        return@withContext content
    }
}
