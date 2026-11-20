import test from 'node:test';
import assert from 'node:assert/strict';
import {
  DEFAULT_SHARE_SUBTITLE_OPTIONS,
  QUICK_TIPS_DISMISSED_KEY,
  WHISPER_MODELS,
  WORKFLOW_MODES,
  chooseSubtitleStrategy,
  completeActiveAndPromoteQueue,
  computeAdaptiveAudioChunkSec,
  computeResumeOffsetSec,
  createInitialQueueState,
  extractAndUnwrapUrl,
  filterWhisperHallucination,
  formatMmSs,
  formatSubtitleDelay,
  hideReadyOffer,
  isTimestampTranslated,
  normalizeWhisperModel,
  onActiveJobMediaReady,
  parseVttOrSrtCues,
  persistQuickTipsDismissed,
  readQuickTipsDismissed,
  removeQueuedJobById,
  searchWhisperLanguages,
  submitJobToQueueState,
  switchToReadyOffer,
} from './subcolabLogic';

test('unwraps google goto and normalizes YouTube shorts and live links', () => {
  const wrapped =
    'Check this out! https://www.google.com/goto?url=https%3A%2F%2Fwww.youtube.com%2Fshorts%2FdQw4w9WgXcQ';
  assert.equal(extractAndUnwrapUrl(wrapped), 'https://www.youtube.com/watch?v=dQw4w9WgXcQ');

  const liveUrl = 'https://www.youtube.com/live/jfKfPfyJRdk?feature=share';
  assert.equal(extractAndUnwrapUrl(liveUrl), 'https://www.youtube.com/watch?v=jfKfPfyJRdk');
});

test('searches Whisper languages by name, ISO code, and alias', () => {
  assert.equal(searchWhisperLanguages('hangul').some((l) => l.code === 'ko'), true);
  assert.equal(searchWhisperLanguages('mandarin').some((l) => l.code === 'zh'), true);
  assert.equal(searchWhisperLanguages('yue').some((l) => l.code === 'yue'), true);
});

test('detects seek-ahead coverage against completed Colab chunks', () => {
  const chunks = [
    { startSec: 0, endSec: 45 },
    { startSec: 45, endSec: 90 },
  ];
  assert.equal(isTimestampTranslated(32, chunks), true);
  assert.equal(isTimestampTranslated(185, chunks), false);
  assert.equal(formatMmSs(185), '03:05');
});

test('computes adaptive audio-only chunk durations by video length', () => {
  assert.equal(computeAdaptiveAudioChunkSec(0), 20);
  assert.equal(computeAdaptiveAudioChunkSec(120), 15);
  assert.equal(computeAdaptiveAudioChunkSec(600), 25);
  assert.equal(computeAdaptiveAudioChunkSec(1800), 40);
  assert.equal(computeAdaptiveAudioChunkSec(3600), 60);
});

test('exposes Watch Now and Download workflow modes', () => {
  const watchNow = WORKFLOW_MODES.find((m) => m.id === 'WATCH_NOW');
  const download = WORKFLOW_MODES.find((m) => m.id === 'DOWNLOAD_AND_TRANSLATE');
  assert.equal(watchNow?.opensPlayer, true);
  assert.equal(download?.opensPlayer, false);
  assert.equal(download?.savesOfflinePack, true);
});

test('chooses Smart Subtitles decision path (direct EN > manual translate > Whisper GPU)', () => {
  const ko = { lang: 'ko', ext: 'vtt', url: 'https://cdn.example/ko.vtt' };
  const ja = { lang: 'ja', ext: 'srt', url: 'https://cdn.example/ja.srt' };
  const en = { lang: 'en', ext: 'vtt', url: 'https://cdn.example/en.vtt' };

  assert.equal(chooseSubtitleStrategy([ko, en], 'ko').kind, 'USE_DIRECT_ENGLISH');
  const koPick = chooseSubtitleStrategy([ko, ja], 'ko');
  assert.equal(koPick.kind, 'TRANSLATE_MANUAL');
  if (koPick.kind === 'TRANSLATE_MANUAL') {
    assert.equal(koPick.sourceLang, 'ko');
  }
  assert.equal(chooseSubtitleStrategy([], 'auto').kind, 'USE_WHISPER_GPU');
});

