import { useState } from 'react';
import { BookOpen, Cpu, Smartphone, Subtitles } from 'lucide-react';
import { ArchitectureView } from './components/ArchitectureView';
import { BuildGuideView } from './components/BuildGuideView';
import { PhonePreview } from './components/PhonePreview';

type TabId = 'preview' | 'architecture' | 'guide';

export default function App() {
  const [activeTab, setActiveTab] = useState<TabId>('preview');

  return (
    <div className="min-h-screen bg-[#0B0F17] text-slate-50">
      <header className="border-b border-slate-800/80 bg-[#0B0F17]/95 backdrop-blur">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-4 px-4 py-4 sm:px-6">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-amber-400 text-slate-950">
              <Subtitles className="h-5 w-5" />
            </div>
            <div>
              <h1 className="text-lg font-bold tracking-tight text-slate-50">
                OpenScribe Android
              </h1>
              <p className="text-xs text-slate-400">
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
                  ? 'bg-amber-400 text-slate-950'
                  : 'border border-slate-800 bg-[#131A26] text-slate-300 hover:bg-slate-800'
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
                  ? 'bg-amber-400 text-slate-950'
                  : 'border border-slate-800 bg-[#131A26] text-slate-300 hover:bg-slate-800'
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
                  ? 'bg-amber-400 text-slate-950'
                  : 'border border-slate-800 bg-[#131A26] text-slate-300 hover:bg-slate-800'
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
    </div>
  );
}
