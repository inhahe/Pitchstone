package app.pitchstone.sync

import android.content.ContentResolver
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log

/**
 * Watches the synced folder for changes other apps make (Syncthing, Obsidian) while Pitchstone
 * is in front, and calls [onChange] once they settle, so that they arrive within seconds.
 *
 * Android's own storage provider (phone storage, memory cards) watches a folder for as long as
 * a listing of it is open, and announces each change to it. So this keeps the listing of every
 * folder synced (not hidden, not left out) open, and listens to the provider's announcements;
 * a folder that announces a change is listed again, which finds folders made in it. A provider
 * that announces nothing (most cloud ones) leaves the syncs on coming to the front and the
 * periodic one. The sync's own writes are announced too: they are told apart by [hold] and
 * [release]. Everything runs on one thread of its own.
 */
class FolderWatcher(private val resolver: ContentResolver, private val onChange: () -> Unit) {
    private companion object {
        const val TAG = "Pitchstone"
        /** Changes come in bursts (Syncthing writes a temporary file, then renames it): wait for quiet. */
        const val QUIET_MS = 2000L
        /** How long after a sync, its writes may still be announced. */
        const val OWN_WRITES_MS = 1500L
        /** At most this many folders are watched; the rest wait for the other syncs. */
        const val MAX_FOLDERS = 300
    }

    private val handler = Handler(HandlerThread("pitchstone-folder-watch").apply { start() }.looper)
    private var tree: Uri? = null
    private var excluded = Excluded(emptyList())
    /** The open listings, by folder document id, and each folder's path in the synced folder. */
    private var listings = HashMap<String, Cursor>()
    private var paths = HashMap<String, String>()
    private var observer: ContentObserver? = null
    private val fire = Runnable { onChange() }

    /** While a sync runs: the folders that announced something meanwhile (null: one unknown). */
    private var holding = false
    private val announcedWhileHeld = HashSet<String?>()
    /** The folders the last sync wrote into, and until when (uptime) their announcements are its own. */
    private var ownFolders = emptySet<String>()
    private var ownWritesUntil = 0L

    /**
     * Watches [treeUri] (a tree from the folder picker) and its folders, without those [excluded]:
     * when the app comes to the front, and when the folder or the folders left out change.
     */
    fun watch(treeUri: Uri, excluded: Collection<String>) {
        handler.post { watchNow(treeUri, Excluded(excluded)) }
    }

    /** Stops watching (the app left the front, or sync was stopped). */
    fun stop() {
        handler.post { stopNow() }
    }

    /** A sync starts: what is announced until [release] may be its own writes. */
    fun hold() {
        handler.post {
            holding = true
            announcedWhileHeld.clear()
        }
    }

    /**
     * The sync is over, having written into the folders [wroteIn] (paths, lower-cased): what they
     * announced meanwhile, and shortly after, is taken for its own writes. What other folders
     * announced was another app's doing, and is synced.
     */
    fun release(wroteIn: Set<String>) {
        handler.post {
            holding = false
            ownFolders = paths.filterValues { it.lowercase() in wroteIn }.keys
            ownWritesUntil = SystemClock.uptimeMillis() + OWN_WRITES_MS
            if (announcedWhileHeld.any { it == null || it !in ownFolders }) schedule()
            announcedWhileHeld.clear()
        }
    }

    private fun schedule() {
        handler.removeCallbacks(fire)
        handler.postDelayed(fire, QUIET_MS)
    }

    private fun watchNow(treeUri: Uri, excluded: Excluded) {
        if (tree != treeUri) stopNow()
        val root = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: IllegalArgumentException) {
            return
        }
        this.excluded = excluded
        val next = HashMap<String, Cursor>()
        val nextPaths = HashMap<String, String>()
        try {
            list(treeUri, root, "", next, nextPaths)
        } catch (e: Exception) {
            // Gone, or no longer allowed: the sync reports that; nothing to watch.
            Log.w(TAG, "Watching the synced folder failed", e)
            next.values.forEach { it.close() }
            return
        }
        // The new listings are open before the old ones close, so no change goes unseen.
        listings.values.forEach { it.close() }
        listings = next
        paths = nextPaths
        if (tree == null) {
            tree = treeUri
            register(treeUri, root)
        }
    }

    /** Opens the listing of folder [docId] (at [path]) and of those below it not open yet. */
    private fun list(treeUri: Uri, docId: String, path: String, into: MutableMap<String, Cursor>, pathsInto: MutableMap<String, String>) {
        if (into.size >= MAX_FOLDERS && docId !in into) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        val cursor = resolver.query(children, projection, null, null, null) ?: return
        // The new listing before the old one closes, as in watchNow.
        into.put(docId, cursor)?.close()
        pathsInto[docId] = path
        val folders = ArrayList<Pair<String, String>>()
        while (cursor.moveToNext()) {
            val id = cursor.getString(0) ?: continue
            val name = cursor.getString(1) ?: continue
            if (cursor.getString(2) != Document.MIME_TYPE_DIR || name.startsWith(".")) continue
            val child = joinPath(path, name)
            if (!excluded.contains(child) && id !in into) folders += id to child
        }
        for ((id, child) in folders) list(treeUri, id, child, into, pathsInto)
    }

    /** Listens to the provider of [treeUri] for changes under its folder [root]. */
    private fun register(treeUri: Uri, root: String) {
        // A volume's top is "primary:"; folders below it "primary:Documents/Vault".
        val prefix = if (root.endsWith(":") || root.endsWith("/")) root else "$root/"
        val o = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val id = uri?.let { runCatching { DocumentsContract.getDocumentId(it) }.getOrNull() }
                // Another app's folder on the same storage: not ours.
                if (id != null && id != root && !id.startsWith(prefix)) return
                announced(id)
            }
        }
        try {
            // The provider announces a folder's changes at its own URIs, which all lie below this.
            resolver.registerContentObserver(Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(treeUri.authority).build(), true, o)
            observer = o
        } catch (e: SecurityException) {
            Log.w(TAG, "The synced folder's provider cannot be listened to", e)
        }
    }

    /** Folder [id] (or an unknown one) announced a change. */
    private fun announced(id: String?) {
        val treeUri = tree ?: return
        // Listed again: a folder made in it is watched from now on.
        val path = id?.let { paths[it] }
        if (id != null && path != null) {
            try {
                list(treeUri, id, path, listings, paths)
            } catch (e: Exception) {
                // Gone meanwhile: its parent announces that too.
                listings.remove(id)?.close()
                paths.remove(id)
            }
        }
        when {
            holding -> announcedWhileHeld += id
            id != null && id in ownFolders && SystemClock.uptimeMillis() < ownWritesUntil -> {}
            else -> schedule()
        }
    }

    private fun stopNow() {
        handler.removeCallbacks(fire)
        observer?.let { resolver.unregisterContentObserver(it) }
        observer = null
        listings.values.forEach { it.close() }
        listings = HashMap()
        paths = HashMap()
        tree = null
        holding = false
    }
}
