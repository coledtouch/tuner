package ai.ciprari.tuner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.ciprari.tuner.data.FreeEpg
import ai.ciprari.tuner.data.LoadStatus
import ai.ciprari.tuner.data.Repo
import ai.ciprari.tuner.data.Source
import ai.ciprari.tuner.data.SourceType
import ai.ciprari.tuner.player.PlayerHolder
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(state: AppState) {
    val sources by Repo.prefs.sources.collectAsState()
    val activeId by Repo.prefs.activeId.collectAsState()
    val channels by Repo.channels.collectAsState()
    val movies by Repo.movies.collectAsState()
    val series by Repo.series.collectAsState()
    val epgStatus by Repo.epgStatus.collectAsState()
    var sheetFor by remember { mutableStateOf<Source?>(null) }
    var confirmDelete by remember { mutableStateOf<Source?>(null) }
    var themeSheet by remember { mutableStateOf(false) }
    var regionSheet by remember { mutableStateOf(false) }
    // Settings values aren't flows; bump this to redraw after changing one.
    var rev by remember { mutableIntStateOf(0) }
    val first = remember { FocusRequester() }

    LaunchedEffect(state.restoreFocus) {
        if (state.restoreFocus) { delay(60); runCatching { first.requestFocus() }; state.restoreFocus = false }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 120.dp, end = 120.dp, top = 12.dp, bottom = 60.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            header("Playlists")
            items(sources, key = { it.id }) { s ->
                val active = s.id == activeId
                SettingRow(
                    title = s.name,
                    subtitle = (if (s.type == SourceType.XTREAM) "Xtream Codes · " + s.server.removePrefix("http://").removePrefix("https://")
                    else "M3U · " + s.m3uUrl.take(60)),
                    trailing = if (active) "Active" else null,
                    modifier = if (s == sources.first()) Modifier.focusRequester(first) else Modifier,
                    onClick = { sheetFor = s },
                )
            }
            item {
                SettingRow("Add a playlist", "Xtream Codes login or M3U link", icon = true, onClick = { state.push(Screen.EditSource(null)) })
            }

            header("Appearance")
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
                    Text("USA / English content", color = Palette.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("U.S. English channels and English movies and series. Entries without a reliable provider country or language label are hidden.", color = Palette.dim, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 5.dp))
                }
            }
            item {
                SettingRow("Theme", "Colors for the guide, menus and highlights", trailing = Palette.theme.name, onClick = { themeSheet = true })
            }

            header("Playback")
            item(key = "fmt$rev") {
                val ts = Repo.prefs.liveFormat == "ts"
                SettingRow(
                    "Live stream format", if (ts) "MPEG-TS (.ts) · most compatible" else "HLS (.m3u8) · try this if channels stutter",
                    trailing = if (ts) "TS" else "HLS",
                    onClick = {
                        Repo.prefs.liveFormat = if (ts) "m3u8" else "ts"; rev++
                        Repo.load(); Toasts.show("Live format changed, reloading channels")
                    },
                )
            }
            item(key = "prev$rev") {
                val on = Repo.prefs.guidePreview
                SettingRow(
                    "Guide preview", "Play the highlighted channel in the guide's preview window",
                    trailing = if (on) "On" else "Off",
                    onClick = {
                        Repo.prefs.guidePreview = !on; rev++
                        if (on) PlayerHolder.stop()
                    },
                )
            }

            header("Guide")
            item(key = "free$rev") {
                val regions = Repo.prefs.freeEpgRegions
                val names = FreeEpg.regions.filter { it.code in regions }.map { it.name }
                SettingRow(
                    "Free guide fallback",
                    if (names.isEmpty()) "Off · only your provider's guide is used"
                    else "Fills missing channels from: " + names.joinToString(", "),
                    trailing = if (names.isEmpty()) "Off" else "${names.size} on",
                    onClick = { regionSheet = true },
                )
            }

            header("Data")
            item {
                SettingRow(
                    "Refresh channels, movies and guide", guideSummary(epgStatus, channels.size, movies.size, series.size),
                    onClick = { Repo.load(force = true); Toasts.show("Refreshing from your provider") },
                )
            }
            item {
                SettingRow(
                    "Clear cached data", "Frees storage. Everything downloads again on next load.",
                    onClick = { Repo.clearCache(); Toasts.show("Cache cleared") },
                )
            }
            item {
                Text(
                    "Tuner 1.0  ·  Hold OK on a guide channel to favorite it  ·  Number keys tune directly",
                    color = Palette.faint, fontSize = 12.sp, modifier = Modifier.padding(top = 18.dp),
                )
            }
        }

        if (themeSheet) ThemeSheet(onClose = { themeSheet = false; rev++; state.restoreFocus = true })
        if (regionSheet) RegionSheet(onClose = { changed ->
            regionSheet = false; rev++; state.restoreFocus = true
            if (changed) { Repo.reloadFreeGuides(); Toasts.show("Looking for guide data in the new regions") }
        })

        sheetFor?.let { s ->
            ActionSheet(
                title = s.name,
                actions = buildList {
                    if (s.id != activeId) add("Use this playlist" to {
                        Repo.prefs.setActive(s.id); state.clearRowStates(); Repo.load(); state.tab = Tab.GUIDE
                    })
                    add("Edit" to { state.push(Screen.EditSource(s.id)) })
                    if (s.id == activeId) add("Refresh now" to { Repo.load(force = true) })
                    add("Delete" to { confirmDelete = s })
                },
                onClose = { sheetFor = null; state.restoreFocus = true },
            )
        }
        confirmDelete?.let { s ->
            ActionSheet(
                title = "Delete ${s.name}?",
                actions = listOf(
                    "Delete" to {
                        val wasActive = s.id == activeId
                        Repo.prefs.deleteSource(s.id)
                        if (Repo.prefs.sources.value.isEmpty()) { PlayerHolder.stop(); state.replaceAll(Screen.EditSource(null)) }
                        else if (wasActive) { state.clearRowStates(); Repo.load() }
                    },
                    "Keep it" to {},
                ),
                onClose = { confirmDelete = null; state.restoreFocus = true },
            )
        }
    }
}

