package app.inkdown.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.inkdown.data.FolderPaths
import app.inkdown.data.TagCount

@Composable
fun AppDrawer(
    scope: Scope,
    total: Int,
    folders: List<String>,
    folderCounts: Map<String, Int>,
    tags: List<TagCount>,
    trashCount: Int,
    onScope: (Scope) -> Unit,
    onNewFolder: (String) -> Unit,
    onRenameFolder: (String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onTrash: () -> Unit,
    onImport: () -> Unit,
    onExportAll: () -> Unit,
    onSettings: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    // A folder is listed when every folder above it is expanded.
    val visible = folders.filter { path -> FolderPaths.withAncestors(path).dropLast(1).all { it in expanded } }

    ModalDrawerSheet {
        LazyColumn(Modifier.padding(horizontal = 12.dp)) {
            item {
                Text(
                    "Inkdown",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 12.dp),
                )
                DrawerItem("All notes", Icons.Outlined.Description, scope == Scope.All, total.toString()) { onScope(Scope.All) }
                DrawerItem("Top level", Icons.Outlined.FolderOpen, scope == Scope.Folder(""), null) { onScope(Scope.Folder("")) }
                SectionHeader("Folders") {
                    IconButton(onClick = { onNewFolder("") }) { Icon(Icons.Outlined.Add, "New folder") }
                }
                if (folders.isEmpty()) {
                    Text("No folders yet", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
                }
            }
            items(visible, key = { "f:$it" }) { path ->
                val hasChildren = folders.any { FolderPaths.parent(it) == path && it != path }
                FolderItem(
                    path = path,
                    count = folderCounts[path] ?: 0,
                    selected = scope == Scope.Folder(path),
                    hasChildren = hasChildren,
                    expanded = path in expanded,
                    onToggle = { expanded = if (path in expanded) expanded - path else expanded + path },
                    onClick = { onScope(Scope.Folder(path)) },
                    onNewSubfolder = { onNewFolder(path) },
                    onRename = { onRenameFolder(path) },
                    onDelete = { onDeleteFolder(path) },
                )
            }
            if (tags.isNotEmpty()) {
                item { SectionHeader("Tags") {} }
                items(tags, key = { "t:${it.tag}" }) { tag ->
                    DrawerItem(
                        "#${tag.display}",
                        Icons.AutoMirrored.Outlined.Label,
                        scope == Scope.Tag(tag.tag),
                        tag.count.toString(),
                    ) { onScope(Scope.Tag(tag.tag)) }
                }
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                DrawerItem("Trash", Icons.Outlined.Delete, false, if (trashCount > 0) trashCount.toString() else null, onTrash)
                DrawerItem("Import notes…", Icons.Outlined.FileUpload, false, null, onImport)
                DrawerItem("Export all notes…", Icons.Outlined.FileDownload, false, null, onExportAll)
                DrawerItem("Settings", Icons.Outlined.Settings, false, null, onSettings)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        action()
    }
}

@Composable
private fun DrawerItem(label: String, icon: ImageVector, selected: Boolean, badge: String?, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        icon = { Icon(icon, null) },
        selected = selected,
        badge = badge?.let { { Text(it) } },
        onClick = onClick,
    )
}

@Composable
private fun FolderItem(
    path: String,
    count: Int,
    selected: Boolean,
    hasChildren: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onNewSubfolder: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val depth = path.count { it == '/' }
    NavigationDrawerItem(
        modifier = Modifier.padding(start = (depth * 16).dp),
        label = { Text(FolderPaths.name(path), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        icon = {
            Box(Modifier.size(24.dp).clickable(enabled = hasChildren, onClick = onToggle), contentAlignment = Alignment.Center) {
                Icon(
                    when {
                        !hasChildren -> Icons.Outlined.Folder
                        expanded -> Icons.Outlined.ExpandMore
                        else -> Icons.Outlined.ChevronRight
                    },
                    if (hasChildren) (if (expanded) "Collapse" else "Expand") else null,
                )
            }
        },
        selected = selected,
        badge = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(count.toString())
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.MoreVert, "Folder actions") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("New subfolder…") }, leadingIcon = { Icon(Icons.Outlined.Add, null) }, onClick = {
                            menu = false
                            onNewSubfolder()
                        })
                        DropdownMenuItem(text = { Text("Rename…") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = {
                            menu = false
                            onRename()
                        })
                        DropdownMenuItem(
                            text = { Text("Delete…", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menu = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        },
        onClick = onClick,
    )
}
