<h1 align = "center">VoiceNotes Application - AI Powered Meeting Recorder</h1>

<h2>Demo Video:</h2>

[![TwinMind](https://i.ytimg.com/vi/SOwRPS5BnYo/hqdefault.jpg)](https://www.youtube.com/watch?v=SOwRPS5BnYo)

<h2>3 Core Features</h2>

*  Record Audio Robustly: Background recording with explicit interruptions handling 
*  Generate Transcript: Convert audio to transcript
*  Generate Summary: Create structured summary from transcript

<h2>Technologies</h2>

*  Kotlin
*  Jetpack Compose (Material 3) – UI
*  Android Services (Foreground Service) – background recording
*  Notification MediaStyle + MediaSessionCompat – lock-screen controls
*  BroadcastReceiver – service ↔ UI communication
*  AudioManager / MediaRecorder – audio capture
*  WorkManager – background summary generation
*  Room Database – local persistence
*  Hilt (Dagger) – dependency injection
*  Coroutines & Flow – async + reactive data
*  OpenAI Whisper API – speech-to-text transcription
*  OpenAI Chat Completions API – meeting summaries

<h2>🧠 AI Summary</h2>

- Automatically generates:
  - **Title**
  - **Summary**
  - **Action Items**
  - **Key Points**
- Summary generation runs safely in background
- Retry support on network failure
- Summary persists even if app is closed or killed

### ⚠️ Smart Safety Handling
- Silent audio detection (warns after 10 seconds of no input)
- Low storage protection:
  - Prevents recording from starting
  - Stops gracefully if storage runs out
- Clear warning messages shown in UI and notification

### 🎧 Audio & Device Handling
- Automatic pause/resume on:
  - Incoming or outgoing phone calls
  - Audio focus loss from other apps
- Detects and adapts to:
  - Bluetooth headset connect/disconnect
  - Wired headset plug/unplug
- Displays current input source:
  - Built-in mic
  - Wired mic
  - Bluetooth mic (SCO)
  