private fun guideSummary(epg: LoadStatus, ch: Int, mv: Int, se: Int): String {
    val guide = when (epg) {
        is LoadStatus.Ready -> "guide loaded"
        is LoadStatus.Loading -> "guide loading"
        is LoadStatus.Error -> "guide: ${epg.message}"
        LoadStatus.Idle -> "guide not loaded"
    }
    return "$ch channels · $mv movies · $se series · $guide"
}

private fun LazyListScope.header(text: String) {
    item(key = "h_$text") {
        Text(text, color = Palette.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp, start = 4.dp))
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    icon: Boolean = false,
    onClick: () -> Unit,
) {
    FocusBox(
        onClick = onClick, modifier = modifier.fillMaxWidth().height(64.dp),
        shape = RoundedCornerShape(10.dp), color = Palette.surface, focusedColor = Palette.focus,
        ring = false, focusedScale = 1.02f,
    ) { f ->
        val fg = if (f) Palette.onFocus else Palette.text
        Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon) Icon(Icons.Rounded.Add, null, tint = if (f) Palette.onFocus else Palette.accent, modifier = Modifier.padding(end = 12.dp).size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(subtitle, color = fg.copy(alpha = 0.6f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            trailing?.let {
                if (it == "Active") Icon(Icons.Rounded.CheckCircle, null, tint = if (f) Palette.onFocus else Palette.good, modifier = Modifier.padding(end = 6.dp).size(18.dp))
                Text(it, color = if (f) Palette.onFocus else Palette.dim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun ActionSheet(title: String, actions: List<Pair<String, () -> Unit>>, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    BackHandler { onClose() }
    LaunchedEffect(Unit) { delay(40); runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().dimBackground(0.7f), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(420.dp).clip(RoundedCornerShape(16.dp)).background(Palette.surface).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            actions.forEachIndexed { i, (label, action) ->
                FocusBox(
                    onClick = { onClose(); action() },
                    modifier = Modifier.fillMaxWidth().height(48.dp).then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                    shape = RoundedCornerShape(10.dp), color = Palette.surface2, focusedColor = Palette.focus,
                    ring = false, focusedScale = 1.02f,
                ) { f ->
                    Text(
                        label, color = if (f) Palette.onFocus else if (label.startsWith("Delete")) Palette.live else Palette.text,
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeSheet(onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    BackHandler { onClose() }
    LaunchedEffect(Unit) { delay(40); runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().dimBackground(0.6f), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier.width(400.dp).fillMaxHeight().background(Palette.surface).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Theme", color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            Text("Changes apply as you move through the list.", color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
            Themes.all.forEach { t ->
                val current = Palette.theme.id == t.id
                FocusBox(
                    onClick = { onClose() },
                    modifier = Modifier.fillMaxWidth().height(56.dp).then(if (current) Modifier.focusRequester(first) else Modifier),
                    shape = RoundedCornerShape(10.dp), color = Palette.surface2, focusedColor = Palette.focus,
                    ring = false, focusedScale = 1.02f,
                    onFocus = { if (it) { Palette.theme = t; Repo.prefs.themeId = t.id } },
                ) { f ->
                    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.clip(RoundedCornerShape(6.dp))) {
                            listOf(t.bg, t.surface2, t.accent, t.focus).forEach { c -> Box(Modifier.size(width = 14.dp, height = 28.dp).background(c)) }
                        }
                        Text(
                            t.name, color = if (f) Palette.onFocus else Palette.text, fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 14.dp),
                        )
                        if (current) Icon(Icons.Rounded.CheckCircle, null, tint = if (f) Palette.onFocus else Palette.accent, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun RegionSheet(onClose: (changed: Boolean) -> Unit) {
    val initial = remember { Repo.prefs.freeEpgRegions.toSet() }
    var selected by remember { mutableStateOf(initial) }
    val first = remember { FocusRequester() }
    fun done() { Repo.prefs.freeEpgRegions = FreeEpg.regions.map { it.code }.filter { it in selected }; onClose(selected != initial) }
    BackHandler { done() }
    LaunchedEffect(Unit) { delay(40); runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().dimBackground(0.6f), contentAlignment = Alignment.CenterEnd) {
        Column(Modifier.width(460.dp).fillMaxHeight().background(Palette.surface).padding(start = 24.dp, end = 24.dp, top = 24.dp)) {
            Text("Free guide fallback", color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "When your provider has no listings for a channel, Tuner matches it by name against these free guides from epgshare01.",
                color = Palette.dim, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    PillButton("Done", { done() }, Modifier.focusRequester(first).padding(bottom = 6.dp), primary = true)
                }
                items(FreeEpg.regions, key = { it.code }) { r ->
                    val on = r.code in selected
                    FocusBox(
                        onClick = { selected = if (on) selected - r.code else selected + r.code },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(10.dp), color = Palette.surface2, focusedColor = Palette.focus,
                        ring = false, focusedScale = 1.02f,
                    ) { f ->
                        val fg = if (f) Palette.onFocus else Palette.text
                        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(20.dp).clip(RoundedCornerShape(5.dp))
                                    .background(if (on) Palette.accent else fg.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center,
                            ) { if (on) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                            Text(r.name, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 12.dp))
                            Text(r.size, color = fg.copy(alpha = 0.55f), fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
