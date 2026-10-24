package app.pitchstone.share

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.text.HtmlCompat
import app.pitchstone.container
import app.pitchstone.data.ShareFormat
import app.pitchstone.export.FileNames
import app.pitchstone.markdown.Html
import app.pitchstone.markdown.Markdown
import app.pitchstone.markdown.PlainText
import java.io.File

/** Sending a note to other apps: email (optionally formatted), plain sharing, or as a file. */
object Sharing {
    /** The note's text in [format], for apps that take plain text. */
    fun text(markdown: String, format: ShareFormat): String = when (format) {
        ShareFormat.MARKDOWN -> markdown.trimEnd()
        ShareFormat.PLAIN, ShareFormat.HTML -> PlainText.render(markdown)
    }

    /** Packages of the installed apps that send email (those handling mailto:). */
    fun emailApps(context: Context): List<String> =
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")), 0)
            .map { it.activityInfo.packageName }
            .distinct()

    /**
     * An email with the note: [title] as subject and the note as body, offered only to email
     * apps. With [ShareFormat.HTML] the body is HTML (EXTRA_HTML_TEXT) and also styled text
     * (EXTRA_TEXT as a Spanned), which is what Gmail and most mail apps render.
     *
     * Without an attachment this is ACTION_SENDTO to mailto:, the documented way to compose an
     * email. Attachments need ACTION_SEND, which any app may take: its chooser leaves out every
     * app that is not an email app (EXTRA_EXCLUDE_COMPONENTS). A mailto: selector no longer
     * resolves for apps targeting Android 15, and the chooser shows at most two
     * EXTRA_INITIAL_INTENTS since Android 10, so neither can list the email apps.
     */
    fun emailIntent(context: Context, title: String, markdown: String, format: ShareFormat, attach: Boolean): Intent {
        fun Intent.withBody(): Intent = apply {
            putExtra(Intent.EXTRA_SUBJECT, title.ifBlank { "Note" })
            if (format == ShareFormat.HTML) {
                val html = Html.emailBody(markdown)
                putExtra(Intent.EXTRA_HTML_TEXT, html)
                putExtra(Intent.EXTRA_TEXT, HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT))
            } else {
                putExtra(Intent.EXTRA_TEXT, text(markdown, format))
            }
        }
        val apps = emailApps(context)
        if (!attach) {
            val compose = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).withBody()
            return if (apps.size > 1) Intent.createChooser(compose, "Email note") else compose
        }
        if (apps.isEmpty()) return Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).withBody()
        val uri = noteFile(context, title, markdown, format)
        val send = Intent(Intent.ACTION_SEND).withBody().apply {
            type = if (format == ShareFormat.HTML) "text/html" else "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (apps.size == 1) return send.setPackage(apps.single())
        val others = context.packageManager.queryIntentActivities(send, 0)
            .filter { it.activityInfo.packageName !in apps }
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
        return Intent.createChooser(send, "Email note").putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, others.toTypedArray())
    }

    /** Share the note as text with any app. */
    fun textIntent(title: String, markdown: String, format: ShareFormat): Intent {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TITLE, title)
            if (format == ShareFormat.HTML) {
                val html = Html.emailBody(markdown)
                putExtra(Intent.EXTRA_HTML_TEXT, html)
                putExtra(Intent.EXTRA_TEXT, HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT))
            } else {
                putExtra(Intent.EXTRA_TEXT, text(markdown, format))
            }
        }
        return Intent.createChooser(intent, "Share note")
    }

    /** Share the note as a file (.md, .txt or .html). */
    fun fileIntent(context: Context, title: String, markdown: String, format: ShareFormat): Intent {
        val uri = noteFile(context, title, markdown, format)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = when (format) {
                ShareFormat.HTML -> "text/html"
                ShareFormat.MARKDOWN -> "text/markdown"
                ShareFormat.PLAIN -> "text/plain"
            }
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(intent, "Share note file")
    }

    /**
     * Writes the note to a file in the app's cache (shared through the FileProvider) and returns
     * its URI. Files shared more than an hour ago are removed, so the cache does not grow; newer
     * ones stay, as the receiving app may not have read them yet.
     */
    fun noteFile(context: Context, title: String, markdown: String, format: ShareFormat): Uri {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
        val sub = File(dir, System.nanoTime().toString()).apply { mkdirs() }
        val name = FileNames.safe(title)
        val (fileName, content) = when (format) {
            ShareFormat.HTML -> "$name.html" to Html.document(title, markdown, context.container.attachments::file)
            ShareFormat.MARKDOWN -> "$name.md" to Markdown.normalizeNewlines(markdown)
            ShareFormat.PLAIN -> "$name.txt" to PlainText.render(markdown) + "\n"
        }
        val file = File(sub, fileName)
        file.writeText(content, Charsets.UTF_8)
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }
}
