package ai.ciprari.tuner

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import ai.ciprari.tuner.data.Category
import ai.ciprari.tuner.data.Channel
import ai.ciprari.tuner.data.LoadStatus
import ai.ciprari.tuner.data.Repo
import ai.ciprari.tuner.data.Source
import ai.ciprari.tuner.data.SourceType
import ai.ciprari.tuner.player.PlayerHolder
import ai.ciprari.tuner.player.TwoChannelPlayer
import ai.ciprari.tuner.ui.AppState
import ai.ciprari.tuner.ui.MultiViewScreen
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
@OptIn(ExperimentalTestApi::class)
class MultiViewNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private lateinit var channels: List<Channel>

    @Before fun seed() {
        Repo.init(rule.activity.applicationContext)
        Repo.prefs.saveSource(Source("multiview-focus", "Local focus fixture", SourceType.M3U, m3uUrl = "http://example.invalid/unused.m3u"))
        Repo.prefs.setActive("multiview-focus")
        Repo.prefs.favorites.value.toList().forEach { Repo.prefs.toggleFavorite(it) }
        Repo.prefs.lastChannelKey = "unchanged-last-channel"
        Repo.prefs.guidePreview = false
        Repo.liveStatus.value = LoadStatus.Ready
        channels = (0 until 4).map { i -> Channel("dual-fixture:$i", i + 1, "ESPN Fixture $i", null, "sports", null, null, "http://example.invalid/unused/$i.m3u8") }
        Repo.channels.value = channels
        Repo.trending.value = emptyList()
        Repo.liveCategories.value = listOf(Category("sports", "USA Sports"))
        Palette.theme = Themes.Midnight
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { TwoChannelPlayer.init(rule.activity); TwoChannelPlayer.stop(); PlayerHolder.stop() }
    }

    @Composable
    @OptIn(ExperimentalComposeUiApi::class)
    private fun Fixture(state: AppState) {
        val input = LocalInputModeManager.current
        SideEffect { input.requestInputMode(InputMode.Keyboard) }
        TunerTheme { MultiViewScreen(state) }
    }

    private fun advance() {
        ShadowLooper.idleMainLooper()
        rule.runOnIdle { Snapshot.sendApplyNotifications() }
        repeat(3) { rule.mainClock.advanceTimeByFrame() }
        rule.waitForIdle()
    }

    private fun awaitTag(tag: String, focused: Boolean = false) {
        rule.waitUntil(timeoutMillis = 5_000) {
            advance()
            rule.onAllNodes(if (focused) hasTestTag(tag) and isFocused() else hasTestTag(tag)).fetchSemanticsNodes().size == 1
        }
    }

    private fun focus(tag: String) {
        awaitTag(tag)
        rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        advance()
        rule.onNodeWithTag(tag).assertIsFocused()
    }

    private fun assertNoPlayback() {
        rule.runOnIdle {
            assertFalse(TwoChannelPlayer.active.value)
            assertEquals(null, TwoChannelPlayer.player(0))
            assertEquals(null, TwoChannelPlayer.player(1))
            assertEquals(null, PlayerHolder.currentUrl)
            assertEquals("unchanged-last-channel", Repo.prefs.lastChannelKey)
        }
    }

    @Test fun pictureInPicturePanesAreReachableWithBothArrowPaths() {
        val state = AppState().apply { tab = Tab.MULTIVIEW; multiView.pictureInPicture = true; multiView.focusedControl = "pane-0" }
        rule.setContent { Fixture(state) }
        awaitTag("dual-pane-0", focused = true)
        rule.onNodeWithTag("dual-pane-0").performKeyInput { pressKey(Key.DirectionRight) }
        awaitTag("dual-pane-1", focused = true)
        rule.onNodeWithTag("dual-pane-1").performKeyInput { pressKey(Key.DirectionLeft) }
        awaitTag("dual-pane-0", focused = true)
        rule.onNodeWithTag("dual-pane-0").performKeyInput { pressKey(Key.DirectionDown) }
        awaitTag("dual-pane-1", focused = true)
        rule.onNodeWithTag("dual-pane-1").performKeyInput { pressKey(Key.DirectionUp) }
        awaitTag("dual-pane-0", focused = true)
        assertNoPlayback()
    }

    private fun restoreMissingControl(control: String, slot: Int) {
        val state = AppState().apply { tab = Tab.MULTIVIEW; multiView.focusedControl = control; multiView.focusedSlot = slot }
        rule.setContent { Fixture(state) }
        awaitTag("dual-pick-$slot", focused = true)
        rule.runOnIdle { assertFalse(state.restoreFocus); assertEquals("pick-$slot", state.multiView.focusedControl) }
        assertNoPlayback()
    }

    @Test fun returningAfterStopWasFocusedUsesTheVisibleChannelPicker() = restoreMissingControl("stop", 1)
    @Test fun returningAfterFirstRetryWasFocusedUsesTheFirstChannelPicker() = restoreMissingControl("retry-0", 0)
    @Test fun returningAfterSecondRetryWasFocusedUsesTheSecondChannelPicker() = restoreMissingControl("retry-1", 1)

    private fun openPicker(state: AppState) {
        rule.setContent { Fixture(state) }
        focus("dual-pick-1")
        rule.onNodeWithTag("dual-pick-1").performClick()
        rule.waitUntil(timeoutMillis = 5_000) { advance(); ShadowDialog.getLatestDialog()?.isShowing == true }
        rule.runOnIdle { ShadowDialog.getLatestDialog()?.window?.decorView?.dispatchWindowFocusChanged(true); Snapshot.sendApplyNotifications() }
        awaitTag("dual-channel:${channels[0].key}")
    }

    @Test fun choosingBeforeWatchReturnsFocusAndDoesNotTuneEitherChannel() {
        val state = AppState().apply { tab = Tab.MULTIVIEW }
        openPicker(state)
        rule.onNodeWithTag("dual-channel:${channels[0].key}").performClick()
        rule.runOnIdle { rule.activity.window.decorView.dispatchWindowFocusChanged(true); Snapshot.sendApplyNotifications() }
        awaitTag("dual-pick-1", focused = true)
        rule.runOnIdle { assertEquals(channels[0].key, state.multiView.secondKey) }
        assertNoPlayback()
    }

    @Test fun cancellingThePickerReturnsToItsChannelButtonWithoutChangingTheChoice() {
        val state = AppState().apply { tab = Tab.MULTIVIEW; multiView.secondKey = channels[2].key }
        openPicker(state)
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle { rule.activity.window.decorView.dispatchWindowFocusChanged(true); Snapshot.sendApplyNotifications() }
        awaitTag("dual-pick-1", focused = true)
        rule.runOnIdle { assertEquals(channels[2].key, state.multiView.secondKey) }
        assertNoPlayback()
    }
}
