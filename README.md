# SUBCOLAB

**SUBCOLAB v1.0** is a free, open-source standalone Android app (Kotlin + Jetpack Compose + Material 3) that translates foreign-language videos into crisp yellow English subtitles on your own free **Google Colab T4 GPU** and plays or downloads them inside **one single APK**.

No Termux. No external `mpv-android`. No `All files access` permission. No developer backend servers.

---

## How SUBCOLAB Enhances OpenScribe

**SUBCOLAB** is based on and inspired by the [OpenScribe](https://github.com/faisal-saddique/openscribe) Colab workflow by **Faisal Saddique** (MIT License), expanding it into a self-contained native Android experience:

- **Smooth Live Streaming (`Watch Now`) with Embedded `libmpv`**: Streams `<=720p` video smoothly through a local OkHttp loopback proxy (`Range` + HLS M3U8 + split DASH audio) on a cinema-black player with live-reloading yellow English subtitles, brightness/volume swipe gestures, immersive landscape fullscreen, and Picture-in-Picture (PiP).
- **Adaptive Audio-Only Chunking & Phone Wi-Fi Audio Relay**: Slices only lightweight audio chunks (`15s`–`60s` 16 kHz mono AAC) and streams them to your Colab T4 GPU so live video playback is never slowed down—even when YouTube blocks Colab datacenter IPs.
- **Direct Share Integration (`YouTube → Share → SUBCOLAB`)**: Pick your source language (~99 Whisper languages) and tap **Watch Now** (smooth live stream that auto-opens once initial subtitles arrive) or **Download** (saves a full `<=720p` MP4 + `.srt`/`.ass`/`.vtt` quietly in the background).
- **Seek-Ahead & Warm T4 Session Reuse**: Jump ahead to any untranslated timestamp to prioritize that section on Colab T4, and keep the T4 runtime warm between videos for faster startup.
- **System / Light / Dark Theme**: Choose your preferred appearance in Settings while keeping the video player cinema-black with `#FFFF00` subtitles.

---

## Quick Install (From GitHub Releases)

1. Go to **[Releases](https://github.com/22-db-22/SUBCOLAB/releases)** and download **`SUBCOLAB-v1.0-arm64.apk`** (for Android 8.0+ 64-bit phones).
2. Tap the downloaded `.apk` in your phone's **Files** app and allow **"Install from this source"** when prompted.
3. Open **SUBCOLAB** and allow **Notifications** (keeps background translation running smoothly).
   - *(Recommended)* Allow **"Display over other apps"** when prompted so **Watch Now** can pop open automatically over YouTube as soon as initial subtitles arrive.
4. Tap the **Settings** gear icon (top right) → **Sign in with Google (Colab)** to complete the one-time OAuth2 sign-in.
5. Open YouTube (or any supported video app), tap **Share → SUBCOLAB**, pick the spoken language (or leave `Auto`), and tap **Watch Now** or **Download**!

---

## Privacy & Google Colab Note

- **Zero Developer Servers**: SUBCOLAB communicates directly between your phone and your own Google Colab runtime.
- **Encrypted Local Tokens**: Your Google OAuth tokens stay strictly on your device inside Android's `EncryptedSharedPreferences`.
- **Your Own Free Colab T4 Tier**: Whisper `large-v3` runs on your personal Google Colab GPU quota. Remember to tap **Stop T4 GPU Runtime** when you are done watching.
- **Scoped Storage**: Downloads are saved cleanly via `MediaStore.Downloads` without requesting `MANAGE_EXTERNAL_STORAGE`.

---

## Optional: Building from Source

This repository includes a ready-to-run GitHub Actions workflow (`.github/workflows/build-apk.yml`), or you can build locally with JDK 17 + Android SDK:

```bash
git clone https://github.com/22-db-22/SUBCOLAB.git
cd SUBCOLAB
gradle testDebugUnitTest
gradle assembleDebug
```

---

## License & Open Source Credits

- **License**: Distributed under the **GNU General Public License v3.0** (`LICENSE`), required for public distribution with `youtubedl-android` (`yt-dlp` + `FFmpeg`) and `libmpv`.
- **Upstream Attribution**: Based on & inspired by [OpenScribe](https://github.com/faisal-saddique/openscribe) by **Faisal Saddique** (MIT License — preserved in `NOTICE`).
- **Powered By**: OpenAI Whisper, SYSTRAN `faster-whisper`, `google-colab-cli` protocol, `libmpv`, and `youtubedl-android` (`yt-dlp` + `FFmpeg`).
- **Created by**: `writtenwithharmony@gmail.com`
