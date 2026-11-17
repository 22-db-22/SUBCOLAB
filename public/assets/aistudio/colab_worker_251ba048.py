#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
OpenScribe Live — Remote Colab T4 GPU Worker (Complete Final)
- Clean subtitles: no fake meta initial_prompt and no harsh deletion of short Korean/Japanese lines
- Restored afftdn + loudnorm audio cleaning and added -nostdin to ffmpeg
- Responds immediately to /content/openscribe_abort from watch.sh on Ctrl+C / early mpv close
- 20s silent heartbeat prevents google-colab-cli 80-second idle disconnects
- Deletes all temporary/relayed audio waste while keeping cached Whisper weights
"""

import ctypes
import gc
import glob
import os
import re
import shutil
import signal
import site
import subprocess
import sys
import textwrap
import threading
import time

# ---------------------------------------------------------------------------
# Configuration (overridden via colab exec --env)
# ---------------------------------------------------------------------------
VIDEO_URL      = os.environ.get("OS_VIDEO_URL", "").strip()
SOURCE_LANG    = os.environ.get("OS_SOURCE_LANG", "auto").strip() or "auto"
INITIAL_PROMPT = os.environ.get("OS_INITIAL_PROMPT", "").strip()
CLEAN_AUDIO    = os.environ.get("OS_CLEAN_AUDIO", "True").lower() == "true"
MAX_LINE_WIDTH = int(os.environ.get("OS_MAX_LINE_WIDTH", "42"))
MAX_LINE_COUNT = int(os.environ.get("OS_MAX_LINE_COUNT", "2"))
PLAYER_CLIENT  = os.environ.get("OS_PLAYER_CLIENT", "tv_embedded").strip() or "tv_embedded"
MODEL_ID       = os.environ.get("OS_MODEL_ID", "large-v3").strip() or "large-v3"

WORKDIR        = "/content/openscribe_job"
MODEL_CACHE    = "/content/whisper_models"
COOKIES_PATH   = "/content/cookies.txt"
SEEK_PATH      = "/content/openscribe_seek.txt"
ABORT_PATH     = "/content/openscribe_abort"
WORKER_PIDFILE = "/content/openscribe_worker.pid"
DEPS_MARKER    = "/content/.openscribe_deps_ok"

LANG_ALIASES = {
    "auto": None,
    "detect": None,
    "afrikaans": "af", "amharic": "am", "arabic": "ar", "assamese": "as",
    "azerbaijani": "az", "bashkir": "ba", "belarusian": "be", "bulgarian": "bg",
    "bengali": "bn", "bangla": "bn", "tibetan": "bo", "breton": "br",
    "bosnian": "bs", "catalan": "ca", "czech": "cs", "welsh": "cy",
    "danish": "da", "german": "de", "deutsch": "de", "greek": "el",
    "english": "en", "spanish": "es", "espanol": "es", "estonian": "et",
    "basque": "eu", "persian": "fa", "farsi": "fa", "finnish": "fi",
    "faroese": "fo", "french": "fr", "francais": "fr", "galician": "gl",
    "gujarati": "gu", "hausa": "ha", "hawaiian": "haw", "hebrew": "he",
    "iw": "he", "hindi": "hi", "croatian": "hr", "haitian": "ht",
    "hungarian": "hu", "armenian": "hy", "indonesian": "id", "in": "id",
    "icelandic": "is", "italian": "it", "italiano": "it",
    "japanese": "ja", "jp": "ja", "nihongo": "ja", "javanese": "jw",
    "georgian": "ka", "kazakh": "kk", "khmer": "km", "kannada": "kn",
    "korean": "ko", "kr": "ko", "hangul": "ko", "latin": "la",
    "luxembourgish": "lb", "lingala": "ln", "lao": "lo", "lithuanian": "lt",
    "latvian": "lv", "malagasy": "mg", "maori": "mi", "macedonian": "mk",
    "malayalam": "ml", "mongolian": "mn", "marathi": "mr", "malay": "ms",
    "maltese": "mt", "burmese": "my", "myanmar": "my", "nepali": "ne",
    "dutch": "nl", "flemish": "nl", "norwegian": "no", "occitan": "oc",
    "punjabi": "pa", "panjabi": "pa", "polish": "pl", "pashto": "ps",
    "portuguese": "pt", "romanian": "ro", "russian": "ru", "sanskrit": "sa",
    "sindhi": "sd", "sinhala": "si", "slovak": "sk", "slovenian": "sl",
    "shona": "sn", "somali": "so", "albanian": "sq", "serbian": "sr",
    "sundanese": "su", "swedish": "sv", "swahili": "sw", "tamil": "ta",
    "telugu": "te", "tajik": "tg", "thai": "th", "turkmen": "tk",
    "tagalog": "tl", "filipino": "tl", "turkish": "tr", "tatar": "tt",
    "ukrainian": "uk", "urdu": "ur", "uzbek": "uz", "vietnamese": "vi",
    "viet": "vi", "tiengviet": "vi", "yiddish": "yi", "yoruba": "yo",
    "chinese": "zh", "mandarin": "zh", "cn": "zh", "cantonese": "yue",
    "yue": "yue",
}


def status(msg: str) -> None:
    print(f"@@OPENSCRIBE_STATUS@@ {msg}", flush=True)


def abort_requested() -> bool:
    return os.path.isfile(ABORT_PATH)


def normalize_language(raw_lang: str, valid_codes=None):
    key = re.sub(r"[\s_-]+", "", (raw_lang or "").strip().lower())
    if not key or key in ("auto", "detect"):
        return None
    if key in LANG_ALIASES:
        code = LANG_ALIASES[key]
        if code is None:
            return None
        if valid_codes is not None and code not in valid_codes and code != "yue":
            return None
        return code
    if valid_codes is not None:
        if key in valid_codes:
            return key
        for vc, vname in (valid_codes.items() if isinstance(valid_codes, dict) else []):
            if re.sub(r"[\s_-]+", "", str(vname).lower()) == key:
                return vc
        status(f"Unrecognized language '{raw_lang}', falling back to auto-detect.")
        return None
    return key


# ---------------------------------------------------------------------------
# 1. Clean Previous Leftovers & Orphan Subprocesses
# ---------------------------------------------------------------------------
def cleanup_temp_files(keep_abort: bool = False) -> None:
    shutil.rmtree(WORKDIR, ignore_errors=True)
    for p in (SEEK_PATH, "/content/audio_16k.wav"):
        if os.path.isfile(p):
            try:
                os.remove(p)
            except OSError:
                pass
    if not keep_abort and os.path.isfile(ABORT_PATH):
        try:
            os.remove(ABORT_PATH)
        except OSError:
            pass
    for relay_file in glob.glob("/content/openscribe_relay_*") + glob.glob("/content/openscribe_relay_audio.*"):
        if os.path.isfile(relay_file):
            try:
                os.remove(relay_file)
            except OSError:
                pass


def cleanup_stale_processes() -> None:
    for proc_name in ("ffmpeg", "yt-dlp"):
        subprocess.run(["pkill", "-9", "-x", proc_name], capture_output=True)

    if os.path.isfile(WORKER_PIDFILE):
        try:
            old_pid = int(open(WORKER_PIDFILE, encoding="utf-8").read().strip())
            if old_pid > 0 and old_pid != os.getpid() and old_pid != os.getppid():
                try:
                    os.kill(old_pid, signal.SIGKILL)
                except OSError:
                    pass
                time.sleep(0.3)
        except Exception:
            pass

    try:
        with open(WORKER_PIDFILE, "w", encoding="utf-8") as f:
            f.write(str(os.getpid()))
    except OSError:
        pass


def _handle_exit_signal(signum, _frame):
    cleanup_temp_files()
    sys.exit(128 + signum)


# ---------------------------------------------------------------------------
# 2. Fast Datacenter Dependency Install (Skipped if already installed in session)
# ---------------------------------------------------------------------------
def ensure_packages() -> None:
    if os.path.isfile(DEPS_MARKER):
        try:
            import faster_whisper  # noqa: F401
            import av  # noqa: F401
            if shutil.which("ffmpeg") and shutil.which("yt-dlp"):
                return
        except Exception:
            pass

    try:
        import faster_whisper  # noqa: F401
        import av  # noqa: F401
        av_major = int(str(getattr(av, "__version__", "99")).split(".", 1)[0])
        if av_major < 18 and shutil.which("ffmpeg") and shutil.which("yt-dlp"):
            open(DEPS_MARKER, "w").close()
            return
    except Exception:
        pass

    status("Installing faster-whisper, yt-dlp, and CUDA 12 libraries in Colab...")
    pkgs = [
        "faster-whisper",
        "av<18",
        "ctranslate2",
        "yt-dlp",
        "nvidia-cublas-cu12",
        "nvidia-cudnn-cu12==9.*",
    ]
    installed = False
    uv_bin = shutil.which("uv")
    if uv_bin:
        res = subprocess.run(
            [uv_bin, "pip", "install", "--system", "-q", "-U", *pkgs],
            text=True,
            capture_output=True,
        )
        installed = (res.returncode == 0)
    if not installed:
        subprocess.run(
            [sys.executable, "-m", "pip", "install", "-q", "-U", *pkgs],
            check=True,
        )

    if shutil.which("ffmpeg") is None:
        status("Installing ffmpeg...")
        subprocess.run("apt-get -qq update && apt-get -qq install -y ffmpeg", shell=True, check=True)

    try:
        open(DEPS_MARKER, "w").close()
    except OSError:
        pass


# ---------------------------------------------------------------------------
# 3. Dynamic CUDA Library Discovery (works across Python 3.10 - 3.13+)
# ---------------------------------------------------------------------------
def preload_cuda_libs() -> None:
    candidate_roots = list(site.getsitepackages())
    try:
        user_site = site.getusersitepackages()
        if user_site:
            candidate_roots.append(user_site)
    except Exception:
        pass

    lib_dirs = []
    loaded = {"cublas": False, "cudnn": False}

    for root in candidate_roots:
        for cublas_file in glob.glob(os.path.join(root, "nvidia", "cublas", "lib", "libcublas.so.12*")):
            lib_dirs.append(os.path.dirname(cublas_file))
            if not loaded["cublas"] and cublas_file.endswith("libcublas.so.12"):
                try:
                    ctypes.CDLL(cublas_file, mode=ctypes.RTLD_GLOBAL)
                    loaded["cublas"] = True
                except OSError:
                    pass

        for cudnn_file in glob.glob(os.path.join(root, "nvidia", "cudnn", "lib", "libcudnn.so.9*")):
            lib_dirs.append(os.path.dirname(cudnn_file))
            if not loaded["cudnn"] and cudnn_file.endswith("libcudnn.so.9"):
                try:
                    ctypes.CDLL(cudnn_file, mode=ctypes.RTLD_GLOBAL)
                    loaded["cudnn"] = True
                except OSError:
                    pass

    if lib_dirs:
        unique_dirs = list(dict.fromkeys(lib_dirs))
        prev = os.environ.get("LD_LIBRARY_PATH", "")
        os.environ["LD_LIBRARY_PATH"] = ":".join(unique_dirs) + (f":{prev}" if prev else "")

    status(f"CUDA libs preloaded (cuBLAS={loaded['cublas']}, cuDNN9={loaded['cudnn']})")


# ---------------------------------------------------------------------------
# 4. Audio-Only Extraction via yt-dlp + English Title Normalization
# ---------------------------------------------------------------------------
_TITLE_EMITTED = False


def _translate_to_english(text: str) -> str:
    raw = (text or "").strip()
    if not raw or not any(ord(ch) > 127 for ch in raw):
        return raw
    import json
    import urllib.parse
    import urllib.request

    try:
        q = urllib.parse.quote(raw[:220])
        url = f"https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=en&dt=t&q={q}"
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=5) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        translated = "".join(
            seg[0] for seg in (data[0] or []) if isinstance(seg, list) and seg and seg[0]
        ).strip()
        return translated or raw
    except Exception:
        return raw


def _safe_english_slug(title: str, fallback: str = "OpenScribe_Translated_Video") -> str:
    import unicodedata

    raw = re.sub(r"\s*-\s*(YouTube|Dailymotion)\s*$", "", (title or "").strip(), flags=re.IGNORECASE)
    ascii_only = unicodedata.normalize("NFKD", raw).encode("ascii", "ignore").decode("ascii")
    cleaned = re.sub(r"[^A-Za-z0-9]+", "_", ascii_only).strip("_")
    cleaned = re.sub(r"_+", "_", cleaned)
    if len(cleaned) > 72:
        cut = cleaned[:72].rsplit("_", 1)[0]
        cleaned = cut or cleaned[:72]
    if sum(ch.isalpha() for ch in cleaned) >= 3:
        return cleaned
    return fallback


def _maybe_emit_english_title(raw_text: str, from_cue: bool = False) -> None:
    global _TITLE_EMITTED
    if _TITLE_EMITTED:
        return
    translated = raw_text if from_cue else _translate_to_english(raw_text)
    slug = _safe_english_slug(translated, fallback="")
    if not slug:
        return
    if from_cue and len(slug.split("_")) < 2 and len(slug) < 8:
        return
    _TITLE_EMITTED = True
    print(f"@@OPENSCRIBE_TITLE@@ {slug}", flush=True)
    status(f"English title: {slug}")


def _normalize_youtube_url(url: str) -> str:
    m = re.search(r"youtube\.com/shorts/([A-Za-z0-9_-]{6,})", url)
    if m:
        return f"https://www.youtube.com/watch?v={m.group(1)}"
    return url


def download_audio(url: str, dest_dir: str):
    import urllib.parse

    status("Downloading audio stream on Colab datacenter fiber...")
    marker = os.path.join(dest_dir, "_ytdlp_path.txt")
    host = urllib.parse.urlparse(url).netloc.lower()
    is_youtube = ("youtube.com" in host or "youtu.be" in host)

    if is_youtube:
        url = _normalize_youtube_url(url)
        clients = [PLAYER_CLIENT]
        for fallback in ("tv_embedded", "tv", "android_vr", "mweb", "default"):
            if fallback not in clients:
                clients.append(fallback)
    else:
        clients = ["default"]

    last_err = ""

    for client in clients:
        if abort_requested():
            return None, "aborted"

        for old_raw in glob.glob(os.path.join(dest_dir, "raw_audio.*")):
            try:
                os.remove(old_raw)
            except OSError:
                pass
        if os.path.exists(marker):
            os.remove(marker)

        cmd = [
            "yt-dlp",
            "--no-playlist",
            "--no-warnings",
            "--force-overwrites",
            "--newline",
            "--progress-delta", "4",
            "--concurrent-fragments", "8",
            "--socket-timeout", "20",
            "--retries", "3",
            "--fragment-retries", "3",
            "--no-part",
            "-f", "ba/b/best",
            "-o", os.path.join(dest_dir, "raw_audio.%(ext)s"),
            "--no-simulate",
            "--print", "before_dl:OPENSCRIBE_META_TITLE:%(title).150s",
            "--print-to-file", "after_move:filepath", marker,
        ]
        if is_youtube and client != "default":
            cmd.extend(["--extractor-args", f"youtube:player_client={client}"])
        if os.path.isfile(COOKIES_PATH):
            cmd.extend(["--cookies", COOKIES_PATH])
        cmd.append(url)

        proc = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
        )
        deadline = time.time() + 240
        tail_lines = []
        assert proc.stdout is not None
        for raw_line in proc.stdout:
            if abort_requested():
                proc.kill()
                return None, "aborted"
            line = raw_line.strip()
            if not line:
                continue
            tail_lines.append(line)
            if len(tail_lines) > 25:
                tail_lines.pop(0)
            if line.startswith("OPENSCRIBE_META_TITLE:") and not _TITLE_EMITTED:
                raw_title = line.split("OPENSCRIBE_META_TITLE:", 1)[1]
                _maybe_emit_english_title(raw_title, from_cue=False)
            elif "[download]" in line:
                status(f"yt-dlp: {line}")
            if time.time() > deadline:
                proc.kill()
                tail_lines.append("yt-dlp timed out after 240s")
                break

        rc = proc.wait()
        if abort_requested():
            return None, "aborted"
        if rc == 0 and os.path.exists(marker):
            path = open(marker, encoding="utf-8").read().strip().splitlines()[-1]
            os.remove(marker)
            if os.path.isfile(path):
                return path, None
        last_err = "\n".join(tail_lines[-12:])
        if is_youtube:
            status(f"yt-dlp client '{client}' failed, trying fallback...")

    if is_youtube and ("Sign in to confirm" in last_err or "not a bot" in last_err):
        return None, "bot_blocked"

    raise RuntimeError(f"yt-dlp failed across all clients:\n{last_err}")


def extract_clean_wav(raw_path: str, wav_path: str) -> None:
    status("Converting & cleaning audio to 16 kHz mono WAV...")
    t_ff = time.time()
    filters = (
        "highpass=f=60,afftdn=nf=-25,loudnorm=I=-16:TP=-1.5:LRA=11"
        if CLEAN_AUDIO
        else "highpass=f=60"
    )
    cmd = [
        "ffmpeg", "-nostdin", "-y", "-threads", "0", "-i", raw_path,
        "-vn", "-sn", "-dn",
        "-af", filters,
        "-ac", "1", "-ar", "16000",
        "-c:a", "pcm_s16le",
        wav_path,
    ]
    res = subprocess.run(cmd, text=True, capture_output=True)
    if res.returncode != 0:
        raise RuntimeError(f"ffmpeg extraction failed:\n{res.stderr[-1500:]}")
    status(f"Audio ready in {time.time() - t_ff:.1f}s")


# ---------------------------------------------------------------------------
# 5. SRT Formatting (Minimal Cleanup — No Profanity/Speech Deletion)
# ---------------------------------------------------------------------------
def ts(seconds: float, sep: str = ",") -> str:
    ms = max(0, int(round(seconds * 1000)))
    h, ms = divmod(ms, 3_600_000)
    m, ms = divmod(ms, 60_000)
    s, ms = divmod(ms, 1000)
    return f"{h:02d}:{m:02d}:{s:02d}{sep}{ms:03d}"


def wrap_subtitle(text: str) -> str:
    lines = textwrap.wrap(text, width=MAX_LINE_WIDTH) or [text]
    if len(lines) > MAX_LINE_COUNT:
        head = lines[: MAX_LINE_COUNT - 1]
        head.append(" ".join(lines[MAX_LINE_COUNT - 1 :]))
        lines = head
    return "\n".join(lines)


def clean_translated_text(raw_text: str) -> str:
    text = re.sub(r"\s+", " ", raw_text or "").strip()
    if not text:
        return ""
    text = re.sub(r"[♪♫]+", "", text).strip()
    if not text or not re.search(r"[A-Za-z0-9]", text):
        return ""
    return text


def emit_srt_block(index: int, start: float, end: float, text: str) -> None:
    wrapped = wrap_subtitle(text)
    start_ms = max(0, int(round(start * 1000)))
    print(f"@@OPENSCRIBE_SRT_BEGIN@@ {start_ms}", flush=True)
    print(f"{index}", flush=True)
    print(f"{ts(start)} --> {ts(end)}", flush=True)
    print(wrapped, flush=True)
    print("@@OPENSCRIBE_SRT_END@@", flush=True)


# ---------------------------------------------------------------------------
# 6. Seek-Aware Whisper Chunk Streaming
# ---------------------------------------------------------------------------
def detect_device():
    try:
        import torch
        if torch.cuda.is_available():
            gpu_name = torch.cuda.get_device_name(0)
            status(f"GPU ready: {gpu_name}")
            return "cuda", "float16"
    except Exception:
        pass
    status("WARNING: No CUDA GPU detected; falling back to CPU int8 (slow).")
    return "cpu", "int8"


def _load_wav_float32(wav_path: str):
    import wave
    import numpy as np

    with wave.open(wav_path, "rb") as wf:
        frames = wf.readframes(wf.getnframes())
    return np.frombuffer(frames, dtype=np.int16).astype(np.float32) / 32768.0


def _build_audio_chunks(audio_arr, sr: int = 16000, target_sec: float = 45.0):
    total_sec = len(audio_arr) / float(sr)
    if total_sec <= 60.0:
        return [(0.0, total_sec)]

    chunks = []
    prev = 0.0
    win_samples = max(1, int(0.10 * sr))
    step_samples = max(1, int(0.05 * sr))

    while total_sec - prev > 55.0:
        nominal = prev + target_sec
        lo_t = max(prev + 15.0, nominal - 5.0)
        hi_t = min(total_sec - 15.0, nominal + 5.0)
        best_t = nominal
        best_energy = float("inf")

        s_lo = int(lo_t * sr)
        s_hi = int(hi_t * sr)
        for pos in range(s_lo, max(s_lo + 1, s_hi - win_samples), step_samples):
            frame = audio_arr[pos : pos + win_samples]
            energy = float((frame * frame).mean())
            if energy < best_energy:
                best_energy = energy
                best_t = (pos + win_samples // 2) / float(sr)

        chunks.append((prev, best_t))
        prev = best_t

    chunks.append((prev, total_sec))
    return chunks


def _pop_seek_request(seen_tokens: set, total_sec: float):
    if not os.path.isfile(SEEK_PATH):
        return None
    try:
        raw = open(SEEK_PATH, encoding="utf-8", errors="ignore").read().strip()
        if not raw or ":" not in raw:
            return None
        token, val_str = raw.split(":", 1)
        if token in seen_tokens:
            return None
        seen_tokens.add(token)
        target_sec = max(0.0, min(max(0.0, total_sec - 1.0), float(val_str)))
        try:
            os.remove(SEEK_PATH)
        except OSError:
            pass
        return target_sec
    except Exception:
        return None


def _chunk_index_for_time(chunks, target_sec: float) -> int:
    for idx, (c_start, c_end) in enumerate(chunks):
        if target_sec < c_end:
            return idx
    return max(0, len(chunks) - 1)


def _load_faster_whisper_model(device: str, compute_type: str):
    from faster_whisper import WhisperModel

    cached_dirs = glob.glob(os.path.join(MODEL_CACHE, f"*{MODEL_ID}*"))
    use_local_only = len(cached_dirs) > 0
    t_load = time.time()
    try:
        status(
            f"Loading faster-whisper {MODEL_ID} ({device}, {compute_type})"
            + (" from local Colab cache..." if use_local_only else "...")
        )
        model = WhisperModel(
            MODEL_ID,
            device=device,
            compute_type=compute_type,
            download_root=MODEL_CACHE,
            local_files_only=use_local_only,
        )
    except Exception:
        status(f"Downloading faster-whisper {MODEL_ID} ({device}, {compute_type})...")
        model = WhisperModel(
            MODEL_ID,
            device=device,
            compute_type=compute_type,
            download_root=MODEL_CACHE,
            local_files_only=False,
        )
    status(f"Model loaded in {time.time() - t_load:.1f}s")
    return model


def stream_faster_whisper(wav_path: str, device: str, compute_type: str) -> int:
    for tok_var in ("HF_TOKEN", "HUGGING_FACE_HUB_TOKEN", "HUGGINGFACE_HUB_TOKEN"):
        os.environ.pop(tok_var, None)
    os.environ["HF_HUB_DISABLE_IMPLICIT_TOKEN"] = "1"
    os.environ["HF_HUB_VERBOSITY"] = "error"

    import logging
    logging.getLogger("huggingface_hub").setLevel(logging.ERROR)

    try:
        from faster_whisper.tokenizer import _LANGUAGE_CODES as fw_codes
        valid_codes = set(fw_codes)
    except Exception:
        valid_codes = None

    model = _load_faster_whisper_model(device, compute_type)

    audio_arr = _load_wav_float32(wav_path)
    sr = 16000
    total_sec = len(audio_arr) / float(sr)
    chunks = _build_audio_chunks(audio_arr, sr=sr, target_sec=45.0)
    n_chunks = len(chunks)
    completed = [False] * n_chunks

    lang_arg = normalize_language(SOURCE_LANG, valid_codes=valid_codes)
    lang_label = lang_arg or "auto"
    status(
        f"Translating speech -> English (lang={lang_label}, beam=5, Silero VAD on, {n_chunks} chunk(s))..."
    )

    cue_idx = 0
    suppressed_count = 0
    seen_seeks = set()
    pending_seek = _pop_seek_request(seen_seeks, total_sec)
    cursor = 0
    reported_meta = False
    reported_backfill = False
    user_prompt = INITIAL_PROMPT or None

    try:
        while not all(completed):
            if abort_requested():
                status("Job aborted by user.")
                break

            if pending_seek is None:
                pending_seek = _pop_seek_request(seen_seeks, total_sec)

            if pending_seek is not None:
                target_idx = _chunk_index_for_time(chunks, pending_seek)
                if completed[target_idx]:
                    nxt = next((i for i in range(target_idx, n_chunks) if not completed[i]), None)
                    if nxt is None:
                        nxt = next((i for i in range(n_chunks) if not completed[i]), None)
                    if nxt is not None:
                        cursor = nxt
                else:
                    cursor = target_idx
                    status(f"Seek jump -> translating from {ts(chunks[cursor][0])}...")
                pending_seek = None

            if completed[cursor]:
                nxt = next((i for i in range(cursor, n_chunks) if not completed[i]), None)
                if nxt is None:
                    nxt = next((i for i in range(n_chunks) if not completed[i]), None)
                    if nxt is not None and not reported_backfill:
                        reported_backfill = True
                        status("Reached end of video; backfilling earlier skipped sections...")
                if nxt is None:
                    break
                cursor = nxt

            c_start, c_end = chunks[cursor]
            s0 = max(0, int(round(c_start * sr)))
            s1 = min(len(audio_arr), int(round(c_end * sr)))
            sub_arr = audio_arr[s0:s1]

            seg_iter, info = model.transcribe(
                sub_arr,
                language=lang_arg,
                task="translate",
                beam_size=5,
                best_of=5,
                patience=1.0,
                temperature=[0.0, 0.2, 0.4, 0.6, 0.8, 1.0],
                compression_ratio_threshold=2.4,
                log_prob_threshold=-1.0,
                no_speech_threshold=0.6,
                condition_on_previous_text=False,
                word_timestamps=False,
                vad_filter=True,
                vad_parameters=dict(min_silence_duration_ms=500, speech_pad_ms=200),
                initial_prompt=user_prompt,
            )

            if not reported_meta:
                reported_meta = True
                status(
                    f"Detected language: {info.language} ({info.language_probability:.0%}) | Duration: {total_sec/60:.1f} min"
                )

            last_norm = ""
            repeat_streak = 0
            aborted_for_seek = False

            for seg in seg_iter:
                if abort_requested():
                    break
                if pending_seek is None:
                    pending_seek = _pop_seek_request(seen_seeks, total_sec)
                if pending_seek is not None and abs(pending_seek - c_start) > 55.0:
                    aborted_for_seek = True
                    break

                text = clean_translated_text(getattr(seg, "text", ""))
                if not text:
                    continue

                norm = text.lower()
                if norm == last_norm:
                    repeat_streak += 1
                else:
                    last_norm = norm
                    repeat_streak = 1

                if repeat_streak >= 4:
                    suppressed_count += 1
                    if repeat_streak == 4:
                        status(f"Suppressed repeated hallucination loop: '{text[:48]}'")
                    continue

                abs_start = c_start + float(seg.start)
                abs_end = min(c_end, c_start + float(seg.end))
                if abs_end <= abs_start:
                    abs_end = abs_start + 0.5

                cue_idx += 1
                _maybe_emit_english_title(text, from_cue=True)
                emit_srt_block(cue_idx, abs_start, abs_end, text)

            if abort_requested():
                status("Job aborted by user.")
                break
            if aborted_for_seek:
                continue

            completed[cursor] = True
            print(f"@@OPENSCRIBE_CHUNK_END@@ {c_start:.2f} {c_end:.2f}", flush=True)
            cursor = (cursor + 1) if (cursor + 1 < n_chunks) else 0

        if not abort_requested():
            print("@@OPENSCRIBE_ALL_DONE@@", flush=True)

        if suppressed_count:
            status(f"Filtered {suppressed_count} repeated hallucination segment(s).")
    finally:
        del model
        gc.collect()
        try:
            import torch
            if torch.cuda.is_available():
                torch.cuda.empty_cache()
        except Exception:
            pass
    return cue_idx


def stream_openai_whisper_fallback(wav_path: str, device: str) -> int:
    status("Emergency fallback: installing openai-whisper...")
    subprocess.run([sys.executable, "-m", "pip", "install", "-q", "-U", "openai-whisper"], check=True)
    import whisper

    model = whisper.load_model(MODEL_ID, device=device, download_root=MODEL_CACHE)
    lang_arg = normalize_language(SOURCE_LANG, valid_codes=whisper.tokenizer.LANGUAGES)
    audio_arr = _load_wav_float32(wav_path)
    sr = 16000
    total_sec = len(audio_arr) / float(sr)
    chunks = _build_audio_chunks(audio_arr, sr=sr, target_sec=45.0)
    n_chunks = len(chunks)
    completed = [False] * n_chunks
    seen_seeks = set()
    pending_seek = _pop_seek_request(seen_seeks, total_sec)
    cue_idx = 0
    cursor = 0
    user_prompt = INITIAL_PROMPT or None

    try:
        while not all(completed):
            if abort_requested():
                break
            if pending_seek is None:
                pending_seek = _pop_seek_request(seen_seeks, total_sec)
            if pending_seek is not None:
                t_idx = _chunk_index_for_time(chunks, pending_seek)
                cursor = t_idx if not completed[t_idx] else next((i for i in range(n_chunks) if not completed[i]), cursor)
                pending_seek = None

            if completed[cursor]:
                nxt = next((i for i in range(cursor, n_chunks) if not completed[i]), None)
                if nxt is None:
                    nxt = next((i for i in range(n_chunks) if not completed[i]), None)
                if nxt is None:
                    break
                cursor = nxt

            c_start, c_end = chunks[cursor]
            sub_arr = audio_arr[max(0, int(round(c_start * sr))) : min(len(audio_arr), int(round(c_end * sr)))]
            result = model.transcribe(
                sub_arr,
                language=lang_arg,
                task="translate",
                beam_size=5,
                best_of=5,
                temperature=(0.0, 0.2, 0.4, 0.6, 0.8, 1.0),
                compression_ratio_threshold=2.4,
                logprob_threshold=-1.0,
                no_speech_threshold=0.6,
                condition_on_previous_text=False,
                word_timestamps=False,
                initial_prompt=user_prompt,
                fp16=(device == "cuda"),
                verbose=False,
            )

            last_norm = ""
            repeat_streak = 0
            for seg in result.get("segments", []):
                if abort_requested():
                    break
                text = clean_translated_text(seg.get("text", ""))
                if not text:
                    continue
                norm = text.lower()
                repeat_streak = repeat_streak + 1 if norm == last_norm else 1
                last_norm = norm
                if repeat_streak >= 4:
                    continue
                cue_idx += 1
                _maybe_emit_english_title(text, from_cue=True)
                emit_srt_block(cue_idx, c_start + float(seg["start"]), min(c_end, c_start + float(seg["end"])), text)

            if abort_requested():
                break
            completed[cursor] = True
            print(f"@@OPENSCRIBE_CHUNK_END@@ {c_start:.2f} {c_end:.2f}", flush=True)
            cursor = (cursor + 1) if (cursor + 1 < n_chunks) else 0

        if not abort_requested():
            print("@@OPENSCRIBE_ALL_DONE@@", flush=True)
    finally:
        del model
        gc.collect()
        try:
            import torch
            if torch.cuda.is_available():
                torch.cuda.empty_cache()
        except Exception:
            pass
    return cue_idx


def main() -> int:
    if not VIDEO_URL:
        print("[ERROR] OS_VIDEO_URL is empty.", file=sys.stderr)
        return 1

    signal.signal(signal.SIGTERM, _handle_exit_signal)
    signal.signal(signal.SIGINT, _handle_exit_signal)

    cleanup_stale_processes()
    cleanup_temp_files(keep_abort=False)

    preset_title = os.environ.get("OS_VIDEO_TITLE", "").strip()
    if preset_title:
        _maybe_emit_english_title(preset_title, from_cue=False)

    os.makedirs(WORKDIR, exist_ok=True)
    raw_audio = None
    wav_audio = os.path.join(WORKDIR, "audio_16k.wav")
    t0 = time.time()
    hb_stop = threading.Event()

    def _heartbeat_loop():
        while not hb_stop.wait(20.0):
            print("@@OPENSCRIBE_HEARTBEAT@@", flush=True)

    threading.Thread(target=_heartbeat_loop, daemon=True).start()

    try:
        ensure_packages()
        preload_cuda_libs()
        device, compute_type = detect_device()

        local_audio = os.environ.get("OS_LOCAL_AUDIO", "").strip()
        if local_audio and os.path.isfile(local_audio):
            status("Using audio relayed from home PC (bypassed Colab YouTube IP block)...")
            raw_audio = local_audio
        else:
            raw_audio, dl_err = download_audio(VIDEO_URL, WORKDIR)
            if dl_err == "aborted":
                status("Job aborted before transcription.")
                return 0
            if dl_err == "bot_blocked":
                print("@@OPENSCRIBE_BOT_BLOCKED@@", flush=True)
                status("YouTube blocked Colab datacenter IP. Requesting home-PC audio relay...")
                return 0

        if abort_requested():
            return 0

        extract_clean_wav(raw_audio, wav_audio)

        if raw_audio and os.path.isfile(raw_audio):
            os.remove(raw_audio)
            raw_audio = None

        if abort_requested():
            return 0

        try:
            total_cues = stream_faster_whisper(wav_audio, device, compute_type)
        except Exception as fw_err:
            if abort_requested():
                return 0
            status(f"faster-whisper error ({type(fw_err).__name__}): {fw_err}")
            total_cues = stream_openai_whisper_fallback(wav_audio, device)

        elapsed = time.time() - t0
        status(f"Completed {total_cues} English subtitle cues in {elapsed:.1f}s.")
        return 0
    except Exception as exc:
        status(f"ERROR: {exc}")
        return 1
    finally:
        hb_stop.set()
        for p in (raw_audio, wav_audio):
            if p and os.path.isfile(p):
                try:
                    os.remove(p)
                except OSError:
                    pass
        cleanup_temp_files(keep_abort=False)


if __name__ == "__main__":
    _rc = main()
    if _rc != 0 and "get_ipython" not in globals():
        sys.exit(_rc)