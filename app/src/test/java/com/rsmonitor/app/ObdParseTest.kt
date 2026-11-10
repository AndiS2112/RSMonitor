package com.rsmonitor.app

import com.rsmonitor.app.obd.isFailedReply
import com.rsmonitor.app.obd.parseRpm
import com.rsmonitor.app.obd.prettyReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdParseTest {
    @Test
    fun rpmFromCompactReply() {
        // 0x1AF8 = 6904; 6904 / 4 = 1726
        assertEquals(1726, parseRpm(listOf("7E804410C1AF8")))
    }

    @Test
    fun rpmFromSpacedReplyWithoutHeader() {
        assertEquals(1726, parseRpm(listOf("41 0C 1A F8")))
    }

    @Test
    fun rpmAtRedline() {
        // 8200 rpm * 4 = 32800 = 0x8020
        assertEquals(8200, parseRpm(listOf("7E804410C8020")))
    }

    @Test
    fun noRpmInErrorReply() {
        assertNull(parseRpm(listOf("NO DATA")))
        assertNull(parseRpm(emptyList()))
        assertNull(parseRpm(listOf("7E803410C1A"))) // cut short
    }

    @Test
    fun prettyPrintsCanFrame() {
        assertEquals("7E8 04 41 0C 1A F8", prettyReply("7E804410C1AF8"))
        assertEquals("7E8 03 41 05 7B", prettyReply("7E8 03 41 05 7B"))
    }

    @Test
    fun prettyLeavesTextAlone() {
        assertEquals("ELM327 v1.5", prettyReply("ELM327 v1.5"))
        assertEquals("OK", prettyReply("OK"))
        assertEquals("NO DATA", prettyReply("NO DATA"))
    }

    @Test
    fun failedReplies() {
        assertTrue(isFailedReply("NO DATA"))
        assertTrue(isFailedReply("?"))
        assertTrue(isFailedReply("CAN ERROR"))
        assertTrue(isFailedReply("UNABLE TO CONNECT"))
        assertTrue(isFailedReply("7E8037F2231")) // UDS negative response to service 22
        assertTrue(isFailedReply("7E8 03 7F 22 31"))
    }

    @Test
    fun goodReplies() {
        assertFalse(isFailedReply("OK"))
        assertFalse(isFailedReply("ELM327 v1.5"))
        assertFalse(isFailedReply("7E804410C1AF8"))
        assertFalse(isFailedReply("7E8 04 62 20 2F 7B")) // UDS positive response
    }
}
