import { Layers, ShieldCheck } from 'lucide-react';

const MODULES = [
  {
    name: 'Embedded Live Player (dev.jdtech.mpv:libmpv:0.5.1)',
    path: 'app/src/main/java/com/openscribe/android/player/MpvPlayerController.kt',
    summary:
      'Hardware-decoded SurfaceView player streaming smoothly via LoopbackMediaProxy (64MiB cache, 30s readahead, split DASH audio-add) on cinema-black with yellow subtitles, gestures, fullscreen, and PiP.',
  },
  {
    name: 'Live Proxy & Adaptive Audio Chunker (youtubedl-android:0.18.1)',
    path: 'app/src/main/java/com/openscribe/android/media/StreamExtractor.kt',
    summary:
      'Resolves direct video + audio streams across 1,500+ yt-dlp sites (Dailymotion, Bilibili, Vimeo, Twitch, TikTok, NicoNico, and YouTube), serves them over OkHttp LoopbackMediaProxy (Range + HLS M3U8), and slices ONLY audio (15s–60s adaptive 24kbps AAC).',
  },
  {
    name: 'Colab OAuth2 & Streaming Queue (google-colab-cli)',
    path: 'app/src/main/java/com/openscribe/android/colab/ColabClient.kt',
    summary:
      'EncryptedSharedPreferences OAuth2, T4 /tun/m/assign XSRF warmup, instant tiny audio-chunk uploads to /content/openscribe_queue_<id>, WebSocket streaming cues, and auto-recover.',
  },
  {
    name: 'Share Dialog & Auto-Open Over Other Apps',
    path: 'app/src/main/java/com/openscribe/android/SharePromptActivity.kt',
    summary:
      'Floating overlay offers Watch Now (smooth live stream + auto-open over Dailymotion, Bilibili, browsers, or YouTube via SYSTEM_ALERT_WINDOW / full-screen intent) and Download (full regular <=720p MP4 quietly in background).',
  },
];

export function ArchitectureView({ isDark = false }: { isDark?: boolean }) {
  const cardCls = isDark
    ? 'border-[#283449] bg-[#131A26] text-[#F8FAFC]'
    : 'border-[#E2DCCD] bg-white text-[#1C2621]';
  const subCardCls = isDark
    ? 'border-[#283449] bg-[#0B0F17] text-[#94A3B8]'
    : 'border-[#E2DCCD] bg-[#FAF7F0] text-[#5A6860]';

  return (
    <div className="space-y-6">
      <div className="grid gap-4 md:grid-cols-2">
        {MODULES.map((mod) => (
          <div key={mod.name} className={`rounded-2xl border p-5 shadow-sm ${cardCls}`}>
            <div className="flex items-start gap-3">
              <div
                className={`rounded-xl p-2.5 ${
                  isDark ? 'bg-[#0C2A3E] text-[#60A5FA]' : 'bg-[#DDF0FB] text-[#2B6CB0]'
                }`}
              >
                <Layers className="h-5 w-5" />
              </div>
              <div>
                <h3 className={`text-sm font-semibold ${isDark ? 'text-white' : 'text-[#1C2621]'}`}>
                  {mod.name}
                </h3>
                <p
                  className={`mt-1 font-mono text-[11px] font-medium ${
                    isDark ? 'text-[#34D399]' : 'text-[#2E7D5B]'
                  }`}
                >
                  {mod.path}
                </p>
                <p
                  className={`mt-2 text-xs leading-relaxed ${
                    isDark ? 'text-[#94A3B8]' : 'text-[#5A6860]'
                  }`}
                >
                  {mod.summary}
                </p>
              </div>
            </div>
          </div>
        ))}
      </div>

      <div className={`rounded-2xl border p-5 shadow-sm ${cardCls}`}>
        <div
          className={`flex items-center gap-2.5 text-sm font-semibold ${
            isDark ? 'text-white' : 'text-[#1C2621]'
          }`}
        >
          <ShieldCheck className="h-5 w-5 text-[#16A34A]" />
          Zero Termux, Zero External mpv-android, Zero All Files Access
        </div>
        <div className="mt-3 grid gap-3 text-xs sm:grid-cols-3">
          <div className={`rounded-xl border p-3.5 ${subCardCls}`}>
            <p className={`font-semibold ${isDark ? 'text-white' : 'text-[#1C2621]'}`}>
              Scoped Downloads
            </p>
            <p className="mt-1">
              Uses Android{' '}
              <code className={`font-medium ${isDark ? 'text-[#60A5FA]' : 'text-[#2B6CB0]'}`}>
                MediaStore.Downloads
              </code>{' '}
              (<code className={isDark ? 'text-white' : 'text-[#1C2621]'}>Download/SUBCOLAB/</code>)
              on API 29+ without{' '}
              <code className={isDark ? 'text-white' : 'text-[#1C2621]'}>
                MANAGE_EXTERNAL_STORAGE
              </code>
              .
            </p>
          </div>
          <div className={`rounded-xl border p-3.5 ${subCardCls}`}>
            <p className={`font-semibold ${isDark ? 'text-white' : 'text-[#1C2621]'}`}>
              Private App Files
            </p>
            <p className="mt-1">
              <code className={`font-medium ${isDark ? 'text-[#34D399]' : 'text-[#2E7D5B]'}`}>
                live.srt
              </code>{' '}
              and imported{' '}
              <code className={`font-medium ${isDark ? 'text-[#34D399]' : 'text-[#2E7D5B]'}`}>
                cookies.txt
              </code>{' '}
              live inside{' '}
              <code className={isDark ? 'text-white' : 'text-[#1C2621]'}>context.filesDir</code> for
              instant{' '}
              <code className={`font-medium ${isDark ? 'text-[#60A5FA]' : 'text-[#2B6CB0]'}`}>
                MPVLib
              </code>{' '}
              reload and Colab upload.
            </p>
          </div>
          <div className={`rounded-xl border p-3.5 ${subCardCls}`}>
            <p className={`font-semibold ${isDark ? 'text-white' : 'text-[#1C2621]'}`}>
              Verbatim Worker Asset
            </p>
            <p className="mt-1">
              <code className={`font-medium ${isDark ? 'text-[#60A5FA]' : 'text-[#2B6CB0]'}`}>
                app/src/main/assets/colab_worker.py
              </code>{' '}
              preserves{' '}
              <code className={`font-medium ${isDark ? 'text-[#34D399]' : 'text-[#2E7D5B]'}`}>
                large-v3
              </code>{' '}
              CUDA preload,{' '}
              <code className={isDark ? 'text-white' : 'text-[#1C2621]'}>CLEAN_AUDIO</code> toggle,
              and 20s heartbeats.
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}
