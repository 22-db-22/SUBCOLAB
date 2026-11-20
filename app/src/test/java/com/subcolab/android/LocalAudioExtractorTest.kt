package com.subcolab.android.media

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalAudioExtractorTest {

    @Test
    fun `buildTitleSlug sanitizes display filenames cleanly`() {
        assertEquals(
            "My_Korean_Drama_Ep01",
            LocalAudioExtractor.buildTitleSlug("My Korean Drama Ep01.mp4"),
        )
        assertEquals(
            "SUBCOLAB_Local_Video",
            LocalAudioExtractor.buildTitleSlug("...mkv"),
        )
    }
}
