package com.reduan.smartinfo.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.reduan.smartinfo.profile.Profile
import com.reduan.smartinfo.profile.ProfileField
import com.reduan.smartinfo.profile.SharingSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

private val Context.profileStore: DataStore<Preferences> by preferencesDataStore(name = "profile")

/**
 * Local-only persistent storage for the profile (Jetpack DataStore + app-private files for the photo).
 * Nothing is uploaded anywhere. allowBackup=false in the manifest keeps it off cloud backups too.
 */
class ProfileRepository(private val context: Context) {
    private val store = context.profileStore
    private val photoDir = File(context.filesDir, "profile").apply { mkdirs() }

    private fun fieldKey(f: ProfileField) = stringPreferencesKey("f_${f.name}")
    private val PHOTO = stringPreferencesKey("photo_path")
    private val LINKS = stringPreferencesKey("links")
    private val SHARE_INIT = booleanPreferencesKey("share_init")
    private val SHARE_FIELDS = stringSetPreferencesKey("share_fields")
    private val SHARE_PHOTO = booleanPreferencesKey("share_photo")
    private val SHARE_LINKS = stringSetPreferencesKey("share_links")
    private val UPDATED = longPreferencesKey("updated_at")

    val profile: Flow<Profile> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map(::toProfile)

    private fun toProfile(p: Preferences): Profile {
        val values = ProfileField.entries.mapNotNull { f -> p[fieldKey(f)]?.let { f to it } }.toMap()
        val sharing = if (p[SHARE_INIT] == true) SharingSelection(
            fields = p[SHARE_FIELDS].orEmpty().mapNotNull { n -> ProfileField.entries.firstOrNull { it.name == n } }.toSet(),
            includePhoto = p[SHARE_PHOTO] ?: false,
            linkIds = p[SHARE_LINKS].orEmpty(),
        ) else SharingSelection.DEFAULT
        return Profile(
            values = values,
            photoPath = p[PHOTO]?.takeIf { File(it).exists() },
            links = LinkCodec.decode(p[LINKS].orEmpty()),
            sharing = sharing,
            updatedAt = p[UPDATED] ?: 0L,
        )
    }

    /** Saves all text fields and links (already validated + normalized). Blank fields are deleted. */
    suspend fun saveDetails(profile: Profile) {
        store.edit { prefs ->
            ProfileField.entries.forEach { f ->
                val v = profile[f]
                if (v.isEmpty()) prefs.remove(fieldKey(f)) else prefs[fieldKey(f)] = v
            }
            prefs[LINKS] = LinkCodec.encode(profile.links)
            // Drop sharing flags for links that no longer exist.
            val ids = profile.links.map { it.id }.toSet()
            prefs[SHARE_LINKS] = prefs[SHARE_LINKS].orEmpty().intersect(ids)
            prefs[UPDATED] = System.currentTimeMillis()
        }
    }

    suspend fun saveSharing(sel: SharingSelection) {
        store.edit { prefs ->
            prefs[SHARE_INIT] = true
            prefs[SHARE_FIELDS] = sel.fields.map { it.name }.toSet()
            prefs[SHARE_PHOTO] = sel.includePhoto
            prefs[SHARE_LINKS] = sel.linkIds
            prefs[UPDATED] = System.currentTimeMillis()
        }
    }

    /** Copies the picked image into app-private storage, downscaled to 512 px. */
    suspend fun setPhoto(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = decode(uri, 512) ?: error("Could not read this image")
            val file = File(photoDir, "photo_${System.currentTimeMillis()}.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            var old: String? = null
            store.edit { prefs ->
                old = prefs[PHOTO]
                prefs[PHOTO] = file.absolutePath
                prefs[UPDATED] = System.currentTimeMillis()
            }
            old?.let { if (it != file.absolutePath) File(it).delete() }
            Unit
        }
    }

    suspend fun removePhoto() {
        var old: String? = null
        store.edit { prefs ->
            old = prefs[PHOTO]
            prefs.remove(PHOTO)
            prefs[SHARE_PHOTO] = false
            prefs[UPDATED] = System.currentTimeMillis()
        }
        withContext(Dispatchers.IO) { old?.let { File(it).delete() } }
    }

    /** Deletes the whole profile, sharing choices and photo from this device. */
    suspend fun reset() {
        store.edit { it.clear() }
        withContext(Dispatchers.IO) { photoDir.listFiles()?.forEach { it.delete() } }
    }

    /** Small JPEG for embedding into vCards (file export + local Wi-Fi only). */
    suspend fun photoForVCard(path: String?): ByteArray? = withContext(Dispatchers.IO) {
        if (path == null) return@withContext null
        runCatching {
            val src = BitmapFactory.decodeFile(path) ?: return@runCatching null
            val scaled = scaleDown(src, 256)
            ByteArrayOutputStream().use { bos ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, bos); bos.toByteArray()
            }
        }.getOrNull()
    }

    private fun decode(uri: Uri, maxSide: Int): Bitmap? {
        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val s = info.size
                val ratio = maxOf(s.width, s.height).toFloat() / maxSide
                if (ratio > 1f) decoder.setTargetSize((s.width / ratio).toInt(), (s.height / ratio).toInt())
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }?.let { scaleDown(it, maxSide) }
        }
    }

    private fun scaleDown(b: Bitmap, maxSide: Int): Bitmap {
        val ratio = maxOf(b.width, b.height).toFloat() / maxSide
        return if (ratio <= 1f) b else Bitmap.createScaledBitmap(b, (b.width / ratio).toInt(), (b.height / ratio).toInt(), true)
    }
}