test('parses WebVTT captions and filters Whisper hallucinations cleanly', () => {
  const sampleVtt = `WEBVTT\n\n00:01.000 --> 00:03.500\n<i>Welcome &amp; enjoy!</i>\n\n00:04.000 --> 00:06.000\n{\\an8}Ramen time`;
  const cues = parseVttOrSrtCues(sampleVtt);
  assert.equal(cues.length, 2);
  assert.equal(cues[0].text, 'Welcome & enjoy!');
  assert.equal(cues[1].text, 'Ramen time');

  assert.equal(filterWhisperHallucination('Thanks for watching.'), '');
  assert.equal(filterWhisperHallucination('[Music]'), '');
  assert.equal(filterWhisperHallucination('Subtitles by Amara.org'), '');
  assert.equal(filterWhisperHallucination('yeah yeah yeah yeah yeah yeah'), '');
  assert.equal(filterWhisperHallucination('[sigh] Let us order ramen now.'), 'Let us order ramen now.');
});

test('computes checkpoint resume offset and formats subtitle delay', () => {
  assert.equal(computeResumeOffsetSec(false, [{ startSec: 0, endSec: 50 }], 0), 50);
  assert.equal(computeResumeOffsetSec(true, [{ startSec: 0, endSec: 50 }], 50), 0);
  assert.equal(computeResumeOffsetSec(false, [{ startSec: 0, endSec: 5 }], 0), 0);
  assert.equal(formatSubtitleDelay(0), '0.0s');
  assert.equal(formatSubtitleDelay(1.5), '+1.5s');
  assert.equal(formatSubtitleDelay(-0.5), '-0.5s');
});

test('restricts Whisper models to large-v3 (default) and large-v3-turbo with safe share defaults', () => {
  assert.deepEqual(
    WHISPER_MODELS.map((m) => m.id),
    ['large-v3', 'large-v3-turbo'],
  );
  assert.equal(normalizeWhisperModel('medium'), 'large-v3');
  assert.equal(normalizeWhisperModel('turbo'), 'large-v3-turbo');
  assert.equal(normalizeWhisperModel('large-v3-turbo'), 'large-v3-turbo');
  assert.equal(normalizeWhisperModel(''), 'large-v3');
  assert.equal(DEFAULT_SHARE_SUBTITLE_OPTIONS.modelId, 'large-v3');
  assert.equal(DEFAULT_SHARE_SUBTITLE_OPTIONS.cleanAudio, false);
  assert.equal(DEFAULT_SHARE_SUBTITLE_OPTIONS.vocalBoost, false);
  assert.equal(DEFAULT_SHARE_SUBTITLE_OPTIONS.highPrecisionBeam, false);
  assert.equal(DEFAULT_SHARE_SUBTITLE_OPTIONS.includeOriginal, false);
});

