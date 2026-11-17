import { CloudDownload, GitBranch, Smartphone, Terminal } from 'lucide-react';

const STEPS = [
  {
    icon: GitBranch,
    title: '1. Push Project to a Free GitHub Repository',
    body: 'Create a free GitHub account, make a new repository (e.g. openscribe-android), and push this generated project folder to the main branch.',
    code: 'git init && git add . && git commit -m "OpenScribe Android" && git branch -M main && git push -u origin main',
  },
  {
    icon: CloudDownload,
    title: '2. Download Compiled APK from GitHub Actions',
    body: 'Open your repo -> Actions tab -> "Build OpenScribe Android APK". GitHub runs JDK 17, Android SDK, unit tests, and Gradle to produce OpenScribe-Android-APKs (arm64-v8a & universal).',
    code: '.github/workflows/build-apk.yml',
  },
  {
    icon: Smartphone,
    title: '3. Install & One-Time Google Sign-In',
    body: 'Unzip the downloaded artifact, tap app-arm64-v8a-debug.apk on your Android phone, allow Notifications, and tap Settings -> Sign in with Google (Colab) to authorize via sdk.cloud.google.com.',
  },
  {
    icon: Terminal,
    title: '4. Share Any Video -> Watch Now or Download',
    body: 'Allow "Display over other apps" once so Watch Now auto-opens over YouTube. Share a video -> pick Language -> tap Watch Now (smooth live stream + adaptive audio-only subs, auto-opens) or Download (full <=720p MP4 quietly in background).',
  },
];

export function BuildGuideView() {
  return (
    <div className="space-y-4">
      {STEPS.map((step) => {
        const Icon = step.icon;
        return (
          <div
            key={step.title}
            className="rounded-2xl border border-slate-800 bg-[#131A26] p-5"
          >
            <div className="flex items-start gap-3.5">
              <div className="rounded-xl bg-amber-400/10 p-2.5 text-amber-400">
                <Icon className="h-5 w-5" />
              </div>
              <div className="min-w-0 flex-1">
                <h3 className="text-sm font-semibold text-slate-100">{step.title}</h3>
                <p className="mt-1.5 text-xs leading-relaxed text-slate-300">{step.body}</p>
                {step.code && (
                  <pre className="mt-3 overflow-x-auto rounded-xl border border-slate-800 bg-[#0B0F17] p-3 font-mono text-[11px] text-sky-300">
                    {step.code}
                  </pre>
                )}
              </div>
            </div>
          </div>
        );
      })}
    </div>
  );
}
