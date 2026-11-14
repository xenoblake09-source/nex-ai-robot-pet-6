# NEX 6.0 — AI-Powered Virtual Robot Pet (`nex-ai-robot-pet-6`)

**Project Name:** NEX 6.0  
**Application ID:** `com.nex.robotpet.v6`  
**Application Name:** `NEX`  
**Purpose:** School Computer Exhibition — Montfort Senior Secondary School, Bagaha  
**Creators:** **ARNAV, DIVYA AND ARYAN**  
**Canonical Creator Response:** `"ARNAV, DIVYA AND ARYAN created me."`

---

## Overview

**NEX 6.0** is a clean-room native Kotlin Android application paired with a secure Python 3.11 FastAPI backend powered exclusively by Google's official `google-genai` SDK. It is completely independent of NEX 5.0 and any older repositories, Render services, or APKs.

### Core Capabilities

1. **Pure Robot Face Main Screen (`MainActivity` + `RobotEyesView`)**
   - Contains **ONLY**:
     1. Pure black `#000000` background.
     2. Two large glowing square EMO-inspired robot eyes.
     3. One small, non-interactive `ONLINE` / `OFFLINE` corner indicator.
   - Zero long-press menus, zero hidden settings hubs, zero tap-on-status-pill panels, zero extra buttons, zero headers/subtitles, and zero text clutter on the main screen.
   - **17 Offline 60-FPS Eye Expressions**: Happy, Sad, Angry, Sleep, Dance, Blink, Surprised, Thinking, Listening, Gaming, Laugh, Cry, Love/Heart, Curious, Excited, Confused, and Custom.
2. **External Non-Interfering Setup (`BackendSetupActivity`)**
   - Pre-configured default HTTPS URL `https://nex-ai-robot-pet-6-backend.onrender.com` works out of the box when your Render service uses that name.
   - If you ever need to set a custom Render HTTPS URL, manage memory/privacy toggles, wipe stored user data, or customize the 17th (`CUSTOM`) eye preset, open **Backend Setup** completely outside the robot face via:
     - **Method A**: Press and hold the **NEX app icon** on your Android Home Screen / App Drawer -> tap **Backend Setup**.
     - **Method B**: Open Android **Settings -> Apps -> NEX -> Additional settings in the app**.
     - **Method C**: Open `nex6://setup` in your mobile browser.
3. **Multilingual Voice System (`VoiceSystemManager`)**
   - Speech-to-Text + Text-to-Speech supporting **English**, **Hindi (Devanagari)**, and **Hinglish** with automatic reply language matching.
   - Foreground `"Hey NEX"` wake-phrase standby + single-tap face wake, acoustic self-echo prevention, and bounded error backoff preventing endless microphone restart loops.
