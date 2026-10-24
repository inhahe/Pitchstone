package app.pitchstone.ui.editor

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToInt

/** Images on their way into a note: their names, and photos made a sensible size. */
object ImagePrep {
    /** With "Shrink large photos" on, photos are scaled to at most this many pixels on their long side. */
    const val MAX_SIDE = 2560

    /** Photo formats: these may be scaled down (others, like PNG screenshots, stay exact). */
    private val PHOTOS = setOf("image/jpeg", "image/heic", "image/heif", "image/webp")
    /** Formats the editor cannot show, turned into JPEG. */
    private val CONVERTED = setOf("image/heic", "image/heif")

    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    /** An image ready to store: its content and the name it would like. */
    class Prepared(val open: () -> InputStream, val name: String)

    /** Obsidian's name for a pasted image: "Pasted image 20261023211500.png". */
    fun pastedName(mime: String, now: LocalDateTime = LocalDateTime.now()): String =
        "Pasted image ${STAMP.format(now)}.${extensionOf(mime) ?: "png"}"

    /** The usual extension for [mime] ("image/jpeg" -> "jpg"), or null. */
    fun extensionOf(mime: String): String? = when (mime.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/svg+xml" -> "svg"
        "image/bmp" -> "bmp"
        "image/avif" -> "avif"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
    }

    /**
     * The name for a picked file called [displayName] (null if unknown): its own name, unless
     * it is only a number (the photo picker's), with [extension]; else "Image <time>.<ext>".
     */
    fun pickedName(displayName: String?, extension: String, now: LocalDateTime = LocalDateTime.now()): String {
        val base = displayName?.substringBeforeLast('.')?.trim().orEmpty()
        return if (base.any { it.isLetter() }) "$base.$extension" else "Image ${STAMP.format(now)}.$extension"
    }

    /**
     * The picked image at [uri]. A HEIC/HEIF photo, which the editor cannot show, becomes JPEG;
     * with [shrink], a photo longer than [MAX_SIDE] on one side is scaled down (as JPEG). Both
     * need Android 9; before it, and for every other image, the file is stored as it is.
     */
    fun fromUri(resolver: ContentResolver, uri: Uri, shrink: Boolean): Prepared {
        var displayName: String? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) displayName = c.getString(0)
        }
        val mime = (resolver.getType(uri) ?: "").lowercase()
        val ext = displayName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() } ?: extensionOf(mime) ?: "png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && mime in PHOTOS) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val tooBig = shrink && max(bounds.outWidth, bounds.outHeight) > MAX_SIDE
            if (mime in CONVERTED || tooBig) {
                val jpeg = reencode(resolver, uri, if (tooBig) MAX_SIDE else Int.MAX_VALUE)
                return Prepared({ ByteArrayInputStream(jpeg) }, pickedName(displayName, "jpg"))
            }
        }
        return Prepared({ resolver.openInputStream(uri) ?: error("Cannot read the picture") }, pickedName(displayName, ext))
    }

    /** The image at [uri] as JPEG, at most [maxSide] pixels on its long side (turned as its EXIF says). */
    private fun reencode(resolver: ContentResolver, uri: Uri, maxSide: Int): ByteArray {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val long = max(info.size.width, info.size.height)
            if (long > maxSide) {
                val f = maxSide.toDouble() / long
                decoder.setTargetSize((info.size.width * f).roundToInt().coerceAtLeast(1), (info.size.height * f).roundToInt().coerceAtLeast(1))
            }
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
    }
}
