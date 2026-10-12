package com.reduan.smartinfo.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reduan.smartinfo.AppViewModel
import com.reduan.smartinfo.nfc.NfcController
import com.reduan.smartinfo.nfc.NfcState
import com.reduan.smartinfo.profile.ProfileField
import com.reduan.smartinfo.qr.QrBitmap
import com.reduan.smartinfo.qr.QrPayload
import com.reduan.smartinfo.ui.InfoBox
import com.reduan.smartinfo.ui.ScreenScaffold
import com.reduan.smartinfo.ui.SectionTitle

@Composable
fun QrScreen(vm: AppViewModel, onOpenCard: () -> Unit, onBack: () -> Unit) {
    val vcard by vm.compactVCard.collectAsStateWithLifecycle()
    val shareable by vm.shareable.collectAsStateWithLifecycle()
    ScreenScaffold("QR code sharing", onBack) {
        val v = vcard
        if (v == null) {
            InfoBox("Nothing is selected for sharing. Choose fields on the My Card screen first.", warning = true)
            Button(onClick = onOpenCard) { Text("Choose what to share") }
            return@ScreenScaffold
        }
        if (!QrPayload.fits(v)) {
            InfoBox("Too much data for a reliable QR code (${QrPayload.sizeOf(v)} bytes, limit ${QrPayload.MAX_BYTES}). Turn off long fields such as the business description, or use Wi-Fi sharing.", warning = true)
            Button(onClick = onOpenCard) { Text("Change selection") }
            return@ScreenScaffold
        }
        val bitmap = remember(v) { QrBitmap.create(v) }
        if (bitmap == null) {
            InfoBox("Could not generate the QR code.", warning = true); return@ScreenScaffold
        }
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(Color.White).padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(bitmap.asImageBitmap(), "QR code with your contact card", Modifier.fillMaxWidth(), filterQuality = FilterQuality.None)
        }
        Text("Works offline. Any phone camera can scan it and offer to save the contact.", style = MaterialTheme.typography.bodyMedium)
        InfoBox("Anyone who can see this code can read the details in it. Photo is never included in the QR.")
        SectionTitle("Included")
        shareable?.summary()?.filter { it.first != "Photo" }?.forEach { (k, value) ->
            Text("$k: $value", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun NfcScreen(vm: AppViewModel, nfc: NfcController, onOpenCard: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val vcard by vm.compactVCard.collectAsStateWithLifecycle()
    val shareable by vm.shareable.collectAsStateWithLifecycle()
    val state by nfc.state.collectAsStateWithLifecycle()
    var useLink by rememberSaveable { mutableStateOf(true) }
    var confirm by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { nfc.cancel() } }

    ScreenScaffold("NFC sharing", onBack) {
        if (!nfc.isSupported) {
            InfoBox("This phone has no NFC hardware. Use QR code or Wi-Fi sharing instead.", warning = true)
            return@ScreenScaffold
        }
        if (!nfc.isEnabled) {
            InfoBox("NFC is turned off.", warning = true)
            Button(onClick = { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }) { Text("Open NFC settings") }
        }
        InfoBox("Android phones cannot send data to each other just by touching backs (Android Beam was removed). Instead, write your card to an NFC tag/sticker/card — anyone who taps it gets your details.")

        val website = shareable?.value(ProfileField.WEBSITE)
        SectionTitle("What to write")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = useLink && website != null, onClick = { useLink = true }, enabled = website != null)
                    Column {
                        Text("Website link (recommended)")
                        Text(website ?: "Add a website and select it for sharing to use this", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !useLink || website == null, onClick = { useLink = false })
                    Column {
                        Text("Contact card (vCard)")
                        Text(vcard?.let { "${it.toByteArray().size} bytes — selected fields only" } ?: "Nothing selected", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        InfoBox("Tags can be read by anyone who taps them. Don't write private details you wouldn't print on a paper card.", warning = true)

        val message = when {
            useLink && website != null -> NfcController.uriMessage(website) to "Website link"
            vcard != null -> NfcController.vcardMessage(vcard!!) to "Contact card"
            else -> null
        }
        if (message == null) {
            Button(onClick = onOpenCard) { Text("Choose what to share") }
        } else {
            Button(onClick = { confirm = true }, enabled = nfc.isEnabled && state !is NfcState.WaitingToWrite, modifier = Modifier.fillMaxWidth()) {
                Text("Write to tag")
            }
        }
        OutlinedButton(onClick = { nfc.armRead() }, enabled = nfc.isEnabled && state !is NfcState.WaitingToRead, modifier = Modifier.fillMaxWidth()) {
            Text("Read a tag")
        }

        when (val st = state) {
            is NfcState.WaitingToWrite -> Waiting("Hold an NFC tag to the back of the phone to write: ${st.description} (${st.bytes} bytes)") { nfc.cancel() }
            NfcState.WaitingToRead -> Waiting("Hold an NFC tag to the back of the phone to read it") { nfc.cancel() }
            is NfcState.Written -> InfoBox("Written successfully (${st.bytes} bytes). Tap the tag with another phone to test it.")
            is NfcState.Error -> InfoBox(st.message, warning = true)
            is NfcState.Read -> {
                SectionTitle("Tag contents")
                st.lines.forEach { (k, v) -> Text("$k: $v", style = MaterialTheme.typography.bodySmall) }
            }
            NfcState.Idle -> {}
        }
    }

    if (confirm) {
        val msg = when {
            useLink && shareable?.value(ProfileField.WEBSITE) != null -> NfcController.uriMessage(shareable!!.value(ProfileField.WEBSITE)!!) to "Website link"
            vcard != null -> NfcController.vcardMessage(vcard!!) to "Contact card"
            else -> null
        }
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Write to NFC tag?") },
            text = { Text("Any existing data on the tag will be overwritten. Anyone who taps the tag will be able to read it.") },
            confirmButton = { TextButton(onClick = { confirm = false; msg?.let { nfc.armWrite(it.first, it.second) } }) { Text("Write") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Waiting(text: String, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text(text, Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}
