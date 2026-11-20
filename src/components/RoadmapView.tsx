import { useState } from 'react';
import {
  ArrowRight,
  Cpu,
  FileCode2,
  Globe,
  Languages,
  Layers,
  Radio,
  SlidersHorizontal,
  WifiOff,
} from 'lucide-react';

type TrackFilter = 'all' | 'manifests' | 'subtitles' | 'offline';

interface UpgradeItem {
  id: string;
  category: Exclude<TrackFilter, 'all'>;
  categoryLabel: string;
  title: string;
  priority: string;
  summary: string;
  implementationNotes: string[];
  targetComponents: string;
}

const UPGRADES: UpgradeItem[] = [
  {
    id: 'dash-hls-direct',
    category: 'manifests',
    categoryLabel: 'Stream Manifests',
    title: '01. Direct DASH (.mpd) & HLS (.m3u8) Manifest Linking',
    priority: 'Priority Upgrade',
    summary:
      'Bypass yt-dlp webpage scraping when users paste or share raw .mpd, .m3u8, or direct CDN stream URLs—enabling live translated subtitles for third-party web players, regional broadcasters, and embedded streams.',
    implementationNotes: [
      'Direct Manifest Fast-Path: detect .mpd, .m3u8, .mp4, .ts, or mime= URLs and route straight to LoopbackMediaProxy without yt-dlp overhead.',
      'MPEG-DASH (.mpd) Proxy Rewriter: mirror rewriteM3u8Playlist with XML BaseURL & SegmentTemplate resolution so libmpv plays video cleanly.',
      'Foreign Audio AdaptationSet Slicing: pick a target <AdaptationSet contentType="audio"> and stream only that audio track to ffmpeg for 15–60s AAC chunking.',
      'Custom Request Headers: allow optional Referer, Origin, Cookie, and User-Agent headers for signed CDN streams.',
    ],
    targetComponents: 'UrlUnwrapper · StreamExtractor · LoopbackMediaProxy',
  },
  {
    id: 'live-sliding-window',
    category: 'manifests',
    categoryLabel: 'Stream Manifests',
    title: '02. Sliding-Window Live Stream Tailing',
    priority: 'Live Streaming',
    summary:
      'Continuously poll HLS EXT-X-MEDIA-SEQUENCE and dynamic DASH MPD segments to translate live broadcasts as new media segments arrive.',
    implementationNotes: [
      'Track seen media sequence numbers inside LoopbackMediaProxy so ffmpeg appends newly published audio segments without restarting.',
      'Emit rolling WebSocket subtitle cues tagged with live program-date-time offsets into libmpv sub-reload.',
    ],
    targetComponents: 'LoopbackMediaProxy · SubcolabCoordinator',
  },
  {
    id: 'webview-sniffer',
    category: 'manifests',
    categoryLabel: 'Stream Manifests',
    title: '03. Built-In Lightweight Stream Sniffer',
    priority: 'Discovery UX',
    summary:
      'An optional in-app WebView browser tab that intercepts .m3u8 and .mpd network requests on any webpage and surfaces a one-tap Translate This Stream button.',
    implementationNotes: [
      'Inspect shouldInterceptRequest URLs for .m3u8, .mpd, and media MIME types while capturing page Referer and Cookie headers automatically.',
      'Forward captured manifest + header bundle directly into the Watch Now or Download workflow.',
    ],
    targetComponents: 'HomeScreen · SharePromptActivity · StreamExtractor',
  },
  {
    id: 'dual-lang-subs',
    category: 'subtitles',
    categoryLabel: 'Subtitles & AI',
    title: '04. Dual-Language Stacked Subtitles (Original + English)',
    priority: 'Language Learning',
    summary:
      'Render both the original spoken-language cues (.orig.srt) and translated English cues simultaneously in libmpv using styled ASS tracks.',
    implementationNotes: [
      'Merge existing Colab .orig.srt and .en.srt streams into a dual-style .ass file with distinct top/bottom alignment and colors.',
      'Add a single-tap toggle in PlayerScreen to switch between English-only, Original-only, and Dual stacked mode.',
    ],
    targetComponents: 'SubtitleManager · MpvPlayerController · PlayerScreen',
  },
  {
    id: 'diarization-glossary',
    category: 'subtitles',
    categoryLabel: 'Subtitles & AI',
    title: '05. Speaker Diarization & Custom Glossary Prompts',
    priority: 'Accuracy Boost',
    summary:
      'Pass user-defined character names, domain jargon, and speaker labels via Whisper initial_prompt and lightweight diarization in the Colab worker.',
    implementationNotes: [
      'Expose an optional Glossary / Context prompt field per job for anime titles, technical talks, or multi-host podcasts.',
      'Prefix speaker turns ([Speaker 1], [Speaker 2]) in generated SRT/ASS cues when diarization is enabled.',
    ],
    targetComponents: 'colab_worker.py · ColabClient · HomeScreen',
  },
  {
    id: 'offline-whisper-cpp',
    category: 'offline',
    categoryLabel: 'Offline & UX',
    title: '06. On-Device whisper.cpp Fallback & Sync Library',
    priority: 'Offline & Polish',
    summary:
      'Run quantized local GGUF Whisper models for short offline clips when Colab quota is unavailable, paired with ±100ms subtitle offset nudging in the player.',
    implementationNotes: [
      'Optional downloadable tiny/base quantized GGUF model executed via JNI (NNAPI/Vulkan) for local files.',
      'Add ±100ms sub-delay controls, custom subtitle font/size presets, and WebDAV/Drive pack backup.',
    ],
    targetComponents: 'LocalAudioExtractor · MpvPlayerController · SettingsRepository',
  },
];

