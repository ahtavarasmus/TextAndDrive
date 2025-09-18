# TextAndDrive

An Android app that allows voice chatting with your chat apps while driving without having to look at the phone screen. It integrates with Beeper to enable hands-free messaging: speak to send messages, and have incoming messages read aloud via text-to-speech.

## Prerequisites

- **Android device** with Beeper Android app installed
- **Same device requirement**: This app must be installed on the same Android device as Beeper
- Android 8.0 (API level 26) or higher
- Android Studio or build tools for compilation

## Build and Installation

### 1. Clone the Repository
```bash
git clone https://github.com/ahtavarasmus/TextAndDrive.git
cd TextAndDrive
```

### 2. Connect Your Device
1. Connect your Android device via USB
2. Enable **Developer Options** and **USB Debugging**

### 3. Build and Install

#### Add 11Labs api key to root local.properties
```
ELEVENLABS_API_KEY=
```

#### Option A: Command Line
```bash
./gradlew installDebug
```

#### Option B: Android Studio
1. Open the project in Android Studio
2. Sync project with Gradle files
3. Run the app: **Run → Run 'app'**

## Setup and Usage

### 1. Grant Permissions
After installation, open the TextAndDrive app and:
- Grant **Beeper permissions** (read/send access)
- Grant **Microphone permission** (for speech-to-text input)
- Once permissions are granted, you're all set! The app can now handle voice interactions with Beeper.

### 2. Using the App
- Hold the screen to record your voice message (speech-to-text via ElevenLabs).
- The app will process and send messages through Beeper.
- Incoming messages can be read aloud (text-to-speech support via ElevenLabs).

## Features

- **Voice Input**: Record and send messages hands-free.
- **Message Reading**: Have chats, contacts, and messages read aloud.
- **Chat Management**: Retrieve and interact with chats, contacts, and messages via voice commands.

## Important Notes

- **Device Requirement**: The app must run on the same Android device as Beeper to access chat data
- **Permissions**: Beeper read/send and microphone permissions are required for voice chatting
- **Safety First**: Always prioritize safe driving—use this app responsibly and in compliance with local laws
