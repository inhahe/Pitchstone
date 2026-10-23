package app.pitchstone.export

object FileNames {
    /** Characters no common file system (FAT, NTFS, ext4, APFS) accepts in a name. */
    private val FORBIDDEN = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    private val RESERVED = Regex("^(con|prn|aux|nul|com[0-9]|lpt[0-9])$", RegexOption.IGNORE_CASE)

    /** Leaves room within the usual 255-byte limit for an extension and a " (99)" suffix. */
    private const val MAX_BYTES = 200

    /**
     * A file name (without extension) for a note titled [title], valid on every common file
     * system: forbidden characters become spaces, leading and trailing dots and spaces go,
     * Windows device names get an underscore, and long titles are cut at a character
     * boundary. An empty result becomes [fallback].
     */
    fun safe(title: String, fallback: String = "Untitled"): String {
        var s = FORBIDDEN.replace(title, " ").replace(Regex("\\s+"), " ").trim().trim('.', ' ')
        if (s.isEmpty()) s = fallback
        if (RESERVED.matches(s)) s += "_"
        if (s.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            val sb = StringBuilder()
            var bytes = 0
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                val chunk = String(Character.toChars(cp))
                val n = chunk.toByteArray(Charsets.UTF_8).size
                if (bytes + n > MAX_BYTES) break
                sb.append(chunk)
                bytes += n
                i += Character.charCount(cp)
            }
            s = sb.toString().trim().trimEnd('.')
        }
        return s.ifEmpty { fallback }
    }

    /**
     * The first of `base.ext`, `base (2).ext`, `base (3).ext`, ... whose lower-cased form is not
     * in [taken] (lower-cased names). Case-insensitive, since FAT and exFAT SD cards are.
     */
    fun unique(base: String, extension: String, taken: Set<String>): String {
        val dot = if (extension.isEmpty()) "" else ".$extension"
        var name = base + dot
        var n = 2
        while (name.lowercase() in taken) {
            name = "$base ($n)$dot"
            n++
        }
        return name
    }
}
