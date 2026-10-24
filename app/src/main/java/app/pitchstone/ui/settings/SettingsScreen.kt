package app.pitchstone.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pitchstone.BuildConfig
import app.pitchstone.container
import app.pitchstone.data.AppSettings
import app.pitchstone.data.ThemeMode
import app.pitchstone.ui.export.SwitchRow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val container = LocalContext.current.container
    val settings by container.settings.settings.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    fun update(transform: (AppSettings) -> AppSettings) = scope.launch { container.settings.update(transform) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        val s = settings ?: return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Section("Editing")
            SwitchRow(
                "Markdown shortcuts",
                "Typing markdown formats as you go: **bold**, # heading, - list, [[link]]. " +
                    "Off: what you type stays exactly as typed, and only the toolbar formats.",
                s.markdownShortcuts,
            ) { v -> update { it.copy(markdownShortcuts = v) } }
            SwitchRow("Formatting toolbar", "Show the toolbar above the keyboard", s.showToolbar) { v -> update { it.copy(showToolbar = v) } }
            SwitchRow("Spell check", "Underline misspelled words", s.spellcheck) { v -> update { it.copy(spellcheck = v) } }
            SwitchRow("Open notes in reading view", "Tap the pencil to edit", s.openInReadingMode) { v -> update { it.copy(openInReadingMode = v) } }
            SwitchRow(
                "Update links when renaming",
                "When a note's title changes, [[links]] to it in other notes change too",
                s.updateLinksOnRename,
            ) { v -> update { it.copy(updateLinksOnRename = v) } }
            SwitchRow(
                "Shrink large photos",
                "Photos put in notes are made at most 2560 pixels wide or high (screenshots and drawings stay as they are)",
                s.shrinkLargePhotos,
            ) { v -> update { it.copy(shrinkLargePhotos = v) } }

            Section("Sync with a folder")
            SyncSection(s.syncFolderUri)

            Section("Text")
            var size by remember(s.fontSize) { mutableFloatStateOf(s.fontSize.toFloat()) }
            Text("Text size: ${size.toInt()}", style = MaterialTheme.typography.bodyLarge)
            Text("The quick brown fox", fontSize = size.sp, modifier = Modifier.padding(vertical = 4.dp))
            Slider(
                value = size,
                onValueChange = { size = it },
                onValueChangeFinished = { update { it.copy(fontSize = size.toInt()) } },
                valueRange = 12f..28f,
                steps = 15,
            )
            SwitchRow("Comfortable line length", "On wide screens, keep lines short enough to read easily", s.readableLineLength) { v ->
                update { it.copy(readableLineLength = v) }
            }

            Section("Appearance")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                val modes = listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark")
                modes.forEachIndexed { i, (mode, label) ->
                    SegmentedButton(
                        selected = s.theme == mode,
                        onClick = { update { it.copy(theme = mode) } },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    ) { Text(label) }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SwitchRow("Wallpaper colours", "Take the app's colours from your wallpaper", s.dynamicColor) { v -> update { it.copy(dynamicColor = v) } }
            }

            Section("About")
            Text("Pitchstone Notes ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Notes are stored on this phone and included in your phone's backup. " +
                    "Deleted notes stay in the trash for 30 days; earlier versions of each note are kept in its version history.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 32.dp),
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 12.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}
