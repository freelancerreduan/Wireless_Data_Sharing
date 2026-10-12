package com.reduan.smartinfo.ui.screens

import android.bluetooth.BluetoothManager
import android.nfc.NfcAdapter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reduan.smartinfo.AppViewModel
import com.reduan.smartinfo.R
import com.reduan.smartinfo.localserver.LocalNetwork
import com.reduan.smartinfo.profile.ProfileField
import com.reduan.smartinfo.ui.Avatar
import com.reduan.smartinfo.ui.InfoBox
import com.reduan.smartinfo.ui.ScreenScaffold
import com.reduan.smartinfo.ui.SectionTitle
import com.reduan.smartinfo.ui.rememberPhoto
import com.reduan.smartinfo.ui.theme.Cyan
import com.reduan.smartinfo.ui.theme.Navy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object Routes {
    const val HOME = "home"; const val EDIT = "edit"; const val CARD = "card"; const val QR = "qr"
    const val NFC = "nfc"; const val NEARBY = "nearby"; const val VERY_NEAR = "verynear"; const val WIFI = "wifi"; const val ABOUT = "about"
}

private data class DeviceStatus(val bluetooth: String, val nfc: String, val network: String)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(vm: AppViewModel, navigate: (String) -> Unit) {
    val context = LocalContext.current
    val profile by vm.profile.collectAsStateWithLifecycle()
    val shareable by vm.shareable.collectAsStateWithLifecycle()
    val visibility by vm.nearbyServer.state.collectAsStateWithLifecycle()
    val scanning by vm.scanner.scanning.collectAsStateWithLifecycle()
    val wifi by vm.wifi.collectAsStateWithLifecycle()
    val status by produceState(DeviceStatus("…", "…", "…")) {
        while (true) {
            val bt = context.getSystemService(BluetoothManager::class.java)?.adapter
            val nfc = NfcAdapter.getDefaultAdapter(context)
            val net = LocalNetwork.candidates().firstOrNull()
            value = DeviceStatus(
                bluetooth = when { bt == null -> "Not supported"; bt.isEnabled -> "On"; else -> "Off" },
                nfc = when { nfc == null -> "Not supported"; nfc.isEnabled -> "On"; else -> "Off" },
                network = net?.address?.hostAddress ?: "None",
            )
            delay(2000)
        }
    }
    val p = profile ?: return
    val photo = rememberPhoto(p.photoPath, p.updatedAt)
    val anyActive = visibility.visible || scanning || wifi.active

    ScreenScaffold("Reduan Smart Info", onBack = null, actions = {
        Image(painterResource(R.drawable.app_logo), contentDescription = null, modifier = Modifier.padding(end = 12.dp).size(32.dp))
    }) {
        Card(Modifier.fillMaxWidth().clickable { navigate(if (p.isEmpty) Routes.EDIT else Routes.CARD) }) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(photo, 56.dp, "Profile photo")
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    if (p.isEmpty) {
                        Text("Create your profile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Add your details once, then share them by QR, NFC, Bluetooth or Wi-Fi.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(p[ProfileField.FULL_NAME].ifEmpty { "Unnamed profile" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        val sub = listOf(p[ProfileField.JOB_TITLE], p[ProfileField.COMPANY]).filter { it.isNotEmpty() }.joinToString(" · ")
                        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall)
                        Text("${shareable?.summary()?.size ?: 0} item(s) selected for sharing", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                OutlinedButton(onClick = { navigate(Routes.EDIT) }) { Text(if (p.isEmpty) "Create" else "Edit") }
            }
        }

        SectionTitle("Status")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = {}, label = { Text("Bluetooth: ${status.bluetooth}") })
            AssistChip(onClick = {}, label = { Text("NFC: ${status.nfc}") })
            AssistChip(onClick = {}, label = { Text("Local network: ${status.network}") })
            AssistChip(onClick = {}, label = { Text("Nearby visibility: ${if (visibility.visible) "On" else "Off"}") })
            AssistChip(onClick = {}, label = { Text("Wi-Fi session: ${if (wifi.active) "Active" else "None"}") })
            AssistChip(onClick = {}, label = { Text("Internet needed: No") })
        }
        if (anyActive) Button(onClick = { vm.stopAllSharing() }, modifier = Modifier.fillMaxWidth()) { Text("Stop all sharing") }
        else Button(onClick = { navigate(Routes.NEARBY) }, enabled = !p.isEmpty, modifier = Modifier.fillMaxWidth()) { Text("Start sharing nearby") }
        visibility.error?.let { InfoBox(it, warning = true) }

        SectionTitle("Share")
        Tile(Icons.Default.Badge, "My digital business card", "Preview, choose fields, export .vcf") { navigate(Routes.CARD) }
        Tile(Icons.Default.QrCode2, "QR code", "Offline contact QR — any phone can scan") { navigate(Routes.QR) }
        Tile(Icons.Default.Wifi, "Wi-Fi share (no app needed)", "Receiver uses a browser over your hotspot") { navigate(Routes.WIFI) }
        Tile(Icons.Default.Nfc, "NFC tag", "Write your card or link to an NFC tag; read tags") { navigate(Routes.NFC) }
        Tile(Icons.Default.Bluetooth, "Nearby devices", "Find other Reduan Smart Info users") { navigate(Routes.NEARBY) }
        Tile(Icons.Default.BluetoothSearching, "Very Near mode", "Only app users with a strong, stable signal") { navigate(Routes.VERY_NEAR) }
        Tile(Icons.Default.Info, "Privacy & about", "What's stored, what's shared, feature status") { navigate(Routes.ABOUT) }
    }
}

