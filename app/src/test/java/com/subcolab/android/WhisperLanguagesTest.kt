package com.subcolab.android.model

import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperLanguagesTest {

    @Test
    fun `catalog contains auto quick chips and ~99 whisper languages`() {
        assertTrue(WhisperLanguages.all.size >= 98)
        assertTrue(WhisperLanguages.quickChips.any { it.code == "auto" })
        assertTrue(WhisperLanguages.quickChips.any { it.code == "zh" })
        assertTrue(WhisperLanguages.quickChips.any { it.code == "ko" })
        assertTrue(WhisperLanguages.quickChips.any { it.code == "ja" })
    }

    @Test
    fun `search matches by iso code name and aliases`() {
        assertTrue(WhisperLanguages.search("mandarin").any { it.code == "zh" })
        assertTrue(WhisperLanguages.search("hangul").any { it.code == "ko" })
        assertTrue(WhisperLanguages.search("yue").any { it.code == "yue" })
    }
}