test('queues jobs sequentially, promotes Queued No. 1, and offers non-interrupting Watch Now / Hide', () => {
  let qState = createInitialQueueState();

  // Start Job 1 (Watch Now) immediately
  const r1 = submitJobToQueueState(qState, {
    id: 'job1',
    url: 'https://www.dailymotion.com/video/video1',
    title: 'Video 1',
    mode: 'WATCH_NOW',
    options: DEFAULT_SHARE_SUBTITLE_OPTIONS,
  });
  qState = r1.state;
  assert.equal(r1.queuePosition, 0);
  assert.equal(qState.activeJob?.id, 'job1');

  // Job 1 ready -> auto-opens player
  qState = onActiveJobMediaReady(qState, 4);
  assert.equal(qState.shouldAutoOpenPlayer, true);
  assert.equal(qState.playingVideo?.id, 'job1');

  // Queue Job 2 (Watch Now) -> Queued No. 1
  const r2 = submitJobToQueueState(qState, {
    id: 'job2',
    url: 'https://vimeo.com/222222',
    title: 'Video 2',
    mode: 'WATCH_NOW',
    options: { ...DEFAULT_SHARE_SUBTITLE_OPTIONS, modelId: 'large-v3-turbo', vocalBoost: true },
  });
  qState = r2.state;
  assert.equal(r2.queuePosition, 1);

  // Queue Job 3 (Download) -> Queued No. 2
  const r3 = submitJobToQueueState(qState, {
    id: 'job3',
    url: 'https://www.bilibili.com/video/BV3',
    title: 'Video 3',
    mode: 'DOWNLOAD_AND_TRANSLATE',
    options: DEFAULT_SHARE_SUBTITLE_OPTIONS,
  });
  qState = r3.state;
  assert.equal(r3.queuePosition, 2);
  assert.equal(qState.queuedJobs.length, 2);

  // Job 1 finishes downloading+translating -> Queued No. 1 promotes to activeJob, Queued No. 2 shifts to 1st
  qState = completeActiveAndPromoteQueue(qState, 5);
  assert.equal(qState.activeJob?.id, 'job2');
  assert.equal(qState.activeJob?.isFromQueue, true);
  assert.equal(qState.queuedJobs.length, 1);
  assert.equal(qState.queuedJobs[0].queueId, 'job3');

  // Job 2 becomes ready -> must NOT interrupt Video 1, emits ReadyQueuedOffer instead
  qState = onActiveJobMediaReady(qState, 4);
  assert.equal(qState.shouldAutoOpenPlayer, false);
  assert.equal(qState.playingVideo?.id, 'job1');
  assert.equal(qState.readyOffers.length, 1);
  assert.equal(qState.readyOffers[0].queueId, 'job2');

  // User clicks Watch Now -> switches playback from Video 1 to Video 2 and clears offer
  qState = switchToReadyOffer(qState, 'job2');
  assert.equal(qState.playingVideo?.id, 'job2');
  assert.equal(qState.readyOffers.length, 0);

  // Job 2 finishes -> Job 3 (Download) promotes to active, emits NO Watch Now offer when media ready
  qState = completeActiveAndPromoteQueue(qState, 5);
  assert.equal(qState.activeJob?.id, 'job3');
  qState = onActiveJobMediaReady(qState, 4);
  assert.equal(qState.readyOffers.length, 0);
  assert.equal(qState.playingVideo?.id, 'job2');

  // Removing an item & hiding an offer work cleanly
  const r4 = submitJobToQueueState(qState, {
    id: 'job4',
    url: 'https://vimeo.com/444',
    title: 'Video 4',
    mode: 'WATCH_NOW',
    options: DEFAULT_SHARE_SUBTITLE_OPTIONS,
  });
  qState = removeQueuedJobById(r4.state, 'job4');
  assert.equal(qState.queuedJobs.length, 0);

  qState = {
    ...qState,
    readyOffers: [{ queueId: 'jobX', title: 'X', url: 'https://vimeo.com/9', cueCount: 4, modelId: 'large-v3' }],
  };
  qState = hideReadyOffer(qState, 'jobX');
  assert.equal(qState.readyOffers.length, 0);
});

test('persists Quick Tips dismissal across reloads', () => {
  const map = new Map<string, string>();
  const mockStorage = {
    getItem: (k: string) => map.get(k) ?? null,
    setItem: (k: string, v: string) => {
      map.set(k, v);
    },
  };
  assert.equal(readQuickTipsDismissed(mockStorage), false);
  persistQuickTipsDismissed(mockStorage);
  assert.equal(map.get(QUICK_TIPS_DISMISSED_KEY), '1');
  assert.equal(readQuickTipsDismissed(mockStorage), true);
});


