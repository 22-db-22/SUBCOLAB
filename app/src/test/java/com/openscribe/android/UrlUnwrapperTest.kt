package com.openscribe.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlUnwrapperTest {

    @Test
    fun `extracts url from YouTube share text and normalizes shorts`() {
        val shared = "Watch this cool clip! https://www.youtube.com/shorts/dQw4w9WgXcQ?feature=share"
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            UrlUnwrapper.extractAndUnwrap(shared),
        )
    }

    @Test
    fun `unwraps google goto redirect wrapper`() {
        val wrapped = "https://www.google.com/goto?url=https%3A%2F%2Fwww.youtube.com%2Fshorts%2FabcDEF12345"
        assertEquals(
            "https://www.youtube.com/watch?v=abcDEF12345",
            UrlUnwrapper.extractAndUnwrap(wrapped),
        )
    }

    @Test
    fun `validates http and https urls`() {
        assertTrue(UrlUnwrapper.isValidVideoUrl("https://youtu.be/dQw4w9WgXcQ"))
        assertFalse(UrlUnwrapper.isValidVideoUrl("not a url"))
        assertFalse(UrlUnwrapper.isValidVideoUrl("ftp://example.com/file.mp4"))
    }
}
