package com.reduan.smartinfo.nfc

import android.app.Activity
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.reduan.smartinfo.profile.VCardParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface NfcState {
    data object Idle : NfcState
    data class WaitingToWrite(val description: String, val bytes: Int) : NfcState
    data object WaitingToRead : NfcState
    data class Written(val bytes: Int) : NfcState
    data class Read(val lines: List<Pair<String, String>>) : NfcState
    data class Error(val message: String) : NfcState
}

/**
 * Writes to / reads from NFC tags with the reader-mode API.
 * Phone-to-phone "tap to send" (Android Beam) was removed from Android and is NOT used.
 */
class NfcController(private val activity: Activity) : DefaultLifecycleObserver {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    private val _state = MutableStateFlow<NfcState>(NfcState.Idle)
    val state: StateFlow<NfcState> = _state

    @Volatile private var pendingWrite: NdefMessage? = null
    @Volatile private var reading = false
    private var resumed = false

    val isSupported get() = adapter != null
    val isEnabled get() = adapter?.isEnabled == true

    fun armWrite(message: NdefMessage, description: String) {
        pendingWrite = message; reading = false
        _state.value = NfcState.WaitingToWrite(description, message.byteArrayLength)
        refreshReaderMode()
    }

    fun armRead() {
        pendingWrite = null; reading = true
        _state.value = NfcState.WaitingToRead
        refreshReaderMode()
    }

    fun cancel() {
        pendingWrite = null; reading = false
        _state.value = NfcState.Idle
        refreshReaderMode()
    }

    override fun onResume(owner: LifecycleOwner) { resumed = true; refreshReaderMode() }
    override fun onPause(owner: LifecycleOwner) { resumed = false; adapter?.disableReaderMode(activity) }

    private fun refreshReaderMode() {
        val a = adapter ?: return
        if (!resumed) return
        activity.runOnUiThread {
            if (pendingWrite != null || reading) {
                val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V
                a.enableReaderMode(activity, ::onTag, flags, null)
            } else a.disableReaderMode(activity)
        }
    }

    private fun onTag(tag: Tag) {
        val msg = pendingWrite
        _state.value = when {
            msg != null -> write(tag, msg).also { if (it is NfcState.Written) pendingWrite = null }
            reading -> read(tag).also { reading = false }
            else -> return
        }
        refreshReaderMode()
    }

    private fun write(tag: Tag, msg: NdefMessage): NfcState {
        val size = msg.byteArrayLength
        Ndef.get(tag)?.let { ndef ->
            return try {
                ndef.connect()
                when {
                    !ndef.isWritable -> NfcState.Error("This tag is read-only.")
                    ndef.maxSize < size -> NfcState.Error("Tag too small: needs $size bytes, tag holds ${ndef.maxSize}. Share fewer fields or use the website link option.")
                    else -> { ndef.writeNdefMessage(msg); NfcState.Written(size) }
                }
            } catch (e: Exception) {
                NfcState.Error("Writing failed — hold the tag still against the phone and try again.")
            } finally { runCatching { ndef.close() } }
        }
        NdefFormatable.get(tag)?.let { f ->
            return try {
                f.connect(); f.format(msg); NfcState.Written(size)
            } catch (e: Exception) {
                NfcState.Error("Could not format this tag. Try an NTAG213/215/216 tag.")
            } finally { runCatching { f.close() } }
        }
        return NfcState.Error("This tag type is not supported (it does not support NDEF).")
    }

    private fun read(tag: Tag): NfcState {
        val ndef = Ndef.get(tag) ?: return NfcState.Error("This tag has no readable NDEF data.")
        return try {
            ndef.connect()
            val msg = ndef.ndefMessage ?: return NfcState.Error("The tag is empty.")
            NfcState.Read(msg.records.flatMap(::describe))
        } catch (e: Exception) {
            NfcState.Error("Reading failed — hold the tag still and try again.")
        } finally { runCatching { ndef.close() } }
    }

    /** Describes records as text only. Nothing read from a tag is opened or executed automatically. */
    private fun describe(r: NdefRecord): List<Pair<String, String>> {
        r.toUri()?.let { return listOf("Link (not opened automatically)" to it.toString()) }
        if (r.tnf == NdefRecord.TNF_MIME_MEDIA) {
            val type = String(r.type, Charsets.US_ASCII).lowercase()
            val text = String(r.payload, Charsets.UTF_8)
            if (type.contains("vcard")) {
                VCardParser.parse(text)?.let { return it.lines }
            }
            return listOf("Data ($type)" to "${r.payload.size} bytes")
        }
        if (r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_TEXT)) {
            val p = r.payload
            if (p.isNotEmpty()) {
                val langLen = p[0].toInt() and 0x3F
                val utf16 = (p[0].toInt() and 0x80) != 0
                val text = String(p, 1 + langLen, p.size - 1 - langLen, if (utf16) Charsets.UTF_16 else Charsets.UTF_8)
                return listOf("Text" to text)
            }
        }
        return listOf("Unsupported record" to "${r.payload.size} bytes")
    }

    companion object {
        fun uriMessage(url: String) = NdefMessage(NdefRecord.createUri(url))
        fun vcardMessage(vcard: String) = NdefMessage(NdefRecord.createMime("text/vcard", vcard.toByteArray(Charsets.UTF_8)))
    }
}
