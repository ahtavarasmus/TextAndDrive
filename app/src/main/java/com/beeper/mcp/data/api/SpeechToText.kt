package com.beeper.mcp.data.api

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Example wrapper showing how to call `ElevenLabsTts` from UI code.
 *
 * Secure your API key: don't hardcode. Use BuildConfig or Android Keystore.
 * Example: BuildConfig.ELEVENLABS_API_KEY (define it in gradle / local.properties or CI secrets)
 */
object SpeechToText {
	fun speak(context: Context, apiKey: String, voiceId: String, text: String) {
		CoroutineScope(Dispatchers.Main).launch {
			try {
				val file = ElevenLabsTts.textToSpeech(context, apiKey, voiceId, text)
				ElevenLabsTts.playFromFile(context, file)
			} catch (e: Exception) {
				e.printStackTrace()
			}
		}
	}

	/**
	 * Example: send a recorded/picked audio file for speech-to-text and receive the transcript.
	 */
	fun transcribeFile(apiKey: String, audioFile: java.io.File, onResult: (String) -> Unit) {
		CoroutineScope(Dispatchers.IO).launch {
			try {
				val result = ElevenLabsSst.speechToTextFile(apiKey, audioFile)
				CoroutineScope(Dispatchers.Main).launch { onResult(result) }
			} catch (e: Exception) {
				e.printStackTrace()
				CoroutineScope(Dispatchers.Main).launch { onResult("ERROR: ${e.message}") }
			}
		}
	}
}

