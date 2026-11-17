import test from 'node:test';
import assert from 'node:assert/strict';
import {
  extractAndUnwrapUrl,
  formatMmSs,
  isTimestampTranslated,
  searchWhisperLanguages,
} from './openscribeLogic';

test('unwraps google goto and normalizes YouTube shorts links', () => {
  const wrapped =
    'Check this out! https://www.google.com/goto?url=https%3A%2F%2Fwww.youtube.com%2Fshorts%2FdQw4w9WgXcQ';
  assert.equal(extractAndUnwrapUrl(wrapped), 'https://www.youtube.com/watch?v=dQw4w9WgXcQ');
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
