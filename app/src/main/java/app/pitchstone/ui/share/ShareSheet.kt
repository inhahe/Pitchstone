package app.pitchstone.ui.share

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pitchstone.container
import app.pitchstone.data.ShareFormat
import app.pitchstone.share.Sharing
import app.pitchstone.ui.export.SwitchRow
import kotlinx.coroutines.launch

/** Email or share a note. [onError] reports a problem (no email app, say). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(title: String, markdown: String, onDismiss: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val settings by container.settings.settings.collectAsState(initial = null)
    val s = settings ?: return
    val scope = rememberCoroutineScope()

    // Problems are reported after the sheet closes, where the message can be seen.
    fun start(build: () -> Intent, missing: String) {
        val problem = try {
            context.startActivity(build())
            null
        } catch (_: ActivityNotFoundException) {
            missing
        } catch (e: Exception) {
            e.message ?: "Could not share the note"
        }
        onDismiss()
        problem?.let(onError)
    }

    // Fully expanded, scrolling if the screen is short: every action must be reachable.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Share “${title.ifBlank { "Untitled" }}”",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("Format", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ShareFormat.entries.forEachIndexed { i, format ->
                    SegmentedButton(
                        selected = s.shareFormat == format,
                        onClick = { scope.launch { container.settings.update { it.copy(shareFormat = format) } } },
                        shape = SegmentedButtonDefaults.itemShape(i, ShareFormat.entries.size),
                    ) { Text(format.label) }
                }
            }
            Text(
                when (s.shareFormat) {
                    ShareFormat.HTML -> "Headings, bold, lists, links and tables appear formatted in the email."
                    ShareFormat.MARKDOWN -> "The note's markdown, symbols and all."
                    ShareFormat.PLAIN -> "Just the text, without formatting symbols."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SwitchRow("Attach as a file", "Also attach the note to the email (.html, .md or .txt)", s.shareAttachFile) { v ->
                scope.launch { container.settings.update { it.copy(shareAttachFile = v) } }
            }
            Button(
                onClick = {
                    if (Sharing.emailApps(context).isEmpty()) {
                        onDismiss()
                        onError("No email app is installed. Use “Share as text” instead.")
                    } else {
                        start({ Sharing.emailIntent(context, title, markdown, s.shareFormat, s.shareAttachFile) }, "No email app is installed")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(Icons.Outlined.Email, null)
                Spacer(Modifier.padding(4.dp))
                Text("Email")
            }
            OutlinedButton(
                onClick = { start({ Sharing.textIntent(title, markdown, s.shareFormat) }, "No app can receive the note") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Share, null)
                Spacer(Modifier.padding(4.dp))
                Text("Share as text…")
            }
            OutlinedButton(
                onClick = { start({ Sharing.fileIntent(context, title, markdown, s.shareFormat) }, "No app can receive the file") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, null)
                Spacer(Modifier.padding(4.dp))
                Text("Share as file…")
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
