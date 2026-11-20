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
  Settings,
  Share2,
  SlidersHorizontal,
  Square,
  Subtitles,
  X,
} from 'lucide-react';
import {
  ChunkRange,
  DEFAULT_SHARE_SUBTITLE_OPTIONS,
  QUICK_LANG_CHIPS,
  QueueSimulationState,
  ShareSubtitleOptions,
  WHISPER_MODELS,
  WORKFLOW_MODES,
  WhisperModelId,
  WorkflowModeId,
  completeActiveAndPromoteQueue,
  createInitialQueueState,
  extractAndUnwrapUrl,
  formatMmSs,
  formatSubtitleDelay,
  hideReadyOffer,
  isTimestampTranslated,
  normalizeWhisperModel,
  onActiveJobMediaReady,
  persistQuickTipsDismissed,
  readQuickTipsDismissed,
  removeQueuedJobById,
  searchWhisperLanguages,
  submitJobToQueueState,
  switchToReadyOffer,
} from '../lib/subcolabLogic';

type SimScreen = 'home' | 'sheet' | 'player' | 'settings';

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

const SAMPLE_PLATFORMS: { label: string; url: string }[] = [
  { label: 'Dailymotion', url: 'https://www.dailymotion.com/video/x8m7q2p' },
  { label: 'Bilibili', url: 'https://www.bilibili.com/video/BV1GJ411x7h7' },
  { label: 'Vimeo', url: 'https://vimeo.com/76979871' },
  { label: 'TikTok', url: 'https://www.tiktok.com/@scout2015/video/6718335390845095173' },
  { label: 'Twitch VOD', url: 'https://www.twitch.tv/videos/1984729104' },
  { label: 'NicoNico', url: 'https://www.nicovideo.jp/watch/sm9' },
  { label: 'YouTube (Fallback)', url: 'https://www.google.com/goto?url=https%3A%2F%2Fwww.youtube.com%2Fshorts%2FdQw4w9WgXcQ' },
];

