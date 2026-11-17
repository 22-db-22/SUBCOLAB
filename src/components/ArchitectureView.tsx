import { Cpu, FileCode2, Layers, ShieldCheck } from 'lucide-react';

const MODULES = [
  {
    name: 'Embedded Player (dev.jdtech.mpv:libmpv:1.0.0)',
    path: 'app/src/main/java/com/openscribe/android/player/MpvPlayerController.kt',
    summary:
      'Hardware-decoded SurfaceView player with yellow subtitles (#FFFF00), split audio-file support, brightness/volume swipes, true immersive fullscreen, in-player background Save, PiP, and atomic sub-reload.',
  },
  {
    name: 'Stream Extractor (youtubedl-android:0.18.1)',
    path: 'app/src/main/java/com/openscribe/android/media/StreamExtractor.kt',
    summary:
      'Resolves <=720p combined streams across ios/mweb/tv_embedded/default, falls back to split 720p or 360p format 18, and handles Phone Wi-Fi Audio Relay on @@OPENSCRIBE_BOT_BLOCKED@@.',
  },
  {
    name: 'Colab OAuth2 & Jupyter Client (google-colab-cli)',
    path: 'app/src/main/java/com/openscribe/android/colab/ColabClient.kt',
    summary:
      'EncryptedSharedPreferences OAuth2 (sdk.cloud.google.com primary + optional custom Client ID loopback), T4 /tun/m/assign XSRF + 4s warmup, /api/contents upload, OkHttp WebSocket kernel execution, 20s heartbeat, and auto-recover.',
  },
  {
    name: 'Foreground Service & Subtitle Engine',
    path: 'app/src/main/java/com/openscribe/android/service/OpenScribeForegroundService.kt',
    summary:
      'Acquires PARTIAL_WAKE_LOCK, streams colab_worker.py verbatim from assets/, auto-opens player at 4 cues, handles Seek-Ahead (/content/openscribe_seek.txt), and saves MP4/SRT/ASS/VTT via MediaStore.',
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
