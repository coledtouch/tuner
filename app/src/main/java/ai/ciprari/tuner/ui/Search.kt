package ai.ciprari.tuner.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.ciprari.tuner.data.PlayRequest
import ai.ciprari.tuner.data.Repo
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class Hit(val key: String, val title: String, val subtitle: String, val image: String?, val wide: Boolean, val channel: ai.ciprari.tuner.data.Channel? = null, val open: () -> Unit)
private data class Section(val title: String, val hits: List<Hit>)

/** Last query, kept across visits to the tab. */
private var lastQuery = ""

@Composable
fun SearchScreen(state: AppState) {
    var query by rememberSaveable { mutableStateOf(lastQuery) }
    var sections by remember { mutableStateOf<List<Section>>(emptyList()) }
    val fieldFocus = remember { FocusRequester() }

    LaunchedEffect(state.restoreFocus) {
        if (state.restoreFocus) { delay(60); runCatching { fieldFocus.requestFocus() }; state.restoreFocus = false }
    }
    LaunchedEffect(query) {
        lastQuery = query
        val q = query.trim().lowercase()
        if (q.length < 2) { sections = emptyList(); return@LaunchedEffect }
        delay(250)
        sections = withContext(Dispatchers.Default) {
            val ch = Repo.channels.value.filter { it.name.lowercase().contains(q) }.take(30).map { c ->
                Hit(c.key, c.name, "Channel ${c.number}", c.logo, true, c) {
                    state.guide.handoff = false
                    state.push(Screen.Player(PlayRequest.Live(c, Repo.channels.value)))
                }
            }
            val mv = Repo.movies.value.filter { it.name.lowercase().contains(q) }.take(40).map { m ->
                Hit(m.id, m.name, listOfNotNull("Movie", m.rating?.let { "★ ${it.take(3)}" }).joinToString(" · "), m.poster, false) {
                    state.push(Screen.MovieDetail(m))
                }
            }
            val se = Repo.series.value.filter { it.name.lowercase().contains(q) }.take(40).map { s ->
                Hit("s:" + s.id, s.name, listOfNotNull("Series", s.year).joinToString(" · "), s.cover, false) {
                    state.push(Screen.SeriesDetail(s))
                }
            }
            listOf(Section("Channels", ch), Section("Movies", mv), Section("Series", se)).filter { it.hits.isNotEmpty() }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 120.dp)) {
        TvTextField(
            label = "", value = query, onChange = { query = it }, focusRequester = fieldFocus,
            placeholder = "Search channels, movies and series", keyboardType = KeyboardType.Text,
            modifier = Modifier.padding(top = 12.dp, bottom = 10.dp),
        )
        when {
            query.trim().length < 2 -> EmptyMessage("Find something to watch", "Press OK on the search box to type.")
            sections.isEmpty() -> EmptyMessage("No matches for \"${query.trim()}\"")
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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

@Composable
private fun HitRow(h: Hit) {
    FocusBox(
        onClick = h.open, modifier = Modifier.fillMaxWidth().height(62.dp),
        shape = RoundedCornerShape(10.dp), color = Palette.surface, focusedColor = Palette.focus,
        ring = false, focusedScale = 1.02f,
    ) { f ->
        val fg = if (f) Palette.onFocus else Palette.text
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(if (h.wide) 64.dp else 34.dp).fillMaxHeight().padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(4.dp)).background(if (h.wide) Palette.bg.copy(alpha = 0f) else Palette.surface2),
                contentAlignment = Alignment.Center,
            ) {
                if (h.channel != null) ChannelLogo(h.channel, Modifier.fillMaxSize())
                else if (h.image != null) AsyncImage(
                    h.image, null, contentScale = if (h.wide) ContentScale.Fit else ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(h.title, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(h.subtitle, color = fg.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}
