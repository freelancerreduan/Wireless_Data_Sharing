package com.reduan.smartinfo

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.reduan.smartinfo.bluetooth.NearbyCardClient
import com.reduan.smartinfo.bluetooth.NearbyCardServer
import com.reduan.smartinfo.bluetooth.NearbyDevice
import com.reduan.smartinfo.bluetooth.NearbyScanner
import com.reduan.smartinfo.localserver.CardContent
import com.reduan.smartinfo.localserver.LocalCardServer
import com.reduan.smartinfo.localserver.ServerEvent
import com.reduan.smartinfo.profile.Profile
import com.reduan.smartinfo.profile.ProfileValidator
import com.reduan.smartinfo.profile.ShareableProfile
import com.reduan.smartinfo.profile.SharingSelection
import com.reduan.smartinfo.profile.VCardBuilder
import com.reduan.smartinfo.profile.VCardParser
import com.reduan.smartinfo.storage.ProfileRepository
import com.reduan.smartinfo.storage.VCardFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.InetAddress

enum class WifiStatus { IDLE, WAITING, PAGE_OPENED, ACCEPTED, DELIVERED, REJECTED, EXPIRED, STOPPED, FAILED }

data class WifiState(
    val status: WifiStatus = WifiStatus.IDLE,
    val url: String? = null,
    val expiresAt: Long = 0L,
    val message: String? = null,
) {
    val active get() = status in setOf(WifiStatus.WAITING, WifiStatus.PAGE_OPENED, WifiStatus.ACCEPTED)
}

data class ReceivedCard(val vcard: String, val parsed: VCardParser.Parsed)

