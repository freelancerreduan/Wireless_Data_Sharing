package com.reduan.smartinfo

import com.reduan.smartinfo.bluetooth.BleProtocol
import com.reduan.smartinfo.bluetooth.ChunkAssembler
import com.reduan.smartinfo.bluetooth.Proximity
import com.reduan.smartinfo.bluetooth.ProximityConfig
import com.reduan.smartinfo.bluetooth.ProximityTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleLogicTest {
    @Test fun chunkRoundTrip() {
        val text = "BEGIN:VCARD\r\n" + "x".repeat(2000) + "\r\nEND:VCARD\r\n"
        val chunks = BleProtocol.split(text.toByteArray())
        assertEquals(5, chunks.size)
        val a = ChunkAssembler()
        chunks.forEachIndexed { i, c -> assertEquals(i == chunks.lastIndex, a.add(c)) }
        assertEquals(text, a.result())
    }

    @Test(expected = IllegalArgumentException::class)
    fun outOfOrderChunkRejected() {
        val chunks = BleProtocol.split(ByteArray(1000))
        ChunkAssembler().add(chunks[1])
    }

    @Test(expected = BleProtocol.TooLargeException::class)
    fun tooLargeCardRejected() {
        BleProtocol.split(ByteArray(BleProtocol.CHUNK_PAYLOAD * BleProtocol.MAX_CHUNKS + 1))
    }

    @Test fun veryNearRequiresStableSignal() {
        val t = ProximityTracker(ProximityConfig(veryNearRssi = -55, stableWindowMs = 2000))
        t.add("d", -45, 0)
        assertEquals(Proximity.NEAR, t.classify("d", 0)) // strong but not yet stable
        for (ms in 500L..2500L step 500) t.add("d", -48, ms)
        assertEquals(Proximity.VERY_NEAR, t.classify("d", 2500))
    }

    @Test fun signalDipBreaksVeryNear() {
        val t = ProximityTracker(ProximityConfig(veryNearRssi = -55, stableWindowMs = 2000))
        listOf(0L to -45, 500L to -45, 1000L to -80, 1500L to -45, 2000L to -45, 2500L to -45)
            .forEach { (ms, r) -> t.add("d", r, ms) }
        assertTrue(t.classify("d", 2500) != Proximity.VERY_NEAR)
    }

    @Test fun bandsAndExpiry() {
        val t = ProximityTracker(ProximityConfig(expiryMs = 10_000))
        t.add("room", -80, 0); t.add("far", -95, 0)
        assertEquals(Proximity.ROOM, t.classify("room", 0))
        assertEquals(Proximity.FAR, t.classify("far", 0))
        assertEquals(setOf("room", "far"), t.prune(20_000).toSet())
        assertTrue(t.ids().isEmpty())
    }
}
