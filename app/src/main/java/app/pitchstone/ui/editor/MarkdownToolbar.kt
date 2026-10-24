package app.pitchstone.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.material3.ripple
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The formatting toolbar above the keyboard. Its buttons are not focusable, so tapping one
 * leaves the cursor and the keyboard in the editor.
 */
@Composable
fun MarkdownToolbar(
    state: ToolbarState,
    onCommand: (String, kotlinx.serialization.json.JsonObject?) -> Unit,
    onWikilink: () -> Unit,
    /** Pick a picture to put in the note. */
    onImage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp, modifier = modifier) {
        Box {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(
                Modifier.fillMaxWidth().height(48.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolButton(Icons.AutoMirrored.Filled.Undo, "Undo", enabled = state.canUndo) { onCommand("undo", null) }
                ToolButton(Icons.AutoMirrored.Filled.Redo, "Redo", enabled = state.canRedo) { onCommand("redo", null) }
                Separator()
                HeadingButton(state, onCommand)
                ToolButton(Icons.Filled.FormatBold, "Bold", active = state.strong) { onCommand("bold", null) }
                ToolButton(Icons.Filled.FormatItalic, "Italic", active = state.em) { onCommand("italic", null) }
                ToolButton(Icons.Filled.FormatStrikethrough, "Strikethrough", active = state.strike) { onCommand("strike", null) }
                ToolButton(Icons.Outlined.BorderColor, "Highlight", active = state.highlight) { onCommand("highlight", null) }
                ToolButton(Icons.Filled.Code, "Inline code", active = state.code) { onCommand("code", null) }
                Separator()
                ToolButton(Icons.AutoMirrored.Filled.FormatListBulleted, "Bulleted list", active = state.list == "bullet" && !state.task) {
                    onCommand("bulletList", null)
                }
                ToolButton(Icons.Filled.FormatListNumbered, "Numbered list", active = state.list == "ordered") { onCommand("orderedList", null) }
                ToolButton(Icons.Filled.Checklist, "Checklist", active = state.task) { onCommand("taskList", null) }
                ToolButton(Icons.AutoMirrored.Filled.FormatIndentDecrease, "Outdent", enabled = state.canOutdent) { onCommand("outdent", null) }
                ToolButton(Icons.AutoMirrored.Filled.FormatIndentIncrease, "Indent", enabled = state.canIndent) { onCommand("indent", null) }
                Separator()
                ToolButton(Icons.Filled.Link, "Link", active = state.link) { onCommand("link", null) }
                TextToolButton("[[ ]]", "Link to a note", onClick = onWikilink)
                ToolButton(Icons.Outlined.Image, "Insert picture", onClick = onImage)
                ToolButton(Icons.Filled.FormatQuote, "Quote", active = state.quote) { onCommand("blockquote", null) }
                ToolButton(Icons.Filled.DataObject, "Code block", active = state.block == "code_block") { onCommand("codeBlock", null) }
                TableButton(state, onCommand)
                ToolButton(Icons.Filled.HorizontalRule, "Divider") { onCommand("horizontalRule", null) }
                TextToolButton("[^1]", "Footnote") { onCommand("footnote", null) }
                ToolButton(Icons.Filled.FormatClear, "Clear formatting") { onCommand("clearFormatting", null) }
            }
        }
    }
}

@Composable
private fun Separator() {
    VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/** A clickable that never takes focus (focus would close the keyboard of the editor). */
private fun Modifier.toolClick(label: String, enabled: Boolean, active: Boolean, onClick: () -> Unit): Modifier =
    this.semantics(mergeDescendants = true) {
        contentDescription = label
        role = Role.Button
        selected = active
    }.clickable(
        // null: clickable keeps its own source, stable across recompositions (a new one each
        // time would cut a ripple short whenever the toolbar state changes).
        interactionSource = null,
        indication = ripple(bounded = false, radius = 22.dp),
        enabled = enabled,
        onClick = onClick,
    )

@Composable
private fun ToolButton(icon: ImageVector, label: String, enabled: Boolean = true, active: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.size(44.dp).padding(4.dp).clip(RoundedCornerShape(10.dp))
            .background(if (active) colors.secondaryContainer else colors.surfaceContainer)
            .toolClick(label, enabled, active, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = when {
                !enabled -> colors.onSurface.copy(alpha = 0.3f)
                active -> colors.onSecondaryContainer
                else -> colors.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun TextToolButton(text: String, label: String, active: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.height(44.dp).padding(4.dp).clip(RoundedCornerShape(10.dp))
            .background(if (active) colors.secondaryContainer else colors.surfaceContainer)
            .toolClick(label, true, active, onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = if (active) colors.onSecondaryContainer else colors.onSurfaceVariant)
    }
}

@Composable
private fun HeadingButton(state: ToolbarState, onCommand: (String, kotlinx.serialization.json.JsonObject?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        if (state.headingLevel > 0) {
            TextToolButton("H${state.headingLevel}", "Heading level", active = true) { open = true }
        } else {
            ToolButton(Icons.Filled.Title, "Heading") { open = true }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Normal text") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Notes, null) },
                onClick = {
                    open = false
                    onCommand("heading", buildJsonObject { put("level", 0) })
                },
            )
            for (level in 1..6) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "Heading $level",
                            fontWeight = FontWeight.Bold,
                            fontSize = (22 - level * 1.5).sp,
                            color = if (state.headingLevel == level) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = {
                        open = false
                        onCommand("heading", buildJsonObject { put("level", level) })
                    },
                )
            }
        }
    }
}

@Composable
private fun TableButton(state: ToolbarState, onCommand: (String, kotlinx.serialization.json.JsonObject?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolButton(Icons.Filled.TableChart, "Table", active = state.table) {
            if (state.table) open = true else onCommand("insertTable", buildJsonObject { put("rows", 3); put("cols", 3) })
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val items = listOf(
                Triple("Add row below", "addRowAfter", null),
                Triple("Add column right", "addColumnAfter", null),
                Triple("Delete row", "deleteRow", null),
                Triple("Delete column", "deleteColumn", null),
                Triple("Align column left", "alignColumn", buildJsonObject { put("align", "left") }),
                Triple("Center column", "alignColumn", buildJsonObject { put("align", "center") }),
                Triple("Align column right", "alignColumn", buildJsonObject { put("align", "right") }),
                Triple("Delete table", "deleteTable", null),
            )
            for ((label, command, args) in items) {
                DropdownMenuItem(
                    text = { Text(label, color = if (command == "deleteTable") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
                    onClick = {
                        open = false
                        onCommand(command, args)
                    },
                )
            }
        }
    }
}
