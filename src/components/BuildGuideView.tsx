import { CloudDownload, GitBranch, Smartphone, Terminal } from 'lucide-react';

const STEPS = [
  {
    icon: GitBranch,
    title: '1. Push Project to GitHub (SUBCOLAB)',
    body: 'Push this repository to github.com/22-db-22/SUBCOLAB (main branch). GitHub Actions automatically builds and tests the Android APK.',
    code: 'git init && git add . && git commit -m "SUBCOLAB v1.0" && git branch -M main && git push -u origin main',
  },
  {
    icon: CloudDownload,
    title: '2. Download SUBCOLAB-v1.0-arm64.apk from Releases or Actions',
    body: 'Install SUBCOLAB-v1.0-arm64.apk from GitHub Releases (or the Actions workflow artifact) on any Android 8.0+ 64-bit phone.',
    code: '.github/workflows/build-apk.yml',
  },
  {
    icon: Smartphone,
    title: '3. Install & One-Time Google Sign-In',
    body: 'Allow Notifications, open SUBCOLAB -> Settings -> Sign in with Google (Colab). Tokens stay encrypted on your phone with zero developer servers.',
  },
  {
    icon: Terminal,
    title: '4. Share Any Video -> Watch Now or Download',
    body: 'Allow "Display over other apps" once so Watch Now auto-opens over YouTube. Share a video -> pick Language -> tap Watch Now (smooth live stream + adaptive audio-only subs) or Download (full <=720p MP4 quietly in background).',
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
            className="rounded-2xl border border-[#E2DCCD] bg-white p-5 shadow-sm"
          >
            <div className="flex items-start gap-3.5">
              <div className="rounded-xl bg-[#DCEFE5] p-2.5 text-[#2E7D5B]">
                <Icon className="h-5 w-5" />
              </div>
              <div className="min-w-0 flex-1">
                <h3 className="text-sm font-semibold text-[#1C2621]">{step.title}</h3>
                <p className="mt-1.5 text-xs leading-relaxed text-[#5A6860]">{step.body}</p>
                {step.code && (
                  <pre className="mt-3 overflow-x-auto rounded-xl border border-[#E2DCCD] bg-[#FAF7F0] p-3 font-mono text-[11px] text-[#2B6CB0]">
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
