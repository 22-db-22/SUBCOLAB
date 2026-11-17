export type WorkflowModeId = 'WATCH_LIVE_AND_SAVE' | 'OFFLINE_PACK_ONLY' | 'WATCH_LIVE_ONLY';

export interface WorkflowModeInfo {
  id: WorkflowModeId;
  title: string;
  subtitle: string;
  opensPlayer: boolean;
  savesOfflinePack: boolean;
}

export const WORKFLOW_MODES: WorkflowModeInfo[] = [
  {
    id: 'WATCH_LIVE_AND_SAVE',
    title: 'Watch Live + Save',
    subtitle: 'Live libmpv player + save <=720p MP4 & SRT/ASS/VTT to Downloads/OpenScribe/',
    opensPlayer: true,
    savesOfflinePack: true,
  },
  {
    id: 'OFFLINE_PACK_ONLY',
    title: 'Offline Pack Only',
    subtitle: 'Translate on Colab T4 & save video + subtitles without opening player',
    opensPlayer: false,
    savesOfflinePack: true,
  },
  {
    id: 'WATCH_LIVE_ONLY',
    title: 'Watch Live Only',
    subtitle: 'Stream video in built-in libmpv with live yellow English subtitles',
    opensPlayer: true,
    savesOfflinePack: false,
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
