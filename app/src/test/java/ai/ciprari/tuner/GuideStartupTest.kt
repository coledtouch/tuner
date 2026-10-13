package ai.ciprari.tuner

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import ai.ciprari.tuner.data.Category
import ai.ciprari.tuner.data.Channel
import ai.ciprari.tuner.data.LoadStatus
import ai.ciprari.tuner.data.Program
import ai.ciprari.tuner.data.Repo
import ai.ciprari.tuner.data.Source
import ai.ciprari.tuner.data.SourceType
import ai.ciprari.tuner.player.PlayerHolder
import ai.ciprari.tuner.ui.AppState
import ai.ciprari.tuner.ui.HomeScreen
import ai.ciprari.tuner.ui.Palette
import ai.ciprari.tuner.ui.Tab
import ai.ciprari.tuner.ui.Themes
import ai.ciprari.tuner.ui.TunerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/** Saved channels must remain useful while their provider refresh is still in progress. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
@OptIn(ExperimentalTestApi::class)
class GuideStartupTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private lateinit var channels: List<Channel>

    @Before fun seedSavedLineup() {
        Repo.init(rule.activity.applicationContext)
        // M3U fixture intentionally has no guide/network work and previews stay off.
        Repo.prefs.saveSource(Source("startup-fixture", "Saved local lineup", SourceType.M3U, m3uUrl = "http://example.invalid/unused.m3u"))
        Repo.prefs.setActive("startup-fixture")
        Repo.prefs.lastChannelKey = null
        Repo.prefs.guidePreview = false
        channels = (0 until 12).map { i -> Channel("startup:$i", 100 + i, "Saved Channel $i", null, "news", null, null, "http://example.invalid/unused/$i.m3u8") }
        Repo.channels.value = channels
        Repo.liveCategories.value = listOf(Category("news", "USA News"))
        Repo.trending.value = emptyList()
        Repo.movies.value = emptyList()
        Repo.movieCategories.value = emptyList()
        Repo.series.value = emptyList()
        Repo.seriesCategories.value = emptyList()
        Repo.liveStatus.value = LoadStatus.Loading("Refreshing channels")
        Repo.vodStatus.value = LoadStatus.Loading("Refreshing movies")
        Repo.seriesStatus.value = LoadStatus.Loading("Refreshing series")
        Repo.epgStatus.value = LoadStatus.Loading("Refreshing guide")
        val now = System.currentTimeMillis()
        Repo.putPrograms(channels.associate { it.key to listOf(Program(now - 60000, now + 3600000, "Saved program")) })
        Palette.theme = Themes.Midnight
        rule.mainClock.autoAdvance = false
    }

    @Composable
    @OptIn(ExperimentalComposeUiApi::class)
    private fun Fixture(state: AppState) {
        val input = LocalInputModeManager.current
        SideEffect { input.requestInputMode(InputMode.Keyboard) }
        TunerTheme { HomeScreen(state) }
    }

    private fun advance() {
        ShadowLooper.idleMainLooper()
        rule.runOnIdle { Snapshot.sendApplyNotifications() }
        repeat(3) { rule.mainClock.advanceTimeByFrame() }
        rule.waitForIdle()
    }

    private fun awaitGridFocus() {
        rule.waitUntil(timeoutMillis = 5000) {
            advance()
            rule.onAllNodes(hasTestTag("guide-grid") and isFocused()).fetchSemanticsNodes().size == 1
        }
    }

    @Test fun savedChannelsAndProgramsRemainNavigableWhileLoading() {
        val state = AppState()
        rule.setContent { Fixture(state) }
        awaitGridFocus()
        rule.onNodeWithContentDescription("Channel 100, Saved Channel 0").assertExists()
        rule.onNodeWithText("Loading channels…").assertDoesNotExist()
        rule.onNodeWithTag("guide-grid").performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionDown) }
        advance()
        rule.runOnIdle {
            assertEquals(channels[2].key, state.guide.selectedChannelKey)
            assertEquals(2, state.guide.row)
            assertEquals(LoadStatus.Loading("Refreshing channels"), Repo.liveStatus.value)
            assertEquals(null, PlayerHolder.currentUrl)
            assertEquals(null, Repo.prefs.lastChannelKey)
        }
        rule.onNodeWithTag("guide-grid").assertIsFocused()
    }

    @Test fun tabsCanBeOpenedWithTheRemoteBeforeRefreshFinishes() {
        val state = AppState()
        rule.setContent { Fixture(state) }
        awaitGridFocus()
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        advance()
        rule.onNodeWithContentDescription("Live TV").assertIsFocused()
        rule.onNodeWithContentDescription("Live TV").performKeyInput { pressKey(Key.DirectionRight) }
        advance()
        rule.onNodeWithContentDescription("Movies").assertIsFocused()
        rule.runOnIdle { assertEquals(Tab.GUIDE, state.tab) }
        rule.onNodeWithContentDescription("Movies").performKeyInput { pressKey(Key.Enter) }
        advance()
        rule.runOnIdle {
            assertEquals(Tab.MOVIES, state.tab)
            assertFalse(Repo.liveStatus.value is LoadStatus.Ready)
            assertEquals(null, PlayerHolder.currentUrl)
        }
    }
}