/**
 * Single source of truth: everything shared is derived from the SAVED profile flow.
 * When the user saves an edit or changes a sharing toggle, QR / NFC / BLE / vCard / Wi-Fi
 * all switch to the new data automatically (running sessions are updated in place).
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ProfileRepository(app)

    val profile: StateFlow<Profile?> = repo.profile.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val shareable: StateFlow<ShareableProfile?> =
        profile.map { it?.let(ShareableProfile::from) }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Compact vCard (no photo) used for QR, NFC and Bluetooth. */
    val compactVCard: StateFlow<String?> =
        shareable.map { s -> s?.takeUnless { it.isEmpty }?.let { VCardBuilder.build(it) } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // ---------- Bluetooth ----------
    val nearbyServer = NearbyCardServer(app)
    val scanner = NearbyScanner(app)
    private val client = NearbyCardClient(app)
    val bleCardError = MutableStateFlow<String?>(null)
    val fetchingKey = MutableStateFlow<String?>(null)
    val fetchError = MutableStateFlow<String?>(null)
    val received = MutableStateFlow<ReceivedCard?>(null)
    private val lastFetch = HashMap<String, Long>()

    // ---------- Local Wi-Fi ----------
    private var wifiServer: LocalCardServer? = null
    val wifi = MutableStateFlow(WifiState())

    init {
        viewModelScope.launch {
            compactVCard.collect { v -> bleCardError.value = nearbyServer.updateCard(v) }
        }
        viewModelScope.launch {
            shareable.collect { s ->
                val srv = wifiServer ?: return@collect
                if (s == null || s.isEmpty) stopWifi() else srv.updateContent(buildContent(s))
            }
        }
    }

    // ---------- Profile editing ----------

    /** Validates, normalizes and saves. Returns field errors (empty map = saved). */
    suspend fun saveDetails(draft: Profile): Map<String, String> {
        val errors = ProfileValidator.validateProfile(draft)
        if (errors.isEmpty()) repo.saveDetails(ProfileValidator.normalize(draft))
        return errors
    }

    fun saveSharing(sel: SharingSelection) = viewModelScope.launch { repo.saveSharing(sel) }

    suspend fun setPhoto(uri: Uri): Result<Unit> = repo.setPhoto(uri)

    fun removePhoto() = viewModelScope.launch { repo.removePhoto() }

    fun resetProfile() = viewModelScope.launch {
        stopAllSharing()
        repo.reset()
    }

    // ---------- vCard export ----------

    private suspend fun buildContent(s: ShareableProfile): CardContent =
        CardContent(s.displayName, s.summary(), VCardBuilder.build(s, repo.photoForVCard(s.photoPath)))

    /** Full vCard (with photo if selected) from the latest saved data. */
    suspend fun exportVCard(): Pair<String, String>? {
        val s = shareable.value ?: return null
        if (s.isEmpty) return null
        return s.displayName to VCardBuilder.build(s, repo.photoForVCard(s.photoPath))
    }

    suspend fun shareVCardIntent(): Intent? = withContext(Dispatchers.IO) {
        val (name, vcard) = exportVCard() ?: return@withContext null
        val app = getApplication<Application>()
        VCardFiles.shareIntent(app, VCardFiles.writeShareFile(app, name, vcard))
    }

    suspend fun saveVCardTo(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        val (_, vcard) = exportVCard() ?: return@withContext Result.failure(Exception("Nothing selected to share"))
        VCardFiles.writeToUri(getApplication(), uri, vcard)
    }

    // ---------- Bluetooth actions ----------

    fun fetchCard(device: NearbyDevice) {
        val now = System.currentTimeMillis()
        if (fetchingKey.value != null) return
        val last = lastFetch[device.key]
        if (last != null && now - last < 10_000) {
            fetchError.value = "Please wait a few seconds before requesting from this device again."; return
        }
        lastFetch[device.key] = now
        fetchingKey.value = device.key
        fetchError.value = null
        viewModelScope.launch {
            client.fetch(device.address)
                .onSuccess { text ->
                    val parsed = VCardParser.parse(text)
                    if (parsed == null) fetchError.value = "The received data was not a contact card."
                    else received.value = ReceivedCard(text, parsed)
                }
                .onFailure { fetchError.value = it.message ?: "Could not get the card." }
            fetchingKey.value = null
        }
    }

    suspend fun importReceivedIntent(): Intent? = withContext(Dispatchers.IO) {
        val r = received.value ?: return@withContext null
        val app = getApplication<Application>()
        VCardFiles.importIntent(app, VCardFiles.writeShareFile(app, r.parsed.displayName, r.vcard, folder = "received"))
    }

    fun discardReceived() {
        received.value = null
        File(getApplication<Application>().cacheDir, "received").listFiles()?.forEach { it.delete() }
    }

    // ---------- Wi-Fi actions ----------

    fun startWifi(address: InetAddress, ttlMs: Long = 5 * 60_000L) = viewModelScope.launch(Dispatchers.IO) {
        stopWifi()
        val s = shareable.value
        if (s == null || s.isEmpty) {
            wifi.value = WifiState(WifiStatus.FAILED, message = "Nothing selected to share. Choose fields on the My Card screen."); return@launch
        }
        val srv = LocalCardServer(address, buildContent(s), ttlMs) { onServerEvent(it) }
        wifiServer = srv
        try {
            srv.start()
        } catch (e: IOException) {
            wifiServer = null
            wifi.value = WifiState(WifiStatus.FAILED, message = "Could not start the local server on ${address.hostAddress}. Check that Wi-Fi or hotspot is on.")
        }
    }

    private fun onServerEvent(e: ServerEvent) {
        val cur = wifi.value
        wifi.value = when (e) {
            is ServerEvent.Started -> WifiState(WifiStatus.WAITING, e.url, e.expiresAt)
            ServerEvent.PageOpened -> cur.copy(status = WifiStatus.PAGE_OPENED)
            ServerEvent.Accepted -> cur.copy(status = WifiStatus.ACCEPTED)
            ServerEvent.Delivered -> WifiState(WifiStatus.DELIVERED)
            ServerEvent.Rejected -> WifiState(WifiStatus.REJECTED)
            ServerEvent.Expired -> WifiState(WifiStatus.EXPIRED)
            ServerEvent.Stopped -> WifiState(WifiStatus.STOPPED)
            is ServerEvent.Failed -> WifiState(WifiStatus.FAILED, message = e.message)
        }
        if (e is ServerEvent.Delivered || e is ServerEvent.Rejected || e is ServerEvent.Expired ||
            e is ServerEvent.Stopped || e is ServerEvent.Failed) wifiServer = null
    }

    fun stopWifi() { wifiServer?.stop(); wifiServer = null }

    fun stopAllSharing() {
        nearbyServer.stop(); scanner.stop(); stopWifi()
    }

    override fun onCleared() {
        stopAllSharing()
        super.onCleared()
    }
}
