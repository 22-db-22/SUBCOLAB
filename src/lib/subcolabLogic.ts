export type WorkflowModeId = 'WATCH_NOW' | 'DOWNLOAD_AND_TRANSLATE';

export interface WorkflowModeInfo {
  id: WorkflowModeId;
  title: string;
  subtitle: string;
  opensPlayer: boolean;
  savesOfflinePack: boolean;
}

export const WORKFLOW_MODES: WorkflowModeInfo[] = [
  {
    id: 'WATCH_NOW',
    title: 'Watch Now (Live Stream)',
    subtitle: 'Smooth live video via loopback proxy + adaptive audio-only subtitle chunks, auto-opens over other apps',
    opensPlayer: true,
    savesOfflinePack: false,
  },
  {
    id: 'DOWNLOAD_AND_TRANSLATE',
    title: 'Download Full MP4',
    subtitle: 'Save full <=720p MP4 + English & original subtitles quietly in background',
    opensPlayer: false,
    savesOfflinePack: true,
  },
];

export interface WhisperLang {
  code: string;
  name: string;
  aliases?: string[];
}

export const QUICK_LANG_CHIPS: WhisperLang[] = [
  { code: 'auto', name: 'Auto-Detect', aliases: ['detect'] },
  { code: 'zh', name: 'Chinese', aliases: ['mandarin', 'cn'] },
  { code: 'ko', name: 'Korean', aliases: ['kr', 'hangul'] },
  { code: 'ja', name: 'Japanese', aliases: ['jp', 'nihongo'] },
];

export const WHISPER_LANGUAGES: WhisperLang[] = [
  ...QUICK_LANG_CHIPS,
  { code: 'yue', name: 'Cantonese' },
  { code: 'es', name: 'Spanish', aliases: ['espanol'] },
  { code: 'fr', name: 'French', aliases: ['francais'] },
  { code: 'de', name: 'German', aliases: ['deutsch'] },
  { code: 'it', name: 'Italian', aliases: ['italiano'] },
  { code: 'pt', name: 'Portuguese' },
  { code: 'ru', name: 'Russian' },
  { code: 'ar', name: 'Arabic' },
  { code: 'hi', name: 'Hindi' },
  { code: 'vi', name: 'Vietnamese', aliases: ['viet'] },
  { code: 'th', name: 'Thai' },
  { code: 'id', name: 'Indonesian', aliases: ['in'] },
  { code: 'tr', name: 'Turkish' },
  { code: 'uk', name: 'Ukrainian' },
  { code: 'pl', name: 'Polish' },
  { code: 'nl', name: 'Dutch', aliases: ['flemish'] },
  { code: 'tl', name: 'Tagalog', aliases: ['filipino'] },
];

export function searchWhisperLanguages(query: string): WhisperLang[] {
  const q = query.trim().toLowerCase();
  if (!q) return WHISPER_LANGUAGES;
  return WHISPER_LANGUAGES.filter(
    (lang) =>
      lang.code.toLowerCase().includes(q) ||
      lang.name.toLowerCase().includes(q) ||
      (lang.aliases ?? []).some((a) => a.toLowerCase().includes(q)),
  );
}

