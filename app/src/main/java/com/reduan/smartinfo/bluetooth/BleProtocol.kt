package com.reduan.smartinfo.bluetooth

import java.util.UUID

/**
 * Custom GATT protocol used only between devices running Reduan Smart Info.
 * The advertisement carries the service UUID + a 4-byte random session id — never any personal data.
 * The card is read in chunks: the client writes a chunk index to CONTROL, then reads DATA.
 * Each DATA value = [index][total][payload...]  (payload ≤ CHUNK_PAYLOAD bytes).
 */
object BleProtocol {
    val SERVICE: UUID = UUID.fromString("7d2a0001-4c1e-4f5b-9a6e-52454455414e")
    val CONTROL: UUID = UUID.fromString("7d2a0002-4c1e-4f5b-9a6e-52454455414e")
    val DATA: UUID = UUID.fromString("7d2a0003-4c1e-4f5b-9a6e-52454455414e")

    const val CHUNK_PAYLOAD = 480
    const val MAX_CHUNKS = 20
    const val SESSION_ID_BYTES = 4

    class TooLargeException : Exception("Card is too large to send over Bluetooth")

    fun split(bytes: ByteArray): List<ByteArray> {
        val total = maxOf(1, (bytes.size + CHUNK_PAYLOAD - 1) / CHUNK_PAYLOAD)
        if (total > MAX_CHUNKS) throw TooLargeException()
        return (0 until total).map { i ->
            val from = i * CHUNK_PAYLOAD
            val to = minOf(bytes.size, from + CHUNK_PAYLOAD)
            byteArrayOf(i.toByte(), total.toByte()) + bytes.copyOfRange(from, to)
        }
    }

    fun toHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}

/** Reassembles chunks on the receiving side, rejecting anything out of order or malformed. */
class ChunkAssembler {
    private val buffer = java.io.ByteArrayOutputStream()
    var expectedIndex = 0
        private set
    var total = -1
        private set

    /** @return true when the whole card has been received. */
    fun add(chunk: ByteArray): Boolean {
        require(chunk.size >= 2) { "Malformed chunk" }
        val idx = chunk[0].toInt() and 0xFF
        val tot = chunk[1].toInt() and 0xFF
        require(idx == expectedIndex) { "Unexpected chunk order" }
        require(tot in 1..BleProtocol.MAX_CHUNKS) { "Invalid chunk count" }
        if (total == -1) total = tot else require(tot == total) { "Chunk count changed" }
        buffer.write(chunk, 2, chunk.size - 2)
        expectedIndex++
        return expectedIndex == total
    }

    fun result(): String = buffer.toByteArray().toString(Charsets.UTF_8)
}
