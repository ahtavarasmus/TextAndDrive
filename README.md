**TextAndDrive** is an Android app for voice-based messaging while driving or multitasking. It integrates with Beeper on-device to let you speak commands to send texts across apps like WhatsApp and iMessage, with incoming messages read aloud. Powered by AI with Marvin's sarcastic personality from *The Hitchhiker's Guide to the Galaxy*, it processes natural voice inputs securely with a private LLM and with few tweaks can be made zero trust private.

Built for the [Junction 2025 AI Agent Hackathon](https://www.hackjunction.com/).

![Demo Video](https://github.com/user-attachments/assets/4b20871c-4e7f-4573-b501-079c679d7fd8)  
*(Hold screen to speak, release to process—Marvin confirms the action.)*

## How It Works
1. **Record**: Press and hold *anywhere* on the screen to capture voice via microphone.
2. **Process**: Release to transcribe (Tinfoil STT), analyze intent (Tinfoil LLM with Beeper tools), and act (e.g., send message).
3. **Respond**: Wait for TTS confirmation or message readout.

Demo mode uses mock chats; real mode accesses your Beeper data.

## Prerequisites
- Android 8.0+ device with [Beeper](https://www.beeper.com/) installed (same device required).
- [ElevenLabs](https://elevenlabs.io/) and [Tinfoil](https://tinfoil.sh/) API keys.
- Android Studio or Gradle for building.

## Build and Installation
1. Clone: `git clone https://github.com/ahtavarasmus/TextAndDrive.git && cd TextAndDrive`
2. Connect device (USB Debugging on).
3. Add keys to `local.properties`:
   ```
   ELEVENLABS_API_KEY=your_key
   TINFOIL_API_KEY=your_key
   ```
4. Build: `./gradlew installDebug` (CLI) or Run in Android Studio.

## Setup and Usage
- Launch and choose **Demo** (no Beeper) or **Real** mode.
- Grant permissions: Beeper read/send, microphone (and notifications on Android 13+).
- **Interact**: Hold screen to speak (e.g., "Send message to Mom on whatsapp that I'm running late"), release, and listen for response.
- "Read chats from Rasmus on discord".

## Features
- **Voice Pipeline**: Tinfoil STT for input; ElevenLabs TTS for output.
- **Smart Actions**: AI fetches/sends chats/messages via Beeper; supports queries like "What's new from Ford?"
- **Modes**: Demo (Marvin's chats mock data) vs. Real (your chats).
- **Privacy**: On-device Beeper access (use Beeper's on-device chats for full e2ee); Transcription and LLM processing happen without third party. For full verifiability, add Tinfoil TTS + pre-call verifier (for zero trust privacy).

## Important Notes
- **Safety**: Hands-free only—test safely, obey laws. Only recommended to be used while driving cars inside video games.

Apache 2.0 Licensed. Questions? [Issues](https://github.com/ahtavarasmus/TextAndDrive/issues).
