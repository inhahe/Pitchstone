package app.pitchstone

import android.app.Application
import android.content.Context
import android.util.Log
import app.pitchstone.data.AppDatabase
import app.pitchstone.data.AttachmentStore
import app.pitchstone.data.NoteRepository
import app.pitchstone.data.SettingsRepository
import app.pitchstone.sync.SyncManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** What a screen can ask the note list to show when it navigates back to it. */
sealed interface ListRequest {
    data class ShowTag(val tag: String) : ListRequest
}

/** The app's long-lived objects, created once per process. */
class AppContainer(context: Context) {
    /** The application context, for reading what other apps hand over (content URIs). */
    val appContext: Context = context.applicationContext
    val database: AppDatabase = AppDatabase.open(context)
    val repository = NoteRepository(database)
    val settings = SettingsRepository(context)

    /** Images stored with the notes (files/attachments). */
    val attachments = AttachmentStore(File(context.filesDir, "attachments"), database)

    private val maintenance = context.getSharedPreferences("maintenance", Context.MODE_PRIVATE)

    /** Work that must finish even if the screen that started it goes away (saving). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Messages for the note list's snackbar, from any screen ("Moved to trash"). A channel, so a
     * message sent while the list is not showing waits until it is.
     */
    val listMessages = Channel<ListMessage>(capacity = 16)

    /** A scope for the note list to switch to (e.g. tapping a #tag in a note). */
    val listRequests = MutableStateFlow<ListRequest?>(null)

    /** Two-way sync with a folder, when one is chosen in Settings. */
    val sync = SyncManager(appContext, database, repository, attachments, settings, appScope, listMessages)

    /**
     * Start-up chores. A failure is logged and left for the next start: an exception here would
     * crash the app, at every start, before anything could be done about it.
     */
    fun startMaintenance() {
        appScope.launch {
            runCatching { repository.purgeOldTrash() }.onFailure { Log.w(TAG, "Emptying old trash failed", it) }
            // Renames the app was stopped in the middle of (see NoteEntity.linkedTitle).
            runCatching { repository.applyPendingRenames(settings.current().updateLinksOnRename) }
                .onFailure { Log.w(TAG, "Completing renames failed", it) }
            // At most daily: it reads every note.
            val now = System.currentTimeMillis()
            if (now - maintenance.getLong(LAST_ATTACHMENT_GC, 0) > 24 * 60 * 60 * 1000L) {
                runCatching { repository.collectAttachmentGarbage(attachments) }
                    .onSuccess { maintenance.edit().putLong(LAST_ATTACHMENT_GC, now).apply() }
                    .onFailure { Log.w(TAG, "Cleaning up attachments failed", it) }
            }
            runCatching { sync.ensureScheduled() }.onFailure { Log.w(TAG, "Scheduling folder sync failed", it) }
        }
    }

    private companion object {
        const val TAG = "Pitchstone"
        const val LAST_ATTACHMENT_GC = "lastAttachmentGc"
    }
}

/** A snackbar message, optionally with an action that undoes what it reports. */
data class ListMessage(val text: String, val undoLabel: String? = null, val undo: (suspend () -> Unit)? = null)

class PitchstoneApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.startMaintenance()
        // Folder sync when the app comes to the front (watching the folder while it stays there),
        // and once it has left it.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = container.sync.onFront()
            override fun onStop(owner: LifecycleOwner) = container.sync.onBack()
        })
    }
}

val Context.container: AppContainer get() = (applicationContext as PitchstoneApp).container
