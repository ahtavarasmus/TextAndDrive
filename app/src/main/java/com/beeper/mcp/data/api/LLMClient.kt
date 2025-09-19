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

        val functionsJson = JSONArray()
        try {
            val tools = com.beeper.mcp.getOpenAITools()
            // The project's getOpenAITools() may return Maps, JSONObjects, or nested structures.
            // Normalize each tool into the function definition object and add to functionsJson.
            tools.forEach { toolItem ->
                try {
                    when (toolItem) {
                        is Map<*, *> -> {
                            val inner = toolItem["function"]
                            if (inner is Map<*, *>) {
                                functionsJson.put(JSONObject(inner))
                            } else if (inner is JSONObject) {
                                functionsJson.put(inner)
                            } else {
                                // If there's no inner function object, try the map itself
                                functionsJson.put(JSONObject(toolItem))
                            }
                        }
                        is JSONObject -> {
                            val inner = if (toolItem.has("function")) toolItem.opt("function") else null
                            if (inner is JSONObject) functionsJson.put(inner) else functionsJson.put(toolItem)
                        }
                        else -> {
                            // Fallback: try to coerce to JSONObject from toString()
                            try { functionsJson.put(JSONObject(toolItem.toString())) } catch (_: Exception) { }
                        }
                    }
                } catch (e: Exception) {
                    try { functionsJson.put(JSONObject(toolItem.toString())) } catch (_: Exception) { }
                }
            }
        } catch (e: Exception) {
            Log.d("LLMClient", "Failed to build functions list: ${e.message}")
        }

        if (functionsJson.length() > 0) {
            Log.d("LLMClient", "Attaching ${functionsJson.length()} function(s) to the LLM request: ${functionsJson}")
        } else {
            Log.d("LLMClient", "No functions attached to LLM request")
        }

        fun buildMessages(userMsg: String, extra: List<JSONObject>? = null): JSONArray {
            val msgs = JSONArray()
            // Stronger system instruction: when a tool can be used, respond ONLY with a function_call
            // object (name + JSON arguments). Do not emit assistant content when a tool is applicable.
            msgs.put(
                JSONObject().put("role", "system").put(
                    "content",
                    "You are an assistant that has access to tools to send and fetch messages to different chat networks. When a tool can handle the user's request, respond ONLY with a function_call object (use the function_call field) specifying the tool name and JSON arguments. Do not include regular assistant text in that case. Only produce a text answer when no tool is applicable."
                )
            )
            msgs.put(JSONObject().put("role", "user").put("content", userMsg))
            extra?.forEach { msgs.put(it) }
            return msgs
        }

        suspend fun callLLM(messages: JSONArray): JSONObject {
            val payload = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", 0.2)

            if (functionsJson.length() > 0) {
                payload.put("functions", functionsJson)
                // Ask the model to prefer function calls. This biases the model to return a
                // function_call object rather than free-form assistant content when a tool matches.
                payload.put("function_call", "auto")
            }

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
        val initialResp = callLLM(initialMessages)
        val choice = initialResp.getJSONArray("choices").getJSONObject(0)
        val message = choice.getJSONObject("message")

        // Defensive handling for function_call: it may be missing, null, a JSONObject, or a string
        val funcObj: JSONObject? = when {
            message.isNull("function_call") -> null
            else -> {
                val raw = message.opt("function_call")
                when (raw) {
                    is JSONObject -> raw
                    is String -> try {
                        JSONObject(raw)
                    } catch (e: Exception) {
                        null
                    }
                    else -> null
                }
            }
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

        return@withContext message.optString("content", "")
    }
}
