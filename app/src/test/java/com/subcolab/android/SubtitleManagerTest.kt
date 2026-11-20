package com.subcolab.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleManagerTest {

    @Test
    fun `parses streaming cues in order and exports srt vtt ass`() {
        val mgr = SubtitleManager()
        val lines = listOf(
            "@@SUBCOLAB_TITLE@@ Cooking_Ramen_In_Tokyo",
            "@@SUBCOLAB_SRT_BEGIN@@ 45000",
            "2",
            "00:00:45,000 --> 00:00:48,500",
            "Second chunk arrived first!",
            "@@SUBCOLAB_SRT_END@@",
            "@@SUBCOLAB_SRT_BEGIN@@ 1200",
            "1",
            "00:00:01,200 --> 00:00:03,800",
            "Welcome to Tokyo!",
            "@@SUBCOLAB_SRT_END@@",
            "@@SUBCOLAB_CHUNK_END@@ 0.00 45.00",
        )
        lines.forEach { mgr.processLine(it) }

        assertEquals("Cooking_Ramen_In_Tokyo", mgr.latestTitleSlug)
        assertEquals(2, mgr.cueCount())

        val sorted = mgr.getSortedCues()
        assertEquals(1200L, sorted[0].startMs)
        assertEquals(45000L, sorted[1].startMs)

        assertTrue(mgr.isTimeTranslated(20.0))
        assertFalse(mgr.isTimeTranslated(120.0))

        val srt = mgr.buildSrtContent()
        assertTrue(srt.contains("00:00:01,200 --> 00:00:03,800"))

        val vtt = mgr.buildVttContent()
        assertTrue(vtt.startsWith("WEBVTT"))
        assertTrue(vtt.contains("00:00:01.200 --> 00:00:03.800"))

        val ass = mgr.buildAssContent("Test")
        assertTrue(ass.contains("[Script Info]"))
        assertTrue(ass.contains("Dialogue: 0,0:00:01.20,0:00:03.80"))
    }

    @Test
    fun `preserves distinct cues sharing identical startMs`() {
        val mgr = SubtitleManager()
        listOf(
            "@@SUBCOLAB_SRT_BEGIN@@ 5000",
            "1",
            "00:00:05,000 --> 00:00:06,000",
            "First line",
            "@@SUBCOLAB_SRT_END@@",
            "@@SUBCOLAB_SRT_BEGIN@@ 5000",
            "2",
            "00:00:05,000 --> 00:00:07,000",
            "Second line",
            "@@SUBCOLAB_SRT_END@@",
        ).forEach { mgr.processLine(it) }

        val cues = mgr.getSortedCues()
        assertEquals(2, cues.size)
        assertEquals("First line", cues[0].text)
        assertEquals("Second line", cues[1].text)
    }

    @Test
    fun `parses dual English and original language subtitle streams independently`() {
        val mgr = SubtitleManager()
        val lines = listOf(
            "@@SUBCOLAB_ORIG_LANG@@ ko",
            "@@SUBCOLAB_SRT_BEGIN@@ 1000",
            "1",
            "00:00:01,000 --> 00:00:03,000",
            "I hope you don't get sick",
            "@@SUBCOLAB_SRT_END@@",
            "@@SUBCOLAB_ORIG_SRT_BEGIN@@ 1000",
            "1",
            "00:00:01,000 --> 00:00:03,000",
            "아프지 마요",
            "@@SUBCOLAB_ORIG_SRT_END@@",
        )
        lines.forEach { mgr.processLine(it) }

        assertEquals("ko", mgr.detectedOrigLang)
        assertEquals(1, mgr.cueCount())
        assertEquals(1, mgr.origCueCount())
        assertEquals("I hope you don't get sick", mgr.getSortedCues()[0].text)
        assertEquals("아프지 마요", mgr.getSortedOrigCues()[0].text)
        assertTrue(mgr.buildOrigSrtContent().contains("아프지 마요"))
        assertTrue(mgr.buildOrigAssContent("Sudden Shower").contains("아프지 마요"))
    }

    @Test
    fun `parses remote chunk streaming protocol events`() {
        val mgr = SubtitleManager()
        val ev1 = mgr.processLine("@@SUBCOLAB_REMOTE_CHUNK@@ 0|0.000|20.040|1|/content/subcolab_chunks_abc/chunk_000.ts")
        val ev2 = mgr.processLine("@@SUBCOLAB_REMOTE_CHUNK@@ 1|20.040|40.120|0|/content/subcolab_chunks_abc/chunk_001.mkv")
        val evDone = mgr.processLine("@@SUBCOLAB_REMOTE_CHUNKS_DONE@@ 2|40.120")

        assertTrue(ev1 is WorkerEvent.RemoteChunkReady)
        val c1 = ev1 as WorkerEvent.RemoteChunkReady
        assertEquals(0, c1.index)
        assertEquals(0.0, c1.startSec, 0.001)
        assertEquals(20.04, c1.endSec, 0.001)
        assertTrue(c1.canUseMpegTs)
        assertEquals("/content/subcolab_chunks_abc/chunk_000.ts", c1.remotePath)

        assertTrue(ev2 is WorkerEvent.RemoteChunkReady)
        val c2 = ev2 as WorkerEvent.RemoteChunkReady
        assertEquals(1, c2.index)
        assertFalse(c2.canUseMpegTs)

        assertTrue(evDone is WorkerEvent.RemoteChunksDone)
        val done = evDone as WorkerEvent.RemoteChunksDone
        assertEquals(2, done.totalCount)
        assertEquals(40.12, done.totalDurationSec, 0.001)
    }

    @Test
    fun `parses WebVTT and SRT external subtitles while stripping tags and entities`() {
        val rawVtt = """
            WEBVTT
            Kind: captions
            Language: en

            NOTE ignore this comment

            00:01.250 --> 00:04.500 align:start position:0%
            <c.colorE5E5E5>Hello &amp; welcome!</c>
            <i>Line two</i>

            00:05.000 --> 00:07.200
            {\an8}Testing &quot;SUBCOLAB&quot;
        """.trimIndent()

        val cues = SubtitleManager.parseVttOrSrtToCues(rawVtt)
        assertEquals(2, cues.size)
        assertEquals(1250L, cues[0].startMs)
        assertEquals(4500L, cues[0].endMs)
        assertEquals("Hello & welcome!\nLine two", cues[0].text)
        assertEquals(5000L, cues[1].startMs)
        assertEquals("Testing \"SUBCOLAB\"", cues[1].text)

        val mgr = SubtitleManager()
        val loaded = mgr.loadExternalCues(cues, intoOriginal = false)
        assertEquals(2, loaded)
        assertEquals(2, mgr.cueCount())
        assertTrue(mgr.buildSrtContent().contains("Hello & welcome!"))
    }
}

