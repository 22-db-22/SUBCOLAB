import { Cpu, FileCode2, Layers, ShieldCheck } from 'lucide-react';

const MODULES = [
  {
    name: 'Embedded Live Player (dev.jdtech.mpv:libmpv:0.5.1)',
    path: 'app/src/main/java/com/openscribe/android/player/MpvPlayerController.kt',
    summary:
      'Hardware-decoded SurfaceView player streaming smoothly via LoopbackMediaProxy (64MiB cache, 30s readahead, split DASH audio-add) with zero chunk cuts, live sub-reload, gestures, fullscreen, and PiP.',
  },
  {
    name: 'Live Proxy & Adaptive Audio Chunker (youtubedl-android:0.18.1)',
    path: 'app/src/main/java/com/openscribe/android/media/StreamExtractor.kt',
    summary:
      'Resolves direct video + audio streams via yt-dlp, serves them to libmpv over OkHttp LoopbackMediaProxy (Range + HLS M3U8), and slices ONLY audio (15s–60s adaptive 24kbps AAC) so live video is never slowed down.',
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
      'Floating overlay offers Watch Now (smooth live stream + auto-open over YouTube via SYSTEM_ALERT_WINDOW / full-screen intent) and Download (full regular <=720p MP4 quietly in background).',
  },
];

export function ArchitectureView() {
  return (
    <div className="space-y-6">
      <div className="grid gap-4 md:grid-cols-2">
        {MODULES.map((mod) => (
          <div
            key={mod.name}
            className="rounded-2xl border border-slate-800 bg-[#131A26] p-5"
          >
            <div className="flex items-start gap-3">
              <div className="rounded-xl bg-sky-500/10 p-2.5 text-sky-400">
                <Layers className="h-5 w-5" />
              </div>
              <div>
                <h3 className="text-sm font-semibold text-slate-100">{mod.name}</h3>
                <p className="mt-1 font-mono text-[11px] text-amber-300">{mod.path}</p>
                <p className="mt-2 text-xs leading-relaxed text-slate-300">{mod.summary}</p>
              </div>
            </div>
          </div>
        ))}
      </div>

      <div className="rounded-2xl border border-slate-800 bg-[#131A26] p-5">
        <div className="flex items-center gap-2.5 text-sm font-semibold text-slate-100">
          <ShieldCheck className="h-5 w-5 text-emerald-400" />
          Zero Termux, Zero External mpv-android, Zero All Files Access
        </div>
        <div className="mt-3 grid gap-3 text-xs text-slate-300 sm:grid-cols-3">
          <div className="rounded-xl border border-slate-800 bg-[#0B0F17] p-3.5">
            <p className="font-semibold text-slate-100">Scoped Downloads</p>
            <p className="mt-1 text-slate-400">
              Uses Android <code className="text-sky-300">MediaStore.Downloads</code> (
              <code className="text-slate-200">Download/OpenScribe/</code>) on API 29+ without{' '}
              <code className="text-slate-200">MANAGE_EXTERNAL_STORAGE</code>.
            </p>
          </div>
          <div className="rounded-xl border border-slate-800 bg-[#0B0F17] p-3.5">
            <p className="font-semibold text-slate-100">Private App Files</p>
            <p className="mt-1 text-slate-400">
              <code className="text-amber-300">live.srt</code> and imported{' '}
              <code className="text-amber-300">cookies.txt</code> live inside{' '}
              <code className="text-slate-200">context.filesDir</code> for instant{' '}
              <code className="text-sky-300">MPVLib</code> reload and Colab upload.
            </p>
          </div>
          <div className="rounded-xl border border-slate-800 bg-[#0B0F17] p-3.5">
            <p className="font-semibold text-slate-100">Verbatim Worker Asset</p>
            <p className="mt-1 text-slate-400">
              <code className="text-sky-300">app/src/main/assets/colab_worker.py</code> preserves{' '}
              <code className="text-amber-300">large-v3</code> CUDA preload,{' '}
              <code className="text-slate-200">CLEAN_AUDIO</code> toggle, and 20s heartbeats.
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}