export function extractAndUnwrapUrl(rawInput: string): string {
  const trimmed = (rawInput || '').trim();
  if (!trimmed) return '';
  const match = trimmed.match(/https?:\/\/[^\s<>"')\]]+/);
  let current = match ? match[0] : trimmed;

  for (let i = 0; i < 3; i++) {
    try {
      const u = new URL(current);
      const host = u.hostname.toLowerCase();
      const path = u.pathname.toLowerCase();
      const isWrapper =
        (host.includes('google.') && (path.startsWith('/goto') || path.startsWith('/url'))) ||
        host === 'l.instagram.com' ||
        host === 'l.facebook.com' ||
        host === 'lm.facebook.com' ||
        (host === 'www.youtube.com' && path === '/redirect');
      if (!isWrapper) break;
      const target = u.searchParams.get('url') || u.searchParams.get('q') || u.searchParams.get('u');
      if (!target || (!target.startsWith('http://') && !target.startsWith('https://'))) break;
      current = target;
    } catch {
      break;
    }
  }

  const shortsMatch = current.match(/youtube\.com\/shorts\/([A-Za-z0-9_-]{6,})/);
  if (shortsMatch) {
    return `https://www.youtube.com/watch?v=${shortsMatch[1]}`;
  }
  const liveMatch = current.match(/youtube\.com\/live\/([A-Za-z0-9_-]{6,})/);
  if (liveMatch) {
    return `https://www.youtube.com/watch?v=${liveMatch[1]}`;
  }
  return current;
}

export interface ChunkRange {
  startSec: number;
  endSec: number;
}

export function isTimestampTranslated(seconds: number, completedChunks: ChunkRange[]): boolean {
  if (seconds < 0) return true;
  return completedChunks.some((c) => seconds >= c.startSec - 0.5 && seconds <= c.endSec + 0.5);
}

export function formatMmSs(seconds: number): string {
  const total = Math.max(0, Math.floor(seconds));
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

export function computeAdaptiveAudioChunkSec(durationSec: number): number {
  if (durationSec <= 0) return 20;
  if (durationSec <= 180) return 15;
  if (durationSec <= 900) return 25;
  if (durationSec <= 2700) return 40;
  return 60;
}

export interface SubtitleTrackCandidate {
  lang: string;
  ext: string;
  url: string;
}

export type SubtitleStrategyResult =
  | { kind: 'USE_WHISPER_GPU' }
  | { kind: 'USE_DIRECT_ENGLISH'; track: SubtitleTrackCandidate }
  | { kind: 'TRANSLATE_MANUAL'; track: SubtitleTrackCandidate; sourceLang: string };

function baseLangCode(raw: string): string {
  return raw.trim().toLowerCase().split(/[-_]/)[0] ?? '';
}

export function chooseSubtitleStrategy(
  manualSubs: SubtitleTrackCandidate[],
  requestedSourceLang: string,
): SubtitleStrategyResult {
  if (manualSubs.length === 0) return { kind: 'USE_WHISPER_GPU' };

  const enCandidates = manualSubs.filter((t) => baseLangCode(t.lang) === 'en');
  if (enCandidates.length > 0) {
    const sorted = [...enCandidates].sort((a, b) => {
      const aExact = a.lang.toLowerCase() === 'en' ? 0 : 1;
      const bExact = b.lang.toLowerCase() === 'en' ? 0 : 1;
      if (aExact !== bExact) return aExact - bExact;
      const aSrt = a.ext.toLowerCase() === 'srt' ? 0 : 1;
      const bSrt = b.ext.toLowerCase() === 'srt' ? 0 : 1;
      return aSrt - bSrt;
    });
    return { kind: 'USE_DIRECT_ENGLISH', track: sorted[0] };
  }

  const nonEn = manualSubs.filter((t) => {
    const base = baseLangCode(t.lang);
    return base !== '' && base !== 'en' && base !== 'live_chat' && base !== 'danmaku';
  });
  if (nonEn.length === 0) return { kind: 'USE_WHISPER_GPU' };

  const req = requestedSourceLang.trim().toLowerCase();
  const bySrtFirst = (list: SubtitleTrackCandidate[]) =>
    [...list].sort((a, b) => (a.ext.toLowerCase() === 'srt' ? 0 : 1) - (b.ext.toLowerCase() === 'srt' ? 0 : 1))[0];

  if (req && req !== 'auto' && req !== 'detect') {
    const reqBase = baseLangCode(req);
    const matched = nonEn.filter((t) => baseLangCode(t.lang) === reqBase);
    if (matched.length > 0) {
      const picked = bySrtFirst(matched);
      return { kind: 'TRANSLATE_MANUAL', track: picked, sourceLang: picked.lang };
    }
  }
  const picked = bySrtFirst(nonEn);
  return { kind: 'TRANSLATE_MANUAL', track: picked, sourceLang: picked.lang };
}

export interface ParsedSubtitleCue {
  index: number;
  startMs: number;
  endMs: number;
  text: string;
}

function parseTsMs(ts: string): number | null {
  const parts = ts.trim().replace(',', '.').split(':');
  if (parts.length !== 2 && parts.length !== 3) return null;
  const h = parts.length === 3 ? Number(parts[0]) : 0;
  const m = Number(parts[parts.length === 3 ? 1 : 0]);
  const secParts = parts[parts.length - 1].split('.');
  const s = Number(secParts[0]);
  const ms = secParts.length > 1 ? Number(secParts[1].padEnd(3, '0').slice(0, 3)) : 0;
  if ([h, m, s, ms].some((n) => Number.isNaN(n))) return null;
  return h * 3_600_000 + m * 60_000 + s * 1_000 + ms;
}

export function parseVttOrSrtCues(rawContent: string): ParsedSubtitleCue[] {
  const cleaned = rawContent.replace(/^\uFEFF/, '').replace(/\r\n/g, '\n').replace(/\r/g, '\n');
  if (!cleaned.trim()) return [];
  const blocks = cleaned.split(/\n{2,}/);
  const out: ParsedSubtitleCue[] = [];

  for (const block of blocks) {
    const lines = block.split('\n').map((l) => l.trimEnd()).filter((l) => l.length > 0);
    if (lines.length === 0) continue;
    const first = lines[0].trim().toUpperCase();
    if (first.startsWith('WEBVTT') || first.startsWith('NOTE') || first.startsWith('STYLE') || first.startsWith('REGION')) {
      continue;
    }
    const timingIdx = lines.findIndex((l) => l.includes('-->'));
    if (timingIdx < 0) continue;
    const [rawStart, rawEnd] = lines[timingIdx].split('-->', 2);
    const startMs = parseTsMs(rawStart.trim().split(' ')[0]);
    if (startMs === null) continue;
    const endMs = parseTsMs(rawEnd.trim().split(' ')[0]) ?? startMs + 1500;
    const textLines = lines
      .slice(timingIdx + 1)
      .map((l) =>
        l
          .replace(/<[^>]+>|\{\\[^}]+\}/g, '')
          .replace(/&nbsp;/g, ' ')
          .replace(/&amp;/g, '&')
          .replace(/&lt;/g, '<')
          .replace(/&gt;/g, '>')
          .replace(/&#39;/g, "'")
          .replace(/&quot;/g, '"')
          .replace(/[ \t]+/g, ' ')
          .trim(),
      )
      .filter(Boolean);
    const uniqueLines = Array.from(new Set(textLines)).join('\n');
    if (!uniqueLines) continue;
    out.push({
      index: out.length + 1,
      startMs,
      endMs: Math.max(endMs, startMs + 200),
      text: uniqueLines,
    });
  }
  return out;
}

const HALLUCINATION_EXACT = new Set([
  'thank you',
  'thank you.',
  'thanks for watching',
  'thanks for watching.',
  'thank you for watching',
  'thank you for watching.',
  'please subscribe',
  'subscribe',
  'subtitles by',
  'translated by',
  'captions by',
  '...',
  '.',
]);

export function filterWhisperHallucination(raw: string): string {
  const stripped = raw.replace(/\[[^\]]{1,40}\]|\([^)]{1,40}\)/g, '').replace(/\s+/g, ' ').trim();
  if (!stripped) return '';
  const low = stripped.toLowerCase().replace(/^[^\p{L}\p{N}]+|[^\p{L}\p{N}]+$/gu, '');
  if (!low || HALLUCINATION_EXACT.has(low) || HALLUCINATION_EXACT.has(stripped.toLowerCase())) {
    return '';
  }
  if (/^(subtitles|translated|captioned|transcribed)\s+by\b/i.test(stripped)) {
    return '';
  }
  const words = stripped.toLowerCase().split(/\s+/);
  if (words.length >= 6 && new Set(words).size <= 2) {
    return '';
  }
  return stripped;
}

export function computeResumeOffsetSec(
  subtitlesCompleted: boolean,
  completedChunks: ChunkRange[],
  savedResumeSec = 0,
): number {
  if (subtitlesCompleted) return 0;
  const maxChunkEnd = completedChunks.reduce((acc, c) => Math.max(acc, c.endSec), 0);
  const best = Math.max(savedResumeSec, maxChunkEnd);
  return best >= 10 ? best : 0;
}

export function formatSubtitleDelay(delaySec: number): string {
  if (Math.abs(delaySec) < 0.05) return '0.0s';
  return `${delaySec > 0 ? '+' : ''}${delaySec.toFixed(1)}s`;
}

export type WhisperModelId = 'large-v3' | 'large-v3-turbo';

export const WHISPER_MODELS: { id: WhisperModelId; label: string }[] = [
  { id: 'large-v3', label: 'large-v3 (Best)' },
  { id: 'large-v3-turbo', label: 'turbo (Fast)' },
];

export function normalizeWhisperModel(raw?: string | null): WhisperModelId {
  const trimmed = (raw ?? '').trim().toLowerCase();
  if (trimmed === 'large-v3-turbo' || trimmed === 'turbo') {
    return 'large-v3-turbo';
  }
  return 'large-v3';
}

export interface ShareSubtitleOptions {
  sourceLang: string;
  modelId: WhisperModelId;
  cleanAudio: boolean;
  vocalBoost: boolean;
  highPrecisionBeam: boolean;
  includeOriginal: boolean;
}

export const DEFAULT_SHARE_SUBTITLE_OPTIONS: ShareSubtitleOptions = {
  sourceLang: 'auto',
  modelId: 'large-v3',
  cleanAudio: false,
  vocalBoost: false,
  highPrecisionBeam: false,
  includeOriginal: false,
};

export interface QueuedJob {
  queueId: string;
  title: string;
  url: string;
  mode: WorkflowModeId;
  options: ShareSubtitleOptions;
}

export interface ReadyQueuedOffer {
  queueId: string;
  title: string;
  url: string;
  cueCount: number;
  modelId: WhisperModelId;
}

export interface ActiveSimJob {
  id: string;
  title: string;
  url: string;
  mode: WorkflowModeId;
  options: ShareSubtitleOptions;
  isFromQueue: boolean;
  cueCount: number;
  status: 'running' | 'completed';
}

export interface PlayingSimVideo {
  id: string;
  title: string;
  url: string;
  cueCount: number;
  modelId: WhisperModelId;
}

export interface QueueSimulationState {
  activeJob: ActiveSimJob | null;
  queuedJobs: QueuedJob[];
  readyOffers: ReadyQueuedOffer[];
  playingVideo: PlayingSimVideo | null;
  shouldAutoOpenPlayer: boolean;
}

export function createInitialQueueState(): QueueSimulationState {
  return {
    activeJob: null,
    queuedJobs: [],
    readyOffers: [],
    playingVideo: null,
    shouldAutoOpenPlayer: false,
  };
}

export function buildShortTitleFromUrl(rawUrl: string): string {
  try {
    const u = new URL(extractAndUnwrapUrl(rawUrl));
    const seg = u.pathname.split('/').filter(Boolean).pop();
    if (seg && seg.toLowerCase() !== 'watch') {
      return `${u.hostname.replace(/^www\./, '').split('.')[0]}_${seg}`.slice(0, 28);
    }
    const v = u.searchParams.get('v');
    if (v) return `youtube_${v}`.slice(0, 28);
    return u.hostname.replace(/^www\./, '');
  } catch {
    return rawUrl.slice(0, 28) || 'video';
  }
}

export function submitJobToQueueState(
  state: QueueSimulationState,
  params: {
    id: string;
    url: string;
    title?: string;
    mode: WorkflowModeId;
    options: ShareSubtitleOptions;
  },
): { state: QueueSimulationState; queuePosition: number } {
  const cleanUrl = extractAndUnwrapUrl(params.url);
  const title = params.title?.trim() || buildShortTitleFromUrl(cleanUrl);
  const normalizedOptions: ShareSubtitleOptions = {
    ...params.options,
    sourceLang: params.options.sourceLang || 'auto',
    modelId: normalizeWhisperModel(params.options.modelId),
  };

  if (state.activeJob && state.activeJob.status === 'running') {
    const queued: QueuedJob = {
      queueId: params.id,
      title,
      url: cleanUrl,
      mode: params.mode,
      options: normalizedOptions,
    };
    const nextQueue = [...state.queuedJobs, queued];
    return {
      state: {
        ...state,
        queuedJobs: nextQueue,
        shouldAutoOpenPlayer: false,
      },
      queuePosition: nextQueue.length,
    };
  }

  return {
    state: {
      ...state,
      activeJob: {
        id: params.id,
        title,
        url: cleanUrl,
        mode: params.mode,
        options: normalizedOptions,
        isFromQueue: false,
        cueCount: 0,
        status: 'running',
      },
      shouldAutoOpenPlayer: false,
    },
    queuePosition: 0,
  };
}

export function onActiveJobMediaReady(
  state: QueueSimulationState,
  cueCount: number,
): QueueSimulationState {
  const active = state.activeJob;
  if (!active || active.status !== 'running') return state;

  const updatedActive: ActiveSimJob = { ...active, cueCount };
  if (active.mode !== 'WATCH_NOW') {
    return { ...state, activeJob: updatedActive, shouldAutoOpenPlayer: false };
  }

  if (!active.isFromQueue && !state.playingVideo) {
    return {
      ...state,
      activeJob: updatedActive,
      playingVideo: {
        id: active.id,
        title: active.title,
        url: active.url,
        cueCount,
        modelId: active.options.modelId,
      },
      shouldAutoOpenPlayer: true,
    };
  }

  // Queued Watch Now job must NOT interrupt current playback; offer top notification ("Watch Now" / "Hide")
  const alreadyOffered = state.readyOffers.some((o) => o.queueId === active.id);
  const nextOffers = alreadyOffered
    ? state.readyOffers.map((o) => (o.queueId === active.id ? { ...o, cueCount } : o))
    : [
        ...state.readyOffers,
        {
          queueId: active.id,
          title: active.title,
          url: active.url,
          cueCount,
          modelId: active.options.modelId,
        },
      ];

  return {
    ...state,
    activeJob: updatedActive,
    readyOffers: nextOffers,
    shouldAutoOpenPlayer: false,
  };
}

export function completeActiveAndPromoteQueue(
  state: QueueSimulationState,
  finalCueCount: number,
): QueueSimulationState {
  const active = state.activeJob;
  const offersWithCurrent = active
    ? state.readyOffers.map((o) => (o.queueId === active.id ? { ...o, cueCount: finalCueCount } : o))
    : state.readyOffers;

  const updatedPlaying =
    state.playingVideo && active && state.playingVideo.id === active.id
      ? { ...state.playingVideo, cueCount: finalCueCount }
      : state.playingVideo;

  const [nextQueued, ...remainingQueue] = state.queuedJobs;
  if (!nextQueued) {
    return {
      ...state,
      activeJob: active ? { ...active, cueCount: finalCueCount, status: 'completed' } : null,
      readyOffers: offersWithCurrent,
      playingVideo: updatedPlaying,
      shouldAutoOpenPlayer: false,
    };
  }

  return {
    ...state,
    activeJob: {
      id: nextQueued.queueId,
      title: nextQueued.title,
      url: nextQueued.url,
      mode: nextQueued.mode,
      options: nextQueued.options,
      isFromQueue: true,
      cueCount: 0,
      status: 'running',
    },
    queuedJobs: remainingQueue,
    readyOffers: offersWithCurrent,
    playingVideo: updatedPlaying,
    shouldAutoOpenPlayer: false,
  };
}

export function switchToReadyOffer(
  state: QueueSimulationState,
  queueId: string,
): QueueSimulationState {
  const offer = state.readyOffers.find((o) => o.queueId === queueId);
  if (!offer) return state;
  return {
    ...state,
    playingVideo: {
      id: offer.queueId,
      title: offer.title,
      url: offer.url,
      cueCount: offer.cueCount,
      modelId: offer.modelId,
    },
    readyOffers: state.readyOffers.filter((o) => o.queueId !== queueId),
    shouldAutoOpenPlayer: true,
  };
}

export function hideReadyOffer(
  state: QueueSimulationState,
  queueId: string,
): QueueSimulationState {
  return {
    ...state,
    readyOffers: state.readyOffers.filter((o) => o.queueId !== queueId),
    shouldAutoOpenPlayer: false,
  };
}

export function removeQueuedJobById(
  state: QueueSimulationState,
  queueId: string,
): QueueSimulationState {
  return {
    ...state,
    queuedJobs: state.queuedJobs.filter((q) => q.queueId !== queueId),
  };
}

export const QUICK_TIPS_DISMISSED_KEY = 'subcolab_quick_tips_dismissed';

export function readQuickTipsDismissed(storage?: Pick<Storage, 'getItem'> | null): boolean {
  if (!storage) return false;
  try {
    return storage.getItem(QUICK_TIPS_DISMISSED_KEY) === '1';
  } catch {
    return false;
  }
}

export function persistQuickTipsDismissed(storage?: Pick<Storage, 'setItem'> | null): boolean {
  if (!storage) return true;
  try {
    storage.setItem(QUICK_TIPS_DISMISSED_KEY, '1');
  } catch {
    // ignore quota/private-mode storage failure
  }
  return true;
}


