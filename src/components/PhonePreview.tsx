import { useEffect, useState } from 'react';
import {
  CheckCircle2,
  Cpu,
  Download,
  ExternalLink,
  FolderDown,
  Languages,
  Loader2,
  Maximize2,
  Minimize2,
  Pause,
  Play,
  RotateCcw,
  Search,
  Share2,
  Square,
  Subtitles,
} from 'lucide-react';
import {
  ChunkRange,
  QUICK_LANG_CHIPS,
  WORKFLOW_MODES,
  WorkflowModeId,
  extractAndUnwrapUrl,
  formatMmSs,
  isTimestampTranslated,
  searchWhisperLanguages,
} from '../lib/openscribeLogic';

type SimScreen = 'home' | 'sheet' | 'player';

interface SimCue {
  timeSec: number;
  text: string;
}

const SAMPLE_CUES: SimCue[] = [
  { timeSec: 2, text: 'Welcome back! Today we are visiting a tiny ramen shop in Tokyo.' },
  { timeSec: 7, text: 'The broth has been simmering for eighteen hours over low heat.' },
  { timeSec: 13, text: 'Let us order the rich tonkotsu bowl with extra char siu.' },
  { timeSec: 19, text: 'Look at that golden oil floating right across the top.' },
  { timeSec: 25, text: 'First sip... wow, deep umami and zero heaviness!' },
];

