package com.dd3boh.xenowave.playback.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class ContentRangeTest {
    @Test
    fun parsesTotalFromContentRange() {
        assertEquals(12345678L, parseContentRangeTotalBytes("bytes 0-0/12345678"))
    }

    @Test
    fun parsesUppercaseContentRange() {
        assertEquals(987654L, parseContentRangeTotalBytes("BYTES 100-200/987654"))
    }

    @Test
    fun returnsNullForUnknownTotal() {
        assertEquals(null, parseContentRangeTotalBytes("bytes 0-0/*"))
    }

    @Test
    fun returnsNullForMalformedContentRange() {
        assertEquals(null, parseContentRangeTotalBytes("bytes 0-0"))
    }
}
