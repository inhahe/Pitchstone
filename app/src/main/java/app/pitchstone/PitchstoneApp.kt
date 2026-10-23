package app.pitchstone

import android.app.Application
import android.content.Context
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

    fun startMaintenance() {
        appScope.launch {
            repository.purgeOldTrash()
            // Renames the app was stopped in the middle of (see NoteEntity.linkedTitle).
            repository.applyPendingRenames(settings.current().updateLinksOnRename)
        }
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