export function PhonePreview() {
  const [rawUrl, setRawUrl] = useState(
    'https://www.google.com/goto?url=https%3A%2F%2Fwww.youtube.com%2Fshorts%2FdQw4w9WgXcQ',
  );
  const [selectedLang, setSelectedLang] = useState('auto');
  const [langQuery, setLangQuery] = useState('');
  const [showLangSearch, setShowLangSearch] = useState(false);
  const [selectedMode, setSelectedMode] = useState<WorkflowModeId>('WATCH_LIVE_AND_SAVE');
  const [screen, setScreen] = useState<SimScreen>('home');

  const [isRunning, setIsRunning] = useState(false);
  const [isGpuWarm, setIsGpuWarm] = useState(false);
  const [statusLine, setStatusLine] = useState('Ready');
  const [cueCount, setCueCount] = useState(0);
  const [completedChunks, setCompletedChunks] = useState<ChunkRange[]>([]);
  const [hasPreviousCache, setHasPreviousCache] = useState(false);

  const [isPaused, setIsPaused] = useState(false);
  const [playTimeSec, setPlayTimeSec] = useState(0);
  const [subsVisible, setSubsVisible] = useState(true);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [bgSaveState, setBgSaveState] = useState<'idle' | 'saving' | 'saved'>('idle');
  const [isSeekingAhead, setIsSeekingAhead] = useState(false);
  const [seekTargetSec, setSeekTargetSec] = useState<number | null>(null);

  const cleanUrl = extractAndUnwrapUrl(rawUrl);
  const filteredLanguages = searchWhisperLanguages(langQuery);

  // Simulated foreground service progression to demonstrate 4-cue auto-open & Seek-Ahead
  useEffect(() => {
    if (!isRunning) return;
    let cancelled = false;
    const timers: number[] = [];

    const run = (delayMs: number, fn: () => void) => {
      const id = window.setTimeout(() => {
        if (!cancelled) fn();
      }, delayMs);
      timers.push(id);
    };

    if (hasPreviousCache && cueCount >= 4) {
      setStatusLine('Smart Cache: Skipped Whisper + MP4 download');
      const modeObj = WORKFLOW_MODES.find((m) => m.id === selectedMode);
      if (modeObj?.opensPlayer) {
        setScreen('player');
      }
      setIsRunning(false);
      return;
    }

    setCueCount(0);
    setCompletedChunks([]);
    setIsGpuWarm(true);
    setStatusLine(isGpuWarm ? 'Reusing warm Colab T4 GPU...' : 'Booting T4 GPU...');

    run(600, () => setStatusLine('Loading faster-whisper large-v3 (CUDA float16)...'));
    run(1100, () => {
      setStatusLine('Translating speech -> English (1/4 cues)...');
      setCueCount(1);
    });
    run(1450, () => setCueCount(2));
    run(1800, () => setCueCount(3));
    run(2150, () => {
      setCueCount(4);
      setCompletedChunks([{ startSec: 0, endSec: 45 }]);
      setStatusLine('4 cues ready — opening player!');
      const modeObj = WORKFLOW_MODES.find((m) => m.id === selectedMode);
      if (modeObj?.opensPlayer) {
        setScreen('player');
        setPlayTimeSec(2);
        setIsPaused(false);
      }
    });
    run(3200, () => {
      setCueCount(5);
      setStatusLine(
        selectedMode === 'WATCH_LIVE_ONLY'
          ? 'Completed (5 cues) — T4 GPU warm'
          : 'Saved MP4 + SRT/ASS/VTT to Downloads/OpenScribe/',
      );
      setHasPreviousCache(true);
      setIsRunning(false);
    });

    return () => {
      cancelled = true;
      timers.forEach((t) => window.clearTimeout(t));
    };
  }, [isRunning]);

  // Playback timer
  useEffect(() => {
    if (screen !== 'player' || isPaused || isSeekingAhead) return;
    const id = window.setInterval(() => {
      setPlayTimeSec((prev) => (prev >= 180 ? 0 : prev + 1));
    }, 1000);
    return () => window.clearInterval(id);
  }, [screen, isPaused, isSeekingAhead]);

  const handleSeek = (target: number) => {
    setPlayTimeSec(target);
    if (!isTimestampTranslated(target, completedChunks)) {
      setIsSeekingAhead(true);
      setSeekTargetSec(target);
      window.setTimeout(() => {
        setCompletedChunks((prev) => [
          ...prev,
          { startSec: Math.max(0, target - 5), endSec: target + 40 },
        ]);
        setIsSeekingAhead(false);
        setSeekTargetSec(null);
      }, 1300);
    }
  };

  const activeCue =
    [...SAMPLE_CUES]
      .slice(0, Math.max(1, cueCount))
      .reverse()
      .find((c) => playTimeSec >= c.timeSec) ?? SAMPLE_CUES[0];

  return (
    <div className="grid gap-8 lg:grid-cols-[minmax(0,1fr)_380px] lg:items-start">
      {/* Left: Interactive Explanation & Controls */}
      <div className="space-y-5">
        <div className="rounded-2xl border border-slate-800 bg-[#131A26] p-6">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <h2 className="text-lg font-semibold text-slate-50">
                Interactive Android Flow Preview
              </h2>
              <p className="mt-1 text-sm text-slate-400">
                Test the exact 1-APK workflow implemented in the Kotlin + Jetpack Compose project:
                Share Sheet unwrapping, ~99 Whisper languages, 4-cue auto-open, Seek-Ahead, and Warm
                T4 Session Reuse.
              </p>
            </div>
            <button
              type="button"
              onClick={() => {
                setRawUrl(
                  'Watch this! https://www.youtube.com/shorts/jNQXAC9IVRw?feature=share',
                );
                setScreen('sheet');
              }}
              className="inline-flex items-center gap-2 rounded-xl border border-sky-500/40 bg-sky-500/10 px-3.5 py-2 text-xs font-semibold text-sky-300 transition hover:bg-sky-500/20"
            >
              <Share2 className="h-3.5 w-3.5" />
              Simulate YouTube Share Intent
            </button>
          </div>

          <div className="mt-5 grid gap-3 sm:grid-cols-3">
            <div className="rounded-xl border border-slate-800 bg-[#0B0F17] p-3.5">
              <p className="text-xs font-semibold uppercase tracking-wider text-amber-400">
                1. Embedded libmpv
              </p>
              <p className="mt-1 text-xs text-slate-300">
                <code className="break-all text-sky-300">dev.jdtech.mpv:libmpv:1.0.0</code> with{' '}
                <code className="text-amber-300">sub-add</code> + live{' '}
                <code className="text-amber-300">sub-reload</code> in private{' '}
                <code className="text-slate-200">filesDir</code>.
              </p>
            </div>
            <div className="rounded-xl border border-slate-800 bg-[#0B0F17] p-3.5">
              <p className="text-xs font-semibold uppercase tracking-wider text-sky-400">
                2. Embedded yt-dlp
              </p>
              <p className="mt-1 text-xs text-slate-300">
                <code className="break-all text-sky-300">youtubedl-android:0.18.1</code> (library, ffmpeg,
                aria2c) + MediaStore saver.
              </p>
            </div>
            <div className="rounded-xl border border-slate-800 bg-[#0B0F17] p-3.5">
              <p className="text-xs font-semibold uppercase tracking-wider text-emerald-400">
                3. Headless Colab T4
              </p>
              <p className="mt-1 text-xs text-slate-300">
                <code className="text-sky-300">google-colab-cli</code> OAuth2 + Jupyter WebSocket
                streaming <code className="text-slate-200">colab_worker.py</code>.
              </p>
            </div>
          </div>
        </div>

        {/* Live Foreground Service & Protocol Log Preview */}
        <div className="rounded-2xl border border-slate-800 bg-[#131A26] p-5">
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-semibold text-slate-100">
              Android Foreground Service State
            </h3>
            <span
              className={`rounded-md px-2.5 py-0.5 text-xs font-medium ${
                isGpuWarm
                  ? 'bg-emerald-500/15 text-emerald-300'
                  : 'bg-slate-800 text-slate-400'
              }`}
            >
              {isGpuWarm ? 'T4 GPU Warm (WakeLock Active)' : 'Idle'}
            </span>
          </div>
          <dl className="mt-4 grid gap-3 text-xs sm:grid-cols-2">
            <div className="rounded-xl border border-slate-800/80 bg-[#0B0F17] p-3">
              <dt className="text-slate-400">Unwrapped Target URL</dt>
              <dd className="mt-1 break-all font-mono text-sky-300">{cleanUrl || 'None'}</dd>
            </div>
            <div className="rounded-xl border border-slate-800/80 bg-[#0B0F17] p-3">
              <dt className="text-slate-400">Live Notification Banner</dt>
              <dd className="mt-1 leading-relaxed font-medium text-amber-300">{statusLine}</dd>
            </div>
          </dl>
        </div>
      </div>

      {/* Right: Realistic Android Phone Frame */}
      <div className="mx-auto w-full max-w-[380px] rounded-[2.2rem] border-[6px] border-slate-800 bg-[#0B0F17] p-3 shadow-2xl">
        {/* Status Bar */}
        <div className="flex items-center justify-between px-3 pb-2 pt-1 text-[11px] font-medium text-slate-400">
          <span>OpenScribe</span>
          <span className="flex items-center gap-1.5">
            {isGpuWarm && <span className="h-2 w-2 rounded-full bg-emerald-400" />}
            {isGpuWarm ? 'T4 Warm' : 'Android 15'}
          </span>
        </div>

        <div className="min-h-[560px] overflow-hidden rounded-[1.5rem] border border-slate-800/90 bg-[#0B0F17]">
          {screen === 'player' ? (
            <div className="flex h-[560px] flex-col justify-between bg-black p-3">
              <div className="flex items-center justify-between gap-1.5">
                <button
                  type="button"
                  onClick={() => setScreen('home')}
                  className="rounded-lg bg-slate-900/90 px-2.5 py-1 text-xs font-medium text-slate-200 hover:bg-slate-800"
                >
                  Back
                </button>
                <span className="truncate text-[11px] font-medium text-amber-400">
                  {cueCount} cues • {bgSaveState === 'saved' ? 'Saved' : isFullscreen ? 'Immersive' : 'libmpv'}
                </span>
                <div className="flex items-center gap-1">
                  <button
                    type="button"
                    onClick={() => {
                      if (bgSaveState !== 'idle') return;
                      setBgSaveState('saving');
                      window.setTimeout(() => setBgSaveState('saved'), 1200);
                    }}
                    aria-label="Save video and subtitles"
                    title="Save <=720p MP4 + subtitles in background"
                    className={`rounded-lg px-2 py-1 text-xs font-medium ${
                      bgSaveState === 'saved'
                        ? 'bg-emerald-500/20 text-emerald-300'
                        : 'bg-slate-800 text-slate-200 hover:bg-slate-700'
                    }`}
                  >
                    {bgSaveState === 'saving' ? (
                      <Loader2 className="h-3.5 w-3.5 animate-spin text-sky-400" />
                    ) : bgSaveState === 'saved' ? (
                      <CheckCircle2 className="h-3.5 w-3.5 text-emerald-400" />
                    ) : (
                      <Download className="h-3.5 w-3.5" />
                    )}
                  </button>
                  <button
                    type="button"
                    onClick={() => setSubsVisible((v) => !v)}
                    aria-label="Toggle subtitles"
                    className={`rounded-lg px-2 py-1 text-xs font-medium ${
                      subsVisible ? 'bg-amber-400 text-slate-950' : 'bg-slate-800 text-slate-300'
                    }`}
                  >
                    <Subtitles className="h-3.5 w-3.5" />
                  </button>
                  <button
                    type="button"
                    onClick={() => setIsFullscreen((f) => !f)}
                    aria-label="Toggle immersive fullscreen"
                    className={`rounded-lg px-2 py-1 text-xs font-medium ${
                      isFullscreen ? 'bg-sky-500/25 text-sky-300' : 'bg-slate-800 text-slate-300'
                    }`}
                  >
                    {isFullscreen ? (
                      <Minimize2 className="h-3.5 w-3.5" />
                    ) : (
                      <Maximize2 className="h-3.5 w-3.5" />
                    )}
                  </button>
                </div>
              </div>

              {/* Video Canvas Area */}
              <div className="relative flex flex-1 flex-col items-center justify-center">
                {isSeekingAhead ? (
                  <div className="flex items-center gap-3 rounded-xl border border-amber-400/40 bg-[#0B0F17]/95 px-4 py-3">
                    <Loader2 className="h-5 w-5 animate-spin text-amber-400" />
                    <div>
                      <p className="text-xs font-semibold text-amber-300">
                        Translating from {formatMmSs(seekTargetSec ?? playTimeSec)}...
                      </p>
                      <p className="text-[11px] text-slate-400">
                        Uploaded /content/openscribe_seek.txt
                      </p>
                    </div>
                  </div>
                ) : (
                  <div className="text-center">
                    <p className="text-xs uppercase tracking-widest text-slate-500">
                      720p Hardware Decoded
                    </p>
                    <p className="mt-1 font-mono text-2xl font-bold text-slate-200">
                      {formatMmSs(playTimeSec)} / 03:00
                    </p>
                  </div>
                )}

                {subsVisible && !isSeekingAhead && (
                  <div className="absolute bottom-4 left-2 right-2 text-center">
                    <span className="inline-block rounded bg-black/75 px-3 py-1.5 text-sm font-bold text-[#FFFF00] shadow">
                      {activeCue.text}
                    </span>
                  </div>
                )}
              </div>

              {/* Seek & Transport Controls */}
              <div className="space-y-2 rounded-xl bg-[#131A26] p-3">
                <div className="flex items-center justify-between gap-2">
                  <button
                    type="button"
                    onClick={() => setIsPaused((p) => !p)}
                    className="inline-flex items-center gap-1.5 rounded-lg bg-amber-400 px-3 py-1.5 text-xs font-bold text-slate-950"
                  >
                    {isPaused ? <Play className="h-3.5 w-3.5" /> : <Pause className="h-3.5 w-3.5" />}
                    {isPaused ? 'Play' : 'Pause'}
                  </button>
                  <button
                    type="button"
                    onClick={() => handleSeek(135)}
                    className="rounded-lg border border-sky-500/40 bg-sky-500/10 px-2.5 py-1.5 text-[11px] font-semibold text-sky-300 hover:bg-sky-500/20"
                  >
                    Test Seek-Ahead (02:15)
                  </button>
                </div>
                <input
                  type="range"
                  aria-label="Seek timeline"
                  min={0}
                  max={180}
                  value={playTimeSec}
                  onChange={(e) => handleSeek(Number(e.target.value))}
                  className="w-full accent-amber-400"
                />
              </div>
            </div>
          ) : (
            <div className="space-y-3.5 p-3.5">
              {/* Header */}
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-amber-400 text-slate-950">
                    <Subtitles className="h-4 w-4" />
                  </div>
                  <div>
                    <p className="text-sm font-bold text-slate-100">OpenScribe</p>
                    <p className="text-[11px] text-slate-400">
                      {isGpuWarm ? 'T4 GPU Warm' : 'Ready'}
                    </p>
                  </div>
                </div>
              </div>

              {/* URL Input */}
              <div className="rounded-xl border border-slate-800 bg-[#131A26] p-3">
                <label
                  htmlFor="sim-url"
                  className="block text-xs font-semibold text-slate-200"
                >
                  Video Link
                </label>
                <input
                  id="sim-url"
                  type="url"
                  value={rawUrl}
                  onChange={(e) => setRawUrl(e.target.value)}
                  className="mt-1.5 w-full rounded-lg border border-slate-700 bg-[#0B0F17] px-2.5 py-2 text-xs text-slate-100 focus:border-amber-400 focus:outline-none"
                />
                <button
                  type="button"
                  onClick={() => setScreen('sheet')}
                  className="mt-2.5 flex w-full items-center justify-center gap-2 rounded-xl bg-amber-400 py-2.5 text-xs font-bold text-slate-950 transition hover:bg-amber-300"
                >
                  <Play className="h-3.5 w-3.5" />
                  Configure &amp; Start Translation
                </button>
              </div>

              {/* Language & Mode Config Sheet */}
              {screen === 'sheet' && (
                <div className="space-y-3 rounded-xl border border-amber-400/50 bg-[#131A26] p-3">
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-bold text-slate-100">
                      Source Language (~99)
                    </span>
                    <button
                      type="button"
                      onClick={() => setShowLangSearch((s) => !s)}
                      className="inline-flex items-center gap-1 text-[11px] font-semibold text-sky-300"
                    >
                      <Search className="h-3 w-3" />
                      {showLangSearch ? 'Hide Search' : 'Search All'}
                    </button>
                  </div>

                  <div className="flex flex-wrap gap-1.5">
                    {QUICK_LANG_CHIPS.map((lang) => (
                      <button
                        key={lang.code}
                        type="button"
                        onClick={() => setSelectedLang(lang.code)}
                        className={`rounded-lg px-2 py-1 text-[11px] font-semibold ${
                          selectedLang === lang.code
                            ? 'bg-amber-400/20 text-amber-300 ring-1 ring-amber-400/60'
                            : 'bg-[#0B0F17] text-slate-300'
                        }`}
                      >
                        {lang.code}
                      </button>
                    ))}
                  </div>

                  {showLangSearch && (
                    <div className="space-y-1.5">
                      <input
                        type="text"
                        aria-label="Search Whisper language"
                        placeholder="Search e.g. spanish, yue, hangul..."
                        value={langQuery}
                        onChange={(e) => setLangQuery(e.target.value)}
                        className="w-full rounded-lg border border-slate-700 bg-[#0B0F17] px-2.5 py-1.5 text-xs text-slate-100"
                      />
                      <div className="max-h-24 space-y-1 overflow-y-auto">
                        {filteredLanguages.slice(0, 6).map((l) => (
                          <button
                            key={l.code}
                            type="button"
                            onClick={() => {
                              setSelectedLang(l.code);
                              setShowLangSearch(false);
                            }}
                            className="flex w-full items-center justify-between rounded px-2 py-1 text-left text-[11px] text-slate-200 hover:bg-slate-800"
                          >
                            <span>{l.name}</span>
                            <span className="font-mono text-sky-300">{l.code}</span>
                          </button>
                        ))}
                      </div>
                    </div>
                  )}

                  <div className="space-y-1.5">
                    <span className="text-xs font-bold text-slate-100">Mode</span>
                    {WORKFLOW_MODES.map((m) => (
                      <button
                        key={m.id}
                        type="button"
                        onClick={() => setSelectedMode(m.id)}
                        className={`block w-full rounded-lg border p-2 text-left ${
                          selectedMode === m.id
                            ? 'border-amber-400 bg-amber-400/10'
                            : 'border-slate-800 bg-[#0B0F17]'
                        }`}
                      >
                        <p className="text-xs font-semibold text-slate-100">{m.title}</p>
                        <p className="text-[10px] text-slate-400">{m.subtitle}</p>
                      </button>
                    ))}
                  </div>

                  <button
                    type="button"
                    onClick={() => {
                      setScreen('home');
                      setIsRunning(true);
                    }}
                    className="w-full rounded-xl bg-amber-400 py-2 text-xs font-bold text-slate-950"
                  >
                    Start T4 GPU Worker
                  </button>
                </div>
              )}

              {/* Active Progress / Warm Reuse */}
              {(isRunning || cueCount > 0 || isGpuWarm) && (
                <div className="space-y-2.5 rounded-xl border border-slate-800 bg-[#131A26] p-3">
                  <div className="flex items-center gap-2">
                    {isRunning ? (
                      <Loader2 className="h-4 w-4 animate-spin text-sky-400" />
                    ) : (
                      <CheckCircle2 className="h-4 w-4 text-emerald-400" />
                    )}
                    <p className="text-xs font-semibold text-slate-100">{statusLine}</p>
                  </div>
                  <p className="text-[11px] text-slate-400">
                    {cueCount} cues • {completedChunks.length} chunk(s) • lang={selectedLang}
                  </p>
                  {cueCount >= 4 && selectedMode !== 'OFFLINE_PACK_ONLY' && (
                    <button
                      type="button"
                      onClick={() => setScreen('player')}
                      className="flex w-full items-center justify-center gap-1.5 rounded-lg bg-amber-400 py-1.5 text-xs font-bold text-slate-950"
                    >
                      <Play className="h-3.5 w-3.5" />
                      Open Built-in libmpv Player
                    </button>
                  )}
                </div>
              )}

              {isGpuWarm && !isRunning && (
                <div className="space-y-2 rounded-xl border border-sky-500/30 bg-[#131A26] p-3">
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-semibold text-sky-300">Warm T4 Reuse</span>
                    <button
                      type="button"
                      onClick={() => {
                        setIsGpuWarm(false);
                        setStatusLine('Colab T4 GPU stopped');
                      }}
                      className="inline-flex items-center gap-1 text-[11px] font-semibold text-rose-400"
                    >
                      <Square className="h-3 w-3" />
                      Stop T4 GPU
                    </button>
                  </div>
                  <div className="grid grid-cols-2 gap-2">
                    <button
                      type="button"
                      onClick={() => setIsRunning(true)}
                      className="inline-flex items-center justify-center gap-1 rounded-lg border border-amber-400/50 py-1.5 text-[11px] font-semibold text-amber-300"
                    >
                      <RotateCcw className="h-3 w-3" />
                      [A] Run Previous
                    </button>
                    <button
                      type="button"
                      onClick={() => {
                        setHasPreviousCache(false);
                        setRawUrl('');
                        setCueCount(0);
                      }}
                      className="inline-flex items-center justify-center gap-1 rounded-lg border border-sky-400/50 py-1.5 text-[11px] font-semibold text-sky-300"
                    >
                      <ExternalLink className="h-3 w-3" />
                      [B] New Link
                    </button>
                  </div>
                  {hasPreviousCache && (
                    <p className="text-[10px] text-emerald-400">
                      Smart Cache: Subtitles + MP4 cached
                    </p>
                  )}
                </div>
              )}
              {screen === 'home' && !isRunning && cueCount === 0 && !isGpuWarm && (
                <div className="space-y-2 rounded-xl border border-slate-800/80 bg-[#131A26]/70 p-3 text-[11px] text-slate-300">
                  <p className="font-semibold text-slate-100">Single-APK Ready</p>
                  <p className="text-slate-400">
                    Share any video from YouTube or tap Configure above. At 4 English cues, the
                    built-in libmpv player opens automatically.
                  </p>
                  <div className="flex flex-wrap gap-1.5 pt-1 text-[10px]">
                    <span className="rounded bg-[#0B0F17] px-2 py-0.5 text-sky-300">
                      No Termux
                    </span>
                    <span className="rounded bg-[#0B0F17] px-2 py-0.5 text-amber-300">
                      No External mpv
                    </span>
                    <span className="rounded bg-[#0B0F17] px-2 py-0.5 text-emerald-300">
                      Scoped MediaStore
                    </span>
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
