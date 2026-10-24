package app.pitchstone.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.pitchstone.ListMessage
import app.pitchstone.container
import app.pitchstone.data.AppDatabase
import app.pitchstone.data.AttachmentStore
import app.pitchstone.data.NoteRepository
import app.pitchstone.data.SettingsRepository
import app.pitchstone.export.SafDir
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Where folder sync stands, for Settings. */
data class SyncStatus(
    val running: Boolean = false,
    /** The last sync that ran. */
    val last: SyncReport? = null,
    /** The last sync that changed something (a later one finding all in step would hide it). */
    val lastChange: SyncReport? = null,
    /** Why the last attempt could not sync at all (the folder cannot be reached, say), or null. */
    val error: String? = null,
)

/**
 * Runs [FolderSync] with the folder chosen in Settings: when the app comes to the front, a
 * few seconds after it leaves it and after a note is closed (in the background, through
 * [SyncWorker]), every 15 minutes or so while a folder is set, and when asked to. One sync at a
 * time; notes open in an editor are left for a later sync to change.
 */
class SyncManager(
    private val context: Context,
    db: AppDatabase,
    repo: NoteRepository,
    attachments: AttachmentStore,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val messages: Channel<ListMessage>,
) {
    private val engine = FolderSync(db, repo, attachments)
    private val entries = db.sync()
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private val _status = MutableStateFlow(
        SyncStatus(last = loadReport(KEY_REPORT), lastChange = loadReport(KEY_CHANGE), error = prefs.getString(KEY_ERROR, null)),
    )
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** Notes open in an editor, with how many editors have each open. */
    private val open = ConcurrentHashMap<Long, Int>()

    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (request in requests) {
                // Requests close together (a note closed as the app leaves the front) make one sync.
                delay(DEBOUNCE_MS)
                requests.tryReceive()
                // A failure must not end this loop: every later request would be dropped.
                try {
                    syncNow()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Sync failed", e)
                }
            }
        }
    }

    /** Note [noteId] is open in an editor: syncs leave it alone until [closed]. */
    fun opened(noteId: Long) {
        open.merge(noteId, 1, Int::plus)
    }

    fun closed(noteId: Long) {
        open.computeIfPresent(noteId) { _, n -> if (n <= 1) null else n - 1 }
    }

    /** Syncs soon, in this process (the app is in use). */
    fun requestSync() {
        requests.trySend(Unit)
    }

    /**
     * Syncs soon from WorkManager, which keeps the process running for it: for when the app has
     * just left the front, and could be stopped before a sync of its own finished.
     */
    fun requestBackgroundSync() {
        // Only with a folder set: a job for nothing at every trip to the background otherwise.
        scope.launch {
            if (runCatching { settings.current().syncFolderUri }.getOrNull() != null) enqueueBackgroundSync()
        }
    }

    private fun enqueueBackgroundSync() {
        // After one running (replacing it would cancel it midway), or replacing one not started.
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_SOON, ExistingWorkPolicy.APPEND_OR_REPLACE,
            // A few seconds: the saves the editor makes on leaving go first.
            OneTimeWorkRequestBuilder<SyncWorker>().setInitialDelay(3, TimeUnit.SECONDS).build(),
        )
    }

    /** Keeps the periodic sync scheduled while a folder is set (after an update, say, or data cleared). */
    suspend fun ensureScheduled() {
        if (settings.current().syncFolderUri != null) schedule()
    }

    private fun schedule() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** What a first sync with the folder [uri] would do. */
    suspend fun preview(uri: Uri): SyncPreview = engine.preview(SafDir.root(context.contentResolver, uri))

    /** The display name of the folder [uri]. */
    fun folderName(uri: String): String = runCatching { SafDir.root(context.contentResolver, Uri.parse(uri)).name }.getOrDefault("the folder")

    /**
     * Starts syncing with [uri] (whose permission is held). Another folder than the one set starts
     * afresh, what was paired with the old one forgotten; the same one (chosen again after its
     * permission was lost) carries on where it was.
     */
    suspend fun start(uri: Uri) {
        lock.withLock {
            val old = settings.current().syncFolderUri
            if (old != uri.toString()) {
                entries.clear()
                prefs.edit().remove(KEY_REPORT).remove(KEY_CHANGE).apply()
                _status.value = SyncStatus()
            }
            settings.update { it.copy(syncFolderUri = uri.toString()) }
            if (old != null && old != uri.toString()) releasePermission(old)
            prefs.edit().remove(KEY_ERROR).apply()
            _status.update { it.copy(error = null) }
        }
        schedule()
        syncNow()
    }

    /** Stops syncing. The notes and the files stay as they are. */
    suspend fun stop() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_PERIODIC)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_SOON)
        lock.withLock {
            val old = settings.current().syncFolderUri
            settings.update { it.copy(syncFolderUri = null) }
            entries.clear()
            if (old != null) releasePermission(old)
            prefs.edit().remove(KEY_REPORT).remove(KEY_CHANGE).remove(KEY_ERROR).apply()
            _status.value = SyncStatus()
        }
    }

    /** Gives back the access to the folder [uri], unless export still remembers it. */
    suspend fun releasePermission(uri: String) {
        if (settings.current().exportTreeUri == uri || settings.current().syncFolderUri == uri) return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    /**
     * Syncs now, if a folder is set, after any sync running; returns what it did, or null if it
     * could not run. [gone] decides about many notes whose files were deleted ([GoneFiles]).
     */
    suspend fun syncNow(gone: GoneFiles = GoneFiles.ASK): SyncReport? = lock.withLock {
        val s = settings.current()
        val uri = s.syncFolderUri ?: return@withLock null
        _status.update { it.copy(running = true) }
        // A sync stopped midway (WorkManager cancelling it) must not leave "Syncing…" up.
        try {
            val root = SafDir.root(context.contentResolver, Uri.parse(uri))
            val report = engine.sync(root, s.updateLinksOnRename, busy = { open.containsKey(it) }, gone = gone)
            val before = _status.value.last
            val changed = report.changes > 0 || report.conflicts.isNotEmpty()
            val lastChange = if (changed) report else _status.value.lastChange
            prefs.edit().apply {
                putString(KEY_REPORT, json.encodeToString(SyncReport.serializer(), report))
                if (changed) putString(KEY_CHANGE, json.encodeToString(SyncReport.serializer(), report))
                remove(KEY_ERROR)
            }.apply()
            _status.value = SyncStatus(last = report, lastChange = lastChange)
            announce(report, before)
            report
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Sync failed", e)
            val why = if (e is SecurityException) {
                "Pitchstone may no longer use the folder. Choose it again with Change folder."
            } else {
                "Could not read the folder: ${e.message ?: e.javaClass.simpleName}"
            }
            prefs.edit().putString(KEY_ERROR, why).apply()
            _status.update { it.copy(running = false, error = why) }
            null
        } finally {
            _status.update { it.copy(running = false) }
        }
    }

    /** Tells the note list what needs the user: notes changed on both sides, and files deleted en masse (once). */
    private fun announce(report: SyncReport, before: SyncReport?) {
        if (report.conflicts.isNotEmpty()) {
            val names = report.conflicts.take(3).joinToString(", ") { "“$it”" } + if (report.conflicts.size > 3) "…" else ""
            val it = if (report.conflicts.size == 1) "its" else "their"
            messages.trySend(ListMessage("Changed here and in the synced folder: $names. The text not kept is in $it history."))
        }
        if (report.heldBack > 0 && report.heldBack != before?.heldBack) {
            messages.trySend(ListMessage("The files of ${report.heldBack} notes were deleted from the synced folder. See Settings."))
        }
    }

    private fun loadReport(key: String): SyncReport? =
        prefs.getString(key, null)?.let { runCatching { json.decodeFromString(SyncReport.serializer(), it) }.getOrNull() }

    private companion object {
        const val TAG = "Pitchstone"
        const val DEBOUNCE_MS = 1500L
        const val KEY_REPORT = "lastReport"
        const val KEY_CHANGE = "lastChange"
        const val KEY_ERROR = "lastError"
        const val WORK_PERIODIC = "folderSync"
        const val WORK_SOON = "folderSyncSoon"
    }
}

/** A sync run by WorkManager: periodically, and just after the app leaves the front. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        applicationContext.container.sync.syncNow()
        // A failure is in the status; retrying sooner than the next period would not help.
        return Result.success()
    }
}
