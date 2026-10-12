package com.reduan.smartinfo.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reduan.smartinfo.AppViewModel
import com.reduan.smartinfo.profile.FieldGroup
import com.reduan.smartinfo.profile.FieldType
import com.reduan.smartinfo.profile.Profile
import com.reduan.smartinfo.profile.ProfileField
import com.reduan.smartinfo.profile.ProfileValidator
import com.reduan.smartinfo.profile.SocialLink
import com.reduan.smartinfo.ui.Avatar
import com.reduan.smartinfo.ui.InfoBox
import com.reduan.smartinfo.ui.ScreenScaffold
import com.reduan.smartinfo.ui.SectionTitle
import com.reduan.smartinfo.ui.rememberPhoto
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun EditProfileScreen(vm: AppViewModel, onBack: () -> Unit) {
    val saved by vm.profile.collectAsStateWithLifecycle()
    val profile = saved ?: return
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // Draft = what's in the text boxes. Loaded from the saved profile; only written on Save.
    val draft = remember { mutableStateMapOf<ProfileField, String>() }
    val links = remember { mutableStateListOf<SocialLink>() }
    var loaded by remember { mutableStateOf(false) }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    fun loadFrom(p: Profile) {
        draft.clear(); ProfileField.entries.forEach { draft[it] = p[it] }
        links.clear(); links.addAll(p.links)
        errors = emptyMap()
    }
    LaunchedEffect(Unit) { if (!loaded) { loadFrom(profile); loaded = true } }

    val dirty = loaded && (ProfileField.entries.any { draft[it].orEmpty().trim() != profile[it] } || links.toList() != profile.links)

    val photo = rememberPhoto(profile.photoPath, profile.updatedAt)
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            vm.setPhoto(uri).onFailure { snackbar.showSnackbar(it.message ?: "Could not use this image") }
                .onSuccess { snackbar.showSnackbar("Photo updated") }
        }
    }

    fun save() {
        if (saving) return
        saving = true
        scope.launch {
            val candidate = profile.copy(values = draft.toMap(), links = links.toList())
            errors = vm.saveDetails(candidate)
            saving = false
            if (errors.isEmpty()) {
                snackbar.showSnackbar("Profile saved")
                loadFrom(ProfileValidator.normalize(candidate))
            } else snackbar.showSnackbar("Please fix ${errors.size} field(s)")
        }
    }

    BackHandler(enabled = dirty) { confirmLeave = true }

    ScreenScaffold(
        title = if (profile.isEmpty) "Create profile" else "Edit profile",
        onBack = { if (dirty) confirmLeave = true else onBack() },
        snackbar = snackbar,
        actions = {
            if (!profile.isEmpty) IconButton(onClick = { confirmReset = true }) {
                Icon(Icons.Default.DeleteForever, contentDescription = "Reset profile")
            }
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { loadFrom(profile) }, enabled = dirty, modifier = Modifier.weight(1f)) { Text("Discard changes") }
                    Button(onClick = ::save, enabled = dirty && !saving, modifier = Modifier.weight(1f)) { Text(if (saving) "Saving…" else "Save") }
                }
            }
        },
    ) {
        InfoBox("Everything is stored only on this phone. You choose what to share on the My Card screen.")

        // Photo
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(photo, 72.dp, "Profile photo")
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Text(if (profile.photoPath == null) "Add photo" else "Change photo")
                    }
                    if (profile.photoPath != null) TextButton(onClick = { vm.removePhoto() }) { Text("Remove photo") }
                }
            }
        }

        FieldGroup.entries.forEach { group ->
            SectionTitle(group.title)
            ProfileField.entries.filter { it.group == group }.forEach { field ->
                val err = errors[field.name]
                OutlinedTextField(
                    value = draft[field].orEmpty(),
                    onValueChange = { v ->
                        if (v.length <= field.maxLength) draft[field] = v
                        if (errors.containsKey(field.name)) errors = errors - field.name
                    },
                    label = { Text(field.label + if (field == ProfileField.FULL_NAME) " *" else "") },
                    isError = err != null,
                    supportingText = {
                        when {
                            err != null -> Text(err)
                            field.type == FieldType.MULTILINE -> Text("${draft[field].orEmpty().length}/${field.maxLength}")
                            field.type == FieldType.URL -> Text("e.g. example.com/yourname")
                            field.sensitive -> Text("Private by default — not shared unless you turn it on")
                        }
                    },
                    singleLine = field.type != FieldType.MULTILINE,
                    minLines = if (field.type == FieldType.MULTILINE) 2 else 1,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = when (field.type) {
                            FieldType.PHONE -> KeyboardType.Phone
                            FieldType.EMAIL -> KeyboardType.Email
                            FieldType.URL -> KeyboardType.Uri
                            else -> KeyboardType.Text
                        },
                        imeAction = if (field.type == FieldType.MULTILINE) ImeAction.Default else ImeAction.Next,
                    ),
                    trailingIcon = {
                        if (draft[field].orEmpty().isNotEmpty()) IconButton(onClick = { draft[field] = "" }) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear ${field.label}")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SectionTitle("Other links")
        links.forEachIndexed { index, link ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = link.label,
                            onValueChange = { v -> if (v.length <= ProfileValidator.MAX_LINK_LABEL) links[index] = link.copy(label = v) },
                            label = { Text("Label (e.g. GitHub, TikTok)") },
                            isError = errors.containsKey("link_label:${link.id}"),
                            supportingText = { errors["link_label:${link.id}"]?.let { Text(it) } },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { links.removeAt(index) }) { Icon(Icons.Default.Delete, contentDescription = "Delete link") }
                    }
                    OutlinedTextField(
                        value = link.url,
                        onValueChange = { v -> if (v.length <= 300) links[index] = link.copy(url = v) },
                        label = { Text("Link") },
                        isError = errors.containsKey("link_url:${link.id}"),
                        supportingText = { errors["link_url:${link.id}"]?.let { Text(it) } },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        if (links.size < ProfileValidator.MAX_LINKS) {
            OutlinedButton(onClick = { links.add(SocialLink(UUID.randomUUID().toString(), "", "")) }) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add link")
            }
        }
        Text("* required", style = MaterialTheme.typography.bodySmall)
    }

    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Delete profile?") },
        text = { Text("This removes your name, contact details, photo, links and sharing choices from this phone. Active sharing sessions will stop. This cannot be undone.") },
        confirmButton = {
            TextButton(onClick = {
                confirmReset = false
                vm.resetProfile()
                loadFrom(Profile())
                scope.launch { snackbar.showSnackbar("Profile deleted") }
            }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
    )

    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false },
        title = { Text("Unsaved changes") },
        text = { Text("Save your changes before leaving?") },
        confirmButton = { TextButton(onClick = { confirmLeave = false; save() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { confirmLeave = false; loadFrom(profile); onBack() }) { Text("Discard") } },
    )
}
