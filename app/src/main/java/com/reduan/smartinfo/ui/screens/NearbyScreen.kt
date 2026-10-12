package com.reduan.smartinfo.ui.screens

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reduan.smartinfo.AppViewModel
import com.reduan.smartinfo.bluetooth.Proximity
import com.reduan.smartinfo.bluetooth.ProximityConfig
import com.reduan.smartinfo.ui.InfoBox
import com.reduan.smartinfo.ui.ScreenScaffold
import com.reduan.smartinfo.ui.SectionTitle
import com.reduan.smartinfo.ui.formatRemaining
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun blePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31) arrayOf(
        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
    ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

fun hasBlePermissions(context: Context) =
    blePermissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

/** Ticks once a second and returns ms left until [until]. */
@Composable
fun rememberRemaining(until: Long): Long {
    val left by produceState(until - System.currentTimeMillis(), until) {
        while (true) {
            value = until - System.currentTimeMillis()
            if (value <= 0) break
            delay(1000)
        }
    }
    return left
}

@Composable
fun NearbyScreen(vm: AppViewModel, veryNearOnly: Boolean, onOpenCard: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val adapter = remember { context.getSystemService(BluetoothManager::class.java)?.adapter }
    var granted by remember { mutableStateOf(hasBlePermissions(context)) }
    val btOn by produceState(adapter?.isEnabled == true) { while (true) { value = adapter?.isEnabled == true; delay(1000) } }

    val serverState by vm.nearbyServer.state.collectAsStateWithLifecycle()
    val scanning by vm.scanner.scanning.collectAsStateWithLifecycle()
    val devices by vm.scanner.devices.collectAsStateWithLifecycle()
    val scanError by vm.scanner.error.collectAsStateWithLifecycle()
    val cardError by vm.bleCardError.collectAsStateWithLifecycle()
    val shareable by vm.shareable.collectAsStateWithLifecycle()
    val fetchingKey by vm.fetchingKey.collectAsStateWithLifecycle()
    val fetchError by vm.fetchError.collectAsStateWithLifecycle()
    val received by vm.received.collectAsStateWithLifecycle()

    var visibleMinutes by rememberSaveable { mutableIntStateOf(3) }
    var threshold by rememberSaveable { mutableIntStateOf(vm.scanner.tracker.config.veryNearRssi) }
    var scanSeconds by rememberSaveable { mutableLongStateOf(60L) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasBlePermissions(context)
    }
    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    ScreenScaffold(if (veryNearOnly) "Very Near mode" else "Nearby devices", onBack) {
        InfoBox("Only phones running Reduan Smart Info with visibility ON can be found. Distance is estimated from Bluetooth signal strength and is approximate — walls, bodies and phone models change it.")

        if (adapter == null) { InfoBox("This phone has no Bluetooth. Use QR or Wi-Fi sharing.", warning = true); return@ScreenScaffold }
        if (!granted) {
            InfoBox("Bluetooth \"Nearby devices\" permission is needed to find other app users and to let them find you. No location is collected." +
                if (Build.VERSION.SDK_INT < 31) " On this Android version Android requires the Location permission (and Location turned on) for Bluetooth scanning." else "", warning = true)
            Button(onClick = { permLauncher.launch(blePermissions()) }) { Text("Grant permission") }
            return@ScreenScaffold
        }
        if (!btOn) {
            InfoBox("Bluetooth is off.", warning = true)
            Button(onClick = {
                runCatching { enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            }) { Text("Turn on Bluetooth") }
            return@ScreenScaffold
        }

        // ---- My visibility ----
        SectionTitle("Let nearby app users get my card")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val s = shareable
                if (s == null || s.isEmpty) {
                    Text("Nothing selected for sharing.")
                    OutlinedButton(onClick = onOpenCard) { Text("Choose what to share") }
                } else {
                    Text("Shares: " + s.summary().filter { it.first != "Photo" }.joinToString { it.first }, style = MaterialTheme.typography.bodySmall)
                    cardError?.let { InfoBox(it, warning = true) }
                    if (serverState.visible) {
                        val left = rememberRemaining(serverState.visibleUntil)
                        Text("Visible · ${formatRemaining(left)} left · ${serverState.readers} phone(s) received your card")
                        Button(onClick = { vm.nearbyServer.stop() }) { Text("Stop sharing") }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1, 3, 5).forEach { m ->
                                FilterChip(selected = visibleMinutes == m, onClick = { visibleMinutes = m }, label = { Text("$m min") })
                            }
                        }
                        Button(onClick = { vm.nearbyServer.start(visibleMinutes * 60_000L) }, enabled = cardError == null) { Text("Start sharing") }
                    }
                    serverState.error?.let { InfoBox(it, warning = true) }
                    Text("While visible, any nearby app user can request these selected details. Bluetooth use increases battery drain; it stops automatically.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // ---- Scanning ----
        SectionTitle("Find nearby app users")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("\"Very near\" signal threshold: $threshold dBm (approx.)", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = threshold.toFloat(), onValueChange = { threshold = it.toInt() }, valueRange = -80f..-40f,
                    onValueChangeFinished = { vm.scanner.tracker.config = ProximityConfig(veryNearRssi = threshold) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30L, 60L, 120L).forEach { sec ->
                        FilterChip(selected = scanSeconds == sec, onClick = { scanSeconds = sec }, label = { Text("${sec}s") })
                    }
                }
                if (scanning) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp)); Text("Scanning…")
                        TextButton(onClick = { vm.scanner.stop() }) { Text("Stop") }
                    }
                } else Button(onClick = {
                    vm.scanner.tracker.config = ProximityConfig(veryNearRssi = threshold)
                    vm.scanner.start(scanSeconds * 1000)
                }) { Text("Start scan") }
                scanError?.let { InfoBox(it, warning = true) }
            }
        }

        val shown = if (veryNearOnly) devices.filter { it.proximity == Proximity.VERY_NEAR } else devices
        if (scanning && shown.isEmpty()) {
            Text(if (veryNearOnly) "No app user is very near yet. Hold the phones close together for a few seconds." else "No app users found yet.",
                style = MaterialTheme.typography.bodySmall)
        }
        shown.forEach { d ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("App user · ${d.key.take(4).uppercase()}")
                        AssistChip(onClick = {}, label = { Text("${d.proximity.label} · ${d.rssi} dBm") })
                    }
                    if (fetchingKey == d.key) CircularProgressIndicator(Modifier.size(24.dp))
                    else Button(onClick = { vm.fetchCard(d) }, enabled = fetchingKey == null) { Text("Get card") }
                }
            }
        }
        fetchError?.let { InfoBox(it, warning = true) }
    }

    received?.let { r ->
        AlertDialog(
            onDismissRequest = { vm.discardReceived() },
            title = { Text(r.parsed.displayName) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoBox("The sender's identity cannot be verified.", warning = true)
                    r.parsed.lines.forEach { (k, v) -> Text("$k: $v", style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        vm.importReceivedIntent()?.let { runCatching { context.startActivity(it) } }
                        vm.received.value = null
                    }
                }) { Text("Save to contacts") }
            },
            dismissButton = { TextButton(onClick = { vm.discardReceived() }) { Text("Discard") } },
        )
    }
}
