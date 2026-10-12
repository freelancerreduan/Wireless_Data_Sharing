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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reduan.smartinfo.AppViewModel
import com.reduan.smartinfo.WifiStatus
import com.reduan.smartinfo.localserver.LocalNetwork
import com.reduan.smartinfo.qr.QrBitmap
import com.reduan.smartinfo.ui.InfoBox
import com.reduan.smartinfo.ui.ScreenScaffold
import com.reduan.smartinfo.ui.SectionTitle
import com.reduan.smartinfo.ui.formatRemaining

@Composable
fun WifiShareScreen(vm: AppViewModel, onOpenCard: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by vm.wifi.collectAsStateWithLifecycle()
    val shareable by vm.shareable.collectAsStateWithLifecycle()
    var candidates by remember { mutableStateOf(LocalNetwork.candidates()) }
    var selected by remember { mutableIntStateOf(0) }

    ScreenScaffold("Wi-Fi share (no app needed)", onBack) {
        InfoBox("The other phone does NOT need this app or internet. It joins your hotspot (or the same Wi-Fi), scans the QR, and downloads your card in its browser after pressing Accept.")
        val s = shareable
        if (s == null || s.isEmpty) {
            InfoBox("Nothing selected for sharing.", warning = true)
            Button(onClick = onOpenCard) { Text("Choose what to share") }
            return@ScreenScaffold
        }

        if (!state.active) {
            SectionTitle("1 · Connect both phones")
            Text("Turn on your hotspot (receiver joins it) or connect both phones to the same Wi-Fi. Android requires you to do this yourself in Settings.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) } }) { Text("Open network settings") }
                OutlinedButton(onClick = { candidates = LocalNetwork.candidates(); selected = 0 }) { Text("Refresh") }
            }
            SectionTitle("2 · Choose network")
            if (candidates.isEmpty()) {
                InfoBox("No Wi-Fi or hotspot network found. Turn one on, then tap Refresh.", warning = true)
            } else Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    candidates.forEachIndexed { i, c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selected == i, onClick = { selected = i })
                            Text(c.label, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            SectionTitle("3 · Start")
            Text("Will share: " + s.summary().joinToString { it.first }, style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = { candidates.getOrNull(selected)?.let { vm.startWifi(it.address) } },
                enabled = candidates.isNotEmpty(), modifier = Modifier.fillMaxWidth(),
            ) { Text("Start sharing session (5 min)") }
        }

        val url = state.url
        if (state.active && url != null) {
            val left = rememberRemaining(state.expiresAt)
            val qr = remember(url) { QrBitmap.create(url) }
            qr?.let {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(Color.White).padding(12.dp)) {
                    Image(it.asImageBitmap(), "QR with the local session link", Modifier.fillMaxWidth(), filterQuality = FilterQuality.None)
                }
            }
            Text("Or type this in the other phone's browser:", style = MaterialTheme.typography.bodySmall)
            Text(url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text(
                when (state.status) {
                    WifiStatus.WAITING -> "Waiting for the receiver to open the link…"
                    WifiStatus.PAGE_OPENED -> "Receiver opened the page — waiting for Accept/Reject…"
                    WifiStatus.ACCEPTED -> "Receiver accepted — waiting for the download…"
                    else -> ""
                } + "  (expires in ${formatRemaining(left)})",
            )
            Button(onClick = { vm.stopWifi() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel session") }
            InfoBox("Not encrypted: plain HTTP on your local network, protected by a random one-time link that expires. Use only on your own hotspot or a network you trust.", warning = true)
        }

        when (state.status) {
            WifiStatus.DELIVERED -> InfoBox("The receiver downloaded your card. Session closed.")
            WifiStatus.REJECTED -> InfoBox("The receiver declined. Nothing was sent.", warning = true)
            WifiStatus.EXPIRED -> InfoBox("The session expired before it was completed.", warning = true)
            WifiStatus.STOPPED -> InfoBox("Session cancelled.")
            WifiStatus.FAILED -> InfoBox(state.message ?: "Something went wrong.", warning = true)
            else -> {}
        }
        Text("If the browser can't open the page: make sure both phones are on the same hotspot/Wi-Fi, turn off mobile data on the receiver if it keeps switching networks, and type the address exactly. Some browsers warn about downloading over http — choose Keep.",
            style = MaterialTheme.typography.bodySmall)
    }
}
