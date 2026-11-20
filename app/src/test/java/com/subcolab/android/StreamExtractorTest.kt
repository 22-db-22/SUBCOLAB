package com.subcolab.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamExtractorTest {

    @Test
    fun `parseSegmentsCsvLine parses valid segment csv rows`() {
        val parsed = StreamExtractor.parseSegmentsCsvLine("chunk_000.ts,0.000000,35.040000")
        assertEquals("chunk_000.ts", parsed?.first)
        assertEquals(0.0, parsed?.second ?: -1.0, 0.001)
        assertEquals(35.04, parsed?.third ?: -1.0, 0.001)
    }

    @Test
    fun `parseSegmentsCsvLine handles malformed or zero-length lines`() {
        assertNull(StreamExtractor.parseSegmentsCsvLine(""))
        assertNull(StreamExtractor.parseSegmentsCsvLine("bad_row"))
        assertNull(StreamExtractor.parseSegmentsCsvLine("chunk_001.ts,10.0,10.0"))
    }

    @Test
    fun `isCodecMpegTsCompatible detects h264 plus aac vs vp9 opus`() {
        assertTrue(StreamExtractor.isCodecMpegTsCompatible("avc1.64001F", "mp4a.40.2"))
        assertTrue(StreamExtractor.isCodecMpegTsCompatible("h264", null))
        assertFalse(StreamExtractor.isCodecMpegTsCompatible("vp9", "opus"))
        assertFalse(StreamExtractor.isCodecMpegTsCompatible("av01.0.05M.08", "mp4a.40.2"))
    }

    @Test
    fun `computeAdaptiveAudioChunkSec scales smoothly by video duration`() {
        assertEquals(20, StreamExtractor.computeAdaptiveAudioChunkSec(0.0))
        assertEquals(15, StreamExtractor.computeAdaptiveAudioChunkSec(90.0))
        assertEquals(15, StreamExtractor.computeAdaptiveAudioChunkSec(180.0))
        assertEquals(25, StreamExtractor.computeAdaptiveAudioChunkSec(600.0))
        assertEquals(40, StreamExtractor.computeAdaptiveAudioChunkSec(1800.0))
        assertEquals(60, StreamExtractor.computeAdaptiveAudioChunkSec(3600.0))
    }

    @Test
    fun `selectSubtitleStrategy prefers creator English then matching non-English then Whisper`() {
        val enVtt = SubtitleTrackCandidate("en", "vtt", "https://example.com/en.vtt")
        val enSrt = SubtitleTrackCandidate("en-US", "srt", "https://example.com/en.srt")
        val koVtt = SubtitleTrackCandidate("ko", "vtt", "https://example.com/ko.vtt")
        val jaSrt = SubtitleTrackCandidate("ja", "srt", "https://example.com/ja.srt")

        val directEn = StreamExtractor.selectSubtitleStrategy(listOf(koVtt, enVtt, enSrt), "ko")
        assertTrue(directEn is SubtitleStrategy.UseDirectEnglish)
        assertEquals("https://example.com/en.vtt", (directEn as SubtitleStrategy.UseDirectEnglish).track.url)

        val translateMatch = StreamExtractor.selectSubtitleStrategy(listOf(koVtt, jaSrt), "ko")
        assertTrue(translateMatch is SubtitleStrategy.TranslateManualSubtitles)
        assertEquals("ko", (translateMatch as SubtitleStrategy.TranslateManualSubtitles).sourceLang)

        val translateAuto = StreamExtractor.selectSubtitleStrategy(listOf(koVtt, jaSrt), "auto")
        assertTrue(translateAuto is SubtitleStrategy.TranslateManualSubtitles)
        assertEquals("ja", (translateAuto as SubtitleStrategy.TranslateManualSubtitles).sourceLang)

        val fallback = StreamExtractor.selectSubtitleStrategy(emptyList(), "auto")
        assertTrue(fallback is SubtitleStrategy.UseWhisperGpu)
    }

    @Test
    fun `parseGoogleTranslateBatchJson extracts joined translated text`() {
        val sample = """[[["Hello world\n","안녕하세요\n",null,null,1],["How are you?","잘 지내요?",null,null,1]],null,"ko"]"""
        assertEquals("Hello world\nHow are you?", StreamExtractor.parseGoogleTranslateBatchJson(sample))
        assertNull(StreamExtractor.parseGoogleTranslateBatchJson("not-json"))
    }
}

