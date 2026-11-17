# OpenScribe Android

**OpenScribe Android** is a standalone, free & open-source Android app (Kotlin + Jetpack Compose + Material 3) that translates foreign-language videos into crisp yellow English subtitles on a free **Google Colab T4 GPU** and plays or saves them inside **one single APK**.

No Termux. No external `mpv-android`. No `All files access` permission. No manual `mpv.conf` editing.

---

## What's Inside One APK

- **Embedded `libmpv` Video Player** (`dev.jdtech.mpv:libmpv:1.0.0`): Hardware-decoded playback with live-reloading yellow English subtitles, split `video + audio-file` support, brightness/volume swipe gestures, true immersive landscape fullscreen (hiding system status & navigation bars), in-player background **Save Video + Subtitles** button, Picture-in-Picture (PiP), and **Seek-Ahead** (jump forward to an untranslated timestamp and OpenScribe pauses with a spinner, tells Colab T4 to jump there, and auto-resumes when subtitles arrive).
- **Embedded `yt-dlp` + `ffmpeg` + `aria2c`** (`youtubedl-android`): Resolves `<=720p` streams cleanly, unwraps `google.com/goto` and `youtube.com/shorts` links, saves `.mp4` + `.srt`/`.ass`/`.vtt` to `Downloads/OpenScribe/`, and automatically relays audio over your phone's Wi-Fi if YouTube ever blocks Colab's datacenter IP.
- **Headless Google Colab T4 Client**: Implements the open-source `google-colab-cli` OAuth2 & Jupyter WebSocket protocol directly in Kotlin (`large-v3` on T4 CUDA, automatic dead-runtime recovery, warm T4 reuse menu).

---

## Beginner Step-by-Step Guide: Get Your APK Without Installing Android Studio

You do **not** need Android Studio or a powerful PC! GitHub's free cloud servers will compile the `.apk` for you automatically.

### Step 1: Upload This Project to a Free GitHub Account
1. Create a free account at [github.com](https://github.com) if you don't have one yet.
2. Click the **+** icon at the top right → **New repository**, name it `openscribe-android`, and click **Create repository**.
3. Push (or upload) all files from this project folder to the `main` branch:
   ```bash
   git init
   git add .
   git commit -m "Initial OpenScribe Android"
   git branch -M main
   git remote add origin https://github.com/YOUR_USERNAME/openscribe-android.git
   git push -u origin main
   ```

### Step 2: Download Your Compiled `.apk` from GitHub Actions
1. Open your repository on GitHub and click the **Actions** tab near the top.
2. Click on the running workflow **"Build OpenScribe Android APK"** (if it hasn't started, click **Run workflow**).
3. Wait ~4–6 minutes for the green checkmark ✅.
4. Scroll down to **Artifacts** and click **OpenScribe-Android-APKs** to download the `.zip` file.
5. Unzip it on your phone (or PC and transfer it). Inside you'll find:
   - `app-arm64-v8a-debug.apk` (recommended for almost all modern Android phones — smaller file)
   - `app-universal-debug.apk` (works on every Android architecture)

### Step 3: Install & Set Up on Your Android Phone
1. Tap the `.apk` file in your phone's **Files** app and allow **"Install from this source"** when prompted.
2. Open **OpenScribe** and allow **Notifications** when asked (this lets OpenScribe keep translating on the T4 GPU while you minimize the app).
3. Tap the **Settings** gear icon (top right) → **Sign in with Google (Colab)**:
   - Your browser opens Google Sign-In (`sdk.cloud.google.com`, matching `google-colab-cli`). Approve access, copy the authorization code shown on Google's page, and paste it into OpenScribe (your token is stored securely in Android's encrypted storage).
   - *(Optional: You can also enter your own Desktop OAuth Client ID & Secret in Settings if you prefer one-tap `http://localhost` loopback sign-in.)*

### Step 4: Test `YouTube → Share → OpenScribe`
1. Open the YouTube app on any Japanese, Korean, Chinese, Spanish, etc. video.
2. Tap **Share → OpenScribe** (or copy the link and paste it on OpenScribe's home screen).
3. Choose:
   - **Source Language**: `auto` (default, ~99 languages), quick chips (`zh`, `ko`, `ja`), or tap **All ~99 Languages** to search.
   - **Mode**:
     1. **Watch Live + Save** (live player + saves `<=720p` `.mp4` + `.srt`/`.ass`/`.vtt` to `Downloads/OpenScribe/`)
     2. **Offline Pack Only** (translates & saves without opening the player)
     3. **Watch Live Only** (streams live in the built-in player)
4. Tap **Start T4 GPU Worker**. You can minimize the app — as soon as **4 English subtitle cues** arrive, OpenScribe automatically opens the built-in `libmpv` player (or posts a tap-to-watch notification) with live yellow English subtitles!
5. When done, your Colab T4 stays warm so you can tap **[A] Run Previous** (smart cache skips Whisper and/or MP4 download) or **[B] New Link**, or tap **Stop T4 GPU** when finished.

---

## Optional: Importing `cookies.txt`
If YouTube ever asks for login cookies on age-restricted or rate-limited videos:
1. Export a Netscape `cookies.txt` file from a logged-in browser.
2. In OpenScribe → **Settings** → **Import cookies.txt**, pick the file using Android's standard file picker.
3. OpenScribe stores it privately inside its own app folder (`filesDir/cookies.txt` — no `All files access` needed) and uploads it to `/content/cookies.txt` on Colab automatically.

---

## Building Locally (Optional)
If you prefer building on your own computer with Android Studio / JDK 17:
```bash
gradle testDebugUnitTest
gradle assembleDebug
```
The compiled APKs will appear in `app/build/outputs/apk/debug/`.
