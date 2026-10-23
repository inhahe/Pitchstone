package app.inkdown

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import app.inkdown.export.Importer
import app.inkdown.ui.AppNavigation
import app.inkdown.ui.NoteRoute
import app.inkdown.ui.theme.InkdownTheme
import app.inkdown.ui.theme.isDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    /** A note to open, created from an incoming share or file. */
    private val openNote = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent {
            val settings by container.settings.settings.collectAsState(initial = null)
            val s = settings ?: return@setContent
            val dark = isDark(s.theme)
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            InkdownTheme(s.theme, s.dynamicColor) {
                val nav = rememberNavController()
                val pending by openNote.collectAsState()
                LaunchedEffect(pending) {
                    pending?.let {
                        openNote.value = null
                        nav.navigate(NoteRoute(it))
                    }
                }
                Surface(color = MaterialTheme.colorScheme.background) { AppNavigation(nav) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Text shared to the app becomes a note; a markdown file opened with it is imported. */
    private fun handle(intent: Intent?) {
        intent ?: return
        val repo = container.repository
        when (intent.action) {
            Intent.ACTION_SEND -> lifecycleScope.launch {
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                val id = if (stream != null) {
                    importFile(stream)
                } else {
                    val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: return@launch
                    val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
                    // Shared text is plain text: its symbols must stay literal in the note.
                    repo.create(title = subject, body = Importer.plainTextToMarkdown(text.replace("\r\n", "\n")))
                }
                if (id != null) openNote.value = id
            }
            Intent.ACTION_VIEW -> intent.data?.let { uri ->
                lifecycleScope.launch { importFile(uri)?.let { openNote.value = it } }
            }
        }
    }

    private suspend fun importFile(uri: Uri): Long? = withContext(Dispatchers.IO) {
        runCatching {
            var name = "Imported note"
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { name = it }
            }
            val text = contentResolver.openInputStream(uri)?.let(Importer::readText) ?: return@runCatching null
            val ext = name.substringAfterLast('.', "").lowercase()
            val body = if (ext == "txt") Importer.plainTextToMarkdown(text) else text
            container.repository.import(name.substringBeforeLast('.'), body, "", createdAt = null, updatedAt = null)
        }.getOrNull()
    }
}
