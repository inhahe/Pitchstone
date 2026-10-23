package app.pitchstone.ui.common

object Urls {
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    /** Whether [url] starts with a scheme ("https:", "mailto:"); "example.com" does not. */
    fun hasScheme(url: String): Boolean = SCHEME.containsMatchIn(url)
}
