package ai.ciprari.tuner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import ai.ciprari.tuner.data.Movie
import ai.ciprari.tuner.data.PlayRequest
import ai.ciprari.tuner.data.Repo
import ai.ciprari.tuner.data.Series
import ai.ciprari.tuner.player.PlayerHolder

sealed interface Screen {
    data object Home : Screen
    data class EditSource(val sourceId: String?) : Screen
    data class MovieDetail(val movie: Movie) : Screen
    data class SeriesDetail(val series: Series) : Screen
    data class Player(val request: PlayRequest) : Screen
}

enum class Tab(val title: String) { GUIDE("Live TV"), MOVIES("Movies"), SERIES("Series"), SEARCH("Search"), SETTINGS("Settings") }

/** Screen-independent state that has to survive navigating to a detail page or the player and back. */
class AppState {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    var tab by mutableStateOf(Tab.GUIDE)
    val guide = GuideState()
    val moviesList = LazyListState()
    val seriesList = LazyListState()
    private val rowStates = HashMap<String, LazyListState>()
    fun rowState(key: String): LazyListState = rowStates.getOrPut(key) { LazyListState() }
    fun clearRowStates() = rowStates.clear()
    var lastFocusedKey by mutableStateOf<String?>(null)
    var lastEpisodeKey by mutableStateOf<String?>(null)
    /** True when the current home tab should grab focus (app start, coming back from a detail page). */
    var restoreFocus by mutableStateOf(true)

    val top: Screen get() = stack.last()
    fun push(s: Screen) { stack.add(s) }
    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
        restoreFocus = true
    }
    fun replaceAll(s: Screen) { stack.clear(); stack.add(s); restoreFocus = true }
}

@Composable
fun App() {
    val state = remember { AppState() }
    val sources by Repo.prefs.sources.collectAsState()
    val activeId by Repo.prefs.activeId.collectAsState()

    // First run: go straight to the add-source form.
    LaunchedEffect(Unit) {
        if (sources.isEmpty()) state.replaceAll(Screen.EditSource(null))
        else Repo.load()
    }
    LaunchedEffect(activeId) { state.guide.reset() }

    BackHandler(enabled = state.stack.size > 1 && state.top !is Screen.Player) { state.pop() }
    // On the home screen, Back from any tab other than the guide returns to the guide first.
    BackHandler(enabled = state.stack.size == 1 && state.top == Screen.Home && state.tab != Tab.GUIDE) {
        state.tab = Tab.GUIDE
        state.restoreFocus = true
    }

    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        when (val s = state.top) {
            Screen.Home -> HomeScreen(state)
            is Screen.EditSource -> key(s) {
                EditSourceScreen(
                    sourceId = s.sourceId,
                    canCancel = sources.isNotEmpty(),
                    onDone = {
                        if (state.stack.size > 1) state.pop() else state.replaceAll(Screen.Home)
                    },
                )
            }
            is Screen.MovieDetail -> key(s) { MovieDetailScreen(s.movie, state) }
            is Screen.SeriesDetail -> key(s) { SeriesDetailScreen(s.series, state) }
            is Screen.Player -> key(s) {
                PlayerScreen(s.request, onExit = { lastChannel ->
                    // Leaving live TV with guide preview on keeps the stream running in the preview window.
                    if (lastChannel == null || !Repo.prefs.guidePreview) PlayerHolder.stop()
                    lastChannel?.let { state.guide.focusChannel(it) }
                    state.pop()
                })
            }
        }
    }
}
