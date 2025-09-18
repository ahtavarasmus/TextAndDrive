How to use ElevenLabs TTS helper

1. Add your API key to `gradle.properties` or local environment. Example in `~/.gradle/gradle.properties`:

ELEVENLABS_API_KEY=your_api_key_here

2. Expose it to BuildConfig (example in app/build.gradle.kts):

android {
defaultConfig {
buildConfigField("String", "ELEVENLABS_API_KEY", "\"${System.getenv("ELEVENLABS_API_KEY") ?: project.findProperty("ELEVENLABS_API_KEY") ?: ""}\"")
}
}

3. Call from an Activity or ViewModel:

SpeechToText.speak(context, BuildConfig.ELEVENLABS_API_KEY, "VOICE_ID", "Hello from ElevenLabs")

Notes:

- Do not hardcode API keys in source control.
- The helper writes a temporary MP3 file into the app cache directory and plays it via MediaPlayer.
- You may want to add error handling and cancellation in production code.

Speech-to-Text (SST) usage:

- To transcribe a file, call the helper: `ElevenLabsSst.speechToTextFile(apiKey, audioFile)`
- Or use the wrapper in this file: `SpeechToText.transcribeFile(apiKey, audioFile) { transcript -> ... }`
- Ensure your audio format is supported (mp3/wav). Adjust media-type in `ElevenLabsSst` if using WAV.
