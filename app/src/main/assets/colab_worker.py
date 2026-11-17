#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
OpenScribe Android — Remote Colab T4 GPU Worker
- Emits BOTH English-translated subtitles AND original-language subtitles
- Supports advanced accuracy/audio cleanup options for re-translation:
  OS_CLEAN_AUDIO, OS_VOCAL_BOOST, OS_BEAM_SIZE, OS_INITIAL_PROMPT, OS_MODEL_ID
- Skips blocked YouTube download on Colab when OS_SKIP_COLAB_YT=True or OS_LOCAL_AUDIO is provided
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

VIDEO_URL        = os.environ.get("OS_VIDEO_URL", "").strip()
SOURCE_LANG      = os.environ.get("OS_SOURCE_LANG", "auto").strip() or "auto"
INITIAL_PROMPT   = os.environ.get("OS_INITIAL_PROMPT", "").strip()
CLEAN_AUDIO      = os.environ.get("OS_CLEAN_AUDIO", "False").lower() == "true"
VOCAL_BOOST      = os.environ.get("OS_VOCAL_BOOST", "False").lower() == "true"
BEAM_SIZE        = max(1, min(10, int(os.environ.get("OS_BEAM_SIZE", "5") or "5")))
MAX_LINE_WIDTH   = int(os.environ.get("OS_MAX_LINE_WIDTH", "42"))
MAX_LINE_COUNT   = int(os.environ.get("OS_MAX_LINE_COUNT", "2"))
PLAYER_CLIENT    = os.environ.get("OS_PLAYER_CLIENT", "default").strip() or "default"
MODEL_ID         = os.environ.get("OS_MODEL_ID", "large-v3").strip() or "large-v3"
EMIT_ORIGINAL    = os.environ.get("OS_EMIT_ORIGINAL", "False").lower() == "true"
SKIP_COLAB_YT    = os.environ.get("OS_SKIP_COLAB_YT", "False").lower() == "true"
STREAMING_QUEUE   = os.environ.get("OS_STREAMING_QUEUE", "False").lower() == "true"
COLAB_CHUNK_STREAM = os.environ.get("OS_COLAB_CHUNK_STREAM", "False").lower() == "true"
DOWNLOAD_MP4_ONLY = os.environ.get("OS_DOWNLOAD_MP4_ONLY", "False").lower() == "true"
MAX_HEIGHT        = max(360, min(1080, int(os.environ.get("OS_MAX_HEIGHT", "720") or "720")))
CHUNK_SEC         = max(12, min(60, int(os.environ.get("OS_CHUNK_SEC", "20") or "20")))
JOB_ID            = re.sub(r"[^A-Za-z0-9_-]", "", os.environ.get("OS_JOB_ID", "default").strip()) or "default"
TOTAL_DUR_HINT    = float(os.environ.get("OS_TOTAL_DURATION_SEC", "0") or "0")

WORKDIR          = "/content/openscribe_job"
QUEUE_DIR        = f"/content/openscribe_queue_{JOB_ID}"
COLAB_CHUNKS_DIR = f"/content/openscribe_chunks_{JOB_ID}"
MODEL_CACHE      = "/content/whisper_models"
COOKIES_PATH     = "/content/cookies.txt"
ABORT_PATH       = "/content/openscribe_abort"
EXPORT_MP4_PATH  = "/content/openscribe_mp4_export.mp4"
WORKER_PIDFILE   = "/content/openscribe_worker.pid"
DEPS_MARKER    = "/content/.openscribe_deps_ok"
DESKTOP_UA     = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)

