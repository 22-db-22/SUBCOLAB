<div align="center">

# SUBCOLAB

**Real-time English subtitles for non-English videos on Android**  
Powered by Whisper `large-v3` on your own free Google Colab T4 GPU + built-in `libmpv` player

[![Download APK](https://img.shields.io/badge/Download-SUBCOLAB%20v1.0-E11D48?style=for-the-badge&logo=android&logoColor=white)](https://github.com/22-db-22/SUBCOLAB/releases)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(arm64)-2563EB?style=for-the-badge)](https://github.com/22-db-22/SUBCOLAB/releases)
[![Whisper](https://img.shields.io/badge/Whisper-large--v3%20(99%20langs)-E11D48?style=for-the-badge)](#features)
[![License](https://img.shields.io/badge/License-GPL--3.0-2563EB?style=for-the-badge)](LICENSE)

</div>

---

## Features
- **Zero browser tabs or Termux:** Native Kotlin + Jetpack Compose Android app.
- **One-Tap Share from YouTube:** Tap **Share → SUBCOLAB** in YouTube or any browser, or paste a link directly.
- **Live Streaming Playback:** Built-in `libmpv` player opens automatically once 4 English cues buffer and reloads subtitles live in-app.
- **All ~99 Languages:** Defaults to `auto` with quick presets for Chinese (`zh`), Korean (`ko`), Japanese (`ja`), or any Whisper language.
- **Seek-Ahead Jump:** Scrub anywhere in the player—playback pauses briefly while Colab jumps translation to your timestamp.
- **Background Saving:** Save `<=720p` `.mp4` + `.srt` / `.ass` / `.vtt` directly to `Downloads/SUBCOLAB/` (even mid-watch).
- **Phone Wi-Fi Audio Relay:** Automatically bypasses YouTube IP blocks on Colab.
- **Customizable UI:** Red & Blue branding, System / Light / Dark themes, and an in-app Debug Logs viewer.

---

## How SUBCOLAB Differs From Original OpenScribe
This project builds on the Colab Whisper workflow from **[faisal-saddique/openscribe](https://github.com/faisal-saddique/openscribe)** (MIT License) and turns it into a standalone live Android app:

| Feature | `faisal-saddique/openscribe` (Original) | SUBCOLAB (Android) |
| :--- | :--- | :--- |
| **Interface** | Google Colab notebook in a browser (10 manual cells) | Native Android app (Kotlin + Jetpack Compose) |
| **Input** | Manual URL paste in notebook cell | **YouTube → Share → SUBCOLAB** or paste link |
| **Colab GPU** | Manual browser tab | Headless OAuth2 + Runtime/WebSocket API |
| **Playback** | Wait for full video to finish, download `.zip` | Live streaming into built-in `libmpv` player at 4 cues |
| **Seeking** | Not supported | **Seek-Ahead Jump** directly on Colab |
| **Blocks & Saving** | Colab only | Built-in `youtubedl-android` audio relay & MP4 saving |

---

## Download & Use
1. Download **`SUBCOLAB-v1.0-arm64.apk`** from **[Releases](https://github.com/22-db-22/SUBCOLAB/releases)** *(Android 8.0+, 64-bit `arm64-v8a`)*.
2. Tap the `.apk` to install *(allow "Install unknown apps" if prompted)*.
3. Open **SUBCOLAB**, allow **Notifications**, and go to **Settings → Sign in with Google** *(one-time Colab authorization)*.
4. Share any video from **YouTube → SUBCOLAB**, pick your language and mode, and watch!

> [!TIP]
> Inside the player, **swipe left** vertically for brightness, **swipe right** for volume, **double-tap** left/right to seek ±10s, or tap the **Download** icon anytime to save the video + subtitles in the background.

---

## Privacy & Colab Limits
> [!NOTE]
> **No developer servers:** Your Google OAuth tokens stay encrypted locally on your device (`EncryptedSharedPreferences`) and connect directly to your own Google Colab session.

- Runs on Google Colab's free T4 GPU tier, so Google's normal session limits and cooldowns apply.
- If YouTube strictly requires login cookies for a video, import a Netscape `cookies.txt` file in **Settings**.

---

## Credits & Licenses
- Distributed under **[GPL-3.0](LICENSE)** (required by bundled `libmpv` / `FFmpeg` / `youtubedl-android` components). See **[NOTICE](NOTICE)** for full attributions.
- Original Colab Whisper workflow inspired by **[faisal-saddique/openscribe](https://github.com/faisal-saddique/openscribe)** (**MIT License**).
- Powered by [OpenAI Whisper](https://github.com/openai/whisper), [SYSTRAN/faster-whisper](https://github.com/SYSTRAN/faster-whisper), [google-colab-cli](https://github.com/googlecolab/google-colab-cli), [dev.jdtech.mpv/libmpv](https://github.com/jarnedemeulemeester/libmpv-android), [youtubedl-android](https://github.com/junkfood02/youtubedl-android), [yt-dlp](https://github.com/yt-dlp/yt-dlp), and [FFmpeg](https://ffmpeg.org/).
