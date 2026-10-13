package ai.ciprari.tuner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import ai.ciprari.tuner.data.Category
import ai.ciprari.tuner.data.Channel
import ai.ciprari.tuner.data.LoadStatus
import ai.ciprari.tuner.data.Movie
import ai.ciprari.tuner.data.PlayRequest
import ai.ciprari.tuner.data.Program
import ai.ciprari.tuner.data.Repo
import ai.ciprari.tuner.data.Source
import ai.ciprari.tuner.data.SourceType
import ai.ciprari.tuner.ui.AppState
import ai.ciprari.tuner.ui.CAT_FAV
import ai.ciprari.tuner.ui.HomeScreen
import ai.ciprari.tuner.ui.Palette
import ai.ciprari.tuner.ui.Screen
import ai.ciprari.tuner.ui.Tab
import ai.ciprari.tuner.ui.Themes
import ai.ciprari.tuner.ui.Toasts
import ai.ciprari.tuner.ui.TunerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
@OptIn(ExperimentalTestApi::class)
class GuideSearchFlowTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val source = "guide-search-flow"
    private lateinit var channels: List<Channel>

    @Before fun seed() {
        Repo.prefs.saveSource(Source(source, "Local UI fixture", SourceType.M3U, m3uUrl = "http://example.invalid/fixture.m3u"))
        Repo.prefs.setActive(source)
        Repo.prefs.favorites.value.toList().forEach { Repo.prefs.toggleFavorite(it) }
        Repo.prefs.lastChannelKey = null
        Repo.prefs.guidePreview = false
        Palette.theme = Themes.Midnight
        Toasts.message = null
        Repo.liveStatus.value = LoadStatus.Ready
        Repo.vodStatus.value = LoadStatus.Ready
        Repo.seriesStatus.value = LoadStatus.Ready
        channels = (0 until 12).map { index ->
            Channel("flow:$index", 100 + index, "News Channel $index", null, "news", "flow$index.us", null, "http://example.invalid/live/$index.ts")
        }
        Repo.channels.value = channels
        Repo.trending.value = emptyList()
        Repo.liveCategories.value = listOf(Category("news", "USA News"))
        Repo.movies.value = (0 until 8).map { index ->
            Movie("flow-m:$index", "Movie $index", null, "8.2", "movies", "http://example.invalid/movie/$index.mp4")
        }
        Repo.movieCategories.value = listOf(Category("movies", "Movies"))
        Repo.series.value = emptyList()
        Repo.seriesCategories.value = emptyList()
        val now = System.currentTimeMillis()
        Repo.putPrograms(channels.associate { channel ->
            channel.key to listOf(
                Program(now - 30 * 60_000, now + 30 * 60_000, "Current bulletin", "News and today's stories."),
                Program(now + 30 * 60_000, now + 90 * 60_000, "Next bulletin", "The next news hour."),
            )
        })
        rule.mainClock.autoAdvance = false
    }

    @Composable
    @OptIn(ExperimentalComposeUiApi::class)
    private fun Fixture(state: AppState) {
        val input = LocalInputModeManager.current
        SideEffect { input.requestInputMode(InputMode.Keyboard) }
        TunerTheme {
            Box(Modifier.fillMaxSize().background(Palette.bg)) {
                if (state.top == Screen.Home) HomeScreen(state) else Text("Opened destination")
            }
        }
    }

    private fun settle(ms: Long = 800) {
        rule.runOnIdle { Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        rule.runOnIdle { Snapshot.sendApplyNotifications() }
        repeat(3) { rule.mainClock.advanceTimeByFrame() }
        rule.waitForIdle()
    }

    private fun movieTag(index: Int) = "search-hit:$source:movie:flow-m:$index"
    private fun channelTag(index: Int) = "search-hit:$source:channel:flow:$index"

    @Test fun searchReturnRestoresTheSelectedResultAndQuery() {
        val state = AppState().apply { tab = Tab.SEARCH; searchQuery = "Movie" }
        rule.setContent { Fixture(state) }
        settle()
        rule.onNodeWithTag(movieTag(1)).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        rule.onNodeWithTag(movieTag(1)).assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.Enter) }
        settle(300)
        rule.runOnIdle { assertEquals("flow-m:1", (state.top as Screen.MovieDetail).movie.id); state.pop() }
        settle()
        rule.onNodeWithTag(movieTag(1)).assertIsFocused()
        rule.runOnIdle { assertEquals("Movie", state.searchQuery); assertFalse(state.restoreFocus) }
    }

    @Test fun searchFavoriteIsASeparateFocusTargetAndDoesNotStartPlayback() {
        val state = AppState().apply { tab = Tab.SEARCH; searchQuery = "100" }
        rule.setContent { Fixture(state) }
        settle()
        rule.onNodeWithTag(channelTag(0)).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithContentDescription("Add News Channel 0 to Favorites").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.Enter) }
        settle(200)
        rule.onNodeWithContentDescription("Remove News Channel 0 from Favorites").assertIsFocused()
        rule.runOnIdle { assertTrue(channels[0].key in Repo.prefs.favorites.value); assertEquals(Screen.Home, state.top) }
        rule.onRoot().performKeyInput { pressKey(Key.Enter) }
        settle(200)
        rule.runOnIdle { assertFalse(channels[0].key in Repo.prefs.favorites.value); assertEquals(Screen.Home, state.top) }
    }

    @Test fun holdingASearchLiveResultSavesItWithoutOpeningPlayback() {
        val state = AppState().apply { tab = Tab.SEARCH; searchQuery = "100" }
        rule.setContent { Fixture(state) }
        settle()
        rule.onNodeWithTag(channelTag(0)).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        rule.onNodeWithTag(channelTag(0)).performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        settle(200)
        rule.runOnIdle { assertTrue(channels[0].key in Repo.prefs.favorites.value); assertEquals(Screen.Home, state.top) }
        rule.onNodeWithTag(channelTag(0)).assertIsFocused()
    }

    @Test fun searchFindsExtraTrendingChannelsAndIncludesThemInThePlayerLineup() {
        val event = channels[0].copy(key = "event:1", number = 500, name = "Featured Event", url = "http://example.invalid/live/event.ts")
        Repo.trending.value = listOf(event)
        val state = AppState().apply { tab = Tab.SEARCH; searchQuery = "Featured" }
        rule.setContent { Fixture(state) }
        settle()
        rule.onNodeWithTag("search-hit:$source:channel:event:1").performClick()
        settle(200)
        rule.runOnIdle {
            val request = (state.top as Screen.Player).request as PlayRequest.Live
            assertEquals(event.key, request.channel.key)
            assertTrue(request.lineup.any { it.key == event.key })
        }
    }

    @Test fun aChannelNumberTakesPriorityOverNameMatchesAndAcceptsLeadingZeroes() {
        val exact = channels[1].copy(number = 1, name = "Exact station")
        Repo.channels.value = (0 until 50).map { index ->
            channels[0].copy(key = "mention:$index", number = 200 + index, name = "001 event $index")
        } + exact
        val state = AppState().apply { tab = Tab.SEARCH; searchQuery = "001" }
        rule.setContent { Fixture(state) }
        settle()
        rule.onNodeWithTag(channelTag(1)).assertExists()
        rule.onNodeWithTag(channelTag(1)).performClick()
        settle(200)
        rule.runOnIdle { assertEquals(exact.key, ((state.top as Screen.Player).request as PlayRequest.Live).channel.key) }
    }

    @Test fun refreshingGuideMetadataKeepsSearchResultFocus() {
        val state = AppState().apply { tab = Tab.SEARCH; searchQuery = "100" }
        rule.setContent { Fixture(state) }
        settle()
        rule.onNodeWithTag(channelTag(0)).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        val now = System.currentTimeMillis()
        rule.runOnIdle { Repo.putPrograms(mapOf(channels[0].key to listOf(Program(now - 60_000, now + 60_000, "Updated bulletin")))) }
        settle(100)
        rule.onNodeWithTag(channelTag(0)).assertIsFocused()
        settle()
        rule.onNodeWithTag(channelTag(0)).assertIsFocused()
        // Search computes off the UI thread. Wait for its result to reach a Compose frame,
        // rather than assuming that advancing the virtual clock also completes that worker.
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.runOnIdle { Snapshot.sendApplyNotifications() }
            rule.mainClock.advanceTimeByFrame()
            rule.onAllNodesWithText("Channel 100  ·  Updated bulletin").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Channel 100  ·  Updated bulletin").assertExists()
    }

    @Test fun backFromResultsReturnsToSearchInput() {
        val state = AppState().apply { tab = Tab.SEARCH; searchQuery = "Movie" }
        rule.setContent { Fixture(state) }
        settle()
        rule.onNodeWithTag(movieTag(1)).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        settle(100)
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        settle(300)
        rule.onNodeWithText("Movie").assertIsFocused()
        rule.runOnIdle { assertEquals(Tab.SEARCH, state.tab); assertEquals(null, state.searchFocusedKey) }
    }

    @Test fun removingAnEarlierFavoriteKeepsTheSelectedChannel() {
        channels.take(5).forEach { Repo.prefs.toggleFavorite(it.key) }
        val state = AppState().apply { guide.category = CAT_FAV; guide.row = 3; guide.initialized = true }
        rule.setContent { Fixture(state) }
        settle()
        rule.runOnIdle { assertEquals(channels[3].key, state.guide.selectedChannelKey); Repo.prefs.toggleFavorite(channels[0].key) }
        settle(300)
        rule.runOnIdle { assertEquals(2, state.guide.row); assertEquals(channels[3].key, state.guide.selectedChannelKey) }
        rule.onNodeWithTag("guide-grid").assertIsFocused()
    }

    @Test fun channelRefreshRestoresSelectionByKeyAcrossAnEmptyList() {
        val state = AppState().apply { guide.row = 4; guide.initialized = true }
        rule.setContent { Fixture(state) }
        settle()
        rule.runOnIdle { assertEquals(channels[4].key, state.guide.selectedChannelKey); Repo.channels.value = emptyList() }
        settle(200)
        rule.runOnIdle { Repo.channels.value = channels.reversed() }
        settle()
        rule.runOnIdle { assertEquals(channels[4].key, state.guide.selectedChannelKey); assertEquals(7, state.guide.row) }
    }

    @Test fun pendingChannelSelectionIsObservedWithoutAnotherStateChange() {
        val state = AppState().apply { guide.initialized = true }
        rule.setContent { Fixture(state) }
        settle()
        rule.runOnIdle { state.guide.pendingChannelKey = channels[7].key }
        settle(500)
        rule.runOnIdle { assertEquals(7, state.guide.row); assertEquals(channels[7].key, state.guide.selectedChannelKey); assertEquals(null, state.guide.pendingChannelKey) }
    }

    @Test fun futureProgramDetailsCanSaveAChannelWithoutTuningIt() {
        val state = AppState().apply { guide.initialized = true }
        rule.setContent { Fixture(state) }
        settle()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight); pressKey(Key.Enter) }
        settle(300)
        rule.onNodeWithText("Add to Favorites").performClick()
        settle(200)
        rule.onNodeWithText("Saved in Favorites").assertExists()
        rule.runOnIdle { assertTrue(channels[0].key in Repo.prefs.favorites.value); assertEquals(Screen.Home, state.top) }
        rule.onNodeWithText("Close").performClick()
        settle(300)
        rule.onNodeWithTag("guide-grid").assertIsFocused()
    }
}
