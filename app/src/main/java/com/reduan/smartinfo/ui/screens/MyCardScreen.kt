package com.reduan.smartinfo.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reduan.smartinfo.AppViewModel
import com.reduan.smartinfo.profile.ProfileField
import com.reduan.smartinfo.storage.FileNames
import com.reduan.smartinfo.ui.BusinessCardPreview
import com.reduan.smartinfo.ui.InfoBox
import com.reduan.smartinfo.ui.ScreenScaffold
import com.reduan.smartinfo.ui.SectionTitle
import com.reduan.smartinfo.ui.rememberPhoto
import com.reduan.smartinfo.ui.theme.Warning
import kotlinx.coroutines.launch

@Composable
fun MyCardScreen(vm: AppViewModel, onEdit: () -> Unit, onBack: () -> Unit) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    val shareable by vm.shareable.collectAsStateWithLifecycle()
    val p = profile ?: return
    val s = shareable ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val photo = rememberPhoto(s.photoPath, p.updatedAt)

    val saveDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-vcard")) { uri ->
        if (uri != null) scope.launch {
            vm.saveVCardTo(uri).onSuccess { snackbar.showSnackbar("Contact file saved") }
                .onFailure { snackbar.showSnackbar(it.message ?: "Could not save the file") }
        }
    }

    ScreenScaffold(
        title = "My digital business card",
        onBack = onBack,
        snackbar = snackbar,
        actions = { IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit profile") } },
    ) {
        if (p.isEmpty) {
            InfoBox("You haven't created a profile yet.")
            Button(onClick = onEdit) { Text("Create profile") }
            return@ScreenScaffold
        }

        Text("Preview — exactly what others receive", style = MaterialTheme.typography.labelLarge)
        BusinessCardPreview(s.entries, s.links.map { it.label to it.url }, photo)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                enabled = !s.isEmpty,
                onClick = {
                    scope.launch {
                        val intent = vm.shareVCardIntent()
                        if (intent != null) context.startActivity(intent) else snackbar.showSnackbar("Select at least one item to share")
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text("Share .vcf") }
            OutlinedButton(
                enabled = !s.isEmpty,
                onClick = { saveDoc.launch(FileNames.sanitize(s.displayName) + ".vcf") },
                modifier = Modifier.weight(1f),
            ) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(6.dp)); Text("Save .vcf") }
        }

        SectionTitle("Choose what to share")
        InfoBox("These choices apply to QR, NFC, Bluetooth, the .vcf file and Wi-Fi sharing. Items marked with a lock are private and off by default.")
        if (s.isEmpty) InfoBox("Nothing is selected — sharing is disabled until you turn something on.", warning = true)

        val sel = p.sharing
        val filled = ProfileField.entries.filter { p[it].isNotEmpty() }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp)) {
                if (p.photoPath != null) {
                    ShareToggle("Photo", "Embedded in .vcf file and Wi-Fi share only", false, sel.includePhoto) {
                        vm.saveSharing(sel.copy(includePhoto = it))
                    }
                    HorizontalDivider()
                }
                filled.forEachIndexed { i, f ->
                    ShareToggle(f.label, p[f], f.sensitive, f in sel.fields) { vm.saveSharing(sel.withField(f, it)) }
                    if (i < filled.lastIndex || p.links.isNotEmpty()) HorizontalDivider()
                }
                p.links.forEachIndexed { i, l ->
                    ShareToggle(l.label, l.url, false, l.id in sel.linkIds) { vm.saveSharing(sel.withLink(l.id, it)) }
                    if (i < p.links.lastIndex) HorizontalDivider()
                }
                if (filled.isEmpty() && p.links.isEmpty()) {
                    Text("Add details in Edit profile first.", Modifier.padding(16.dp))
                }
            }
        }
        Row {
            TextButton(onClick = { vm.saveSharing(sel.copy(fields = filled.filter { !it.sensitive }.toSet(), linkIds = emptySet(), includePhoto = false)) }) {
                Text("Public fields only")
            }
            TextButton(onClick = { vm.saveSharing(sel.copy(fields = emptySet(), linkIds = emptySet(), includePhoto = false)) }) {
                Text("Clear all")
            }
        }
    }
}

@Composable
private fun ShareToggle(title: String, value: String, private: Boolean, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (private) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.Lock, contentDescription = "Private", tint = Warning, modifier = Modifier.padding(top = 2.dp))
                }
            }
            Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
