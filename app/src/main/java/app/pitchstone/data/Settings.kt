package app.pitchstone.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class SortOrder(val label: String) {
    MODIFIED_NEWEST("Last edited"),
    MODIFIED_OLDEST("Last edited (oldest first)"),
    CREATED_NEWEST("Created"),
    CREATED_OLDEST("Created (oldest first)"),
    TITLE_AZ("Title A–Z"),
    TITLE_ZA("Title Z–A"),
}

enum class ExportFormat(val extension: String, val label: String) {
    MARKDOWN("md", "Markdown (.md)"),
    TEXT("txt", "Plain text (.txt)"),
}

enum class ExportLayout(val label: String) {
    FILE_PER_NOTE("One file per note"),
    SINGLE_FILE("All in one file"),
}

enum class ConflictPolicy(val label: String) {
    KEEP_BOTH("Keep both (add a number)"),
    REPLACE("Replace the existing file"),
    SKIP("Skip that note"),
}

enum class ShareFormat(val label: String) {
    HTML("Formatted"),
    MARKDOWN("Markdown"),
    PLAIN("Plain text"),
}

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** Typing markdown syntax formats on the fly; off makes typed symbols literal. */
    val markdownShortcuts: Boolean = true,
    val showToolbar: Boolean = true,
    val fontSize: Int = 17,
    val spellcheck: Boolean = true,
    val readableLineLength: Boolean = true,
    val openInReadingMode: Boolean = false,
    val updateLinksOnRename: Boolean = true,
    /** Photos inserted into notes are scaled down to [app.pitchstone.ui.editor.ImagePrep.MAX_SIDE]. */
    val shrinkLargePhotos: Boolean = true,
    val sortOrder: SortOrder = SortOrder.MODIFIED_NEWEST,
    val exportFormat: ExportFormat = ExportFormat.MARKDOWN,
    val exportLayout: ExportLayout = ExportLayout.FILE_PER_NOTE,
    val exportKeepFolders: Boolean = true,
    val exportTitleHeading: Boolean = false,
    val exportMetadata: Boolean = false,
    val exportTextKeepsMarkdown: Boolean = false,
    val exportConflict: ConflictPolicy = ConflictPolicy.KEEP_BOTH,
    /** The folder (a document tree URI) of the last export, offered again next time. */
    val exportTreeUri: String? = null,
    val shareFormat: ShareFormat = ShareFormat.HTML,
    val shareAttachFile: Boolean = false,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val theme = stringPreferencesKey("theme")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val markdownShortcuts = booleanPreferencesKey("markdown_shortcuts")
        val showToolbar = booleanPreferencesKey("show_toolbar")
        val fontSize = intPreferencesKey("font_size")
        val spellcheck = booleanPreferencesKey("spellcheck")
        val readableLineLength = booleanPreferencesKey("readable_line_length")
        val openInReadingMode = booleanPreferencesKey("open_in_reading_mode")
        val updateLinksOnRename = booleanPreferencesKey("update_links_on_rename")
        val shrinkLargePhotos = booleanPreferencesKey("shrink_large_photos")
        val sortOrder = stringPreferencesKey("sort_order")
        val exportFormat = stringPreferencesKey("export_format")
        val exportLayout = stringPreferencesKey("export_layout")
        val exportKeepFolders = booleanPreferencesKey("export_keep_folders")
        val exportTitleHeading = booleanPreferencesKey("export_title_heading")
        val exportMetadata = booleanPreferencesKey("export_metadata")
        val exportTextKeepsMarkdown = booleanPreferencesKey("export_text_keeps_markdown")
        val exportConflict = stringPreferencesKey("export_conflict")
        val exportTreeUri = stringPreferencesKey("export_tree_uri")
        val shareFormat = stringPreferencesKey("share_format")
        val shareAttachFile = booleanPreferencesKey("share_attach_file")
    }

    private inline fun <reified E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, default: E): E =
        this[key]?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    val settings: Flow<AppSettings> = context.dataStore.data.map { readFrom(it, AppSettings()) }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val d = AppSettings()
            val now = transform(readFrom(p, d))
            p[Keys.theme] = now.theme.name
            p[Keys.dynamicColor] = now.dynamicColor
            p[Keys.markdownShortcuts] = now.markdownShortcuts
            p[Keys.showToolbar] = now.showToolbar
            p[Keys.fontSize] = now.fontSize.coerceIn(12, 28)
            p[Keys.spellcheck] = now.spellcheck
            p[Keys.readableLineLength] = now.readableLineLength
            p[Keys.openInReadingMode] = now.openInReadingMode
            p[Keys.updateLinksOnRename] = now.updateLinksOnRename
            p[Keys.shrinkLargePhotos] = now.shrinkLargePhotos
            p[Keys.sortOrder] = now.sortOrder.name
            p[Keys.exportFormat] = now.exportFormat.name
            p[Keys.exportLayout] = now.exportLayout.name
            p[Keys.exportKeepFolders] = now.exportKeepFolders
            p[Keys.exportTitleHeading] = now.exportTitleHeading
            p[Keys.exportMetadata] = now.exportMetadata
            p[Keys.exportTextKeepsMarkdown] = now.exportTextKeepsMarkdown
            p[Keys.exportConflict] = now.exportConflict.name
            if (now.exportTreeUri != null) p[Keys.exportTreeUri] = now.exportTreeUri else p.remove(Keys.exportTreeUri)
            p[Keys.shareFormat] = now.shareFormat.name
            p[Keys.shareAttachFile] = now.shareAttachFile
        }
    }

    private fun readFrom(p: Preferences, d: AppSettings) = AppSettings(
        theme = p.enum(Keys.theme, d.theme),
        dynamicColor = p[Keys.dynamicColor] ?: d.dynamicColor,
        markdownShortcuts = p[Keys.markdownShortcuts] ?: d.markdownShortcuts,
        showToolbar = p[Keys.showToolbar] ?: d.showToolbar,
        fontSize = p[Keys.fontSize] ?: d.fontSize,
        spellcheck = p[Keys.spellcheck] ?: d.spellcheck,
        readableLineLength = p[Keys.readableLineLength] ?: d.readableLineLength,
        openInReadingMode = p[Keys.openInReadingMode] ?: d.openInReadingMode,
        updateLinksOnRename = p[Keys.updateLinksOnRename] ?: d.updateLinksOnRename,
        shrinkLargePhotos = p[Keys.shrinkLargePhotos] ?: d.shrinkLargePhotos,
        sortOrder = p.enum(Keys.sortOrder, d.sortOrder),
        exportFormat = p.enum(Keys.exportFormat, d.exportFormat),
        exportLayout = p.enum(Keys.exportLayout, d.exportLayout),
        exportKeepFolders = p[Keys.exportKeepFolders] ?: d.exportKeepFolders,
        exportTitleHeading = p[Keys.exportTitleHeading] ?: d.exportTitleHeading,
        exportMetadata = p[Keys.exportMetadata] ?: d.exportMetadata,
        exportTextKeepsMarkdown = p[Keys.exportTextKeepsMarkdown] ?: d.exportTextKeepsMarkdown,
        exportConflict = p.enum(Keys.exportConflict, d.exportConflict),
        exportTreeUri = p[Keys.exportTreeUri],
        shareFormat = p.enum(Keys.shareFormat, d.shareFormat),
        shareAttachFile = p[Keys.shareAttachFile] ?: d.shareAttachFile,
    )
}
