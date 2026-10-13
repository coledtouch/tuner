package ai.ciprari.tuner.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.ciprari.tuner.data.LoadStatus
import ai.ciprari.tuner.data.Repo
import kotlinx.coroutines.delay

/** Lightweight toast shown at the bottom of the home screen. */
object Toasts {
    var message by mutableStateOf<String?>(null)
    private var seq = 0
    var id by mutableStateOf(0)
        private set
    fun show(text: String) { message = text; id = ++seq }
}

@Composable
fun HomeScreen(state: AppState) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Palette.surface, Palette.bg, Palette.bg)))) {
        Column(Modifier.fillMaxSize()) {
            TopBar(state)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (state.tab) {
                    Tab.GUIDE -> GuideScreen(state)
                    Tab.MOVIES -> MoviesScreen(state)
                    Tab.SERIES -> SeriesScreen(state)
                    Tab.STREAMING -> TorrentioScreen(state)
                    Tab.SEARCH -> SearchScreen(state)
                    Tab.SETTINGS -> SettingsScreen(state)
                }
            }
        }
    }
}

@Composable
private fun TopBar(state: AppState) {
    val now = rememberNow(15_000)
    val live by Repo.liveStatus.collectAsState()
    val epg by Repo.epgStatus.collectAsState()
    val vod by Repo.vodStatus.collectAsState()
    val series by Repo.seriesStatus.collectAsState()
    val focusManager = LocalFocusManager.current
    val busy = listOf(live, epg, vod, series).firstOrNull { it is LoadStatus.Loading } as? LoadStatus.Loading

    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val containerWidth = maxWidth
    val wide = maxWidth >= 850.dp
    val showClock = maxWidth >= 720.dp
    val inset = if (maxWidth < 720.dp) 20.dp else 28.dp
    Row(
        Modifier.fillMaxWidth().height(if (wide) 64.dp else 58.dp).padding(horizontal = inset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(Palette.accent),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.PlayArrow, null, tint = Palette.onAccent, modifier = Modifier.size(22.dp)) }
        if (showClock) Column(Modifier.padding(start = 9.dp)) {
            Text("tuner", color = Palette.text, fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
            Text("LIVE & ON DEMAND", color = Palette.dim, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
        }
        Spacer(Modifier.width(if (wide) 28.dp else 16.dp))
        Row(Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Tab.entries.forEach { t ->
                val selected = state.tab == t
                FocusBox(
                    onClick = {
                        if (state.tab == t) focusManager.moveFocus(FocusDirection.Down)
                        else { state.tab = t; state.restoreFocus = true }
                    },
                    modifier = Modifier.semantics { this.selected = selected; contentDescription = t.title },
                    shape = RoundedCornerShape(10.dp),
                    focusedScale = 1.025f,
                    color = if (selected) Palette.accent.copy(alpha = 0.15f) else Palette.bg.copy(alpha = 0f),
                    focusedColor = Palette.focus,
                    ring = false,
                ) { f ->
                    val c = when { f -> Palette.onFocus; selected -> Palette.accent; else -> Palette.dim }
                    Row(Modifier.padding(horizontal = if (wide) 13.dp else 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(when (t) {
                        Tab.GUIDE -> Icons.Rounded.LiveTv
                        Tab.MOVIES -> Icons.Rounded.Movie
                        Tab.SERIES -> Icons.Rounded.Tv
                        Tab.STREAMING -> Icons.Rounded.Cloud
                        Tab.SEARCH -> Icons.Rounded.Search
                        Tab.SETTINGS -> Icons.Rounded.Settings
                    }, null, tint = c, modifier = Modifier.size(18.dp))
                    if (wide || t in listOf(Tab.GUIDE, Tab.MOVIES, Tab.SERIES, Tab.STREAMING)) {
                    Text(
                        t.title,
                        color = c,
                        fontSize = 15.sp, fontWeight = if (selected || f) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                    )
                    }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (busy != null) {
            CircularProgressIndicator(color = Palette.dim, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
            if (containerWidth >= 1120.dp) Text("Updating library", color = Palette.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp, end = 16.dp))
            else Spacer(Modifier.width(12.dp))
        }
        if (showClock) Text(fmtTime(now), color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
    }
}

/** Shown over every screen, the player included, so "Skipping a dead channel" is seen while watching. */
@Composable
internal fun ToastHost(modifier: Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Toasts.id) {
        if (Toasts.message == null) return@LaunchedEffect
        visible = true; delay(2200); visible = false
    }
    AnimatedVisibility(visible, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier.clip(RoundedCornerShape(50)).background(Palette.surface2).padding(horizontal = 22.dp, vertical = 11.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(Palette.accent))
                Text(Toasts.message ?: "", color = Palette.text, fontSize = 15.sp, modifier = Modifier.padding(start = 10.dp))
            }
        }
    }
}