const FILTERS: { id: TrackFilter; label: string }[] = [
  { id: 'all', label: 'All Upgrades' },
  { id: 'manifests', label: 'DASH & .m3u8 Manifests' },
  { id: 'subtitles', label: 'Subtitles & AI' },
  { id: 'offline', label: 'Offline & UX' },
];

export function RoadmapView({ isDark = false }: { isDark?: boolean }) {
  const [filter, setFilter] = useState<TrackFilter>('all');
  const [expandedId, setExpandedId] = useState<string>('dash-hls-direct');

  const visibleUpgrades =
    filter === 'all' ? UPGRADES : UPGRADES.filter((item) => item.category === filter);

  const cardSurface = isDark
    ? 'border-[#283449] bg-[#131A26] text-[#F8FAFC]'
    : 'border-[#E2DCCD] bg-white text-[#1C2621]';
  const subtleSurface = isDark
    ? 'border-[#283449] bg-[#0B0F17] text-[#94A3B8]'
    : 'border-[#E2DCCD] bg-[#FAF7F0] text-[#5A6860]';
  const mutedText = isDark ? 'text-[#94A3B8]' : 'text-[#5A6860]';
  const headingText = isDark ? 'text-[#F8FAFC]' : 'text-[#1C2621]';

  return (
    <div className="space-y-6">
      {/* Header & DASH/HLS Pipeline Blueprint */}
      <section className={`rounded-2xl border p-6 shadow-sm ${cardSurface}`}>
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="max-w-2xl">
            <p
              className={`text-xs font-semibold tracking-wide ${
                isDark ? 'text-[#60A5FA]' : 'text-[#2E7D5B]'
              }`}
            >
              Contributor Roadmap · Extensible Architecture
            </p>
            <h2 className={`mt-1 text-xl font-bold tracking-tight ${headingText}`}>
              Future Upgrades: Direct DASH (.mpd), HLS (.m3u8) &amp; Beyond
            </h2>
            <p className={`mt-2 text-sm leading-relaxed ${mutedText}`}>
              SUBCOLAB prioritizes video platforms and raw streams that lack built-in translation.
              Here is the architectural blueprint for linking raw{' '}
              <code className="font-mono font-semibold">.mpd</code> and{' '}
              <code className="font-mono font-semibold">.m3u8</code> manifests directly, along with
              modular upgrades contributors can build next.
            </p>
          </div>

          <div
            className={`flex flex-wrap items-center gap-1 rounded-xl border p-1 ${
              isDark ? 'border-[#283449] bg-[#0B0F17]' : 'border-[#E2DCCD] bg-[#FAF7F0]'
            }`}
          >
            {FILTERS.map((f) => (
              <button
                key={f.id}
                type="button"
                onClick={() => setFilter(f.id)}
                className={`whitespace-nowrap shrink-0 rounded-lg px-3 py-1.5 text-xs font-semibold transition ${
                  filter === f.id
                    ? isDark
                      ? 'bg-[#2563EB] text-white'
                      : 'bg-[#2E7D5B] text-white'
                    : isDark
                      ? 'text-[#94A3B8] hover:text-white'
                      : 'text-[#5A6860] hover:text-[#1C2621]'
                }`}
              >
                {f.label}
              </button>
            ))}
          </div>
        </div>

        {/* Clean Pipeline Diagram */}
        <div className={`mt-6 rounded-xl border p-4 ${subtleSurface}`}>
          <div className="flex flex-wrap items-center justify-between gap-2 pb-3">
            <span className={`text-xs font-semibold ${headingText}`}>
              Proposed Direct Manifest Ingestion Flow (.mpd / .m3u8 / Raw CDN)
            </span>
            <span className="font-mono text-[11px] tabular-nums">
              127.0.0.1 Loopback Proxy → libmpv + Whisper large-v3
            </span>
          </div>
          <div className="grid gap-3 md:grid-cols-4">
            <div className={`rounded-lg border p-3 ${cardSurface}`}>
              <div className="flex items-center gap-2 text-xs font-semibold">
                <Globe className={`h-4 w-4 ${isDark ? 'text-[#60A5FA]' : 'text-[#2B6CB0]'}`} />
                <span>1. Detect Manifest</span>
              </div>
              <p className={`mt-1.5 text-xs leading-relaxed ${mutedText}`}>
                Recognize <code className="font-mono">.mpd</code>,{' '}
                <code className="font-mono">.m3u8</code>, or{' '}
                <code className="font-mono">.mp4</code> URLs + optional{' '}
                <code className="font-mono">Referer</code> header and skip{' '}
                <code className="font-mono">yt-dlp</code> scraping.
              </p>
            </div>
            <div className={`rounded-lg border p-3 ${cardSurface}`}>
              <div className="flex items-center gap-2 text-xs font-semibold">
                <FileCode2 className={`h-4 w-4 ${isDark ? 'text-[#34D399]' : 'text-[#2E7D5B]'}`} />
                <span>2. Rewrite &amp; Proxy</span>
              </div>
              <p className={`mt-1.5 text-xs leading-relaxed ${mutedText}`}>
                Rewrite HLS segment URIs &amp; DASH <code className="font-mono">&lt;BaseURL&gt;</code>{' '}
                / <code className="font-mono">SegmentTemplate</code> nodes locally with OkHttp TLS.
              </p>
            </div>
            <div className={`rounded-lg border p-3 ${cardSurface}`}>
              <div className="flex items-center gap-2 text-xs font-semibold">
                <Radio className={`h-4 w-4 ${isDark ? 'text-[#60A5FA]' : 'text-[#2B6CB0]'}`} />
                <span>3. Slice Audio Track</span>
              </div>
              <p className={`mt-1.5 text-xs leading-relaxed ${mutedText}`}>
                Select target foreign audio <code className="font-mono">AdaptationSet</code> and
                slice 15–60s <code className="font-mono">16kHz</code> AAC chunks without stalling
                video.
              </p>
            </div>
            <div className={`rounded-lg border p-3 ${cardSurface}`}>
              <div className="flex items-center gap-2 text-xs font-semibold">
                <Cpu className={`h-4 w-4 ${isDark ? 'text-[#34D399]' : 'text-[#2E7D5B]'}`} />
                <span>4. Live Sub-Reload</span>
              </div>
              <p className={`mt-1.5 text-xs leading-relaxed ${mutedText}`}>
                Stream English cues from Colab T4 Whisper <code className="font-mono">large-v3</code>{' '}
                into <code className="font-mono">libmpv</code> as playback continues smoothly.
              </p>
            </div>
          </div>
        </div>
      </section>

      {/* Upgrade Tracks Grid */}
      <div className="grid gap-4 md:grid-cols-2">
        {visibleUpgrades.map((item) => {
          const isOpen = expandedId === item.id;
          const Icon =
            item.category === 'manifests'
              ? Layers
              : item.category === 'subtitles'
                ? Languages
                : item.id === 'offline-whisper-cpp'
                  ? WifiOff
                  : SlidersHorizontal;

          return (
            <article
              key={item.id}
              className={`flex flex-col justify-between rounded-2xl border p-5 shadow-sm transition ${cardSurface}`}
            >
              <div>
                <div className="flex items-center justify-between gap-2 text-xs">
                  <span className={mutedText}>
                    {item.categoryLabel} · {item.priority}
                  </span>
                  <Icon
                    className={`h-4 w-4 shrink-0 ${
                      isDark ? 'text-[#60A5FA]' : 'text-[#2E7D5B]'
                    }`}
                  />
                </div>
                <h3 className={`mt-2 text-base font-semibold ${headingText}`}>{item.title}</h3>
                <p className={`mt-2 text-xs leading-relaxed ${mutedText}`}>{item.summary}</p>

                {isOpen && (
                  <ul className={`mt-4 space-y-2 border-t pt-3 text-xs ${isDark ? 'border-[#283449]' : 'border-[#E2DCCD]'}`}>
                    {item.implementationNotes.map((note) => (
                      <li key={note} className={`flex items-start gap-2 leading-relaxed ${mutedText}`}>
                        <ArrowRight
                          className={`mt-0.5 h-3.5 w-3.5 shrink-0 ${
                            isDark ? 'text-[#34D399]' : 'text-[#2E7D5B]'
                          }`}
                        />
                        <span>{note}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </div>

              <div
                className={`mt-4 flex flex-wrap items-center justify-between gap-2 border-t pt-3 text-xs ${
                  isDark ? 'border-[#283449]' : 'border-[#E2DCCD]'
                }`}
              >
                <span className={`font-mono text-[11px] ${mutedText}`}>{item.targetComponents}</span>
                <button
                  type="button"
                  onClick={() => setExpandedId(isOpen ? '' : item.id)}
                  className={`whitespace-nowrap shrink-0 font-semibold transition hover:underline ${
                    isDark ? 'text-[#60A5FA]' : 'text-[#2B6CB0]'
                  }`}
                >
                  {isOpen ? 'Hide Technical Notes' : 'View Technical Notes'}
                </button>
              </div>
            </article>
          );
        })}
      </div>
    </div>
  );
}
