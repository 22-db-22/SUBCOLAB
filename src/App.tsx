import { useState } from 'react';
import { BookOpen, Cpu, ExternalLink, Moon, Smartphone, Sun } from 'lucide-react';
import { ArchitectureView } from './components/ArchitectureView';
import { BuildGuideView } from './components/BuildGuideView';
import { PhonePreview } from './components/PhonePreview';

type TabId = 'preview' | 'architecture' | 'guide';

function SubcolabLogo({ className = 'h-10 w-10' }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 108 108"
      className={`${className} rounded-xl shadow-sm`}
      aria-hidden="true"
    >
      <rect width="108" height="108" fill="#1D4ED8" />
      <circle cx="54" cy="54" r="44" fill="#2563EB" />
      <circle cx="54" cy="54" r="36" fill="#1E3A8A" />
      <rect
        x="30"
        y="30"
        width="48"
        height="32"
        rx="6"
        fill="#EF4444"
        stroke="#DC2626"
        strokeWidth="1.5"
      />
      <path
        d="M48.5,38.5 C48.5,37.0 50.2,36.1 51.5,36.9 L63.5,44.4 C64.8,45.2 64.8,47.0 63.5,47.8 L51.5,55.3 C50.2,56.1 48.5,55.2 48.5,53.7 Z"
        fill="#FFFFFF"
      />
      <rect x="31.8" y="68.5" width="44.4" height="5.4" rx="2.7" fill="#EF4444" />
      <rect x="39.7" y="77.2" width="28.6" height="4.6" rx="2.3" fill="#60A5FA" />
    </svg>
  );
}

