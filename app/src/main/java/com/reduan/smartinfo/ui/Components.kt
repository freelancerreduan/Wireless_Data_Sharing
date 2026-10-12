package com.reduan.smartinfo.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.reduan.smartinfo.profile.ProfileField
import com.reduan.smartinfo.ui.theme.Cyan
import com.reduan.smartinfo.ui.theme.Navy
import com.reduan.smartinfo.ui.theme.NavyLight
import com.reduan.smartinfo.ui.theme.Warning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)?,
    snackbar: SnackbarHostState? = null,
    actions: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = { actions() },
            )
        },
        bottomBar = bottomBar,
        snackbarHost = { snackbar?.let { SnackbarHost(it) } },
    ) { padding ->
        val mod = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
        Column(
            modifier = if (scroll) mod.verticalScroll(rememberScrollState()) else mod,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Loads the locally stored profile photo off the main thread. */
@Composable
fun rememberPhoto(path: String?, version: Long): ImageBitmap? {
    val bmp by produceState<ImageBitmap?>(null, path, version) {
        value = if (path == null) null else withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        }
    }
    return bmp
}

@Composable
fun Avatar(photo: ImageBitmap?, size: Dp, contentDescription: String?) {
    Box(
        Modifier.size(size).clip(CircleShape).background(NavyLight),
        contentAlignment = Alignment.Center,
    ) {
        if (photo != null) Image(photo, contentDescription, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.Person, contentDescription, tint = Cyan, modifier = Modifier.size(size * 0.55f))
    }
}

/** Live business-card preview. Shows exactly the entries passed in (i.e. only what will be shared). */
@Composable
fun BusinessCardPreview(
    entries: List<Pair<ProfileField, String>>,
    extraLinks: List<Pair<String, String>>,
    photo: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val name = entries.firstOrNull { it.first == ProfileField.FULL_NAME }?.second
    val title = listOfNotNull(
        entries.firstOrNull { it.first == ProfileField.JOB_TITLE }?.second,
        entries.firstOrNull { it.first == ProfileField.COMPANY }?.second,
    ).joinToString(" · ")
    val rest = entries.filter { it.first !in setOf(ProfileField.FULL_NAME, ProfileField.JOB_TITLE, ProfileField.COMPANY) }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Column(
            Modifier.background(Brush.linearGradient(listOf(NavyLight, Navy))).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(photo, 64.dp, "Profile photo")
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(name ?: "No name shared", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (title.isNotEmpty()) Text(title, color = Cyan, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (rest.isEmpty() && extraLinks.isEmpty() && name == null && title.isEmpty()) {
                Text("Nothing is selected for sharing yet.", color = Color(0xFFB8C7E6))
            }
            rest.forEach { (f, v) -> CardLine(f.label, v) }
            extraLinks.forEach { (l, v) -> CardLine(l, v) }
        }
    }
}

@Composable
private fun CardLine(label: String, value: String) {
    Column {
        Text(label, color = Cyan, style = MaterialTheme.typography.labelSmall)
        Text(value, color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun InfoBox(text: String, warning: Boolean = false) {
    val color = if (warning) Warning else MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.12f)).padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(if (warning) Icons.Default.ErrorOutline else Icons.Default.Info, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
}

@Composable
fun rememberNow(): Long = remember { System.currentTimeMillis() }

fun formatRemaining(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
