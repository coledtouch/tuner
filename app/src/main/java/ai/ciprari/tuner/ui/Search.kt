package ai.ciprari.tuner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.ciprari.tuner.data.PlayRequest
import ai.ciprari.tuner.data.Repo
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

private data class Hit(val key: String, val title: String, val subtitle: String, val image: String?, val wide: Boolean, val channel: ai.ciprari.tuner.data.Channel? = null, val open: () -> Unit)
private data class Section(val title: String, val hits: List<Hit>)

/** Last query, kept across visits to the tab. */
private var lastQuery = ""

@Composable
fun SearchScreen(state: AppState) {
    var query by rememberSaveable { mutableStateOf(lastQuery) }
    var sections by remember { mutableStateOf<List<Section>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val channels by Repo.channels.collectAsState()
    val movies by Repo.movies.collectAsState()
    val series by Repo.series.collectAsState()
    val searchList = state.rowState("search-results")
    val fieldFocus = remember { FocusRequester() }
    val normalized = query.trim()
    val ready = normalized.length >= 2 || (normalized.isNotEmpty() && normalized.all { it.isDigit() })

    LaunchedEffect(state.restoreFocus) {
        if (state.restoreFocus) { delay(60); runCatching { fieldFocus.requestFocus() }; state.restoreFocus = false }
    }
    LaunchedEffect(query, channels, movies, series) {
        val changed = lastQuery != query
        lastQuery = query
        val q = query.trim().lowercase(Locale.ROOT)
        if (!ready) { sections = emptyList(); searching = false; return@LaunchedEffect }
        searching = true
        if (changed) searchList.scrollToItem(0)
        delay(250)
        sections = withContext(Dispatchers.Default) {
            val ch = channels.filter { it.name.lowercase(Locale.ROOT).contains(q) || it.number.toString() == q }.take(30).map { c ->
                val nowProgram = Repo.programsFor(c)?.firstOrNull { it.isOnAt(System.currentTimeMillis()) }
                Hit(c.key, c.name, "Channel ${c.number}" + (nowProgram?.title?.takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: "  ·  Live TV"), c.logo, true, c) {
                    state.guide.handoff = false
                    state.push(Screen.Player(PlayRequest.Live(c, Repo.channels.value)))
                }
            }
            val mv = movies.filter { it.name.lowercase(Locale.ROOT).contains(q) }.take(40).map { m ->
                Hit(m.id, m.name, listOfNotNull("Movie", m.rating?.let { "★ ${it.take(3)}" }).joinToString(" · "), m.poster, false) {
                    state.push(Screen.MovieDetail(m))
                }
            }
            val se = series.filter { it.name.lowercase(Locale.ROOT).contains(q) }.take(40).map { s ->
                Hit("s:" + s.id, s.name, listOfNotNull("Series", s.year).joinToString(" · "), s.cover, false) {
                    state.push(Screen.SeriesDetail(s))
                }
            }
            listOf(Section("Live TV", ch), Section("Movies", mv), Section("Series", se)).filter { it.hits.isNotEmpty() }
        }
        searching = false
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
    val compact = maxWidth < 760.dp || maxHeight < 400.dp
    Column(Modifier.fillMaxSize().padding(horizontal = if (compact) 24.dp else 48.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Search", color = Palette.text, fontSize = if (compact) 24.sp else 28.sp, fontWeight = FontWeight.Bold)
            Text("Find your next favorite", color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvTextField(
                label = "", value = query, onChange = { query = it }, focusRequester = fieldFocus,
                placeholder = "Search titles, channels or channel numbers", keyboardType = KeyboardType.Text,
                modifier = Modifier.weight(1f),
            )
            if (query.isNotEmpty()) PillButton("Clear", { query = ""; fieldFocus.requestFocus() })
        }
        Text(
            when { searching -> "Searching your library…"; !ready -> "Press OK to type  ·  Search with 2 letters or a channel number"; else -> "Showing ${sections.sumOf { it.hits.size }} matches  ·  Select live TV to watch, or a title for details" },
            color = Palette.dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
        when {
            searching -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Palette.accent, strokeWidth = 3.dp, modifier = Modifier.size(30.dp))
                Text("Finding something good…", color = Palette.dim, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
            }
            !ready -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                if (!compact) Icon(Icons.Rounded.Search, null, tint = Palette.accent,
                    modifier = Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(Palette.accent.copy(alpha = 0.12f)).padding(13.dp))
                Text("Everything you love, one search", color = Palette.text, fontSize = if (compact) 19.sp else 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = if (compact) 0.dp else 16.dp))
                Text("Find a channel, movie or series by name.\nKnow your channel number? Enter it to jump straight there.",
                    color = Palette.dim, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton("Live TV", { state.tab = Tab.GUIDE; state.restoreFocus = true }, icon = { Icon(Icons.Rounded.Tv, null, tint = it, modifier = Modifier.size(18.dp)) })
                    PillButton("Movies", { state.tab = Tab.MOVIES; state.restoreFocus = true }, icon = { Icon(Icons.Rounded.Movie, null, tint = it, modifier = Modifier.size(18.dp)) })
                    PillButton("Series", { state.tab = Tab.SERIES; state.restoreFocus = true }, icon = { Icon(Icons.Rounded.Tv, null, tint = it, modifier = Modifier.size(18.dp)) })
                }
            }
            sections.isEmpty() -> EmptyMessage("No matches for “${query.trim().take(45)}”", "Try a shorter title, another spelling or a channel number.")
            else -> LazyColumn(state = searchList, contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                sections.forEach { sec ->
                    item(key = "h" + sec.title) {
                        SectionTitle(sec.title + "  ·  ${sec.hits.size}", Modifier.padding(top = 14.dp, bottom = 4.dp, start = 4.dp))
                    }
                    items(sec.hits, key = { sec.title + it.key }) { h -> HitRow(h) }
                }
            }
        }
        }
    }
    }
}

@Composable
private fun HitRow(h: Hit) {
    FocusBox(
        onClick = h.open, modifier = Modifier.fillMaxWidth().height(70.dp),
        shape = RoundedCornerShape(10.dp), color = Palette.surface, focusedColor = Palette.focus,
        ring = false, focusedScale = 1.02f,
    ) { f ->
        val fg = if (f) Palette.onFocus else Palette.text
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(if (h.wide) 64.dp else 34.dp).fillMaxHeight().padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(6.dp)).background(Palette.surface2),
                contentAlignment = Alignment.Center,
            ) {
                if (h.channel != null) ChannelLogo(h.channel, Modifier.fillMaxSize().padding(4.dp))
                else if (h.image != null) AsyncImage(
                    h.image, null, contentScale = if (h.wide) ContentScale.Fit else ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                ) else Icon(Icons.Rounded.Movie, null, tint = Palette.dim, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(h.title, color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(h.subtitle, color = fg.copy(alpha = 0.72f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
            Icon(if (h.channel != null) Icons.Rounded.PlayArrow else Icons.Rounded.ArrowForward, null, tint = fg.copy(alpha = if (f) 1f else 0.42f), modifier = Modifier.padding(start = 16.dp, end = 10.dp).size(22.dp))
        }
    }
}