export default function App() {
  const [activeTab, setActiveTab] = useState<TabId>('preview');
  const [isDark, setIsDark] = useState(false);

  return (
    <div
      className={`min-h-screen transition-colors ${
        isDark ? 'bg-[#0B0F17] text-[#F8FAFC]' : 'bg-[#FAF7F0] text-[#1C2621]'
      }`}
    >
      <header
        className={`border-b backdrop-blur ${
          isDark
            ? 'border-[#283449] bg-[#0B0F17]/95'
            : 'border-[#E2DCCD] bg-[#FAF7F0]/95'
        }`}
      >
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-4 px-4 py-4 sm:px-6">
          <div className="flex items-center gap-3">
            <SubcolabLogo className="h-10 w-10" />
            <div>
              <div className="flex items-center gap-2">
                <h1 className="text-lg font-bold tracking-tight">SUBCOLAB</h1>
                <span
                  className={`rounded-md px-1.5 py-0.5 text-[10px] font-bold ${
                    isDark
                      ? 'bg-[#172554] text-[#60A5FA]'
                      : 'bg-[#DCEFE5] text-[#2E7D5B]'
                  }`}
                >
                  v1.0
                </span>
              </div>
              <p className={`text-xs ${isDark ? 'text-[#94A3B8]' : 'text-[#5A6860]'}`}>
                Kotlin + Jetpack Compose + Material 3 • Single-APK Translator &amp; Player
              </p>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <nav aria-label="Workspace views" className="flex flex-wrap gap-2">
              <button
                type="button"
                onClick={() => setActiveTab('preview')}
                className={`inline-flex items-center gap-2 rounded-xl px-3.5 py-2 text-xs font-semibold transition ${
                  activeTab === 'preview'
                    ? isDark
                      ? 'bg-[#2563EB] text-white shadow-sm'
                      : 'bg-[#2E7D5B] text-white shadow-sm'
                    : isDark
                      ? 'border border-[#283449] bg-[#131A26] text-[#94A3B8] hover:bg-[#1B2434] hover:text-white'
                      : 'border border-[#E2DCCD] bg-white text-[#5A6860] hover:bg-[#F1ECE1] hover:text-[#1C2621]'
                }`}
              >
                <Smartphone className="h-4 w-4" />
                App Preview
              </button>
              <button
                type="button"
                onClick={() => setActiveTab('architecture')}
                className={`inline-flex items-center gap-2 rounded-xl px-3.5 py-2 text-xs font-semibold transition ${
                  activeTab === 'architecture'
                    ? isDark
                      ? 'bg-[#2563EB] text-white shadow-sm'
                      : 'bg-[#2E7D5B] text-white shadow-sm'
                    : isDark
                      ? 'border border-[#283449] bg-[#131A26] text-[#94A3B8] hover:bg-[#1B2434] hover:text-white'
                      : 'border border-[#E2DCCD] bg-white text-[#5A6860] hover:bg-[#F1ECE1] hover:text-[#1C2621]'
                }`}
              >
                <Cpu className="h-4 w-4" />
                Android Stack
              </button>
              <button
                type="button"
                onClick={() => setActiveTab('guide')}
                className={`inline-flex items-center gap-2 rounded-xl px-3.5 py-2 text-xs font-semibold transition ${
                  activeTab === 'guide'
                    ? isDark
                      ? 'bg-[#2563EB] text-white shadow-sm'
                      : 'bg-[#2E7D5B] text-white shadow-sm'
                    : isDark
                      ? 'border border-[#283449] bg-[#131A26] text-[#94A3B8] hover:bg-[#1B2434] hover:text-white'
                      : 'border border-[#E2DCCD] bg-white text-[#5A6860] hover:bg-[#F1ECE1] hover:text-[#1C2621]'
                }`}
              >
                <BookOpen className="h-4 w-4" />
                Release Guide
              </button>
            </nav>

            <button
              type="button"
              onClick={() => setIsDark((d) => !d)}
              aria-label={isDark ? 'Switch to Light Mode' : 'Switch to Dark Mode'}
              className={`inline-flex items-center gap-1.5 rounded-xl border px-3 py-2 text-xs font-semibold transition ${
                isDark
                  ? 'border-[#283449] bg-[#131A26] text-[#60A5FA] hover:bg-[#1B2434]'
                  : 'border-[#E2DCCD] bg-white text-[#1C2621] hover:bg-[#F1ECE1]'
              }`}
            >
              {isDark ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
              {isDark ? 'Light' : 'Dark'}
            </button>
          </div>
        </div>
      </header>

      <main className="mx-auto max-w-6xl px-4 py-6 sm:px-6">
        {activeTab === 'preview' && <PhonePreview isDark={isDark} />}
        {activeTab === 'architecture' && <ArchitectureView />}
        {activeTab === 'guide' && <BuildGuideView />}
      </main>

      <footer
        className={`mt-8 border-t ${
          isDark
            ? 'border-[#283449] bg-[#131A26]/70 text-[#94A3B8]'
            : 'border-[#E2DCCD] bg-[#F1ECE1]/60 text-[#5A6860]'
        }`}
      >
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-3 px-4 py-4 text-xs sm:px-6">
          <div>
            <span className={`font-semibold ${isDark ? 'text-white' : 'text-[#1C2621]'}`}>
              SUBCOLAB v1.0
            </span>
            {' • '}GPL-3.0 • Based on OpenScribe (MIT) by Faisal Saddique • Created by{' '}
            <span className={`font-medium ${isDark ? 'text-white' : 'text-[#1C2621]'}`}>
              writtenwithharmony@gmail.com
            </span>
          </div>
          <a
            href="https://github.com/22-db-22/SUBCOLAB"
            target="_blank"
            rel="noreferrer"
            className={`inline-flex items-center gap-1.5 rounded-lg border px-3 py-1.5 font-semibold transition ${
              isDark
                ? 'border-[#38BDF8]/40 bg-[#0C2A3E] text-[#60A5FA] hover:bg-[#103852]'
                : 'border-[#5B9BD5]/40 bg-[#DDF0FB] text-[#2B6CB0] hover:bg-[#cde6f8]'
            }`}
          >
            github.com/22-db-22/SUBCOLAB
            <ExternalLink className="h-3.5 w-3.5" />
          </a>
        </div>
      </footer>
    </div>
  );
}
