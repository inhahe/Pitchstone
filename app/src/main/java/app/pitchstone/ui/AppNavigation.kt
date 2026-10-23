package app.pitchstone.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.pitchstone.ListRequest
import app.pitchstone.container
import app.pitchstone.ui.editor.EditorScreen
import app.pitchstone.ui.history.HistoryScreen
import app.pitchstone.ui.notes.NotesScreen
import app.pitchstone.ui.settings.SettingsScreen
import app.pitchstone.ui.trash.TrashScreen
import kotlinx.serialization.Serializable

@Serializable
object NotesRoute

@Serializable
data class NoteRoute(val id: Long, val anchor: String? = null)

@Serializable
object SettingsRoute

@Serializable
object TrashRoute

@Serializable
data class HistoryRoute(val noteId: Long)

/** Pops [entry] if it is still the current screen: a second "back" from it is ignored. */
private fun NavHostController.popIfCurrent(entry: NavBackStackEntry) {
    if (currentBackStackEntry?.id == entry.id) popBackStack()
}

@Composable
fun AppNavigation(nav: NavHostController) {
    val container = LocalContext.current.container
    NavHost(nav, startDestination = NotesRoute) {
        composable<NotesRoute> {
            NotesScreen(
                onOpenNote = { nav.navigate(NoteRoute(it)) },
                onSettings = { nav.navigate(SettingsRoute) },
                onTrash = { nav.navigate(TrashRoute) },
            )
        }
        composable<NoteRoute> { entry ->
            val route = entry.toRoute<NoteRoute>()
            EditorScreen(
                noteId = route.id,
                anchor = route.anchor,
                onBack = { nav.popIfCurrent(entry) },
                onOpenNote = { id, anchor -> if (nav.currentBackStackEntry?.id == entry.id) nav.navigate(NoteRoute(id, anchor)) },
                onShowTag = { tag ->
                    container.listRequests.value = ListRequest.ShowTag(tag)
                    nav.popBackStack(NotesRoute, inclusive = false)
                },
                onHistory = { id -> nav.navigate(HistoryRoute(id)) },
            )
        }
        composable<SettingsRoute> { entry -> SettingsScreen(onBack = { nav.popIfCurrent(entry) }) }
        composable<TrashRoute> { entry -> TrashScreen(onBack = { nav.popIfCurrent(entry) }) }
        composable<HistoryRoute> { entry ->
            HistoryScreen(entry.toRoute<HistoryRoute>().noteId, onBack = { nav.popIfCurrent(entry) })
        }
    }
}
