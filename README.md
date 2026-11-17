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
- **Zero browser tabs or Termux:** Standalone native Kotlin + Jetpack Compose Android app.
- **One-Tap Share from YouTube:** Tap **Share → SUBCOLAB** in YouTube or any browser, or paste a link directly.
- **Live Streaming Playback:** Built-in `libmpv` player opens automatically once 4 English cues buffer and reloads subtitles live in-app.
- **All ~99 Languages:** Defaults to `auto` with quick presets for Chinese (`zh`), Korean (`ko`), Japanese (`ja`), or any Whisper language.
- **Seek-Ahead Jump:** Scrub anywhere in the player—playback pauses briefly while Colab jumps translation to your timestamp.
- **Background Saving:** Save `<=720p` `.mp4` + `.srt` / `.ass` / `.vtt` directly to `Downloads/SUBCOLAB/` (even mid-watch).
- **Phone Wi-Fi Audio Relay:** Automatically bypasses YouTube IP blocks on Colab.
- **Customizable UI:** Red & Blue branding, System / Light / Dark themes, and an in-app Debug Logs viewer.

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

## License & Attributions
Distributed under **[GPL-3.0](LICENSE)**. See **[NOTICE](NOTICE)** for full third-party credits and open-source licenses.