@Composable
private fun Tile(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Short branded intro after the system splash. Tap to skip; no fake loading. */
@Composable
fun IntroScreen(onDone: () -> Unit) {
    val scale = remember { Animatable(0.7f) }
    val alpha = remember { Animatable(0f) }
    val pulse by rememberInfiniteTransition(label = "signal").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "pulse",
    )
    LaunchedEffect(Unit) {
        launch { scale.animateTo(1f, tween(600)) }
        alpha.animateTo(1f, tween(500))
        delay(700)
        onDone()
    }
    Box(Modifier.fillMaxSize().background(Navy).clickable { onDone() }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(220.dp)) {
                    for (i in 0..2) {
                        val t = (pulse + i / 3f) % 1f
                        val r = size.minDimension / 2 * (0.55f + 0.45f * t)
                        drawArc(
                            color = Cyan.copy(alpha = (1f - t) * 0.6f), startAngle = -60f, sweepAngle = 120f, useCenter = false,
                            topLeft = Offset(center.x - r, center.y - r), size = Size(r * 2, r * 2), style = Stroke(width = 6f),
                        )
                    }
                }
                Image(painterResource(R.drawable.app_logo), "Reduan Smart Info logo", Modifier.size(130.dp).scale(scale.value))
            }
            Spacer(Modifier.height(20.dp))
            Text("Reduan Smart Info", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, modifier = Modifier.alpha(alpha.value))
            Text("Share your card — offline", color = Cyan, modifier = Modifier.alpha(alpha.value))
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    ScreenScaffold("Privacy & about", onBack) {
        Image(painterResource(R.drawable.app_logo), null, Modifier.size(96.dp).align(Alignment.CenterHorizontally))
        Text("Reduan Smart Info · v0.1.0", Modifier.align(Alignment.CenterHorizontally))
        SectionTitle("Privacy")
        listOf(
            "Your profile is stored only on this phone (app-private storage). It is never uploaded, and it is excluded from cloud backup.",
            "Only the items you switch on in My Card are shared — by every method. Phone, email, address and WhatsApp are off until you enable them.",
            "Bluetooth advertisements contain only a random session id — no name or contact details.",
            "Nearby visibility and Wi-Fi sessions stop automatically and you can stop them at any time.",
            "No analytics or tracking.",
            "Delete everything from Edit profile → delete icon.",
        ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        SectionTitle("Feature status (this version)")
        listOf(
            "Ready: profile editor with validation, photo, extra links, reset",
            "Ready: field-level sharing controls, live card preview",
            "Ready: .vcf share / save, offline QR, NFC tag write & read",
            "Ready: Bluetooth discovery + card exchange between app users (foreground only)",
            "Ready: Wi-Fi/hotspot card share to a browser with Accept/Reject",
            "Not yet: sending arbitrary files, QR scanning inside the app, sharing history, background Bluetooth service, Bangla UI translation",
        ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    }
}
