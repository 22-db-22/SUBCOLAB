import { useEffect, useState } from 'react';
import {
  CheckCircle2,
  Download,
  ExternalLink,
  Info,
  Lightbulb,
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
  X,
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
  const [selectedMode, setSelectedMode] = useState<WorkflowModeId>('WATCH_NOW');
  const [screen, setScreen] = useState<SimScreen>('home');
  const [showAbout, setShowAbout] = useState(false);
  const [showQuickTip, setShowQuickTip] = useState(true);

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

    run(600, () =>
      setStatusLine(
        selectedMode === 'WATCH_NOW'
          ? 'Live stream proxy ready • slicing 15s adaptive audio chunks...'
          : 'Downloading full <=720p MP4 & extracting audio in background...',
      ),
    );
    run(1100, () => {
      setStatusLine('Translating audio chunk 1 -> English...');
      setCueCount(1);
    });
    run(1450, () => setCueCount(2));
    run(1800, () => setCueCount(3));
    run(2150, () => {
      setCueCount(4);
      setCompletedChunks([{ startSec: 0, endSec: 45 }]);
      const modeObj = WORKFLOW_MODES.find((m) => m.id === selectedMode);
      if (modeObj?.opensPlayer) {
        setStatusLine('Initial subs ready — auto-opening Live Stream player!');
        setScreen('player');
        setPlayTimeSec(2);
        setIsPaused(false);
      } else {
        setStatusLine('Background Download: 4 cues ready, finishing full MP4...');
      }
    });
    run(3200, () => {
      setCueCount(5);
      setStatusLine(
        selectedMode === 'WATCH_NOW'
          ? 'Live Stream + all 5 cues ready (subtitles saved)'
          : 'Saved full MP4 + SRT/ASS/VTT to Downloads/OpenScribe/',
      );
      setHasPreviousCache(true);
      setIsRunning(false);
    });

    return () => {
      cancelled = true;
      timers.forEach((t) => window.clearTimeout(t));
    };
  }, [isRunning]);

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
        <div className="rounded-2xl border border-[#E2DCCD] bg-white p-6 shadow-sm">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <h2 className="text-lg font-semibold text-[#1C2621]">
                Interactive Android Flow Preview
              </h2>
              <p className="mt-1 text-sm text-[#5A6860]">
                Calm Cream, Sage Green &amp; Soft Sky Blue Material 3 theme — while keeping the
                embedded libmpv player cinema-black with high-contrast yellow subtitles.
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
              className="inline-flex items-center gap-2 rounded-xl border border-[#5B9BD5]/50 bg-[#DDF0FB] px-3.5 py-2 text-xs font-semibold text-[#2B6CB0] transition hover:bg-[#cde6f8]"
            >
              <Share2 className="h-3.5 w-3.5" />
              Simulate YouTube Share Intent
            </button>
          </div>

          <div className="mt-5 grid gap-3 sm:grid-cols-3">
            <div className="rounded-xl border border-[#E2DCCD] bg-[#FAF7F0] p-3.5">
              <p className="text-xs font-semibold uppercase tracking-wider text-[#2E7D5B]">
                1. Embedded libmpv
              </p>
              <p className="mt-1 text-xs text-[#5A6860]">
                <code className="break-all font-medium text-[#2B6CB0]">
                  dev.jdtech.mpv:libmpv:0.5.1
                </code>{' '}
                streaming smoothly on cinema-black with live{' '}
                <code className="font-medium text-[#2E7D5B]">sub-reload</code>.
              </p>
            </div>
            <div className="rounded-xl border border-[#E2DCCD] bg-[#FAF7F0] p-3.5">
              <p className="text-xs font-semibold uppercase tracking-wider text-[#2B6CB0]">
                2. Embedded yt-dlp
              </p>
              <p className="mt-1 text-xs text-[#5A6860]">
                <code className="break-all font-medium text-[#2B6CB0]">
                  youtubedl-android:0.18.1
                </code>{' '}
                with loopback live proxy + adaptive audio-only chunks.
              </p>
            </div>
            <div className="rounded-xl border border-[#E2DCCD] bg-[#FAF7F0] p-3.5">
              <p className="text-xs font-semibold uppercase tracking-wider text-[#16A34A]">
                3. Headless Colab T4
              </p>
              <p className="mt-1 text-xs text-[#5A6860]">
                <code className="font-medium text-[#2B6CB0]">google-colab-cli</code> OAuth2 +
                Whisper <code className="font-medium text-[#1C2621]">large-v3</code> WebSocket
                streaming.
              </p>
            </div>
          </div>
        </div>

        {/* Live Foreground Service & Protocol State */}
        <div className="rounded-2xl border border-[#E2DCCD] bg-white p-5 shadow-sm">
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-semibold text-[#1C2621]">
              Android Foreground Service State
            </h3>
            <span
              className={`rounded-md px-2.5 py-0.5 text-xs font-semibold ${
                isGpuWarm
                  ? 'bg-[#DDF0FB] text-[#2B6CB0]'
                  : 'bg-[#F1ECE1] text-[#5A6860]'
              }`}
            >
              {isGpuWarm ? 'T4 GPU Warm (WakeLock Active)' : 'Idle'}
            </span>
          </div>
          <dl className="mt-4 grid gap-3 text-xs sm:grid-cols-2">
            <div className="rounded-xl border border-[#E2DCCD] bg-[#FAF7F0] p-3">
              <dt className="text-[#5A6860]">Unwrapped Target URL</dt>
              <dd className="mt-1 break-all font-mono text-[#2B6CB0]">{cleanUrl || 'None'}</dd>
            </div>
            <div className="rounded-xl border border-[#E2DCCD] bg-[#FAF7F0] p-3">
              <dt className="text-[#5A6860]">Live Notification Banner</dt>
              <dd className="mt-1 leading-relaxed font-semibold text-[#2E7D5B]">{statusLine}</dd>
            </div>
          </dl>
        </div>

        {/* About OpenScribe Card */}
        <div className="rounded-2xl border border-[#E2DCCD] bg-white p-5 shadow-sm">
          <div className="flex items-center justify-between gap-2">
            <div>
              <h3 className="text-sm font-semibold text-[#1C2621]">About OpenScribe</h3>
              <p className="text-xs font-medium text-[#2E7D5B]">OpenScribe Android v1.0.0</p>
            </div>
            <span className="rounded-lg bg-[#DCEFE5] px-2.5 py-1 text-[11px] font-semibold text-[#2E7D5B]">
              Cream + Sage + Sky
            </span>
          </div>
          <p className="mt-2 text-xs leading-relaxed text-[#5A6860]">
            Live AI video translation powered by Whisper large-v3 on Google Colab T4 &amp; libmpv.
          </p>
          <div className="mt-3 flex flex-wrap items-center justify-between gap-2 border-t border-[#E2DCCD] pt-3 text-xs">
            <span className="text-[#5A6860]">
              Created by <strong className="text-[#1C2621]">writtenwithharmony@gmail.com</strong>
            </span>
            <a
              href="https://github.com/22-db-22/openscribe-android"
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center gap-1 font-semibold text-[#2B6CB0] hover:underline"
            >
              GitHub Repository
              <ExternalLink className="h-3.5 w-3.5" />
            </a>
          </div>
        </div>
      </div>

      {/* Right: Realistic Android Phone Frame */}
      <div
        className={`mx-auto w-full max-w-[380px] rounded-[2.2rem] border-[6px] border-[#26322C] p-3 shadow-xl transition-colors ${
          screen === 'player' ? 'bg-black' : 'bg-[#FAF7F0]'
        }`}
      >
        {/* Status Bar (Light icons on Cinema Player, Dark icons on Cream Home) */}
        <div
          className={`flex items-center justify-between px-3 pb-2 pt-1 text-[11px] font-semibold ${
            screen === 'player' ? 'text-slate-300' : 'text-[#1C2621]'
          }`}
        >
          <span>OpenScribe</span>
          <span className="flex items-center gap-1.5">
            {isGpuWarm && (
              <span
                className={`h-2 w-2 rounded-full ${
                  screen === 'player' ? 'bg-[#7CC4FA]' : 'bg-[#5B9BD5]'
                }`}
              />
            )}
            {isGpuWarm ? 'T4 Warm' : 'Android 15'}
          </span>
        </div>

        <div
          className={`min-h-[560px] overflow-hidden rounded-[1.5rem] border ${
            screen === 'player'
              ? 'border-slate-800 bg-black'
              : 'border-[#E2DCCD] bg-[#FAF7F0]'
          }`}
        >
          {screen === 'player' ? (
            <div className="flex h-[560px] flex-col justify-between bg-black p-3">
              <div className="flex items-center justify-between gap-1.5">
                <button
                  type="button"
                  onClick={() => setScreen('home')}
                  className="rounded-lg bg-slate-900/90 px-2.5 py-1 text-xs font-medium text-white hover:bg-slate-800"
                >
                  Back
                </button>
                <span className="truncate text-[11px] font-semibold text-[#FFFF00]">
                  {cueCount} cues •{' '}
                  {selectedMode === 'WATCH_NOW'
                    ? 'Live Stream'
                    : bgSaveState === 'saved'
                      ? 'Saved'
                      : isFullscreen
                        ? 'Immersive'
                        : 'libmpv'}
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
                      <Loader2 className="h-3.5 w-3.5 animate-spin text-[#7CC4FA]" />
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
                      subsVisible ? 'bg-[#FFFF00] text-black' : 'bg-slate-800 text-slate-300'
                    }`}
                  >
                    <Subtitles className="h-3.5 w-3.5" />
                  </button>
                  <button
                    type="button"
                    onClick={() => setIsFullscreen((f) => !f)}
                    aria-label="Toggle immersive fullscreen"
                    className={`rounded-lg px-2 py-1 text-xs font-medium ${
                      isFullscreen ? 'bg-[#7CC4FA]/25 text-[#7CC4FA]' : 'bg-slate-800 text-slate-300'
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
                  <div className="flex items-center gap-3 rounded-xl border border-[#FFFF00]/40 bg-[#0B0F17]/95 px-4 py-3">
                    <Loader2 className="h-5 w-5 animate-spin text-[#FFFF00]" />
                    <div>
                      <p className="text-xs font-semibold text-[#FFFF00]">
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
                    className="inline-flex items-center gap-1.5 rounded-lg bg-[#FFFF00] px-3 py-1.5 text-xs font-bold text-black"
                  >
                    {isPaused ? <Play className="h-3.5 w-3.5" /> : <Pause className="h-3.5 w-3.5" />}
                    {isPaused ? 'Play' : 'Pause'}
                  </button>
                  <button
                    type="button"
                    onClick={() => handleSeek(135)}
                    className="rounded-lg border border-[#7CC4FA]/40 bg-[#7CC4FA]/10 px-2.5 py-1.5 text-[11px] font-semibold text-[#7CC4FA] hover:bg-[#7CC4FA]/20"
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
                  className="w-full accent-[#FFFF00]"
                />
              </div>
            </div>
          ) : (
            <div className="space-y-3 p-3.5">
              {/* Header */}
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2.5">
                  <div className="flex h-8 w-8 items-center justify-center rounded-xl bg-[#2E7D5B] text-white shadow-sm">
                    <Subtitles className="h-4 w-4" />
                  </div>
                  <div>
                    <p className="text-sm font-bold text-[#1C2621]">OpenScribe</p>
                    <p
                      className={`text-[11px] font-medium ${
                        isGpuWarm ? 'text-[#2B6CB0]' : 'text-[#5A6860]'
                      }`}
                    >
                      {isGpuWarm ? 'T4 GPU Warm' : 'Live AI Video Translator'}
                    </p>
                  </div>
                </div>
                <button
                  type="button"
                  onClick={() => setShowAbout((a) => !a)}
                  aria-label="About OpenScribe"
                  className="rounded-lg p-1.5 text-[#5A6860] transition hover:bg-[#F1ECE1] hover:text-[#1C2621]"
                >
                  <Info className="h-4 w-4" />
                </button>
              </div>

              {showAbout && (
                <div className="space-y-1.5 rounded-2xl border border-[#E2DCCD] bg-white p-3 text-[11px] shadow-sm">
                  <div className="flex items-center justify-between">
                    <span className="font-bold text-[#1C2621]">OpenScribe Android v1.0.0</span>
                    <button
                      type="button"
                      onClick={() => setShowAbout(false)}
                      aria-label="Close about"
                      className="text-[#5A6860]"
                    >
                      <X className="h-3.5 w-3.5" />
                    </button>
                  </div>
                  <p className="text-[#5A6860]">Created by writtenwithharmony@gmail.com</p>
                  <p className="font-medium text-[#2B6CB0]">
                    github.com/22-db-22/openscribe-android
                  </p>
                </div>
              )}

              {/* Quick Tips Card */}
              {showQuickTip && screen === 'home' && (
                <div className="flex items-start gap-2.5 rounded-2xl border border-[#E2DCCD] bg-white p-2.5 text-[11px] shadow-xs">
                  <div className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-lg bg-[#DDF0FB] text-[#2B6CB0]">
                    <Lightbulb className="h-3.5 w-3.5" />
                  </div>
                  <div className="min-w-0 flex-1">
                    <p className="font-semibold text-[#1C2621]">Quick Tip</p>
                    <p className="text-[#5A6860]">
                      Share directly from YouTube → OpenScribe, or swipe left/right in the player
                      for brightness &amp; volume.
                    </p>
                  </div>
                  <button
                    type="button"
                    onClick={() => setShowQuickTip(false)}
                    aria-label="Dismiss tip"
                    className="text-[#5A6860] hover:text-[#1C2621]"
                  >
                    <X className="h-3.5 w-3.5" />
                  </button>
                </div>
              )}

              {/* URL Input */}
              <div className="rounded-2xl border border-[#E2DCCD] bg-white p-3 shadow-xs">
                <label
                  htmlFor="sim-url"
                  className="block text-xs font-semibold text-[#1C2621]"
                >
                  Video Link
                </label>
                <input
                  id="sim-url"
                  type="url"
                  value={rawUrl}
                  onChange={(e) => setRawUrl(e.target.value)}
                  className="mt-1.5 w-full rounded-xl border border-[#E2DCCD] bg-[#FAF7F0] px-2.5 py-2 text-xs text-[#1C2621] focus:border-[#2E7D5B] focus:outline-none"
                />
                <button
                  type="button"
                  onClick={() => setScreen('sheet')}
                  className="mt-2.5 flex w-full items-center justify-center gap-2 rounded-xl bg-[#2E7D5B] py-2.5 text-xs font-bold text-white transition hover:bg-[#25694c]"
                >
                  <Play className="h-3.5 w-3.5" />
                  Configure &amp; Start Translation
                </button>
              </div>

              {/* Language & Mode Config Sheet */}
              {screen === 'sheet' && (
                <div className="space-y-3 rounded-2xl border border-[#2E7D5B]/45 bg-white p-3 shadow-sm">
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-bold text-[#1C2621]">
                      Source Language (~99)
                    </span>
                    <button
                      type="button"
                      onClick={() => setShowLangSearch((s) => !s)}
                      className="inline-flex items-center gap-1 text-[11px] font-semibold text-[#2B6CB0]"
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
                        className={`rounded-lg px-2.5 py-1 text-[11px] font-semibold transition ${
                          selectedLang === lang.code
                            ? 'bg-[#DCEFE5] text-[#2E7D5B] ring-1 ring-[#2E7D5B]/50'
                            : 'bg-[#F1ECE1] text-[#5A6860]'
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
                        className="w-full rounded-lg border border-[#E2DCCD] bg-[#FAF7F0] px-2.5 py-1.5 text-xs text-[#1C2621]"
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
                            className="flex w-full items-center justify-between rounded px-2 py-1 text-left text-[11px] text-[#1C2621] hover:bg-[#F1ECE1]"
                          >
                            <span>{l.name}</span>
                            <span className="font-mono font-semibold text-[#2B6CB0]">
                              {l.code}
                            </span>
                          </button>
                        ))}
                      </div>
                    </div>
                  )}

                  <div className="grid grid-cols-2 gap-2 pt-1">
                    <button
                      type="button"
                      onClick={() => {
                        setSelectedMode('DOWNLOAD_AND_TRANSLATE');
                        setScreen('home');
                        setIsRunning(true);
                      }}
                      className="inline-flex items-center justify-center gap-1.5 rounded-xl border border-[#5B9BD5] bg-[#DDF0FB] py-2 text-xs font-bold text-[#2B6CB0] transition hover:bg-[#cde6f8]"
                    >
                      <Download className="h-3.5 w-3.5" />
                      Download
                    </button>
                    <button
                      type="button"
                      onClick={() => {
                        setSelectedMode('WATCH_NOW');
                        setScreen('home');
                        setIsRunning(true);
                      }}
                      className="inline-flex items-center justify-center gap-1.5 rounded-xl bg-[#2E7D5B] py-2 text-xs font-bold text-white transition hover:bg-[#25694c]"
                    >
                      <Play className="h-3.5 w-3.5" />
                      Watch Now
                    </button>
                  </div>
                  <p className="text-[10px] leading-relaxed text-[#5A6860]">
                    Watch Now streams smoothly &amp; auto-opens over other apps once initial subs
                    arrive. Download saves full MP4 quietly in background.
                  </p>
                </div>
              )}

              {/* Active Progress / Warm Reuse */}
              {(isRunning || cueCount > 0 || isGpuWarm) && (
                <div className="space-y-2.5 rounded-2xl border border-[#E2DCCD] bg-white p-3 shadow-xs">
                  <div className="flex items-center gap-2">
                    {isRunning ? (
                      <Loader2 className="h-4 w-4 animate-spin text-[#5B9BD5]" />
                    ) : (
                      <CheckCircle2 className="h-4 w-4 text-[#16A34A]" />
                    )}
                    <p className="text-xs font-semibold text-[#1C2621]">{statusLine}</p>
                  </div>
                  <p className="text-[11px] text-[#5A6860]">
                    {cueCount} cues • {completedChunks.length} chunk(s) • lang={selectedLang}
                  </p>
                  {cueCount >= 4 && (
                    <button
                      type="button"
                      onClick={() => setScreen('player')}
                      className="flex w-full items-center justify-center gap-1.5 rounded-xl bg-[#2E7D5B] py-2 text-xs font-bold text-white"
                    >
                      <Play className="h-3.5 w-3.5" />
                      Open Built-in libmpv Player
                    </button>
                  )}
                </div>
              )}

              {isGpuWarm && !isRunning && (
                <div className="space-y-2 rounded-2xl border border-[#5B9BD5]/40 bg-white p-3 shadow-xs">
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-semibold text-[#2B6CB0]">Warm T4 Reuse</span>
                    <button
                      type="button"
                      onClick={() => {
                        setIsGpuWarm(false);
                        setStatusLine('Colab T4 GPU stopped');
                      }}
                      className="inline-flex items-center gap-1 text-[11px] font-semibold text-[#DC2626]"
                    >
                      <Square className="h-3 w-3" />
                      Stop T4 GPU
                    </button>
                  </div>
                  <div className="grid grid-cols-2 gap-2">
                    <button
                      type="button"
                      onClick={() => setIsRunning(true)}
                      className="inline-flex items-center justify-center gap-1 rounded-xl border border-[#2E7D5B]/50 bg-[#DCEFE5]/50 py-1.5 text-[11px] font-semibold text-[#2E7D5B]"
                    >
                      <RotateCcw className="h-3 w-3" />
                      Run Previous
                    </button>
                    <button
                      type="button"
                      onClick={() => {
                        setHasPreviousCache(false);
                        setRawUrl('');
                        setCueCount(0);
                      }}
                      className="inline-flex items-center justify-center gap-1 rounded-xl border border-[#5B9BD5]/50 bg-[#DDF0FB]/60 py-1.5 text-[11px] font-semibold text-[#2B6CB0]"
                    >
                      <ExternalLink className="h-3 w-3" />
                      New Link
                    </button>
                  </div>
                  {hasPreviousCache && (
                    <p className="text-[10px] font-medium text-[#16A34A]">
                      Smart Cache: Subtitles + MP4 cached
                    </p>
                  )}
                </div>
              )}

              {screen === 'home' && !isRunning && cueCount === 0 && !isGpuWarm && (
                <div className="space-y-2 rounded-2xl border border-[#E2DCCD] bg-[#F1ECE1]/70 p-3 text-[11px] text-[#5A6860]">
                  <p className="font-semibold text-[#1C2621]">Single-APK Ready</p>
                  <p>
                    Share any video from YouTube or tap Configure above. Watch Now live-streams
                    smoothly and auto-opens once initial subtitles arrive.
                  </p>
                  <div className="flex flex-wrap gap-1.5 pt-1 text-[10px] font-semibold">
                    <span className="rounded-md bg-[#DDF0FB] px-2 py-0.5 text-[#2B6CB0]">
                      No Termux
                    </span>
                    <span className="rounded-md bg-[#DCEFE5] px-2 py-0.5 text-[#2E7D5B]">
                      Embedded libmpv
                    </span>
                    <span className="rounded-md bg-white px-2 py-0.5 text-[#1C2621] ring-1 ring-[#E2DCCD]">
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
