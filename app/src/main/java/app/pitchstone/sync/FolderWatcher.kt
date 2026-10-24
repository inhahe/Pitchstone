package app.pitchstone.sync

import android.content.ContentResolver
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log

/**
 * Watches the synced folder for changes other apps make (Syncthing, Obsidian) while Pitchstone
 * is in front, and calls [onChange] once they settle, so that they arrive within seconds.
 *
 * Android's own storage provider (phone storage, memory cards) watches a folder for as long as
 * a listing of it is open, and announces each change to it. So this keeps the listing of every
 * folder synced (not hidden, not left out) open, and listens to the provider's announcements.
 * A provider that announces nothing (most cloud ones) leaves the syncs on coming to the front
 * and the periodic one. Everything runs on one thread of its own.
 */
class FolderWatcher(private val resolver: ContentResolver, private val onChange: () -> Unit) {
    private companion object {
        const val TAG = "Pitchstone"
        /** Changes come in bursts (Syncthing writes a temporary file, then renames it): wait for quiet. */
        const val QUIET_MS = 2000L
        /** At most this many folders are watched; the rest wait for the other syncs. */
        const val MAX_FOLDERS = 300
    }

    private val handler = Handler(HandlerThread("pitchstone-folder-watch").apply { start() }.looper)
    private var tree: Uri? = null
    /** The open listings, by folder document id. */
    private var listings = HashMap<String, Cursor>()
    private var observer: ContentObserver? = null
    private val fire = Runnable { onChange() }

    /**
     * Watches [treeUri] (a tree from the folder picker) and its folders, without those [excluded].
     * Called again after each sync, to watch folders made meanwhile and drop those gone.
     */
    fun watch(treeUri: Uri, excluded: Collection<String>) {
        handler.post { watchNow(treeUri, Excluded(excluded)) }
    }

    /** Stops watching (the app left the front, or sync was stopped). */
    fun stop() {
        handler.post { stopNow() }
    }

    private fun watchNow(treeUri: Uri, excluded: Excluded) {
        if (tree != treeUri) stopNow()
        val rootId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: IllegalArgumentException) {
            return
        }
        val next = HashMap<String, Cursor>()
        try {
            list(treeUri, rootId, "", excluded, next)
        } catch (e: Exception) {
            // Gone, or no longer allowed: the sync reports that; nothing to watch.
            Log.w(TAG, "Watching the synced folder failed", e)
            next.values.forEach { it.close() }
            return
        }
        // The new listings are open before the old ones close, so no change goes unseen.
        listings.values.forEach { it.close() }
        listings = next
        if (tree == null) {
            tree = treeUri
            register(treeUri, rootId)
        }
    }

    /** Opens the listing of folder [docId] (at [path]) and those below it, into [into]. */
    private fun list(treeUri: Uri, docId: String, path: String, excluded: Excluded, into: MutableMap<String, Cursor>) {
        if (into.size >= MAX_FOLDERS) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        val cursor = resolver.query(children, projection, null, null, null) ?: return
        into[docId] = cursor
        val folders = ArrayList<Pair<String, String>>()
        while (cursor.moveToNext()) {
            val id = cursor.getString(0) ?: continue
            val name = cursor.getString(1) ?: continue
            if (cursor.getString(2) != Document.MIME_TYPE_DIR || name.startsWith(".")) continue
            val child = joinPath(path, name)
            if (!excluded.contains(child)) folders += id to child
        }
        for ((id, child) in folders) list(treeUri, id, child, excluded, into)
    }

    /** Listens to the provider of [treeUri] for changes under its folder [rootId]. */
    private fun register(treeUri: Uri, rootId: String) {
        // A volume's top is "primary:"; folders below it "primary:Documents/Vault".
        val prefix = if (rootId.endsWith(":") || rootId.endsWith("/")) rootId else "$rootId/"
        val o = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val id = uri?.let { runCatching { DocumentsContract.getDocumentId(it) }.getOrNull() }
                // Another app's folder on the same storage: not ours.
                if (id != null && id != rootId && !id.startsWith(prefix)) return
                handler.removeCallbacks(fire)
                handler.postDelayed(fire, QUIET_MS)
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

    private fun stopNow() {
        handler.removeCallbacks(fire)
        observer?.let { resolver.unregisterContentObserver(it) }
        observer = null
        listings.values.forEach { it.close() }
        listings = HashMap()
        tree = null
    }
}
