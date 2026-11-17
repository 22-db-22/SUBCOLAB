import { useState } from 'react';
import { BookOpen, Cpu, ExternalLink, Smartphone } from 'lucide-react';
import { ArchitectureView } from './components/ArchitectureView';
import { BuildGuideView } from './components/BuildGuideView';
import { PhonePreview } from './components/PhonePreview';

type TabId = 'preview' | 'architecture' | 'guide';

function OpenScribeLogo({ className = 'h-10 w-10' }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 108 108"
      className={`${className} rounded-xl shadow-sm`}
      aria-hidden="true"
    >
      <rect width="108" height="108" fill="#2E7D5B" />
      <circle cx="54" cy="54" r="44" fill="#368E68" />
      <circle cx="54" cy="54" r="36" fill="#276E50" />
      <rect
        x="31"
        y="30"
        width="46"
        height="32"
        rx="5"
        fill="none"
        stroke="#DCEFE5"
        strokeWidth="3"
      />
      <path
        d="M48.5,38.5 C48.5,37.0 50.2,36.1 51.5,36.9 L63.2,44.4 C64.5,45.2 64.5,47.0 63.2,47.8 L51.5,55.3 C50.2,56.1 48.5,55.2 48.5,53.7 Z"
        fill="#FAF7F0"
      />
      <rect x="31.8" y="68.5" width="44.4" height="5.4" rx="2.7" fill="#FAF7F0" />
      <rect x="39.7" y="77.2" width="28.6" height="4.6" rx="2.3" fill="#7CC4FA" />
    </svg>
  );
}

export default function App() {
  const [activeTab, setActiveTab] = useState<TabId>('preview');

  return (
    <div className="min-h-screen bg-[#FAF7F0] text-[#1C2621]">
      <header className="border-b border-[#E2DCCD] bg-[#FAF7F0]/95 backdrop-blur">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-4 px-4 py-4 sm:px-6">
          <div className="flex items-center gap-3">
            <OpenScribeLogo className="h-10 w-10" />
            <div>
              <h1 className="text-lg font-bold tracking-tight text-[#1C2621]">
                OpenScribe Android
              </h1>
              <p className="text-xs text-[#5A6860]">
                Kotlin + Jetpack Compose + Material 3 • Single-APK Translator &amp; Player
              </p>
            </div>
          </div>

          <nav aria-label="Workspace views" className="flex flex-wrap gap-2">
            <button
              type="button"
              onClick={() => setActiveTab('preview')}
              className={`inline-flex items-center gap-2 rounded-xl px-3.5 py-2 text-xs font-semibold transition ${
                activeTab === 'preview'
                  ? 'bg-[#2E7D5B] text-white shadow-sm'
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
                  ? 'bg-[#2E7D5B] text-white shadow-sm'
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
                  ? 'bg-[#2E7D5B] text-white shadow-sm'
                  : 'border border-[#E2DCCD] bg-white text-[#5A6860] hover:bg-[#F1ECE1] hover:text-[#1C2621]'
              }`}
            >
              <BookOpen className="h-4 w-4" />
              GitHub APK Guide
            </button>
          </nav>
        </div>
      </header>

      <main className="mx-auto max-w-6xl px-4 py-6 sm:px-6">
        {activeTab === 'preview' && <PhonePreview />}
        {activeTab === 'architecture' && <ArchitectureView />}
        {activeTab === 'guide' && <BuildGuideView />}
      </main>

      <footer className="mt-8 border-t border-[#E2DCCD] bg-[#F1ECE1]/60">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-3 px-4 py-4 text-xs text-[#5A6860] sm:px-6">
          <div>
            <span className="font-semibold text-[#1C2621]">OpenScribe Android v1.0.0</span>
            {' • '}Created by{' '}
            <span className="font-medium text-[#1C2621]">writtenwithharmony@gmail.com</span>
          </div>
          <a
            href="https://github.com/22-db-22/openscribe-android"
            target="_blank"
            rel="noreferrer"
            className="inline-flex items-center gap-1.5 rounded-lg border border-[#5B9BD5]/40 bg-[#DDF0FB] px-3 py-1.5 font-semibold text-[#2B6CB0] transition hover:bg-[#cde6f8]"
          >
            github.com/22-db-22/openscribe-android
            <ExternalLink className="h-3.5 w-3.5" />
          </a>
        </div>
      </footer>
    </div>
  );
}