LANG_ALIASES = {
    "auto": None, "detect": None,
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


_PRINT_LOCK = threading.Lock()


def emit_line(line: str) -> None:
    with _PRINT_LOCK:
        print(line, flush=True)


def status(msg: str) -> None:
    emit_line(f"@@OPENSCRIBE_STATUS@@ {msg}")


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


def cleanup_temp_files(
    keep_abort: bool = False,
    keep_audio_path: str = "",
    keep_queue_dir: str = "",
    keep_chunks_dir: str = "",
    keep_export: bool = False,
) -> None:
    shutil.rmtree(WORKDIR, ignore_errors=True)
    keep_q_abs = os.path.abspath(keep_queue_dir) if keep_queue_dir else ""
    for q_dir in glob.glob("/content/openscribe_queue_*"):
        if keep_q_abs and os.path.abspath(q_dir) == keep_q_abs:
            continue
        shutil.rmtree(q_dir, ignore_errors=True)
    keep_c_abs = os.path.abspath(keep_chunks_dir) if keep_chunks_dir else ""
    for c_dir in glob.glob("/content/openscribe_chunks_*"):
        if keep_c_abs and os.path.abspath(c_dir) == keep_c_abs:
            continue
        shutil.rmtree(c_dir, ignore_errors=True)
    for p in ("/content/openscribe_seek.txt", "/content/audio_16k.wav"):
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
    if not keep_export:
        for exp_file in glob.glob("/content/openscribe_mp4_export.*"):
            if os.path.isfile(exp_file):
                try:
                    os.remove(exp_file)
                except OSError:
                    pass
    keep_abs = os.path.abspath(keep_audio_path) if keep_audio_path else ""
    for relay_file in (
        glob.glob("/content/openscribe_relay_*")
        + glob.glob("/content/openscribe_relay_audio.*")
        + glob.glob("/content/openscribe_local_*")
    ):
        if keep_abs and os.path.abspath(relay_file) == keep_abs:
            continue
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
                time.sleep(0.2)
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


def _run_with_progress(cmd, label: str, interval: float = 12.0, shell: bool = False):
    t_start = time.time()
    proc = subprocess.Popen(
        cmd,
        shell=shell,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    output_tail = []

    def _drain():
        if proc.stdout is None:
            return
        for line in proc.stdout:
            s = line.strip()
            if s:
                output_tail.append(s)
                if len(output_tail) > 20:
                    output_tail.pop(0)

    t_reader = threading.Thread(target=_drain, daemon=True)
    t_reader.start()

    while proc.poll() is None:
        if abort_requested():
            proc.kill()
            raise RuntimeError("Job aborted by user during setup.")
        time.sleep(1.0)
        elapsed = time.time() - t_start
        if elapsed >= interval and int(elapsed) % int(interval) == 0:
            status(f"{label} ({elapsed:.0f}s elapsed)...")

    t_reader.join(timeout=2.0)
    return proc.returncode, "\n".join(output_tail)


def _ensure_ytdlp(upgrade: bool = False) -> None:
    if not upgrade and shutil.which("yt-dlp") is not None:
        return
    status("Updating yt-dlp in Colab..." if upgrade else "Installing yt-dlp in Colab...")
    args = ["-U", "yt-dlp"] if upgrade else ["yt-dlp"]
    installed = False
    uv_bin = shutil.which("uv")
    if uv_bin:
        rc, _ = _run_with_progress(
            [uv_bin, "pip", "install", "--system", "-q", *args],
            "Installing yt-dlp",
        )
        installed = (rc == 0)
    if not installed:
        rc, tail = _run_with_progress(
            [sys.executable, "-m", "pip", "install", "-q", *args],
            "Installing yt-dlp via pip",
        )
        if rc != 0 and shutil.which("yt-dlp") is None:
            raise RuntimeError(f"yt-dlp install failed:\n{tail}")
    if shutil.which("yt-dlp") is None:
        raise RuntimeError("yt-dlp binary still not found after installation.")


def _ensure_ffmpeg() -> None:
    if shutil.which("ffmpeg") is not None:
        return
    status("Step 1/4: Installing ffmpeg...")
    rc, tail = _run_with_progress(
        "apt-get -qq update && apt-get -qq install -y ffmpeg",
        "Step 1/4: Installing ffmpeg",
        shell=True,
    )
    if rc != 0:
        raise RuntimeError(f"ffmpeg install failed:\n{tail}")


def ensure_packages() -> None:
    need_ytdlp = COLAB_CHUNK_STREAM or ((not SKIP_COLAB_YT) and (not STREAMING_QUEUE) and (not os.environ.get("OS_LOCAL_AUDIO", "").strip()))
    status("Step 1/4: Checking Colab Python packages...")

    has_whisper_stack = False
    try:
        import faster_whisper  # noqa: F401
        import av  # noqa: F401
        av_major = int(str(getattr(av, "__version__", "99")).split(".", 1)[0])
        has_whisper_stack = (av_major < 18)
    except Exception:
        has_whisper_stack = False

    if os.path.isfile(DEPS_MARKER) and has_whisper_stack:
        _ensure_ffmpeg()
        if need_ytdlp:
            _ensure_ytdlp(upgrade=False)
        status("Step 1/4: Colab packages ready.")
        return

    if has_whisper_stack and shutil.which("ffmpeg"):
        if need_ytdlp:
            _ensure_ytdlp(upgrade=False)
        try:
            open(DEPS_MARKER, "w").close()
        except OSError:
            pass
        status("Step 1/4: Colab packages ready.")
        return

    status("Step 1/4: Installing faster-whisper & CUDA 12 libraries in Colab...")
    pkgs = [
        "faster-whisper",
        "av<18",
        "ctranslate2",
        "nvidia-cublas-cu12",
        "nvidia-cudnn-cu12==9.*",
    ]
    if need_ytdlp:
        pkgs.append("yt-dlp")

    installed = False
    uv_bin = shutil.which("uv")
    if uv_bin:
        rc, _ = _run_with_progress(
            [uv_bin, "pip", "install", "--system", "-q", *pkgs],
            "Step 1/4: Installing faster-whisper & CUDA 12 libraries",
        )
        installed = (rc == 0)
    if not installed:
        rc, tail = _run_with_progress(
            [sys.executable, "-m", "pip", "install", "-q", *pkgs],
            "Step 1/4: Installing faster-whisper & CUDA 12 libraries via pip",
        )
        if rc != 0:
            raise RuntimeError(f"pip install failed:\n{tail}")

    _ensure_ffmpeg()

    try:
        open(DEPS_MARKER, "w").close()
    except OSError:
        pass
    status("Step 1/4: Package installation complete.")


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

    status(f"Step 2/4: CUDA libs preloaded (cuBLAS={loaded['cublas']}, cuDNN9={loaded['cudnn']})")


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
    emit_line(f"@@OPENSCRIBE_TITLE@@ {slug}")
    status(f"English title: {slug}")


def _normalize_youtube_url(url: str) -> str:
    m = re.search(r"youtube\.com/shorts/([A-Za-z0-9_-]{6,})", url)
    if m:
        return f"https://www.youtube.com/watch?v={m.group(1)}"
    return url


def download_audio(url: str, dest_dir: str):
    import urllib.parse

    _ensure_ytdlp(upgrade=False)
    status("Step 3/4: Downloading audio stream on Colab datacenter fiber...")
    marker = os.path.join(dest_dir, "_ytdlp_path.txt")
    parsed = urllib.parse.urlparse(url)
    host = parsed.netloc.lower()
    is_youtube = ("youtube.com" in host or "youtu.be" in host)

    if is_youtube and SKIP_COLAB_YT:
        return None, "bot_blocked"

    if is_youtube:
        url = _normalize_youtube_url(url)
        attempts = [(c, False) for c in dict.fromkeys([PLAYER_CLIENT, "default", "mweb", "android_vr"])]
    else:
        attempts = [("default", False), ("default", True)]

    last_err = ""
    upgraded_once = False

    for idx, (client, use_desktop_headers) in enumerate(attempts):
        if abort_requested():
            return None, "aborted"

        if not is_youtube and idx > 0 and not upgraded_once:
            upgraded_once = True
            run_upgrade_err = None
            try:
                _ensure_ytdlp(upgrade=True)
            except Exception as ue:
                run_upgrade_err = ue
            if run_upgrade_err is not None:
                status(f"yt-dlp upgrade skipped: {run_upgrade_err}")

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
            "--geo-bypass",
            "--newline",
            "--progress-delta", "4",
            "--concurrent-fragments", "8",
            "--socket-timeout", "20",
            "--retries", "2",
            "--fragment-retries", "2",
            "--no-part",
            "-f", "ba/b/best/worst",
            "-o", os.path.join(dest_dir, "raw_audio.%(ext)s"),
            "--no-simulate",
            "--print", "before_dl:OPENSCRIBE_META_TITLE:%(title).150s",
            "--print-to-file", "after_move:filepath", marker,
        ]
        if is_youtube and client != "default":
            cmd.extend(["--extractor-args", f"youtube:player_client={client}"])
        if use_desktop_headers and parsed.scheme and parsed.netloc:
            cmd.extend(["--user-agent", DESKTOP_UA, "--referer", f"{parsed.scheme}://{parsed.netloc}/"])
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
        deadline = time.time() + 180
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
                tail_lines.append("yt-dlp timed out")
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
        elif idx == 0:
            status("Retrying non-YouTube audio fetch with upgraded yt-dlp & desktop headers...")

    if is_youtube:
        return None, "bot_blocked"

    raise RuntimeError(f"yt-dlp failed across all clients:\n{last_err}")


def _download_video_with_ytdlp(url: str, output_mp4: str, max_height: int = 720, emit_title: bool = False) -> str:
    import urllib.parse

    _ensure_ffmpeg()
    _ensure_ytdlp(upgrade=False)

    parsed = urllib.parse.urlparse(url)
    h = max(360, min(1080, int(max_height or 720)))
    formats = [
        f"bv*[height<={h}][vcodec^=avc1]+ba[acodec^=mp4a]/bv*[height<={h}]+ba/b[height<={h}]/b",
        f"b[height<={h}]/b/best/worst",
    ]
    last_err = ""
    for attempt_idx, use_desktop in enumerate((False, True)):
        if abort_requested():
            raise RuntimeError("Job aborted by user.")
        if attempt_idx > 0:
            try:
                _ensure_ytdlp(upgrade=True)
            except Exception:
                pass
        for fmt in formats:
            if os.path.isfile(output_mp4):
                try:
                    os.remove(output_mp4)
                except OSError:
                    pass
            status(f"Colab relay: downloading <={h}p MP4...")
            cmd = [
                "yt-dlp",
                "--no-playlist",
                "--no-warnings",
                "--force-overwrites",
                "--no-part",
                "--geo-bypass",
                "--newline",
                "--progress-delta", "4",
                "--concurrent-fragments", "8",
                "--socket-timeout", "20",
                "--retries", "2",
                "--fragment-retries", "2",
                "-f", fmt,
                "--merge-output-format", "mp4",
                "-o", output_mp4,
            ]
            if emit_title:
                cmd.extend(["--no-simulate", "--print", "before_dl:OPENSCRIBE_META_TITLE:%(title).150s"])
            if use_desktop and parsed.scheme and parsed.netloc:
                cmd.extend(["--user-agent", DESKTOP_UA, "--referer", f"{parsed.scheme}://{parsed.netloc}/"])
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
            tail_lines = []
            t_start = time.time()
            assert proc.stdout is not None
            for raw_line in proc.stdout:
                if abort_requested():
                    proc.kill()
                    raise RuntimeError("Job aborted by user.")
                line = raw_line.strip()
                if not line:
                    continue
                if line.startswith("OPENSCRIBE_META_TITLE:"):
                    if emit_title and not _TITLE_EMITTED:
                        raw_title = line.split("OPENSCRIBE_META_TITLE:", 1)[1]
                        _maybe_emit_english_title(raw_title, from_cue=False)
                    continue
                tail_lines.append(line)
                if len(tail_lines) > 20:
                    tail_lines.pop(0)
                if time.time() - t_start >= 12.0 and "[download]" in line:
                    status(f"Colab relay MP4: {line}")

            rc = proc.wait()
            if rc == 0 and os.path.isfile(output_mp4) and os.path.getsize(output_mp4) > 0:
                return output_mp4
            last_err = "\n".join(tail_lines)

    raise RuntimeError(f"Colab MP4 relay failed:\n{last_err}")


def download_mp4_export(url: str, output_mp4: str, max_height: int = 720) -> str:
    _download_video_with_ytdlp(url, output_mp4, max_height=max_height, emit_title=False)
    emit_line(f"@@OPENSCRIBE_MP4_READY@@ {output_mp4}")
    status("Colab relay MP4 ready for transfer.")
    return output_mp4


def _probe_codecs_mpegts_ok(video_path: str) -> bool:
    try:
        res = subprocess.run(
            [
                "ffprobe", "-v", "error",
                "-show_entries", "stream=codec_type,codec_name",
                "-of", "csv=p=0",
                video_path,
            ],
            capture_output=True,
            text=True,
            timeout=15,
        )
        if res.returncode != 0:
            return False
        vcodec = ""
        acodec = ""
        for line in res.stdout.splitlines():
            parts = [p.strip().lower() for p in line.split(",")]
            if len(parts) >= 2:
                if parts[0] == "video" and not vcodec:
                    vcodec = parts[1]
                elif parts[0] == "audio" and not acodec:
                    acodec = parts[1]
        v_ok = vcodec.startswith(("avc", "h264", "hevc", "hev1", "mp4v"))
        a_ok = (not acodec) or acodec == "none" or acodec.startswith(("mp4a", "aac", "mp3"))
        return v_ok and a_ok
    except Exception:
        return False


def _segment_video_for_remote_chunks(video_path: str, out_dir: str, chunk_sec: int):
    shutil.rmtree(out_dir, ignore_errors=True)
    os.makedirs(out_dir, exist_ok=True)
    prefer_ts = _probe_codecs_mpegts_ok(video_path)
    attempts = [("mpegts", "ts", True), ("matroska", "mkv", False)] if prefer_ts else [("matroska", "mkv", False)]

    last_err = ""
    for seg_fmt, ext, use_ts in attempts:
        for old_f in glob.glob(os.path.join(out_dir, "*")):
            try:
                os.remove(old_f)
            except OSError:
                pass
        csv_path = os.path.join(out_dir, "segments.csv")
        pattern = os.path.join(out_dir, f"chunk_%03d.{ext}")
        cmd = [
            "ffmpeg", "-nostdin", "-y",
            "-hide_banner", "-loglevel", "warning",
            "-i", video_path,
            "-map", "0:v:0", "-map", "0:a:0?",
            "-c", "copy",
            "-f", "segment",
            "-segment_time", str(chunk_sec),
            "-segment_format", seg_fmt,
            "-reset_timestamps", "1",
            "-segment_list_type", "csv",
            "-segment_list", csv_path,
            pattern,
        ]
        res = subprocess.run(cmd, capture_output=True, text=True)
        if res.returncode == 0 and os.path.isfile(csv_path):
            parsed_chunks = []
            idx = 0
            for raw in open(csv_path, encoding="utf-8"):
                parts = raw.strip().split(",")
                if len(parts) < 3:
                    continue
                fname = ",".join(parts[:-2]).strip()
                try:
                    c_start = float(parts[-2].strip())
                    c_end = float(parts[-1].strip())
                except ValueError:
                    continue
                c_path = os.path.join(out_dir, fname)
                if os.path.isfile(c_path) and os.path.getsize(c_path) > 0 and c_end > c_start:
                    parsed_chunks.append((idx, c_start, c_end, c_path))
                    idx += 1
            if parsed_chunks:
                total_dur = parsed_chunks[-1][2]
                return use_ts, parsed_chunks, total_dur
        last_err = res.stderr[-500:] if res.stderr else f"rc={res.returncode}"

    raise RuntimeError(f"Failed to segment Colab video into chunks: {last_err}")


def _extract_wav_with_pyav(raw_path: str, wav_path: str) -> None:
    import wave
    import av
    import numpy as np

    if os.path.isfile(wav_path):
        try:
            os.remove(wav_path)
        except OSError:
            pass

    resampler = av.AudioResampler(format="s16", layout="mono", rate=16000)
    resampler_key = None
    samples_written = 0
    base_t = None

    with av.open(raw_path) as container:
        audio_stream = next((s for s in container.streams if s.type == "audio"), None)
        if audio_stream is None:
            raise RuntimeError("No audio stream found in downloaded media.")
        tb = audio_stream.time_base

        with wave.open(wav_path, "wb") as wf:
            wf.setnchannels(1)
            wf.setsampwidth(2)
            wf.setframerate(16000)

            for packet in container.demux(audio_stream):
                if abort_requested():
                    raise RuntimeError("Job aborted by user.")
                try:
                    frames = packet.decode()
                except Exception:
                    continue

                for frame in frames:
                    try:
                        ch = getattr(frame.layout, "nb_channels", 0) or len(getattr(frame.layout, "channels", ()))
                        if ch <= 0 or ch > 8:
                            continue

                        if frame.pts is not None and tb is not None:
                            t_sec = float(frame.pts * tb)
                            if base_t is None:
                                base_t = 0.0 if t_sec < 10.0 else t_sec
                            expected_sample = max(0, int(round((t_sec - base_t) * 16000)))
                            gap = expected_sample - samples_written
                            if 640 <= gap <= 480000:
                                wf.writeframes(b"\x00\x00" * gap)
                                samples_written += gap

                        cur_key = (ch, getattr(frame.layout, "name", ""), frame.sample_rate, frame.format.name)
                        if resampler_key != cur_key:
                            resampler = av.AudioResampler(format="s16", layout="mono", rate=16000)
                            resampler_key = cur_key

                        out_frames = resampler.resample(frame)
                        if not out_frames:
                            continue
                        if not isinstance(out_frames, (list, tuple)):
                            out_frames = [out_frames]
                        for of in out_frames:
                            arr = of.to_ndarray()
                            pcm = np.asarray(arr, dtype=np.int16).reshape(-1).astype("<i2", copy=False).tobytes()
                            if pcm:
                                wf.writeframes(pcm)
                                samples_written += len(pcm) // 2
                    except Exception:
                        continue

            try:
                flushed = resampler.resample(None)
                if flushed:
                    if not isinstance(flushed, (list, tuple)):
                        flushed = [flushed]
                    for of in flushed:
                        arr = of.to_ndarray()
                        pcm = np.asarray(arr, dtype=np.int16).reshape(-1).astype("<i2", copy=False).tobytes()
                        if pcm:
                            wf.writeframes(pcm)
                            samples_written += len(pcm) // 2
            except Exception:
                pass

    if samples_written < 1600:
        raise RuntimeError(f"PyAV fallback decoded insufficient audio ({samples_written} samples).")


def extract_clean_wav(raw_path: str, wav_path: str, quiet: bool = False) -> None:
    if not quiet:
        status("Converting & syncing audio to 16 kHz mono WAV...")
    t_ff = time.time()
    filter_parts = ["aresample=async=1:first_pts=0"]
    if VOCAL_BOOST:
        filter_parts.extend([
            "highpass=f=120",
            "lowpass=f=7500",
            "afftdn=nf=-22",
            "dynaudnorm=f=150:g=15",
        ])
    elif CLEAN_AUDIO:
        filter_parts.extend([
            "highpass=f=60",
            "afftdn=nf=-25",
            "loudnorm=I=-16:TP=-1.5:LRA=11",
        ])
    else:
        filter_parts.append("highpass=f=60")

    filters = ",".join(filter_parts)
    cmd = [
        "ffmpeg", "-nostdin", "-y",
        "-err_detect", "ignore_err",
        "-threads", "0", "-i", raw_path,
        "-vn", "-sn", "-dn",
        "-af", filters,
        "-ac", "1", "-ar", "16000",
        "-c:a", "pcm_s16le",
        wav_path,
    ]
    res = subprocess.run(cmd, text=True, capture_output=True)
    if res.returncode != 0 or not os.path.isfile(wav_path) or os.path.getsize(wav_path) < 3200:
        ff_err = res.stderr[-900:] if res.stderr else "unknown ffmpeg error"
        if not quiet:
            status("FFmpeg hit corrupt audio stream — recovering via PyAV frame filter...")
        if os.path.isfile(wav_path):
            try:
                os.remove(wav_path)
            except OSError:
                pass
        try:
            _extract_wav_with_pyav(raw_path, wav_path)
        except Exception as pyav_err:
            raise RuntimeError(f"ffmpeg extraction failed:\n{ff_err}\nPyAV recovery also failed: {pyav_err}") from pyav_err
    if not quiet:
        status(f"Audio ready in {time.time() - t_ff:.1f}s")


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


def clean_text(raw_text: str, require_ascii: bool = True) -> str:
    text = re.sub(r"\s+", " ", raw_text or "").strip()
    if not text:
        return ""
    text = re.sub(r"[♪♫]+", "", text).strip()
    if not text:
        return ""
    if require_ascii and not re.search(r"[A-Za-z0-9]", text):
        return ""
    return text


def emit_srt_block(index: int, start: float, end: float, text: str, is_original: bool = False) -> None:
    wrapped = wrap_subtitle(text)
    start_ms = max(0, int(round(start * 1000)))
    begin_tag = "@@OPENSCRIBE_ORIG_SRT_BEGIN@@" if is_original else "@@OPENSCRIBE_SRT_BEGIN@@"
    end_tag = "@@OPENSCRIBE_ORIG_SRT_END@@" if is_original else "@@OPENSCRIBE_SRT_END@@"
    with _PRINT_LOCK:
        print(f"{begin_tag} {start_ms}", flush=True)
        print(f"{index}", flush=True)
        print(f"{ts(start)} --> {ts(end)}", flush=True)
        print(wrapped, flush=True)
        print(end_tag, flush=True)


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


def _build_audio_chunks(audio_arr, sr: int = 16000, target_sec: float = 45.0, first_chunk_sec: float = 20.0):
    total_sec = len(audio_arr) / float(sr)
    if total_sec <= 35.0:
        return [(0.0, total_sec)]

    chunks = []
    prev = 0.0
    win_samples = max(1, int(0.25 * sr))
    step_samples = max(1, int(0.05 * sr))

    while total_sec - prev > 32.0:
        step_target = first_chunk_sec if prev == 0.0 and first_chunk_sec > 0.0 else target_sec
        if total_sec - prev <= step_target + 10.0:
            break
        nominal = prev + step_target
        lo_t = max(prev + 12.0, nominal - 6.0)
        hi_t = min(total_sec - 12.0, nominal + 6.0)
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


def _load_faster_whisper_model(device: str, compute_type: str):
    from faster_whisper import WhisperModel

    model_store = globals().setdefault("_OPENSCRIBE_MODEL_CACHE", {})
    cache_key = (MODEL_ID, device, compute_type)
    if cache_key in model_store:
        status(f"Step 4/4: Reusing warm {MODEL_ID} model already in VRAM (0.0s)")
        return model_store[cache_key]

    # Evict other cached models to prevent GPU VRAM exhaustion
    if model_store:
        model_store.clear()
        gc.collect()
        try:
            import torch
            if torch.cuda.is_available():
                torch.cuda.empty_cache()
        except Exception:
            pass

    cached_dirs = glob.glob(os.path.join(MODEL_CACHE, f"*{MODEL_ID}*"))
    use_local_only = len(cached_dirs) > 0
    t_load = time.time()
    stop_progress = threading.Event()

    def _model_progress_loop():
        while not stop_progress.wait(12.0):
            elapsed = time.time() - t_load
            status(
                f"Step 4/4: Downloading/loading faster-whisper {MODEL_ID} weights ({elapsed:.0f}s elapsed)..."
            )

    threading.Thread(target=_model_progress_loop, daemon=True).start()
    try:
        try:
            status(
                f"Step 4/4: Loading faster-whisper {MODEL_ID} ({device}, {compute_type})"
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
            status(f"Step 4/4: Downloading faster-whisper {MODEL_ID} weights (~3 GB) to Colab T4...")
            model = WhisperModel(
                MODEL_ID,
                device=device,
                compute_type=compute_type,
                download_root=MODEL_CACHE,
                local_files_only=False,
            )
    finally:
        stop_progress.set()

    model_store[cache_key] = model
    status(f"Step 4/4: Model {MODEL_ID} loaded on {device} in {time.time() - t_load:.1f}s")
    return model


def _build_transcribe_runner(model, valid_codes):
    lang_arg = normalize_language(SOURCE_LANG, valid_codes=valid_codes)
    state = {
        "lang_arg": lang_arg,
        "detected_lang": lang_arg,
        "reported_meta": False,
        "en_cue_idx": 0,
        "orig_cue_idx": 0,
    }
    user_prompt = INITIAL_PROMPT or None

    def run_chunk_pass(sub_arr, task_name: str, pass_lang, use_vad: bool, c_start: float, c_end: float, total_sec_hint: float = 0.0):
        patience = 1.4 if BEAM_SIZE >= 7 else 1.0
        kwargs = dict(
            language=pass_lang,
            task=task_name,
            beam_size=BEAM_SIZE,
            best_of=BEAM_SIZE,
            patience=patience,
            temperature=[0.0, 0.2, 0.4],
            compression_ratio_threshold=2.4,
            log_prob_threshold=-1.2 if use_vad else -1.45,
            no_speech_threshold=0.72 if use_vad else 0.85,
            condition_on_previous_text=False,
            repetition_penalty=1.05,
            word_timestamps=False,
            vad_filter=use_vad,
            initial_prompt=user_prompt,
        )
        if use_vad:
            kwargs["vad_parameters"] = dict(
                threshold=0.28,
                min_silence_duration_ms=450,
                max_speech_duration_s=8.0,
                speech_pad_ms=250,
            )

        try:
            seg_iter, info = model.transcribe(sub_arr, **kwargs)
        except TypeError:
            kwargs.pop("repetition_penalty", None)
            seg_iter, info = model.transcribe(sub_arr, **kwargs)

        if not state["detected_lang"] and getattr(info, "language", None):
            state["detected_lang"] = str(info.language)
        if not state["reported_meta"]:
            state["reported_meta"] = True
            emit_line(f"@@OPENSCRIBE_ORIG_LANG@@ {state['detected_lang'] or 'auto'}")
            dur_note = f" | Duration: {total_sec_hint / 60:.1f} min" if total_sec_hint > 0 else ""
            status(
                f"Detected language: {info.language} ({info.language_probability:.0%}){dur_note}"
            )

        cues = []
        last_norm = ""
        repeat_streak = 0

        for seg in seg_iter:
            if abort_requested():
                break

            text = clean_text(getattr(seg, "text", ""), require_ascii=(task_name == "translate"))
            if not text:
                continue

            norm = text.lower()
            if norm == last_norm:
                repeat_streak += 1
            else:
                last_norm = norm
                repeat_streak = 1

            if repeat_streak >= 3:
                continue

            abs_start = c_start + float(seg.start)
            abs_end = min(c_end, c_start + float(seg.end))
            if abs_end - abs_start > 9.0:
                abs_end = min(c_end, abs_start + 8.5)
            if abs_end <= abs_start:
                abs_end = min(c_end, abs_start + 0.6)

            cues.append((abs_start, abs_end, text))

        return cues

    return state, run_chunk_pass


def _translate_array_chunks(audio_arr, base_offset_sec: float, model, valid_codes, state, run_chunk_pass, total_sec_hint: float = 0.0, chunk_label_prefix: str = "", min_emit_sec: float = 0.0):
    import numpy as np
    sr = 16000
    sub_chunks = _build_audio_chunks(audio_arr, sr=sr, target_sec=45.0)
    n_sub = len(sub_chunks)

    for cursor, (rel_start, rel_end) in enumerate(sub_chunks):
        if abort_requested():
            break
        s0 = max(0, int(round(rel_start * sr)))
        s1 = min(len(audio_arr), int(round(rel_end * sr)))
        sub_arr = audio_arr[s0:s1]
        c_start = base_offset_sec + rel_start
        c_end = base_offset_sec + rel_end
        if min_emit_sec > 0.0 and c_end <= min_emit_sec + 0.25:
            emit_line(f"@@OPENSCRIBE_CHUNK_END@@ {c_start:.2f} {c_end:.2f}")
            continue

        if chunk_label_prefix:
            label = chunk_label_prefix if n_sub == 1 else f"{chunk_label_prefix} ({cursor + 1}/{n_sub})"
        else:
            label = f"chunk {cursor + 1}/{n_sub}"
        status(f"Translating -> English {label} ({ts(c_start, '.')} -> {ts(c_end, '.')})...")

        chunk_cues = run_chunk_pass(
            sub_arr,
            task_name="translate",
            pass_lang=state["lang_arg"] or state["detected_lang"],
            use_vad=True,
            c_start=c_start,
            c_end=c_end,
            total_sec_hint=total_sec_hint,
        )

        if not abort_requested():
            chunk_rms = float(np.sqrt(np.mean(sub_arr * sub_arr))) if len(sub_arr) > 0 else 0.0
            if (c_end - c_start) >= 10.0 and chunk_rms > 0.004 and len(chunk_cues) < 2:
                retry_cues = run_chunk_pass(
                    sub_arr,
                    task_name="translate",
                    pass_lang=state["lang_arg"] or state["detected_lang"],
                    use_vad=False,
                    c_start=c_start,
                    c_end=c_end,
                    total_sec_hint=total_sec_hint,
                )
                if len(retry_cues) > len(chunk_cues):
                    chunk_cues = retry_cues

        if abort_requested():
            break

        for abs_start, abs_end, text in chunk_cues:
            if min_emit_sec > 0.0:
                mid = (abs_start + abs_end) * 0.5
                if mid < min_emit_sec or abs_end <= min_emit_sec + 0.25:
                    continue
                if abs_start < min_emit_sec:
                    abs_start = min_emit_sec
            state["en_cue_idx"] += 1
            _maybe_emit_english_title(text, from_cue=True)
            emit_srt_block(state["en_cue_idx"], abs_start, abs_end, text, is_original=False)
            if EMIT_ORIGINAL and (state["detected_lang"] == "en"):
                state["orig_cue_idx"] += 1
                emit_srt_block(state["orig_cue_idx"], abs_start, abs_end, text, is_original=True)

        emit_line(f"@@OPENSCRIBE_CHUNK_END@@ {c_start:.2f} {c_end:.2f}")


def stream_faster_whisper(wav_path: str, device: str, compute_type: str) -> int:
    for tok_var in ("HF_TOKEN", "HUGGING_FACE_HUB_TOKEN", "HUGGINGFACE_HUB_TOKEN"):
        os.environ.pop(tok_var, None)
    os.environ["HF_HUB_DISABLE_IMPLICIT_TOKEN"] = "1"
    os.environ["HF_HUB_VERBOSITY"] = "error"

    import logging
    import numpy as np
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

    state, run_chunk_pass = _build_transcribe_runner(model, valid_codes)
    lang_label = state["lang_arg"] or "auto"
    status(
        f"Translating speech -> English (lang={lang_label}, beam={BEAM_SIZE}, {n_chunks} chunk(s))..."
    )

    _translate_array_chunks(
        audio_arr=audio_arr,
        base_offset_sec=0.0,
        model=model,
        valid_codes=valid_codes,
        state=state,
        run_chunk_pass=run_chunk_pass,
        total_sec_hint=total_sec,
    )

    # Pass 2: Original Language Transcript (using cached warm VRAM model & known language)
    if EMIT_ORIGINAL and not abort_requested() and state["detected_lang"] != "en":
        orig_lang_arg = state["lang_arg"] or state["detected_lang"]
        emit_line(f"@@OPENSCRIBE_ORIG_LANG@@ {orig_lang_arg or 'orig'}")
        status(f"Transcribing original subtitles ({orig_lang_arg or 'auto'})...")
        for cursor, (c_start, c_end) in enumerate(chunks):
            if abort_requested():
                break
            s0 = max(0, int(round(c_start * sr)))
            s1 = min(len(audio_arr), int(round(c_end * sr)))
            sub_arr = audio_arr[s0:s1]

            status(f"Original language ({orig_lang_arg or 'auto'}) chunk {cursor + 1}/{n_chunks}...")
            orig_cues = run_chunk_pass(
                sub_arr, task_name="transcribe", pass_lang=orig_lang_arg, use_vad=True, c_start=c_start, c_end=c_end
            )
            if not abort_requested():
                chunk_rms = float(np.sqrt(np.mean(sub_arr * sub_arr))) if len(sub_arr) > 0 else 0.0
                if (c_end - c_start) >= 10.0 and chunk_rms > 0.004 and len(orig_cues) < 2:
                    retry_orig = run_chunk_pass(
                        sub_arr, task_name="transcribe", pass_lang=orig_lang_arg, use_vad=False, c_start=c_start, c_end=c_end
                    )
                    if len(retry_orig) > len(orig_cues):
                        orig_cues = retry_orig

            for abs_start, abs_end, text in orig_cues:
                state["orig_cue_idx"] += 1
                emit_srt_block(state["orig_cue_idx"], abs_start, abs_end, text, is_original=True)

    if not abort_requested():
        emit_line("@@OPENSCRIBE_ALL_DONE@@")
    return state["en_cue_idx"]


def run_colab_chunk_stream(device: str, compute_type: str, wav_path: str) -> int:
    for tok_var in ("HF_TOKEN", "HUGGING_FACE_HUB_TOKEN", "HUGGINGFACE_HUB_TOKEN"):
        os.environ.pop(tok_var, None)
    os.environ["HF_HUB_DISABLE_IMPLICIT_TOKEN"] = "1"
    os.environ["HF_HUB_VERBOSITY"] = "error"

    import logging
    import numpy as np
    logging.getLogger("huggingface_hub").setLevel(logging.ERROR)

    try:
        from faster_whisper.tokenizer import _LANGUAGE_CODES as fw_codes
        valid_codes = set(fw_codes)
    except Exception:
        valid_codes = None

    raw_video_path = os.path.join(WORKDIR, "colab_stream_video.mp4")
    prep_done = threading.Event()
    prep_error = []

    def _media_prep_worker():
        try:
            _download_video_with_ytdlp(VIDEO_URL, raw_video_path, max_height=MAX_HEIGHT, emit_title=True)
            if abort_requested():
                return
            status("Colab relay: splitting video into quick-start chunks...")
            use_ts, parsed_chunks, total_dur = _segment_video_for_remote_chunks(
                raw_video_path, COLAB_CHUNKS_DIR, CHUNK_SEC
            )
            ts_flag = 1 if use_ts else 0
            for idx, c_start, c_end, c_path in parsed_chunks:
                emit_line(f"@@OPENSCRIBE_REMOTE_CHUNK@@ {idx}|{c_start:.3f}|{c_end:.3f}|{ts_flag}|{c_path}")
            emit_line(f"@@OPENSCRIBE_REMOTE_CHUNKS_DONE@@ {len(parsed_chunks)}|{total_dur:.3f}")
            status(f"Colab relay: {len(parsed_chunks)} video chunks ready for background transfer.")
            if abort_requested():
                return
            extract_clean_wav(raw_video_path, wav_path, quiet=True)
        except Exception as exc:
            prep_error.append(exc)
        finally:
            if os.path.isfile(raw_video_path):
                try:
                    os.remove(raw_video_path)
                except OSError:
                    pass
            prep_done.set()

    prep_thread = threading.Thread(target=_media_prep_worker, daemon=True)
    prep_thread.start()

    # Load Whisper on T4 GPU while Colab downloads, chunks, and extracts WAV concurrently!
    model = _load_faster_whisper_model(device, compute_type)
    while not prep_done.wait(0.5):
        if abort_requested():
            raise RuntimeError("Job aborted by user.")
    if prep_error:
        raise prep_error[0]

    audio_arr = _load_wav_float32(wav_path)
    sr = 16000
    total_sec = len(audio_arr) / float(sr)
    chunks = _build_audio_chunks(audio_arr, sr=sr, target_sec=45.0, first_chunk_sec=float(CHUNK_SEC))
    n_chunks = len(chunks)

    state, run_chunk_pass = _build_transcribe_runner(model, valid_codes)
    lang_label = state["lang_arg"] or "auto"
    status(
        f"Translating speech -> English (lang={lang_label}, beam={BEAM_SIZE}, {n_chunks} chunk(s))..."
    )

    _translate_array_chunks(
        audio_arr=audio_arr,
        base_offset_sec=0.0,
        model=model,
        valid_codes=valid_codes,
        state=state,
        run_chunk_pass=run_chunk_pass,
        total_sec_hint=total_sec,
    )

    if EMIT_ORIGINAL and not abort_requested() and state["detected_lang"] != "en":
        orig_lang_arg = state["lang_arg"] or state["detected_lang"]
        emit_line(f"@@OPENSCRIBE_ORIG_LANG@@ {orig_lang_arg or 'orig'}")
        status(f"Transcribing original subtitles ({orig_lang_arg or 'auto'})...")
        for cursor, (c_start, c_end) in enumerate(chunks):
            if abort_requested():
                break
            s0 = max(0, int(round(c_start * sr)))
            s1 = min(len(audio_arr), int(round(c_end * sr)))
            sub_arr = audio_arr[s0:s1]

            status(f"Original language ({orig_lang_arg or 'auto'}) chunk {cursor + 1}/{n_chunks}...")
            orig_cues = run_chunk_pass(
                sub_arr, task_name="transcribe", pass_lang=orig_lang_arg, use_vad=True, c_start=c_start, c_end=c_end
            )
            if not abort_requested():
                chunk_rms = float(np.sqrt(np.mean(sub_arr * sub_arr))) if len(sub_arr) > 0 else 0.0
                if (c_end - c_start) >= 10.0 and chunk_rms > 0.004 and len(orig_cues) < 2:
                    retry_orig = run_chunk_pass(
                        sub_arr, task_name="transcribe", pass_lang=orig_lang_arg, use_vad=False, c_start=c_start, c_end=c_end
                    )
                    if len(retry_orig) > len(orig_cues):
                        orig_cues = retry_orig

            for abs_start, abs_end, text in orig_cues:
                state["orig_cue_idx"] += 1
                emit_srt_block(state["orig_cue_idx"], abs_start, abs_end, text, is_original=True)

    if not abort_requested():
        emit_line("@@OPENSCRIBE_ALL_DONE@@")
    return state["en_cue_idx"]


def stream_queue_faster_whisper(device: str, compute_type: str) -> int:
    import json
    import logging
    import numpy as np

    for tok_var in ("HF_TOKEN", "HUGGING_FACE_HUB_TOKEN", "HUGGINGFACE_HUB_TOKEN"):
        os.environ.pop(tok_var, None)
    os.environ["HF_HUB_DISABLE_IMPLICIT_TOKEN"] = "1"
    os.environ["HF_HUB_VERBOSITY"] = "error"
    logging.getLogger("huggingface_hub").setLevel(logging.ERROR)

    try:
        from faster_whisper.tokenizer import _LANGUAGE_CODES as fw_codes
        valid_codes = set(fw_codes)
    except Exception:
        valid_codes = None

    os.makedirs(QUEUE_DIR, exist_ok=True)
    model = _load_faster_whisper_model(device, compute_type)
    print("@@OPENSCRIBE_WORKER_READY@@", flush=True)
    status("Whisper ready on T4 GPU — waiting for audio chunk...")

    state, run_chunk_pass = _build_transcribe_runner(model, valid_codes)
    processed_ready = set()
    saved_orig_chunks = []

    while not abort_requested():
        ready_files = sorted(glob.glob(os.path.join(QUEUE_DIR, "chunk_*.ready")))
        pending = [rf for rf in ready_files if rf not in processed_ready]

        if pending:
            for rf in pending:
                if abort_requested():
                    break
                processed_ready.add(rf)
                stem = os.path.basename(rf).rsplit(".", 1)[0]
                meta = None
                try:
                    raw_ready = open(rf, encoding="utf-8").read().strip()
                    if raw_ready.startswith("{"):
                        meta = json.loads(raw_ready)
                except Exception:
                    meta = None
                if meta is None:
                    meta_path = os.path.join(QUEUE_DIR, f"{stem}.meta")
                    if not os.path.isfile(meta_path):
                        continue
                    with open(meta_path, encoding="utf-8") as mf:
                        meta = json.load(mf)
                audio_rel = meta.get("audio", "")
                audio_path = audio_rel if os.path.isabs(audio_rel) else os.path.join(QUEUE_DIR, audio_rel)
                offset_sec = float(meta.get("offset_sec", 0.0))
                min_emit_sec = float(meta.get("min_emit_sec", 0.0))
                chunk_idx = int(meta.get("index", 0))
                if not os.path.isfile(audio_path):
                    continue

                wav_path = os.path.join(QUEUE_DIR, f"{stem}.wav")
                extract_clean_wav(audio_path, wav_path, quiet=True)
                try:
                    os.remove(audio_path)
                except OSError:
                    pass

                audio_arr = _load_wav_float32(wav_path)
                sr = 16000
                dur_sec = len(audio_arr) / float(sr)
                _translate_array_chunks(
                    audio_arr=audio_arr,
                    base_offset_sec=offset_sec,
                    model=model,
                    valid_codes=valid_codes,
                    state=state,
                    run_chunk_pass=run_chunk_pass,
                    total_sec_hint=TOTAL_DUR_HINT,
                    chunk_label_prefix=f"chunk {chunk_idx + 1}",
                    min_emit_sec=min_emit_sec,
                )
                if EMIT_ORIGINAL:
                    saved_orig_chunks.append((wav_path, offset_sec, offset_sec + dur_sec))
                else:
                    try:
                        os.remove(wav_path)
                    except OSError:
                        pass
            continue

        eof_path = os.path.join(QUEUE_DIR, "EOF")
        if os.path.isfile(eof_path):
            break
        time.sleep(0.2)

    # Pass 2: Original Language Transcript if requested
    if EMIT_ORIGINAL and not abort_requested() and state["detected_lang"] != "en" and saved_orig_chunks:
        orig_lang_arg = state["lang_arg"] or state["detected_lang"]
        print(f"@@OPENSCRIBE_ORIG_LANG@@ {orig_lang_arg or 'orig'}", flush=True)
        status(f"Transcribing original subtitles ({orig_lang_arg or 'auto'})...")
        for idx, (wav_path, c_start, c_end) in enumerate(saved_orig_chunks):
            if abort_requested():
                break
            audio_arr = _load_wav_float32(wav_path)
            orig_cues = run_chunk_pass(
                audio_arr, task_name="transcribe", pass_lang=orig_lang_arg, use_vad=True, c_start=c_start, c_end=c_end
            )
            for abs_start, abs_end, text in orig_cues:
                state["orig_cue_idx"] += 1
                emit_srt_block(state["orig_cue_idx"], abs_start, abs_end, text, is_original=True)
            try:
                os.remove(wav_path)
            except OSError:
                pass

    if not abort_requested():
        emit_line("@@OPENSCRIBE_ALL_DONE@@")
    return state["en_cue_idx"]


def main() -> None:
    local_audio = os.environ.get("OS_LOCAL_AUDIO", "").strip()
    if DOWNLOAD_MP4_ONLY:
        if not VIDEO_URL:
            raise RuntimeError("OS_VIDEO_URL is required for MP4 relay export.")
    elif not VIDEO_URL and not local_audio and not STREAMING_QUEUE:
        raise RuntimeError("Neither OS_VIDEO_URL nor OS_LOCAL_AUDIO is set.")

    signal.signal(signal.SIGTERM, _handle_exit_signal)
    signal.signal(signal.SIGINT, _handle_exit_signal)

    cleanup_stale_processes()
    cleanup_temp_files(
        keep_abort=False,
        keep_audio_path=local_audio,
        keep_queue_dir=QUEUE_DIR if STREAMING_QUEUE else "",
        keep_chunks_dir=COLAB_CHUNKS_DIR if COLAB_CHUNK_STREAM else "",
        keep_export=False,
    )

    preset_title = os.environ.get("OS_VIDEO_TITLE", "").strip()
    if preset_title and not DOWNLOAD_MP4_ONLY:
        _maybe_emit_english_title(preset_title, from_cue=False)

    os.makedirs(WORKDIR, exist_ok=True)
    if STREAMING_QUEUE:
        os.makedirs(QUEUE_DIR, exist_ok=True)
    if COLAB_CHUNK_STREAM:
        os.makedirs(COLAB_CHUNKS_DIR, exist_ok=True)
    raw_audio = None
    wav_audio = os.path.join(WORKDIR, "audio_16k.wav")
    keep_export_file = False
    t0 = time.time()
    hb_stop = threading.Event()

    def _heartbeat_loop():
        while not hb_stop.wait(15.0):
            emit_line("@@OPENSCRIBE_HEARTBEAT@@")

    threading.Thread(target=_heartbeat_loop, daemon=True).start()

    try:
        if DOWNLOAD_MP4_ONLY:
            download_mp4_export(VIDEO_URL, EXPORT_MP4_PATH, MAX_HEIGHT)
            keep_export_file = True
            return

        ensure_packages()
        preload_cuda_libs()
        device, compute_type = detect_device()

        if COLAB_CHUNK_STREAM:
            total_cues = run_colab_chunk_stream(device, compute_type, wav_audio)
            elapsed = time.time() - t0
            orig_note = " + original subtitles" if EMIT_ORIGINAL else ""
            status(f"Completed {total_cues} English cues{orig_note} in {elapsed:.1f}s.")
            return

        if STREAMING_QUEUE:
            total_cues = stream_queue_faster_whisper(device, compute_type)
            elapsed = time.time() - t0
            orig_note = " + original subtitles" if EMIT_ORIGINAL else ""
            status(f"Completed {total_cues} English cues{orig_note} in {elapsed:.1f}s.")
            return

        if local_audio and os.path.isfile(local_audio):
            status("Using audio relayed/extracted from device...")
            raw_audio = local_audio
        else:
            raw_audio, dl_err = download_audio(VIDEO_URL, WORKDIR)
            if dl_err == "aborted":
                status("Job aborted before transcription.")
                return
            if dl_err == "bot_blocked":
                emit_line("@@OPENSCRIBE_BOT_BLOCKED@@")
                status("Colab YouTube download skipped/blocked. Using phone audio...")
                return

        if abort_requested():
            return

        extract_clean_wav(raw_audio, wav_audio)

        if raw_audio and os.path.isfile(raw_audio):
            os.remove(raw_audio)
            raw_audio = None

        if abort_requested():
            return

        total_cues = stream_faster_whisper(wav_audio, device, compute_type)
        elapsed = time.time() - t0
        orig_note = " + original subtitles" if EMIT_ORIGINAL else ""
        status(f"Completed {total_cues} English cues{orig_note} in {elapsed:.1f}s.")
    except Exception as exc:
        status(f"ERROR: {exc}")
        raise
    finally:
        hb_stop.set()
        for p in (raw_audio, wav_audio):
            if p and os.path.isfile(p):
                try:
                    os.remove(p)
                except OSError:
                    pass
        cleanup_temp_files(
            keep_abort=False,
            keep_chunks_dir=COLAB_CHUNKS_DIR if COLAB_CHUNK_STREAM else "",
            keep_export=keep_export_file,
        )


if __name__ == "__main__":
    main()
