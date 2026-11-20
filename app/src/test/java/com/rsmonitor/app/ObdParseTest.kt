package com.rsmonitor.app

import com.rsmonitor.app.obd.RecordPlan
import com.rsmonitor.app.obd.engineOilCelsius
import com.rsmonitor.app.obd.gearboxOilCelsius
import com.rsmonitor.app.obd.hexString
import com.rsmonitor.app.obd.isCarReply
import com.rsmonitor.app.obd.isFailedReply
import com.rsmonitor.app.obd.isPositiveRead
import com.rsmonitor.app.obd.parsePids
import com.rsmonitor.app.obd.parseRpm
import com.rsmonitor.app.obd.parseUdsData
import com.rsmonitor.app.obd.parseUdsGroup
import com.rsmonitor.app.obd.prettyReply
import com.rsmonitor.app.obd.recordRow
import com.rsmonitor.app.obd.referenceTorque
import com.rsmonitor.app.obd.rpmOf
import com.rsmonitor.app.obd.torquePercent
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

    @Test
    fun udsSingleFrame() {
        assertEquals(listOf(0x7B), parseUdsData(listOf("7E8046220A17B"), 0x20A1))
        assertEquals(listOf(0x01, 0xF4), parseUdsData(listOf("7E9 05 62 21 04 01 F4"), 0x2104))
    }

    @Test
    fun udsMultiFrame() {
        // 17 data bytes spelling TESTVIN0123456789, split over three frames
        val reply = listOf(
            "7E9 10 14 62 F1 90 54 45 53",
            "7E9 21 54 56 49 4E 30 31 32",
            "7E9 22 33 34 35 36 37 38 39",
        )
        val data = parseUdsData(reply, 0xF190)
        assertEquals("TESTVIN0123456789", data!!.map { it.toChar() }.joinToString(""))
    }

    @Test
    fun udsIgnoresPaddingAfterTheData() {
        // length byte says 4, so the trailing AA AA AA is padding
        assertEquals(listOf(0x7B), parseUdsData(listOf("7E8046220A17BAAAAAA"), 0x20A1))
    }

    @Test
    fun udsRefusedOrMissing() {
        assertNull(parseUdsData(listOf("7E8037F2231"), 0x20A1)) // negative response
        assertNull(parseUdsData(listOf("NO DATA"), 0x20A1))
        assertNull(parseUdsData(emptyList(), 0x20A1))
        assertNull(parseUdsData(listOf("7E8046220A27B"), 0x20A1)) // answer for another ID
    }

    @Test
    fun hexFormatting() {
        assertEquals("7B 01 FF", hexString(listOf(0x7B, 0x01, 0xFF)))
        assertEquals("", hexString(emptyList()))
    }

    @Test
    fun positiveReadWhateverTheIds() {
        assertTrue(isPositiveRead(listOf("7E8 04 62 20 96 FF")))
        assertTrue(isPositiveRead(listOf("7E9 10 14 62 F1 90 54 45 53", "7E9 21 54 56 49 4E 30 31 32")))
        assertFalse(isPositiveRead(listOf("7E8 03 7F 22 31")))
        assertFalse(isPositiveRead(listOf("NO DATA")))
        assertFalse(isPositiveRead(emptyList()))
    }

    @Test
    fun engineOilTemperatureAsReadOnTheCar() {
        // the two values from the scans: 2879 and 3679 tenths of a kelvin
        assertEquals(15, engineOilCelsius(listOf("7E8 05 62 11 BD 0B 3F")))
        assertEquals(95, engineOilCelsius(listOf("7E80562 11BD0E5F")))
        assertEquals(-10, engineOilCelsius(listOf("7E8 05 62 11 BD 0A 47")))
        assertNull(engineOilCelsius(listOf("7E8 03 7F 22 31")))
        assertNull(engineOilCelsius(listOf("7E8 04 62 11 BD 0E")))
    }

    @Test
    fun gearboxOilTemperatureAsReadOnTheCar() {
        // the car's own answer after standing for days, 18 °C outside
        assertEquals(19, gearboxOilCelsius(listOf("7E9 04 62 21 04 3B")))
        assertEquals(62, gearboxOilCelsius(listOf("7E904622104 66")))
        assertEquals(-10, gearboxOilCelsius(listOf("7E9 04 62 21 04 1E")))
        assertNull(gearboxOilCelsius(listOf("7E9 03 7F 22 31")))
        assertNull(gearboxOilCelsius(listOf("NO DATA")))
    }

    @Test
    fun engineSpeedAndTorqueFromOneAnswer() {
        // asked "010C62": 0x1AF8 / 4 = 1726 rpm, 0x9B - 125 = 30 %
        val pids = parsePids(listOf("7E8 06 41 0C 1A F8 62 9B"))
        assertEquals(1726, rpmOf(pids))
        assertEquals(30, torquePercent(pids))
        assertNull(referenceTorque(pids))
    }

    @Test
    fun torqueAnswersOnTheirOwn() {
        assertEquals(-5, torquePercent(parsePids(listOf("7E803416278"))))   // coasting
        assertEquals(430, referenceTorque(parsePids(listOf("7E804416301AE"))))
        assertEquals(430, referenceTorque(parsePids(listOf("41 63 01 AE"))))  // adapter headers off
    }

    @Test
    fun paddingAfterTheAnswerIsNotRead() {
        // the frame is filled up to eight bytes with 05, which is also a known value's number
        val pids = parsePids(listOf("7E804410C1AF8050505"))
        assertEquals(1726, rpmOf(pids))
        assertEquals(setOf(0x0C), pids.keys)
    }

    @Test
    fun noValuesInARefusalOrAnError() {
        assertTrue(parsePids(listOf("NO DATA")).isEmpty())
        assertTrue(parsePids(listOf("7E8 03 7F 01 12")).isEmpty())
        assertTrue(parsePids(emptyList()).isEmpty())
        // an answer cut short carries nothing usable
        assertNull(rpmOf(parsePids(listOf("7E803410C1A"))))
    }

    @Test
    fun anyFrameFromAControlUnitMeansTheCarIsThere() {
        assertTrue(isCarReply(listOf("7E804410C1AF8")))
        assertTrue(isCarReply(listOf("7E8 03 7F 22 31")))   // a refusal is still an answer
        assertFalse(isCarReply(listOf("NO DATA")))
        assertFalse(isCarReply(listOf("UNABLE TO CONNECT")))
        assertFalse(isCarReply(listOf("OK")))
        assertFalse(isCarReply(emptyList()))
    }

    @Test
    fun severalIdsFromOneAnswer() {
        // engine speed, torque and the selector, as the gearbox answers "22 2102 2002 210C"
        val reply = listOf(
            "7E9 10 0C 62 21 02 0A 78 20",
            "7E9 21 02 00 0E 21 0C 03 AA",
        )
        val ids = listOf(0x2102, 0x2002, 0x210C)
        assertEquals(
            listOf(listOf(0x0A, 0x78), listOf(0x00, 0x0E), listOf(0x03)),
            parseUdsGroup(reply, ids, listOf(2, 2, 1)),
        )
        // two one-byte values fit in a single frame
        assertEquals(
            listOf(listOf(0x06), listOf(0x03)),
            parseUdsGroup(listOf("7E9 07 62 21 0E 06 21 0F 03"), listOf(0x210E, 0x210F), listOf(1, 1)),
        )
    }

    @Test
    fun aGroupAnswerMustHoldExactlyWhatWasAsked() {
        val ids = listOf(0x210E, 0x210F)
        assertNull(parseUdsGroup(listOf("7E9 03 7F 22 31"), ids, listOf(1, 1)))            // refused
        assertNull(parseUdsGroup(listOf("7E9 04 62 21 0E 06"), ids, listOf(1, 1)))        // only the first
        assertNull(parseUdsGroup(listOf("7E9 07 62 21 0F 03 21 0E 06"), ids, listOf(1, 1))) // other order
        assertNull(parseUdsGroup(listOf("7E9 07 62 21 0E 06 21 0F 03"), ids, listOf(2, 1))) // other sizes
        assertNull(parseUdsGroup(listOf("NO DATA"), ids, listOf(1, 1)))
    }

    @Test
    fun aRecordedRow() {
        assertEquals(
            "12340,0A78,000E,03,06,03,2142=00D4,665",
            recordRow(12340, listOf(listOf(0x0A, 0x78), listOf(0x00, 0x0E), listOf(0x03), listOf(0x06), listOf(0x03)), 0x2142, listOf(0x00, 0xD4), 665),
        )
        // values that did not answer leave their place empty
        assertEquals("500,,,,,,2105=,", recordRow(500, listOf(null, null, null, null, null), 0x2105, null, null))
    }

    @Test
    fun theRecordingGroupsFitTheAdapter() {
        // "22" and three IDs is seven bytes, the most the adapter sends in one request
        assertEquals(RecordPlan.FAST, RecordPlan.GROUPS.flatMap { it.ids })
        for (group in RecordPlan.GROUPS) {
            assertTrue(group.request.length <= 14)
            assertEquals(group.ids.size, group.sizes.size)
        }
    }
}
