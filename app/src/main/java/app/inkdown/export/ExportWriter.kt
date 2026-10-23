package app.inkdown.export

import app.inkdown.data.ConflictPolicy

data class ExportResult(
    val written: List<String>,
    val skipped: List<String>,
    val failed: List<Pair<String, String>>,
) {
    val ok: Boolean get() = failed.isEmpty()
}

object ExportWriter {
    /**
     * Writes [files] below [root]. A file whose name exists already is handled by [policy];
     * a name written earlier in this same export is never overwritten (two notes with one
     * title get "Title (2)"). [progress] is called with the number of files done.
     */
    fun write(
        root: DocumentDir,
        files: List<PlannedFile>,
        policy: ConflictPolicy,
        progress: (Int) -> Unit = {},
    ): ExportResult {
        val written = ArrayList<String>()
        val skipped = ArrayList<String>()
        val failed = ArrayList<Pair<String, String>>()
        val dirs = HashMap<List<String>, DocumentDir>()
        val existing = HashMap<List<String>, MutableMap<String, DocEntry>>()
        val ours = HashMap<List<String>, MutableSet<String>>()
        files.forEachIndexed { index, file ->
            val display = (file.folder + "${file.baseName}.${file.extension}").joinToString("/")
            try {
                val dir = dirs.getOrPut(file.folder) {
                    file.folder.fold(root) { d, name -> d.subdirectory(name) }
                }
                val entries = existing.getOrPut(file.folder) {
                    dir.list().associateBy { it.name.lowercase() }.toMutableMap()
                }
                val mine = ours.getOrPut(file.folder) { HashSet() }
                val wanted = "${file.baseName}.${file.extension}"
                val taken = entries.keys + mine
                val mime = mimeTypeFor(file.extension)
                val clash = entries[wanted.lowercase()]
                val bytes = file.content.toByteArray(Charsets.UTF_8)
                when {
                    wanted.lowercase() in mine || (clash != null && clash.isDirectory) ||
                        (clash != null && policy == ConflictPolicy.KEEP_BOTH) -> {
                        val name = FileNames.unique(file.baseName, file.extension, taken)
                        val (actual, out) = dir.createFile(name, mime)
                        out.use { it.write(bytes) }
                        mine += actual.lowercase()
                        written += (file.folder + actual).joinToString("/")
                    }
                    clash != null && policy == ConflictPolicy.SKIP -> skipped += display
                    clash != null -> {
                        dir.overwrite(clash, mime).use { it.write(bytes) }
                        mine += clash.name.lowercase()
                        written += display
                    }
                    else -> {
                        val (actual, out) = dir.createFile(wanted, mime)
                        out.use { it.write(bytes) }
                        mine += actual.lowercase()
                        written += (file.folder + actual).joinToString("/")
                    }
                }
            } catch (e: Exception) {
                failed += display to (e.message ?: e.javaClass.simpleName)
            }
            progress(index + 1)
        }
        return ExportResult(written, skipped, failed)
    }
}
