<div align="center">

# SUBCOLAB

**Real-time English subtitles for international & social videos on Android — without browser tabs or Termux**  
Powered by Whisper `large-v3` on your own free Google Colab T4 GPU + built-in `libmpv` player & `yt-dlp`

[![Download APK](https://img.shields.io/badge/Download-SUBCOLAB%20v1.0-E11D48?style=for-the-badge&logo=android&logoColor=white)](https://github.com/22-db-22/SUBCOLAB/releases)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(arm64)-2563EB?style=for-the-badge)](https://github.com/22-db-22/SUBCOLAB/releases)
[![Whisper](https://img.shields.io/badge/Whisper-large--v3%20(99%20langs)-E11D48?style=for-the-badge)](#supported-platforms--media)
[![yt-dlp](https://img.shields.io/badge/yt--dlp-1%2C500%2B%20Sites-2563EB?style=for-the-badge)](#supported-platforms--media)
[![License](https://img.shields.io/badge/License-GPL--3.0-2563EB?style=for-the-badge)](LICENSE)

</div>

---

## Why SUBCOLAB?

Works on Most major video platforms—like YouTube, Dailymotion, Instagarm, Bilibili, Vimeo, TikTok, Twitch VODs, NicoNico, Reddit, X, etc .

**SUBCOLAB** solves subtitles/translation issue in a single standalone Android APK: share any video link or pick a local file, slice lightweight audio chunks directly on your phone, translate them with **Whisper `large-v3`** on your own **Google Colab T4 GPU**, and watch smoothly inside an embedded **`libmpv`** player with live-reloading English subtitles.

---

## Supported Platforms & Media

Powered by an embedded [`youtubedl-android`](https://github.com/yausername/youtubedl-android) (`yt-dlp` + `ffmpeg` + `aria2c`) engine, SUBCOLAB works across **1,500+ video extractors** plus local storage:

| Category | Compatible Platforms & Sources | Why SUBCOLAB Helps |
| :--- | :--- | :--- |
| **International & Regional Video** | **Dailymotion**, **Bilibili** (`bilibili.com`, `bangumi`), **Vimeo**, **NicoNico**, **VK Video**, **OK.ru**, **Rumble**, **Odysee**, **PeerTube** | Full-length dramas, documentaries, creator vlogs, and regional clips without English captions |
| **Social & Short-Form Apps** | **TikTok**, **Instagram Reels**, **X (Twitter)**, **Reddit**, **Facebook Watch** | Share directly from the app's **Share → SUBCOLAB** sheet to translate instantly |
| **Live VODs & Gaming Clips** | **Twitch** (VODs, clips, highlights), **Kick** | Long-form VOD translation with adaptive 40–60s audio chunking & seek-ahead jump |
| **Offline & Local Media** | Device `.mp4`, `.mkv`, `.webm`, `.mov`, and audio files | Pick any downloaded video on your phone and generate `.srt` / `.ass` / `.vtt` packs |
| **YouTube *(Optional Fallback)*** | **YouTube** (`watch`, `shorts`, `live`) | Useful when creator subtitles or YouTube's built-in auto-translate are disabled or missing |

> [!TIP]
> You can also paste links wrapped in Google, Instagram, or Facebook redirect URLs (`google.com/goto?url=...`, `l.instagram.com`, etc.)—SUBCOLAB unwraps them automatically.

----
## ScreenShots
<p align="center">
  <img width="200" alt="Home Screen" src="https://github.com/user-attachments/assets/7cc933db-1305-4d4b-aa64-fe8dc1775b59" />
  <img width="200" alt="Share Overlay" src="https://github.com/user-attachments/assets/dd179077-8f2e-4133-b84b-2b13e6fd9e83" />
  <img width="200" alt="Translating" src="https://github.com/user-attachments/assets/7c009d6a-cb01-456e-b652-07f2dca6a9a9" />
  <img width="200" alt="Downloads" src="https://github.com/user-attachments/assets/68601831-757c-4eec-9605-88762454aab5" />
</p>

<p align="center">
  <img width="200" alt="Video Quality" src="https://github.com/user-attachments/assets/36dca93a-b988-4128-95a6-41100be94c7b" />
  <img width="200" alt="Subtitle Options" src="https://github.com/user-attachments/assets/fe428a02-9fc9-4324-b0c7-758bd0aa1320" />
  <img width="200" alt="Settings Theme" src="https://github.com/user-attachments/assets/a08827db-95ba-4408-80a8-a9672776c2dc" />
  <img width="200" alt="Settings Engine" src="https://github.com/user-attachments/assets/ba7b2b76-700e-42e9-9c5d-2e5835c56db2" />
</p>

----

## Core Features

- **Zero Browser Tabs or Termux:** Native Kotlin + Jetpack Compose + Material 3 Android app (`arm64-v8a`).
- **One-Tap Share Overlay:** Tap **Share → SUBCOLAB** inside Dailymotion, Bilibili, Vimeo, TikTok, Twitch, your browser, or YouTube to open a floating launcher directly over the source app.
- **Two Tailored Workflows:**
  - **Watch Now (Live Stream):** Resolves direct streams through a local `127.0.0.1` loopback proxy, slices **only audio** (`16 kHz` mono `24 kbps` AAC) in adaptive `15s–60s` chunks, and auto-opens the `libmpv` player over your current app once the first 4 English cues arrive.
  - **Download Full MP4:** Quietly saves a `<=720p` `.mp4` along with `.srt`, `.ass`, and `.vtt` subtitle files to `Downloads/SUBCOLAB/`.
- **All ~99 Whisper Languages:** Defaults to `auto` detection with one-tap presets (`zh`, `ko`, `ja`) and full search across ~99 languages & dialects (`yue`, `es`, `fr`, `de`, `ar`, `hi`, `vi`, `th`, etc.).
- **Seek-Ahead Jump:** Scrub to any untranslated timestamp in the player—playback pauses briefly while Colab prioritizes and translates from your target timestamp.
- **Phone Wi-Fi Audio Relay:** Streams and chunks audio from your phone so Colab never hits cloud datacenter IP blocks on restricted sites.
- **Background Saving Mid-Watch:** Tap the **Download** button inside the live player anytime to save the video + translated subtitles in the background without interrupting playback.
- **System / Light / Dark Themes & Debug Logs:** Custom Light/Dark/System theming plus an in-app real-time log viewer and crash log export.

---

## How It Works

```text
┌───────────────────────────────────────────────────────────────────────────┐
│  Android Phone (Single Standalone APK — Kotlin + Jetpack Compose)         │
│                                                                           │
│  1. Share / Paste URL (Dailymotion, Bilibili, Vimeo, TikTok, ..., YT)     │
│       │                                                                   │
│       ▼                                                                   │
│  2. Embedded yt-dlp + Loopback HTTP Proxy (127.0.0.1)                     │
│       ├──► Full <=720p Video Stream ──────────────► Embedded libmpv       │
│       └──► Adaptive 15–60s Audio-Only Chunks (.aac)     ▲  (live          │
│                 │                                       │   sub-reload)   │
└─────────────────┼───────────────────────────────────────┼─────────────────┘
                  │ HTTPS / WebSocket                     │
                  ▼                                       │
┌─────────────────────────────────────────────────────────┼─────────────────┐
│  Your Google Colab T4 GPU Session (Zero Developer Servers)                │
│                                                                           │
│  3. Whisper large-v3 (CUDA) translates chunks ──► Live EN SRT/ASS/VTT ────┘
└───────────────────────────────────────────────────────────────────────────┘
```

---

## Download & Quick Start

1. Download **`SUBCOLAB-v1.0-arm64.apk`** from **[GitHub Releases](https://github.com/22-db-22/SUBCOLAB/releases)** *(Android 8.0+, 64-bit `arm64-v8a`)*.
2. Tap the `.apk` to install *(allow **Install unknown apps** if prompted)*.
3. Open **SUBCOLAB**, grant **Notifications** *(and **Display over other apps** if you want Watch Now to auto-open over source apps)*.
4. Go to **Settings → Sign in with Google** to authorize your own Google Colab session *(one-time setup)*.
5. Share a video from **Dailymotion, Bilibili, Vimeo, TikTok, Twitch, your browser, or YouTube → SUBCOLAB** *(or paste a link / pick a local file)*, choose your language and mode, and watch!

---

## Player Controls & Gestures

Inside the cinema-black `libmpv` player:

| Gesture / Control | Action |
| :--- | :--- |
| **Vertical Swipe (Left Half)** | Adjust screen brightness smoothly |
| **Vertical Swipe (Right Half)** | Adjust media volume smoothly |
| **Double-Tap Left / Right** | Seek backward / forward `10s` |
| **Scrub Timeline Ahead** | Automatically triggers **Seek-Ahead Jump** on Colab from the new timestamp |
| **Download Icon** | Save `<=720p` `.mp4` + `.srt` / `.ass` / `.vtt` in the background while watching |
| **CC Toggle / Fullscreen / PiP** | Toggle high-contrast yellow subtitles, immersive mode, or Picture-in-Picture |

---

## Privacy & Colab Limits

> [!NOTE]
> **Zero developer servers:** Your Google OAuth2 tokens stay encrypted on your phone via Android `EncryptedSharedPreferences` and communicate strictly with your own Google Colab runtime.

- Runs on Google Colab's free T4 GPU tier, so Google's standard session duration and cooldown limits apply.
- Leave the **T4 GPU warm** between videos in the Home screen for faster ~15s subtitle starts without reloading `large-v3`.
- For private or login-gated videos (e.g., Vimeo showcases, NicoNico, Bilibili HD, or age-restricted links), import a Netscape `cookies.txt` file in **Settings**.

---

## Future Upgrades & Contributor Roadmap

Planned upgrades and areas where contributions are welcome at **[github.com/22-db-22/SUBCOLAB](https://github.com/22-db-22/SUBCOLAB)**:

- **Android TV / Google TV Support:** Leanback launcher entry, full D-pad/remote navigation, and living-room subtitle scaling in `libmpv`.
- **Target Output Languages Beyond English:** Translate captions into any target language (Hindi, Spanish, Arabic, Japanese, etc.) instead of Whisper's English-only `translate` mode.
- **Direct MPEG-DASH (`.mpd`) & Live Stream Tailing:** `.mpd` XML rewriting inside `LoopbackMediaProxy` plus continuous sliding-window translation for active livestreams.
- **Dual-Language Stacked Subtitles & Timing Offset:** Render original + translated tracks together in `.ass` and add live `±100ms` subtitle delay controls in the player.
- **On-Device Offline Mode (`whisper.cpp`):** Optional downloadable quantized models (`tiny`/`base`/`small`) for short clips with zero Colab or internet dependency.
- **In-App Stream Sniffer & Speaker Diarization:** Optional WebView sniffer to grab embedded `.m3u8`/`.mpd` streams directly, plus `[Speaker 1]` labels for podcasts and debates.


Contributions, forks, and pull requests are welcome at **[github.com/22-db-22/SUBCOLAB](https://github.com/22-db-22/SUBCOLAB)**!

---

## License & Attributions

- Repository: **[github.com/22-db-22/SUBCOLAB](https://github.com/22-db-22/SUBCOLAB)**
- Distributed under **[GPL-3.0](LICENSE)**. See **[NOTICE](NOTICE)** for full third-party credits and open-source licenses.
- Based on & inspired by [OpenScribe](https://github.com/faisal-saddique/openscribe) (MIT) by Faisal Saddique.
