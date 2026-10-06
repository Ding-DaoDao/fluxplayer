package com.fluxplayer.app.core.data.pan123

import org.junit.Assert.assertEquals
import org.junit.Test

class Pan123FileIdTest {
    @Test
    fun stringAndNumericIdsProduceSameDirectoryKey() {
        assertEquals("123", parsePan123FileId("123"))
        assertEquals("123", parsePan123FileId(123))
        assertEquals("123", parsePan123FileId(123L))
    }

    @Test
    fun largeNumericIdKeepsEveryDigit() {
        assertEquals("9007199254740993", parsePan123FileId(9007199254740993L))
        assertEquals("9223372036854775807", parsePan123FileId(Long.MAX_VALUE))
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingIdDoesNotBecomeNullDirectory() {
        parsePan123FileId(null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyIdDoesNotBecomeRootDirectory() {
        parsePan123FileId("")
    }
}
