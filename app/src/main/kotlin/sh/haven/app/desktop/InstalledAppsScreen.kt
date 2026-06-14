package sh.haven.app.desktop

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.haven.app.R as AppR
import sh.haven.core.local.AppScanResult
import sh.haven.core.local.InstalledApp

/**
 * A flat, searchable list of the GUI apps installed in the active guest — the
 * xfce4-style application menu. Tapping a row launches the app in a cage window
 * (via [onLaunch]); a per-row toggle chooses whether it opens fullscreen
 * (default on — this is a launcher). Rendered full-screen (the caller hosts it
 * in a full-window Dialog), one pane at a time for portrait phones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstalledAppsScreen(
    result: AppScanResult?,
    scanning: Boolean,
    onLaunch: (InstalledApp, fullscreen: Boolean) -> Unit,
    onClose: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    // Per-app fullscreen choice, keyed by exec; defaults on for any not toggled.
    val fullscreenChoice = remember { mutableStateMapOf<String, Boolean>() }

    val apps = result?.apps.orEmpty()
    val filtered = remember(apps, query) {
        val q = query.trim()
        if (q.isBlank()) apps else apps.filter { it.name.contains(q, ignoreCase = true) }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(AppR.string.app_desktop_installed_apps_title))
                        result?.let {
                            Text(
                                stringResource(AppR.string.app_desktop_installed_apps_count, it.total, it.iconsResolved),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(AppR.string.app_desktop_installed_apps_close_cd))
                    }
                },
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                label = { Text(stringResource(AppR.string.app_desktop_installed_apps_search)) },
            )
            when {
                scanning && apps.isEmpty() ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                filtered.isEmpty() ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text(
                            stringResource(AppR.string.app_desktop_installed_apps_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filtered, key = { it.exec + " " + it.name }) { app ->
                        val fs = fullscreenChoice[app.exec] ?: true
                        InstalledAppRow(
                            app = app,
                            fullscreen = fs,
                            onToggleFullscreen = { fullscreenChoice[app.exec] = !fs },
                            onLaunch = {
                                onLaunch(app, fs)
                                onClose()
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun InstalledAppRow(
    app: InstalledApp,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onLaunch: () -> Unit,
) {
    val icon = remember(app.iconPath) {
        app.iconPath?.let { runCatching { BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull() }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onLaunch)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = stringResource(AppR.string.app_desktop_installed_app_icon_cd, app.name),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Filled.Apps,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Text(
            app.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onToggleFullscreen) {
            Icon(
                if (fullscreen) Icons.Filled.Fullscreen else Icons.Filled.FullscreenExit,
                contentDescription = stringResource(
                    if (fullscreen) AppR.string.app_desktop_installed_app_fullscreen_on_cd
                    else AppR.string.app_desktop_installed_app_fullscreen_off_cd,
                    app.name,
                ),
                tint = if (fullscreen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
