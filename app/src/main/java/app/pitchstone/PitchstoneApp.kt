package app.pitchstone

import android.app.Application
import android.content.Context
import android.util.Log
import app.pitchstone.data.AppDatabase
import app.pitchstone.data.NoteRepository
import app.pitchstone.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** What a screen can ask the note list to show when it navigates back to it. */
sealed interface ListRequest {
    data class ShowTag(val tag: String) : ListRequest
}

/** The app's long-lived objects, created once per process. */
class AppContainer(context: Context) {
    val database: AppDatabase = AppDatabase.open(context)
    val repository = NoteRepository(database)
    val settings = SettingsRepository(context)

    /** Work that must finish even if the screen that started it goes away (saving). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Messages for the note list's snackbar, from any screen ("Moved to trash"). A channel, so a
     * message sent while the list is not showing waits until it is.
     */
    val listMessages = Channel<ListMessage>(capacity = 16)

    /** A scope for the note list to switch to (e.g. tapping a #tag in a note). */
    val listRequests = MutableStateFlow<ListRequest?>(null)

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
        }
    }

    private companion object {
        const val TAG = "Pitchstone"
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
    }
}

val Context.container: AppContainer get() = (applicationContext as PitchstoneApp).container
