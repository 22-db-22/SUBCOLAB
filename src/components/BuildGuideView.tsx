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
    body: 'Allow "Display over other apps" once so Watch Now auto-opens over source apps. Share from Dailymotion, Bilibili, Vimeo, TikTok, Twitch, NicoNico, browsers, or YouTube -> pick Language -> tap Watch Now (smooth live stream + adaptive audio-only subs) or Download (full <=720p MP4 quietly in background).',
  },
];

export function BuildGuideView({ isDark = false }: { isDark?: boolean }) {
  return (
    <div className="space-y-4">
      {STEPS.map((step) => {
        const Icon = step.icon;
        return (
          <div
            key={step.title}
            className={`rounded-2xl border p-5 shadow-sm ${
              isDark ? 'border-[#283449] bg-[#131A26]' : 'border-[#E2DCCD] bg-white'
            }`}
          >
            <div className="flex items-start gap-3.5">
              <div
                className={`rounded-xl p-2.5 ${
                  isDark ? 'bg-[#172554] text-[#60A5FA]' : 'bg-[#DCEFE5] text-[#2E7D5B]'
                }`}
              >
                <Icon className="h-5 w-5" />
              </div>
              <div className="min-w-0 flex-1">
                <h3
                  className={`text-sm font-semibold ${
                    isDark ? 'text-white' : 'text-[#1C2621]'
                  }`}
                >
                  {step.title}
                </h3>
                <p
                  className={`mt-1.5 text-xs leading-relaxed ${
                    isDark ? 'text-[#94A3B8]' : 'text-[#5A6860]'
                  }`}
                >
                  {step.body}
                </p>
                {step.code && (
                  <pre
                    className={`mt-3 overflow-x-auto rounded-xl border p-3 font-mono text-[11px] ${
                      isDark
                        ? 'border-[#283449] bg-[#0B0F17] text-[#60A5FA]'
                        : 'border-[#E2DCCD] bg-[#FAF7F0] text-[#2B6CB0]'
                    }`}
                  >
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
