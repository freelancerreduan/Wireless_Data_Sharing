package com.reduan.smartinfo.qr

object QrPayload {
    /** Above this size a phone camera struggles to scan reliably. */
    const val MAX_BYTES = 1_400

    fun sizeOf(text: String) = text.toByteArray(Charsets.UTF_8).size

    fun fits(text: String) = sizeOf(text) <= MAX_BYTES
}
