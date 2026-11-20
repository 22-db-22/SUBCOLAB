import test from 'node:test';
import assert from 'node:assert/strict';
import {
  WORKFLOW_MODES,
  chooseSubtitleStrategy,
  computeAdaptiveAudioChunkSec,
  computeResumeOffsetSec,
  extractAndUnwrapUrl,
  filterWhisperHallucination,
  formatMmSs,
  formatSubtitleDelay,
  isTimestampTranslated,
  parseVttOrSrtCues,
  searchWhisperLanguages,
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

