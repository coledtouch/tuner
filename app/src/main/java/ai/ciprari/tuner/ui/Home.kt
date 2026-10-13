package ai.ciprari.tuner.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(state)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (state.tab) {
                    Tab.GUIDE -> GuideScreen(state)
                    Tab.MOVIES -> MoviesScreen(state)
                    Tab.SERIES -> SeriesScreen(state)
                    Tab.SEARCH -> SearchScreen(state)
                    Tab.SETTINGS -> SettingsScreen(state)
                }
            }
        }
        ToastHost(Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp))
    }
}

@Composable
private fun TopBar(state: AppState) {
    val now = rememberNow(15_000)
    val live by Repo.liveStatus.collectAsState()
    val epg by Repo.epgStatus.collectAsState()
    val vod by Repo.vodStatus.collectAsState()
    val focusManager = LocalFocusManager.current
    val busy = listOf(live, epg, vod).firstOrNull { it is LoadStatus.Loading } as? LoadStatus.Loading

    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).background(Palette.accent),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.PlayArrow, null, tint = Palette.onFocus, modifier = Modifier.size(20.dp)) }
        Text("Tuner", color = Palette.text, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
        Spacer(Modifier.width(36.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tab.entries.forEach { t ->
                val selected = state.tab == t
                FocusBox(
                    onClick = { state.tab = t; focusManager.moveFocus(FocusDirection.Down) },
                    shape = RoundedCornerShape(50),
                    color = if (selected) Palette.surface2 else Palette.bg,
                    focusedColor = Palette.focus,
                    ring = false,
                    onFocus = { if (it) state.tab = t },
                ) { f ->
                    Text(
                        t.title,
                        color = when { f -> Palette.onFocus; selected -> Palette.text; else -> Palette.dim },
                        fontSize = 15.sp, fontWeight = if (selected || f) FontWeight.SemiBold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (busy != null) {
            CircularProgressIndicator(color = Palette.dim, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
            Text(busy.message, color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp, end = 18.dp))
        }
        Text(fmtTime(now), color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ToastHost(modifier: Modifier) {
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
