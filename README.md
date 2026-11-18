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

Most major video platforms—like **YouTube, Dailymotion, Bilibili, Vimeo, TikTok, Twitch VODs, NicoNico, Reddit, X, and regional broadcasters**—do **not** offer built-in real-time English translation for foreign-language videos.

**SUBCOLAB** solves this in a single standalone Android APK: share any video link or pick a local file, slice lightweight audio chunks directly on your phone, translate them with **Whisper `large-v3`** on your own **Google Colab T4 GPU**, and watch smoothly inside an embedded **`libmpv`** player with live-reloading English subtitles.

*(Because YouTube already provides built-in auto-translated captions on most videos, SUBCOLAB focuses primarily on platforms that lack native translation—while still supporting YouTube whenever captions are disabled, region-locked, or inaccurate.)*

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

---

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

SUBCOLAB is designed as a modular foundation for mobile AI subtitling. Below are the highest-impact upgrades contributors can build next:

### 1. Direct DASH (`.mpd`), HLS (`.m3u8`), & Raw Stream Links with Translated Subtitles

**Why it matters:** Today, `StreamExtractor` sends every URL through `yt-dlp` page scraping first, while the local `LoopbackMediaProxy` already rewrites HLS `.m3u8` playlists. Many third-party web players, international broadcasters, anime/drama hosts, and IPTV feeds expose direct `.mpd` (MPEG-DASH) or `.m3u8` (HLS) manifest URLs—often protected by short-lived signed tokens or `Referer`/`Origin` checks—where `yt-dlp` fails without full webpage context even though `libmpv` and `ffmpeg` can play the manifest natively.

**Proposed Upgrade Architecture:**
- **Direct Manifest Fast-Path (`yt-dlp` Bypass):** Detect `.mpd`, `.m3u8`, `.mp4`, `.mkv`, `.ts`, and `mime=` stream URLs in `UrlUnwrapper` / `StreamExtractor` and route them straight to `LoopbackMediaProxy` + `libmpv` without invoking `yt-dlp` extraction.
- **MPEG-DASH (`.mpd`) Manifest Rewriting & Audio-Track Slicing:** Mirror the existing `rewriteM3u8Playlist` helper with an `.mpd` XML rewriter inside `LoopbackMediaProxy` that resolves relative `<BaseURL>` and `<SegmentTemplate>` paths, lets users select a specific foreign-language `<AdaptationSet contentType="audio">`, and feeds only that audio track to `ffmpeg` for 15–60s AAC chunking.
- **Custom HTTP Header & Browser-Sniffer Extras:** Add optional `Referer`, `Origin`, `User-Agent`, and `Cookie` fields in the Home link sheet (plus Intent extras support so browser stream-sniffer apps can share `.m3u8`/`.mpd` links along with their headers).
- **Sliding-Window Live Manifest Tailing:** Poll `EXT-X-MEDIA-SEQUENCE` (HLS) and `MPD@type="dynamic"` segments incrementally so active livestreams receive rolling translated subtitles in near real-time.

---

### 2. More Community Upgrades to Build On

| Upgrade Track | What to Build | Impact |
| :--- | :--- | :--- |
| **Dual-Language Stacked Subtitles** | Combine the original spoken transcript (`.orig.srt`) and English translation into a styled `.ass` track rendered simultaneously in `libmpv` | Ideal for language learners watching Bilibili, NicoNico, or Dailymotion |
| **Built-In Lightweight Stream Sniffer** | Add an optional in-app WebView tab that intercepts `.m3u8` and `.mpd` network requests on any webpage and offers a one-tap **Translate Stream** button | Eliminates external sniffer apps for embedded web videos |
| **Speaker Diarization & Custom Glossary** | Integrate Whisper `initial_prompt` (character names, domain terms) and lightweight speaker labeling (`[Speaker 1]`) inside `colab_worker.py` | Major readability boost for multi-host podcasts, debates, and anime |
| **On-Device Offline Fallback (`whisper.cpp`)** | Bundle or dynamically download quantized `whisper.cpp` (`tiny` / `base` / `small` GGUF) with Vulkan/NNAPI acceleration | Translate short clips and local videos with zero internet or Colab quota |
| **Subtitle Timing Offset & Sync Library** | Add `±100ms` subtitle delay controls in `MpvPlayerController`, font size/outline presets, and optional WebDAV/Drive sync for saved packs | Polished viewing control and cross-device subtitle backups |

Contributions, forks, and pull requests are welcome at **[github.com/22-db-22/SUBCOLAB](https://github.com/22-db-22/SUBCOLAB)**!

---

## License & Attributions

- Repository: **[github.com/22-db-22/SUBCOLAB](https://github.com/22-db-22/SUBCOLAB)**
- Distributed under **[GPL-3.0](LICENSE)**. See **[NOTICE](NOTICE)** for full third-party credits and open-source licenses.
- Based on & inspired by [OpenScribe](https://github.com/faisal-saddique/openscribe) (MIT) by Faisal Saddique.
