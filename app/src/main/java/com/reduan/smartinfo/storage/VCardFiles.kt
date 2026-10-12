package com.reduan.smartinfo.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Writes .vcf files to a private cache folder and exposes them through FileProvider. */
object VCardFiles {
    private fun authority(context: Context) = "${context.packageName}.files"

    fun writeShareFile(context: Context, displayName: String, vcard: String, folder: String = "shared"): File {
        val dir = File(context.cacheDir, folder).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // keep only the latest export
        return File(dir, FileNames.sanitize(displayName) + ".vcf").apply { writeText(vcard, Charsets.UTF_8) }
    }

    fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(context, authority(context), file)

    fun shareIntent(context: Context, file: File): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/x-vcard"
            putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share contact card")
    }

    /** Opens a received card in the Contacts app so the receiver can choose to save it. */
    fun importIntent(context: Context, file: File): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(context, file), "text/x-vcard")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    fun writeToUri(context: Context, uri: Uri, vcard: String): Result<Unit> = runCatching {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(vcard.toByteArray(Charsets.UTF_8)) }
            ?: error("Could not open the selected location")
    }
}