export function PhonePreview({ isDark = false }: { isDark?: boolean }) {
  const [rawUrl, setRawUrl] = useState(
    'https://www.dailymotion.com/video/x8m7q2p',
  );
  const [selectedLang, setSelectedLang] = useState('auto');
  const [langQuery, setLangQuery] = useState('');
  const [showLangSearch, setShowLangSearch] = useState(false);
  const [defaultModelId, setDefaultModelId] = useState<WhisperModelId>('large-v3');
  const [defaultCleanAudio, setDefaultCleanAudio] = useState(false);
  const [shareOptions, setShareOptions] = useState<ShareSubtitleOptions>({
    ...DEFAULT_SHARE_SUBTITLE_OPTIONS,
  });

  const [selectedMode, setSelectedMode] = useState<WorkflowModeId>('WATCH_NOW');
  const [screen, setScreen] = useState<SimScreen>('home');
  const [showAbout, setShowAbout] = useState(false);
  const [showQuickTip, setShowQuickTip] = useState<boolean>(() =>
    typeof window !== 'undefined' ? !readQuickTipsDismissed(window.localStorage) : true,
  );

  const [queueState, setQueueState] = useState<QueueSimulationState>(() => createInitialQueueState());
  const [isGpuWarm, setIsGpuWarm] = useState(false);
  const [statusLine, setStatusLine] = useState('Ready');
  const [completedChunks, setCompletedChunks] = useState<ChunkRange[]>([]);
  const [hasPreviousCache, setHasPreviousCache] = useState(false);
  const [queueToast, setQueueToast] = useState<string | null>(null);
  const [jobSeq, setJobSeq] = useState(1);

  const [isPaused, setIsPaused] = useState(false);
  const [playTimeSec, setPlayTimeSec] = useState(0);
  const [subsVisible, setSubsVisible] = useState(true);
  const [showSubControls, setShowSubControls] = useState(false);
  const [subDelaySec, setSubDelaySec] = useState(0);
  const [subFontSizePx, setSubFontSizePx] = useState(14);
  const [subBoxEnabled, setSubBoxEnabled] = useState(true);
  const [subBorderPx, setSubBorderPx] = useState(2);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [bgSaveState, setBgSaveState] = useState<'idle' | 'saving' | 'saved'>('idle');
  const [isSeekingAhead, setIsSeekingAhead] = useState(false);
  const [seekTargetSec, setSeekTargetSec] = useState<number | null>(null);

  const cleanUrl = extractAndUnwrapUrl(rawUrl);
  const filteredLanguages = searchWhisperLanguages(langQuery);
  const activeJob = queueState.activeJob;
  const isRunning = activeJob?.status === 'running';
  const cueCount = queueState.playingVideo?.cueCount ?? activeJob?.cueCount ?? 0;

  const openShareSheet = (prefillUrl?: string) => {
    if (prefillUrl) setRawUrl(prefillUrl);
    setShareOptions({
      sourceLang: selectedLang || 'auto',
      modelId: normalizeWhisperModel(defaultModelId),
      cleanAudio: defaultCleanAudio,
      vocalBoost: false,
      highPrecisionBeam: false,
      includeOriginal: false,
    });
    setScreen('sheet');
  };

  const handleStartOrQueue = (mode: WorkflowModeId, opts: ShareSubtitleOptions, customUrl?: string) => {
    const targetUrl = customUrl ?? rawUrl;
    const nextId = `job_${jobSeq}`;
    setJobSeq((n) => n + 1);
    setSelectedMode(mode);
    setSelectedLang(opts.sourceLang || 'auto');

    const result = submitJobToQueueState(queueState, {
      id: nextId,
      url: targetUrl,
      mode,
      options: opts,
    });
    setQueueState(result.state);
    setScreen('home');
    if (result.queuePosition > 0) {
      setQueueToast(`Added as Queued No. ${result.queuePosition} (${mode === 'WATCH_NOW' ? 'Watch Now' : 'Download'})`);
      window.setTimeout(() => setQueueToast(null), 2600);
    }
  };

  useEffect(() => {
    if (!activeJob || activeJob.status !== 'running') return;
    let cancelled = false;
    const timers: number[] = [];

    const run = (delayMs: number, fn: () => void) => {
      const id = window.setTimeout(() => {
        if (!cancelled) fn();
      }, delayMs);
      timers.push(id);
    };

    setCompletedChunks([]);
    setIsGpuWarm(true);
    const modelLabel = activeJob.options.modelId;
    setStatusLine(
      `${activeJob.isFromQueue ? '[Queued] ' : ''}Running ${activeJob.title} (${modelLabel})...`,
    );

    run(550, () =>
      setStatusLine(
        activeJob.mode === 'WATCH_NOW'
          ? `Streaming ${activeJob.title} • slicing adaptive audio chunks...`
          : `Downloading ${activeJob.title} <=720p MP4 & translating...`,
      ),
    );
    run(1050, () => {
      setStatusLine(`Translating ${activeJob.title} -> English (${modelLabel})...`);
      setQueueState((prev) =>
        prev.activeJob?.id === activeJob.id
          ? { ...prev, activeJob: { ...prev.activeJob, cueCount: 1 } }
          : prev,
      );
    });
    run(1400, () =>
      setQueueState((prev) =>
        prev.activeJob?.id === activeJob.id
          ? { ...prev, activeJob: { ...prev.activeJob, cueCount: 2 } }
          : prev,
      ),
    );
    run(1750, () =>
      setQueueState((prev) =>
        prev.activeJob?.id === activeJob.id
          ? { ...prev, activeJob: { ...prev.activeJob, cueCount: 3 } }
          : prev,
      ),
    );
    run(2100, () => {
      setCompletedChunks([{ startSec: 0, endSec: 45 }]);
      setStatusLine(
        activeJob.mode === 'WATCH_NOW'
          ? activeJob.isFromQueue
            ? `${activeJob.title} ready — tap Watch Now or Hide in notification`
            : `Initial subs ready for ${activeJob.title}`
          : `Background Download: ${activeJob.title} 4 cues ready, finishing MP4...`,
      );
      setQueueState((prev) =>
        prev.activeJob?.id === activeJob.id ? onActiveJobMediaReady(prev, 4) : prev,
      );
    });
    run(3200, () => {
      setHasPreviousCache(true);
      setStatusLine(
        activeJob.mode === 'WATCH_NOW'
          ? `${activeJob.title} complete (5 cues saved)`
          : `Saved ${activeJob.title} MP4 + SRT/ASS/VTT`,
      );
      setQueueState((prev) =>
        prev.activeJob?.id === activeJob.id ? completeActiveAndPromoteQueue(prev, 5) : prev,
      );
    });

    return () => {
      cancelled = true;
      timers.forEach((t) => window.clearTimeout(t));
    };
  }, [activeJob?.id]);

  useEffect(() => {
    if (!queueState.shouldAutoOpenPlayer || !queueState.playingVideo) return;
    setStatusLine(`Playing ${queueState.playingVideo.title}`);
    setPlayTimeSec(2);
    setIsPaused(false);
    setScreen('player');
    setQueueState((prev) => (prev.shouldAutoOpenPlayer ? { ...prev, shouldAutoOpenPlayer: false } : prev));
  }, [queueState.shouldAutoOpenPlayer, queueState.playingVideo?.id]);

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

  const effectiveTimeSec = Math.max(0, playTimeSec - subDelaySec);
  const activeCue =
    [...SAMPLE_CUES]
      .slice(0, Math.max(1, cueCount))
      .reverse()
      .find((c) => effectiveTimeSec >= c.timeSec) ?? SAMPLE_CUES[0];

  return (
    <div className="grid gap-8 lg:grid-cols-[minmax(0,1fr)_380px] lg:items-start">
      {/* Left: Interactive Explanation & Controls */}
      <div className="space-y-5">
        <div
          className={`rounded-2xl border p-6 shadow-sm ${
            isDark ? 'border-[#283449] bg-[#131A26]' : 'border-[#E2DCCD] bg-white'
          }`}
        >
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <h2
                className={`text-lg font-semibold ${
                  isDark ? 'text-[#F8FAFC]' : 'text-[#1C2621]'
                }`}
              >
                Interactive Android Flow Preview
              </h2>
              <p className={`mt-1 text-sm ${isDark ? 'text-[#94A3B8]' : 'text-[#5A6860]'}`}>
                Test SUBCOLAB v1.0 in Light or Dark mode — while keeping the embedded libmpv player
                cinema-black with high-contrast yellow subtitles.
              </p>
            </div>
            <button
              type="button"
              onClick={() => openShareSheet('Check this clip! https://www.dailymotion.com/video/x8m7q2p')}
              className="inline-flex items-center gap-2 rounded-xl border border-[#5B9BD5]/50 bg-[#DDF0FB] px-3.5 py-2 text-xs font-semibold text-[#2B6CB0] transition hover:bg-[#cde6f8]"
            >
              <Share2 className="h-3.5 w-3.5" />
              Simulate App Share Intent
            </button>
          </div>

          <div className="mt-4 flex flex-wrap items-center gap-1.5">
            <span className={`mr-1 text-xs font-semibold ${isDark ? 'text-[#94A3B8]' : 'text-[#5A6860]'}`}>
              Try Supported Sites:
            </span>
            {SAMPLE_PLATFORMS.map((site) => (
              <button
                key={site.label}
                type="button"
                onClick={() => setRawUrl(site.url)}
                className={`rounded-lg border px-2.5 py-1 text-[11px] font-semibold transition ${
                  rawUrl.includes(site.url.split('/')[2] ?? '')
                    ? isDark
                      ? 'border-[#2563EB] bg-[#172554] text-[#60A5FA]'
                      : 'border-[#2E7D5B] bg-[#DCEFE5] text-[#2E7D5B]'
                    : isDark
                      ? 'border-[#283449] bg-[#0B0F17] text-[#94A3B8] hover:text-white'
                      : 'border-[#E2DCCD] bg-[#FAF7F0] text-[#5A6860] hover:text-[#1C2621]'
                }`}
              >
                {site.label}
              </button>
            ))}
          </div>

          <div className="mt-5 grid gap-3 sm:grid-cols-3">
            <div
              className={`rounded-xl border p-3.5 ${
                isDark ? 'border-[#283449] bg-[#0B0F17]' : 'border-[#E2DCCD] bg-[#FAF7F0]'
              }`}
            >
              <p
                className={`text-xs font-semibold uppercase tracking-wider ${
                  isDark ? 'text-[#60A5FA]' : 'text-[#2E7D5B]'
                }`}
              >
                1. Embedded libmpv
              </p>
              <p className={`mt-1 text-xs ${isDark ? 'text-[#94A3B8]' : 'text-[#5A6860]'}`}>
                <code className={`break-all font-medium ${isDark ? 'text-[#60A5FA]' : 'text-[#2B6CB0]'}`}>
                  dev.jdtech.mpv:libmpv:0.5.1
                </code>{' '}
                streaming smoothly on cinema-black with live{' '}
                <code className={`font-medium ${isDark ? 'text-[#34D399]' : 'text-[#2E7D5B]'}`}>
                  sub-reload
                </code>.
              </p>
            </div>
            <div
              className={`rounded-xl border p-3.5 ${
                isDark ? 'border-[#283449] bg-[#0B0F17]' : 'border-[#E2DCCD] bg-[#FAF7F0]'
              }`}
            >
              <p
                className={`text-xs font-semibold uppercase tracking-wider ${
                  isDark ? 'text-[#38BDF8]' : 'text-[#2B6CB0]'
                }`}
              >
                2. Embedded yt-dlp
              </p>
              <p className={`mt-1 text-xs ${isDark ? 'text-[#94A3B8]' : 'text-[#5A6860]'}`}>
                <code className={`break-all font-medium ${isDark ? 'text-[#60A5FA]' : 'text-[#2B6CB0]'}`}>
                  youtubedl-android:0.18.1
                </code>{' '}
                with loopback live proxy + adaptive audio-only chunks.
              </p>
            </div>
            <div
              className={`rounded-xl border p-3.5 ${
                isDark ? 'border-[#283449] bg-[#0B0F17]' : 'border-[#E2DCCD] bg-[#FAF7F0]'
              }`}
            >
              <p
                className={`text-xs font-semibold uppercase tracking-wider ${
                  isDark ? 'text-[#34D399]' : 'text-[#16A34A]'
                }`}
              >
                3. Headless Colab T4
              </p>
              <p className={`mt-1 text-xs ${isDark ? 'text-[#94A3B8]' : 'text-[#5A6860]'}`}>
                <code className={`font-medium ${isDark ? 'text-[#60A5FA]' : 'text-[#2B6CB0]'}`}>
                  google-colab-cli
                </code>{' '}
                OAuth2 + Whisper{' '}
                <code className={`font-medium ${isDark ? 'text-white' : 'text-[#1C2621]'}`}>
                  large-v3
                </code>{' '}
                WebSocket streaming.
              </p>
            </div>
          </div>
        </div>

        {/* Live Foreground Service & Protocol State */}
        <div
          className={`rounded-2xl border p-5 shadow-sm ${
            isDark ? 'border-[#283449] bg-[#131A26]' : 'border-[#E2DCCD] bg-white'
          }`}
        >
          <div className="flex items-center justify-between">
            <h3
              className={`text-sm font-semibold ${
                isDark ? 'text-[#F8FAFC]' : 'text-[#1C2621]'
              }`}
            >
              Android Foreground Service State
            </h3>
            <span
              className={`rounded-md px-2.5 py-0.5 text-xs font-semibold ${
                isGpuWarm
                  ? isDark
                    ? 'bg-[#0C2A3E] text-[#60A5FA]'
                    : 'bg-[#DDF0FB] text-[#2B6CB0]'
                  : isDark
                    ? 'bg-[#1B2434] text-[#94A3B8]'
                    : 'bg-[#F1ECE1] text-[#5A6860]'
              }`}
            >
              {isGpuWarm ? 'T4 GPU Warm (WakeLock Active)' : 'Idle'}
            </span>
          </div>
          <dl className="mt-4 grid gap-3 text-xs sm:grid-cols-2">
            <div
              className={`rounded-xl border p-3 ${
                isDark ? 'border-[#283449] bg-[#0B0F17]' : 'border-[#E2DCCD] bg-[#FAF7F0]'
              }`}
            >
              <dt className={isDark ? 'text-[#94A3B8]' : 'text-[#5A6860]'}>Unwrapped Target URL</dt>
              <dd
                className={`mt-1 break-all font-mono ${
                  isDark ? 'text-[#60A5FA]' : 'text-[#2B6CB0]'
                }`}
              >
                {cleanUrl || 'None'}
              </dd>
            </div>
            <div
              className={`rounded-xl border p-3 ${
                isDark ? 'border-[#283449] bg-[#0B0F17]' : 'border-[#E2DCCD] bg-[#FAF7F0]'
              }`}
            >
              <dt className={isDark ? 'text-[#94A3B8]' : 'text-[#5A6860]'}>
                Live Notification Banner
              </dt>
              <dd
                className={`mt-1 leading-relaxed font-semibold ${
                  isDark ? 'text-[#34D399]' : 'text-[#2E7D5B]'
                }`}
              >
                {statusLine}
              </dd>
            </div>
          </dl>
        </div>

        {/* About SUBCOLAB Card */}
        <div
          className={`rounded-2xl border p-5 shadow-sm ${
            isDark ? 'border-[#283449] bg-[#131A26]' : 'border-[#E2DCCD] bg-white'
          }`}
        >
          <div className="flex items-center justify-between gap-2">
            <div>
              <h3
                className={`text-sm font-semibold ${
                  isDark ? 'text-[#F8FAFC]' : 'text-[#1C2621]'
                }`}
              >
                About SUBCOLAB
              </h3>
              <p
                className={`text-xs font-medium ${
                  isDark ? 'text-[#60A5FA]' : 'text-[#2E7D5B]'
                }`}
              >
                SUBCOLAB v1.0
              </p>
            </div>
            <span
              className={`rounded-lg px-2.5 py-1 text-[11px] font-semibold ${
                isDark ? 'bg-[#172554] text-[#60A5FA]' : 'bg-[#DCEFE5] text-[#2E7D5B]'
              }`}
            >
              {isDark ? 'Dark Mode' : 'Light Mode'}
            </span>
          </div>
          <p className={`mt-2 text-xs leading-relaxed ${isDark ? 'text-[#94A3B8]' : 'text-[#5A6860]'}`}>
            Live AI video translation powered by Whisper large-v3 on Google Colab T4 &amp; libmpv.
            Based on &amp; inspired by OpenScribe by Faisal Saddique (MIT License).
          </p>
          <div
            className={`mt-3 flex flex-wrap items-center justify-between gap-2 border-t pt-3 text-xs ${
              isDark ? 'border-[#283449]' : 'border-[#E2DCCD]'
            }`}
          >
            <span className={isDark ? 'text-[#94A3B8]' : 'text-[#5A6860]'}>
              Open Source Repository
            </span>
            <a
              href="https://github.com/22-db-22/SUBCOLAB"
              target="_blank"
              rel="noreferrer"
              className={`inline-flex items-center gap-1 font-semibold hover:underline ${
                isDark ? 'text-[#60A5FA]' : 'text-[#2B6CB0]'
              }`}
            >
              github.com/22-db-22/SUBCOLAB
              <ExternalLink className="h-3.5 w-3.5" />
            </a>
          </div>
        </div>
      </div>

      {/* Right: Realistic Android Phone Frame */}
      <div
        className={`mx-auto w-full max-w-[380px] rounded-[2.2rem] border-[6px] border-[#26322C] p-3 shadow-xl transition-colors ${
          screen === 'player' ? 'bg-black' : isDark ? 'bg-[#0B0F17]' : 'bg-[#FAF7F0]'
        }`}
      >
        {/* Status Bar (Light icons on Cinema Player & Dark Mode, Dark icons on Light Home) */}
        <div
          className={`flex items-center justify-between px-3 pb-2 pt-1 text-[11px] font-semibold ${
            screen === 'player' || isDark ? 'text-slate-200' : 'text-[#1C2621]'
          }`}
        >
          <span>SUBCOLAB</span>
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
              : isDark
                ? 'border-[#283449] bg-[#0B0F17]'
                : 'border-[#E2DCCD] bg-[#FAF7F0]'
          }`}
        >
          {/* Android Top Notification Shade for Queued Videos ("Watch Now" / "Hide") */}
          {queueState.readyOffers.length > 0 && (
            <div className="border-b border-[#FFFF00]/40 bg-[#131A26] px-3 py-2 text-white">
              <div className="flex items-center justify-between gap-2">
                <div className="min-w-0 flex-1">
                  <p className="text-[10px] font-bold uppercase tracking-wider text-[#FFFF00]">
                    Android Notification • Ready to Watch
                  </p>
                  <p className="truncate text-[11px] font-semibold text-white">
                    {queueState.readyOffers[0].title} ({queueState.readyOffers[0].cueCount} cues)
                  </p>
                </div>
                <div className="flex shrink-0 items-center gap-1.5">
                  <button
                    type="button"
                    onClick={() =>
                      setQueueState((prev) => hideReadyOffer(prev, queueState.readyOffers[0].queueId))
                    }
                    className="rounded-lg border border-slate-600 px-2 py-1 text-[10px] font-semibold text-slate-200 hover:bg-slate-800"
                  >
                    Hide
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      const offerId = queueState.readyOffers[0].queueId;
                      setQueueState((prev) => switchToReadyOffer(prev, offerId));
                      setPlayTimeSec(2);
                      setIsPaused(false);
                      setScreen('player');
                    }}
                    className="rounded-lg bg-[#2E7D5B] px-2.5 py-1 text-[10px] font-bold text-white hover:bg-[#25694c]"
                  >
                    Watch Now
                  </button>
                </div>
              </div>
            </div>
          )}
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
                  {queueState.playingVideo ? `${queueState.playingVideo.title} • ` : ''}
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
                    onClick={() => setShowSubControls((s) => !s)}
                    aria-label="Subtitle Timing & Style"
                    className={`rounded-lg px-2 py-1 text-xs font-medium ${
                      showSubControls ? 'bg-[#7CC4FA] text-black' : 'bg-slate-800 text-slate-300'
                    }`}
                  >
                    <SlidersHorizontal className="h-3.5 w-3.5" />
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
                {showSubControls && (
                  <div className="absolute right-1 top-2 z-20 w-60 space-y-2 rounded-xl border border-[#FFFF00]/40 bg-[#131A26]/95 p-2.5 text-[11px] text-white shadow-lg">
                    <div className="flex items-center justify-between">
                      <span className="font-bold text-[#FFFF00]">Subtitle Sync &amp; Style</span>
                      <button
                        type="button"
                        onClick={() => setShowSubControls(false)}
                        aria-label="Close subtitle style drawer"
                        className="text-slate-400 hover:text-white"
                      >
                        <X className="h-3.5 w-3.5" />
                      </button>
                    </div>
                    <div>
                      <p className="mb-1 text-[10px] text-slate-300">
                        Delay: <strong className="text-[#FFFF00]">{formatSubtitleDelay(subDelaySec)}</strong>
                      </p>
                      <div className="grid grid-cols-3 gap-1">
                        <button
                          type="button"
                          onClick={() => setSubDelaySec((d) => Math.max(-15, Number((d - 0.5).toFixed(1))))}
                          className="rounded border border-slate-700 bg-slate-900 py-1 font-semibold hover:bg-slate-800"
                        >
                          -0.5s
                        </button>
                        <button
                          type="button"
                          onClick={() => setSubDelaySec(0)}
                          className="rounded border border-[#7CC4FA]/40 bg-slate-900 py-1 font-semibold text-[#7CC4FA]"
                        >
                          Reset
                        </button>
                        <button
                          type="button"
                          onClick={() => setSubDelaySec((d) => Math.min(15, Number((d + 0.5).toFixed(1))))}
                          className="rounded border border-slate-700 bg-slate-900 py-1 font-semibold hover:bg-slate-800"
                        >
                          +0.5s
                        </button>
                      </div>
                    </div>
                    <div>
                      <div className="flex justify-between text-[10px]">
                        <span>Size</span>
                        <span className="text-[#FFFF00]">{subFontSizePx}px</span>
                      </div>
                      <input
                        type="range"
                        aria-label="Subtitle font size"
                        min={11}
                        max={22}
                        value={subFontSizePx}
                        onChange={(e) => setSubFontSizePx(Number(e.target.value))}
                        className="w-full accent-[#FFFF00]"
                      />
                    </div>
                    <div className="grid grid-cols-2 gap-1">
                      <button
                        type="button"
                        onClick={() => setSubBoxEnabled(false)}
                        className={`rounded py-1 text-[10px] font-semibold ${
                          !subBoxEnabled ? 'bg-[#FFFF00] text-black' : 'bg-slate-800 text-slate-300'
                        }`}
                      >
                        Outline ({subBorderPx}px)
                      </button>
                      <button
                        type="button"
                        onClick={() => setSubBoxEnabled(true)}
                        className={`rounded py-1 text-[10px] font-semibold ${
                          subBoxEnabled ? 'bg-[#FFFF00] text-black' : 'bg-slate-800 text-slate-300'
                        }`}
                      >
                        Dark Box
                      </button>
                    </div>
                    {!subBoxEnabled && (
                      <input
                        type="range"
                        aria-label="Subtitle outline thickness"
                        min={0}
                        max={4}
                        value={subBorderPx}
                        onChange={(e) => setSubBorderPx(Number(e.target.value))}
                        className="w-full accent-[#7CC4FA]"
                      />
                    )}
                  </div>
                )}

                {isSeekingAhead ? (
                  <div className="flex items-center gap-3 rounded-xl border border-[#FFFF00]/40 bg-[#0B0F17]/95 px-4 py-3">
                    <Loader2 className="h-5 w-5 animate-spin text-[#FFFF00]" />
                    <div>
                      <p className="text-xs font-semibold text-[#FFFF00]">
                        Translating from {formatMmSs(seekTargetSec ?? playTimeSec)}...
                      </p>
                      <p className="text-[11px] text-slate-400">
                        Uploaded /content/subcolab_seek.txt
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
                    <span
                      style={{
                        fontSize: `${subFontSizePx}px`,
                        textShadow: !subBoxEnabled && subBorderPx > 0
                          ? `0 0 ${subBorderPx * 2}px #000, 1px 1px 0 #000`
                          : undefined,
                      }}
                      className={`inline-block rounded px-3 py-1.5 font-bold text-[#FFFF00] ${
                        subBoxEnabled ? 'bg-black/75 shadow' : 'bg-transparent'
                      }`}
                    >
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
          ) : screen === 'settings' ? (
            <div className="space-y-3 p-3.5">
              <div className="flex items-center justify-between">
                <button
                  type="button"
                  onClick={() => setScreen('home')}
                  className={`rounded-lg px-2 py-1 text-xs font-semibold ${
                    isDark ? 'bg-[#131A26] text-white' : 'bg-[#F1ECE1] text-[#1C2621]'
                  }`}
                >
                  Back
                </button>
                <span className={`text-sm font-bold ${isDark ? 'text-white' : 'text-[#1C2621]'}`}>
                  Settings
                </span>
                <span className="w-10" />
              </div>

              <div
                className={`space-y-3 rounded-2xl border p-3.5 text-xs ${
                  isDark
                    ? 'border-[#283449] bg-[#131A26] text-white'
                    : 'border-[#E2DCCD] bg-white text-[#1C2621]'
                }`}
              >
                <p className="font-bold">Whisper &amp; Subtitle Engine</p>
                <div className="flex items-center justify-between gap-2">
                  <div>
                    <p className="font-medium">Clean Noisy Audio</p>
                    <p className={`text-[11px] ${isDark ? 'text-[#94A3B8]' : 'text-[#5A6860]'}`}>
                      Default for shared &amp; home jobs
                    </p>
                  </div>
                  <input
                    type="checkbox"
                    aria-label="Default clean audio"
                    checked={defaultCleanAudio}
                    onChange={(e) => setDefaultCleanAudio(e.target.checked)}
                    className="h-4 w-4 accent-[#2E7D5B]"
                  />
                </div>
                <div className="space-y-1.5">
                  <p className="font-medium">faster-whisper Model (T4 GPU)</p>
                  <div className="grid grid-cols-2 gap-2">
                    {WHISPER_MODELS.map((m) => (
                      <button
                        key={m.id}
                        type="button"
                        onClick={() => setDefaultModelId(m.id)}
                        className={`rounded-xl px-2.5 py-2 text-[11px] font-semibold transition ${
                          defaultModelId === m.id
                            ? isDark
                              ? 'bg-[#172554] text-[#60A5FA] ring-1 ring-[#2563EB]'
                              : 'bg-[#DCEFE5] text-[#2E7D5B] ring-1 ring-[#2E7D5B]'
                            : isDark
                              ? 'bg-[#0B0F17] text-[#94A3B8]'
                              : 'bg-[#F1ECE1] text-[#5A6860]'
                        }`}
                      >
                        {m.label}
                      </button>
                    ))}
                  </div>
                </div>
              </div>
            </div>
          ) : (
            <div className="space-y-3 p-3.5">
              {/* Header */}
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2.5">
                  <div className="flex h-8 w-8 items-center justify-center rounded-xl bg-[#2563EB] text-white shadow-sm">
                    <Subtitles className="h-4 w-4" />
                  </div>
                  <div>
                    <p
                      className={`text-sm font-bold ${
                        isDark ? 'text-white' : 'text-[#1C2621]'
                      }`}
                    >
                      SUBCOLAB
                    </p>
                    <p
                      className={`text-[11px] font-medium ${
                        isGpuWarm
                          ? isDark
                            ? 'text-[#60A5FA]'
                            : 'text-[#2B6CB0]'
                          : isDark
                            ? 'text-[#94A3B8]'
                            : 'text-[#5A6860]'
                      }`}
                    >
                      {isGpuWarm ? 'T4 GPU Warm' : 'Live AI Video Translator'}
                    </p>
                  </div>
                </div>
                <div className="flex items-center gap-1">
                  <button
                    type="button"
                    onClick={() => setShowAbout((a) => !a)}
                    aria-label="About SUBCOLAB"
                    className="rounded-lg p-1.5 text-[#5A6860] transition hover:bg-[#F1ECE1] hover:text-[#1C2621]"
                  >
                    <Info className="h-4 w-4" />
                  </button>
                  <button
                    type="button"
                    onClick={() => setScreen('settings')}
                    aria-label="Settings"
                    className="rounded-lg p-1.5 text-[#5A6860] transition hover:bg-[#F1ECE1] hover:text-[#1C2621]"
                  >
                    <Settings className="h-4 w-4" />
                  </button>
                </div>
              </div>

              {showAbout && (
                <div
                  className={`space-y-1.5 rounded-2xl border p-3 text-[11px] shadow-sm ${
                    isDark
                      ? 'border-[#283449] bg-[#131A26] text-[#F8FAFC]'
                      : 'border-[#E2DCCD] bg-white text-[#1C2621]'
                  }`}
                >
                  <div className="flex items-center justify-between">
                    <span className="font-bold">SUBCOLAB v1.0</span>
                    <button
                      type="button"
                      onClick={() => setShowAbout(false)}
                      aria-label="Close about"
                      className={isDark ? 'text-[#94A3B8]' : 'text-[#5A6860]'}
                    >
                      <X className="h-3.5 w-3.5" />
                    </button>
                  </div>
                  <a
                    href="https://github.com/22-db-22/SUBCOLAB"
                    target="_blank"
                    rel="noreferrer"
                    className={`inline-flex items-center gap-1 font-medium hover:underline ${
                      isDark ? 'text-[#60A5FA]' : 'text-[#2B6CB0]'
                    }`}
                  >
                    github.com/22-db-22/SUBCOLAB
                    <ExternalLink className="h-3 w-3" />
                  </a>
                </div>
              )}

              {/* Quick Tips Card (permanently dismissed once closed) */}
              {showQuickTip && screen === 'home' && (
                <div
                  className={`flex items-start gap-2.5 rounded-2xl border p-2.5 text-[11px] shadow-xs ${
                    isDark
                      ? 'border-[#283449] bg-[#131A26]'
                      : 'border-[#E2DCCD] bg-white'
                  }`}
                >
                  <div
                    className={`mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-lg ${
                      isDark ? 'bg-[#0C2A3E] text-[#60A5FA]' : 'bg-[#DDF0FB] text-[#2B6CB0]'
                    }`}
                  >
                    <Lightbulb className="h-3.5 w-3.5" />
                  </div>
                  <div className="min-w-0 flex-1">
                    <p className={`font-semibold ${isDark ? 'text-white' : 'text-[#1C2621]'}`}>
                      Quick Tip
                    </p>
                    <p className={isDark ? 'text-[#94A3B8]' : 'text-[#5A6860]'}>
                      Share from Dailymotion, Bilibili, Vimeo, TikTok, or YouTube → SUBCOLAB, or
                      swipe left/right in the player for brightness &amp; volume.
                    </p>
                  </div>
                  <button
                    type="button"
                    onClick={() => {
                      persistQuickTipsDismissed(
                        typeof window !== 'undefined' ? window.localStorage : undefined,
                      );
                      setShowQuickTip(false);
                    }}
                    aria-label="Dismiss tip"
                    className={isDark ? 'text-[#94A3B8] hover:text-white' : 'text-[#5A6860] hover:text-[#1C2621]'}
                  >
                    <X className="h-3.5 w-3.5" />
                  </button>
                </div>
              )}

              {queueToast && (
                <div className="rounded-xl border border-[#38BDF8]/50 bg-[#0C2A3E] px-3 py-2 text-[11px] font-semibold text-[#60A5FA]">
                  {queueToast}
                </div>
              )}

              {/* URL Input */}
              <div
                className={`rounded-2xl border p-3 shadow-xs ${
                  isDark ? 'border-[#283449] bg-[#131A26]' : 'border-[#E2DCCD] bg-white'
                }`}
              >
                <label
                  htmlFor="sim-url"
                  className={`block text-xs font-semibold ${
                    isDark ? 'text-white' : 'text-[#1C2621]'
                  }`}
                >
                  Video Link
                </label>
                <input
                  id="sim-url"
                  type="url"
                  value={rawUrl}
                  onChange={(e) => setRawUrl(e.target.value)}
                  className={`mt-1.5 w-full rounded-xl border px-2.5 py-2 text-xs focus:outline-none ${
                    isDark
                      ? 'border-[#283449] bg-[#0B0F17] text-white focus:border-[#2563EB]'
                      : 'border-[#E2DCCD] bg-[#FAF7F0] text-[#1C2621] focus:border-[#2E7D5B]'
                  }`}
                />
                <button
                  type="button"
                  onClick={() => openShareSheet()}
                  className={`mt-2.5 flex w-full items-center justify-center gap-2 rounded-xl py-2.5 text-xs font-bold text-white transition ${
                    isDark
                      ? 'bg-[#2563EB] hover:bg-[#1D4ED8]'
                      : 'bg-[#2E7D5B] hover:bg-[#25694c]'
                  }`}
                >
                  <Play className="h-3.5 w-3.5" />
                  {isRunning ? `Queue Next Link (No. ${queueState.queuedJobs.length + 1})` : 'Configure & Start Translation'}
                </button>
              </div>

              {/* Share Overlay Card: App Name -> Language -> Subtitle Settings -> Buttons (cleanly fits in viewport) */}
              {screen === 'sheet' && (
                <div
                  className={`space-y-2.5 rounded-2xl border p-3 shadow-sm ${
                    isDark
                      ? 'border-[#2563EB]/45 bg-[#131A26] text-white'
                      : 'border-[#2E7D5B]/45 bg-white text-[#1C2621]'
                  }`}
                >
                  <div className="flex items-center justify-between border-b border-slate-700/30 pb-1.5">
                    <div className="flex items-center gap-1.5">
                      <Subtitles className="h-4 w-4 text-[#EAB308]" />
                      <span className="text-sm font-bold">SUBCOLAB</span>
                    </div>
                    <button
                      type="button"
                      onClick={() => setScreen('home')}
                      className={`text-[11px] font-medium ${isDark ? 'text-[#94A3B8]' : 'text-[#5A6860]'}`}
                    >
                      Cancel
                    </button>
                  </div>

                  <div className="max-h-[290px] space-y-2.5 overflow-y-auto pr-0.5">
                    <div className="flex items-center justify-between">
                      <span className="text-xs font-bold">Language</span>
                      <button
                        type="button"
                        onClick={() => setShowLangSearch((s) => !s)}
                        className={`inline-flex items-center gap-1 text-[11px] font-semibold ${
                          isDark ? 'text-[#60A5FA]' : 'text-[#2B6CB0]'
                        }`}
                      >
                        <Search className="h-3 w-3" />
                        {showLangSearch ? 'Hide Search' : 'All ~99'}
                      </button>
                    </div>

                    <div className="flex flex-wrap gap-1.5">
                      {QUICK_LANG_CHIPS.map((lang) => (
                        <button
                          key={lang.code}
                          type="button"
                          onClick={() =>
                            setShareOptions((o) => ({ ...o, sourceLang: lang.code }))
                          }
                          className={`rounded-lg px-2.5 py-1 text-[11px] font-semibold transition ${
                            shareOptions.sourceLang === lang.code
                              ? isDark
                                ? 'bg-[#172554] text-[#60A5FA] ring-1 ring-[#2563EB]/60'
                                : 'bg-[#DCEFE5] text-[#2E7D5B] ring-1 ring-[#2E7D5B]/50'
                              : isDark
                                ? 'bg-[#0B0F17] text-[#94A3B8]'
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
                          className={`w-full rounded-lg border px-2.5 py-1.5 text-xs ${
                            isDark
                              ? 'border-[#283449] bg-[#0B0F17] text-white'
                              : 'border-[#E2DCCD] bg-[#FAF7F0] text-[#1C2621]'
                          }`}
                        />
                        <div className="max-h-20 space-y-1 overflow-y-auto">
                          {filteredLanguages.slice(0, 6).map((l) => (
                            <button
                              key={l.code}
                              type="button"
                              onClick={() => {
                                setShareOptions((o) => ({ ...o, sourceLang: l.code }));
                                setShowLangSearch(false);
                              }}
                              className={`flex w-full items-center justify-between rounded px-2 py-1 text-left text-[11px] ${
                                isDark ? 'text-white hover:bg-[#1B2434]' : 'text-[#1C2621] hover:bg-[#F1ECE1]'
                              }`}
                            >
                              <span>{l.name}</span>
                              <span className={`font-mono font-semibold ${isDark ? 'text-[#60A5FA]' : 'text-[#2B6CB0]'}`}>
                                {l.code}
                              </span>
                            </button>
                          ))}
                        </div>
                      </div>
                    )}

                    <div className="space-y-1.5 border-t border-slate-700/25 pt-2">
                      <p className="text-xs font-bold">Whisper Model</p>
                      <div className="grid grid-cols-2 gap-1.5">
                        {WHISPER_MODELS.map((m) => (
                          <button
                            key={m.id}
                            type="button"
                            onClick={() =>
                              setShareOptions((o) => ({ ...o, modelId: m.id }))
                            }
                            className={`rounded-lg px-2 py-1.5 text-[11px] font-semibold transition ${
                              shareOptions.modelId === m.id
                                ? isDark
                                  ? 'bg-[#0C2A3E] text-[#38BDF8] ring-1 ring-[#38BDF8]/60'
                                  : 'bg-[#DDF0FB] text-[#2B6CB0] ring-1 ring-[#2B6CB0]/40'
                                : isDark
                                  ? 'bg-[#0B0F17] text-[#94A3B8]'
                                  : 'bg-[#F1ECE1] text-[#5A6860]'
                            }`}
                          >
                            {m.label}
                          </button>
                        ))}
                      </div>
                    </div>

                    <div className="space-y-1.5 text-[11px]">
                      {[
                        { key: 'cleanAudio' as const, label: 'Clean Audio' },
                        { key: 'vocalBoost' as const, label: 'Vocal Boost / Cut Music' },
                        { key: 'highPrecisionBeam' as const, label: 'High-Precision Beam Search' },
                        { key: 'includeOriginal' as const, label: 'Include Original Subtitles' },
                      ].map((opt) => (
                        <label
                          key={opt.key}
                          className="flex cursor-pointer items-center justify-between gap-2 py-0.5"
                        >
                          <span className="font-medium">{opt.label}</span>
                          <input
                            type="checkbox"
                            checked={shareOptions[opt.key]}
                            onChange={(e) =>
                              setShareOptions((prev) => ({ ...prev, [opt.key]: e.target.checked }))
                            }
                            className="h-3.5 w-3.5 accent-[#2E7D5B]"
                          />
                        </label>
                      ))}
                    </div>
                  </div>

                  <div className="grid grid-cols-2 gap-2 border-t border-slate-700/25 pt-2">
                    <button
                      type="button"
                      onClick={() => handleStartOrQueue('DOWNLOAD_AND_TRANSLATE', shareOptions)}
                      className="inline-flex items-center justify-center gap-1.5 rounded-xl border border-[#5B9BD5] bg-[#DDF0FB] py-2 text-xs font-bold text-[#2B6CB0] transition hover:bg-[#cde6f8]"
                    >
                      <Download className="h-3.5 w-3.5" />
                      Download
                    </button>
                    <button
                      type="button"
                      onClick={() => handleStartOrQueue('WATCH_NOW', shareOptions)}
                      className="inline-flex items-center justify-center gap-1.5 rounded-xl bg-[#2E7D5B] py-2 text-xs font-bold text-white transition hover:bg-[#25694c]"
                    >
                      <Play className="h-3.5 w-3.5" />
                      Watch Now
                    </button>
                  </div>
                </div>
              )}

              {/* Active Job Progress Card */}
              {(activeJob || isGpuWarm) && (
                <div
                  className={`space-y-2 rounded-2xl border p-3 shadow-xs ${
                    isDark ? 'border-[#283449] bg-[#131A26]' : 'border-[#E2DCCD] bg-white'
                  }`}
                >
                  <div className="flex items-center gap-2">
                    {isRunning ? (
                      <Loader2 className="h-4 w-4 animate-spin text-[#5B9BD5]" />
                    ) : (
                      <CheckCircle2 className="h-4 w-4 text-[#16A34A]" />
                    )}
                    <p className={`text-xs font-semibold ${isDark ? 'text-white' : 'text-[#1C2621]'}`}>
                      {statusLine}
                    </p>
                  </div>
                  {activeJob && (
                    <p className={`text-[11px] ${isDark ? 'text-[#94A3B8]' : 'text-[#5A6860]'}`}>
                      {activeJob.title} • {activeJob.cueCount} cues • {activeJob.options.modelId}
                    </p>
                  )}
                  {(queueState.playingVideo || (activeJob && activeJob.cueCount >= 4)) && (
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

              {/* Queued Job Process Boxes (Queued No. 1, Queued No. 2, ...) */}
              {queueState.queuedJobs.map((qItem, idx) => (
                <div
                  key={qItem.queueId}
                  className={`flex items-center justify-between gap-2 rounded-2xl border px-3 py-2.5 text-xs shadow-xs ${
                    isDark ? 'border-[#283449] bg-[#131A26]' : 'border-[#E2DCCD] bg-white'
                  }`}
                >
                  <span
                    className={`shrink-0 rounded-lg px-2 py-0.5 text-[10px] font-bold ${
                      qItem.mode === 'WATCH_NOW'
                        ? 'bg-[#DCEFE5] text-[#2E7D5B]'
                        : 'bg-[#DDF0FB] text-[#2B6CB0]'
                    }`}
                  >
                    Queued No. {idx + 1}
                  </span>
                  <div className="min-w-0 flex-1">
                    <p className={`truncate font-semibold ${isDark ? 'text-white' : 'text-[#1C2621]'}`}>
                      {qItem.title}
                    </p>
                    <p className={`truncate text-[10px] ${isDark ? 'text-[#94A3B8]' : 'text-[#5A6860]'}`}>
                      {qItem.mode === 'WATCH_NOW' ? 'Watch Now' : 'Download'} • {qItem.options.sourceLang} • {qItem.options.modelId}
                    </p>
                  </div>
                  <button
                    type="button"
                    aria-label={`Remove Queued No. ${idx + 1}`}
                    onClick={() => setQueueState((prev) => removeQueuedJobById(prev, qItem.queueId))}
                    className={isDark ? 'text-[#94A3B8] hover:text-white' : 'text-[#5A6860] hover:text-[#1C2621]'}
                  >
                    <X className="h-3.5 w-3.5" />
                  </button>
                </div>
              ))}

              {isGpuWarm && !isRunning && (
                <div
                  className={`space-y-2 rounded-2xl border p-3 shadow-xs ${
                    isDark ? 'border-[#38BDF8]/40 bg-[#131A26]' : 'border-[#5B9BD5]/40 bg-white'
                  }`}
                >
                  <div className="flex items-center justify-between">
                    <span className={`text-xs font-semibold ${isDark ? 'text-[#60A5FA]' : 'text-[#2B6CB0]'}`}>
                      Warm T4 Reuse
                    </span>
                    <button
                      type="button"
                      onClick={() => {
                        setIsGpuWarm(false);
                        setQueueState(createInitialQueueState());
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
                      onClick={() => handleStartOrQueue(selectedMode, shareOptions)}
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
                <div
                  className={`space-y-2 rounded-2xl border p-3 text-[11px] ${
                    isDark
                      ? 'border-[#283449] bg-[#131A26] text-[#94A3B8]'
                      : 'border-[#E2DCCD] bg-[#F1ECE1]/70 text-[#5A6860]'
                  }`}
                >
                  <p className={`font-semibold ${isDark ? 'text-white' : 'text-[#1C2621]'}`}>
                    Single-APK Ready
                  </p>
                  <p>
                    Share any video from Dailymotion, Bilibili, Vimeo, TikTok, Twitch, browsers, or
                    YouTube. Watch Now live-streams smoothly and auto-opens once initial subtitles
                    arrive.
                  </p>
                  <div className="flex flex-wrap gap-1.5 pt-1 text-[10px] font-semibold">
                    <span
                      className={`rounded-md px-2 py-0.5 ${
                        isDark
                          ? 'bg-[#0C2A3E] text-[#60A5FA]'
                          : 'bg-[#DDF0FB] text-[#2B6CB0]'
                      }`}
                    >
                      No Termux
                    </span>
                    <span
                      className={`rounded-md px-2 py-0.5 ${
                        isDark
                          ? 'bg-[#1D283A] text-[#34D399]'
                          : 'bg-[#DCEFE5] text-[#2E7D5B]'
                      }`}
                    >
                      Embedded libmpv
                    </span>
                    <span
                      className={`rounded-md px-2 py-0.5 ring-1 ${
                        isDark
                          ? 'bg-[#0B0F17] text-[#E2E8F0] ring-[#283449]'
                          : 'bg-white text-[#1C2621] ring-[#E2DCCD]'
                      }`}
                    >
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