4. **Real Google Gemini AI Backend (`backend/app.py`)**
   - Uses **only** the official `google-genai` SDK (`client.models.generate_content`).
   - Reads `GEMINI_API_KEY` and `GEMINI_MODEL` (default: `gemini-3.5-flash-lite`, verified stable Free-Tier multimodal model in Google's official docs; also supports `gemini-3.8-flash` or `gemini-4-hydrogen`) strictly from server environment variables—never stored in APK, Android code, GitHub, or logs.
   - Implements connection/request timeouts, bounded retries with exponential backoff on transient `503`/`504`/timeout errors, sliding-window rate limiting, payload size caps, and safe HTTP error diagnostics (`401`, `403`, `404`, `429`, `503`, `504`).
5. **20 Verified Real Animal Audio Assets (`res/raw/animal_*.wav`)**
   - Dog, Cat, Cow, Lion, Tiger, Elephant, Horse, Sheep, Goat, Monkey, Duck, Chicken, Rooster, Wolf, Fox, Bear, Snake, Frog, Bird, and Dolphin — triggered individually by voice or via `"Open animal sounds"` (Play, Stop, Volume).
6. **Camera & Computer Vision (`VisionActivity`)**
   - Launched by voice (`"Open camera"` / `"What is this?"` / `"Yeh kya hai?"`). CameraX front/rear preview with **offline on-device ML Kit Face Detection, Face Presence, and Live Eye Gaze Tracking**, plus **online Gemini Vision** object recognition (`POST /vision`). Never activates without explicit user permission.
7. **Music & Dance Studio (`MusicDanceActivity`)**
   - Launched by voice (`"Play music"` / `"Gaana chalao"`). Plays user-selected local audio files via Android Storage Access Framework (no copyrighted music bundled), with AudioFocus management, beat-synced eye animations, and random dance moves.
8. **NEX Arcade — 7 Complete Mini-Games (`GamesHubActivity` + `MiniGameActivity`)**
   - Launched by voice (`"Let's play a game"` or by game name): *Tic-Tac-Toe vs NEX*, *Memory Matrix Match*, *Catch the Falling Stars*, *NEX Simon Sequence*, *Whack-a-Glitch*, *Reaction Speed Reflex*, and *Math Blaster Quiz* — each with instructions, live score, persistent best score, and instant Restart.
9. **Live Weather, Time, Date & Verified Montfort Bagaha School Knowledge**
   - Real local clock/date, live Open-Meteo HTTPS weather (never invented offline), real `ConnectivityManager` online/offline indicator, and verified facts from `https://montfortbagaha.org/` (including Principal **Bro. Vincent Madhanu**).

---

## Browser-Only Deployment Guide (No Terminal Required)

### 1. Create a Fresh GitHub Repository
1. Open [https://github.com/new](https://github.com/new) in your browser.
2. Set Repository name to `nex-ai-robot-pet-6` (do **not** touch your old NEX 5.0 repository).
3. Upload the extracted contents inside `nex-ai-robot-pet-6/` from `NEX_6.0_COMPLETE_SOURCE.zip` via GitHub's web uploader and commit.

### 2. Deploy the Backend on Render
1. Open [https://dashboard.render.com/](https://dashboard.render.com/) -> **New +** -> **Web Service**.
2. Connect your new `nex-ai-robot-pet-6` repository.
3. Set:
   - **Name**: `nex-ai-robot-pet-6-backend`
   - **Root Directory**: `backend`
   - **Runtime**: `Python 3`
   - **Build Command**: `pip install -r requirements.txt`
   - **Start Command**: `uvicorn app:app --host 0.0.0.0 --port $PORT`
4. Under **Environment Variables**, add:
   - `GEMINI_API_KEY` = *(Your Google AI Studio API Key — keep secret)*
   - `GEMINI_MODEL` = `gemini-3.5-flash-lite`
   - `PYTHON_VERSION` = `3.11.9`
5. Click **Deploy Web Service**. Once live, open `https://<your-service>.onrender.com/health` in your browser and confirm `"status": "ok"` and `"gemini_configured": true`.

### 3. Install `NEX_6.0_ANDROID.apk` & Connect URL (If Custom)
1. Copy `NEX_6.0_ANDROID.apk` to your Android phone and install it.
2. If your Render URL is `https://nex-ai-robot-pet-6-backend.onrender.com`, NEX is already pre-configured automatically!
3. If Render assigned a different URL (e.g. `https://nex-ai-robot-pet-6-backend-xxxx.onrender.com`), **press and hold the NEX app icon on your phone's Home Screen** -> tap **Backend Setup** -> paste your HTTPS URL -> tap **Verify /health Connection** -> tap **Save & Launch NEX**.

---

## Documented Technical Limitations

- **Android Wake-Word Constraints**: Android restricts continuous background microphone access and OEM `SpeechRecognizer` implementations impose brief recognition windows. `"Hey NEX"` wake-phrase standby operates while NEX is open in the foreground (`keepScreenOn` keeps NEX awake during exhibitions); single-tapping NEX's eyes is always available as an instant trigger.
- **Online vs Offline Features**: All 17 eye expressions, blinking/gaze physics, 20 animal sounds, 7 mini-games, local music playback & dance, time/date, creator identity, Montfort Bagaha school facts, and on-device ML Kit face detection/tracking work **100% offline**. Conversational Gemini AI (`/chat`), Gemini camera object recognition (`/vision`), and live Open-Meteo weather require an active internet connection.
- **Render Free-Tier Cold Starts**: Render Free web services spin down after ~15 minutes of inactivity and take ~30–50 seconds on the first wake request. Open `/health` in a browser 1 minute before exhibition judging begins.
