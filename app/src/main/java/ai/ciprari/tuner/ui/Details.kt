package ai.ciprari.tuner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.ciprari.tuner.data.Episode
import ai.ciprari.tuner.data.Movie
import ai.ciprari.tuner.data.PlayRequest
import ai.ciprari.tuner.data.Repo
import ai.ciprari.tuner.data.Series
import ai.ciprari.tuner.data.SeriesInfo
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

@Composable
private fun Backdrop(image: String?) {
    Box(Modifier.fillMaxSize()) {
        if (image != null) AsyncImage(
            image, null, contentScale = ContentScale.Crop, alpha = 0.5f,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.7f),
        )
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.3f to Palette.bg, 0.8f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Palette.bg)))
    }
}

@Composable
fun MovieDetailScreen(movie: Movie, state: AppState) {
    val info by produceState<ai.ciprari.tuner.data.MovieInfo?>(null, movie.id) { value = Repo.movieInfo(movie) }
    val recents by Repo.prefs.recents.collectAsState()
    val resume = recents.firstOrNull { it.id == movie.id }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(50); runCatching { first.requestFocus() } }

    fun play(from: Long) = state.push(
        Screen.Player(PlayRequest.Vod(movie.id, movie.name, info?.year, movie.poster, movie.url, from)),
    )

    Box(Modifier.fillMaxSize()) {
        Backdrop(info?.backdrop ?: movie.poster)
        Row(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(200.dp).height(300.dp).clip(RoundedCornerShape(12.dp)).background(Palette.surface2)) {
                val poster = info?.poster ?: movie.poster
                if (poster != null) AsyncImage(poster, movie.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(40.dp))
            Column(Modifier.weight(1f)) {
                Text(movie.name, color = Palette.text, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 38.sp)
                val meta = listOfNotNull(
                    info?.year, info?.duration?.takeIf { it.isNotBlank() && it != "00:00:00" }, info?.genre,
                    (info?.rating ?: movie.rating)?.let { "★ ${it.take(3)}" },
                ).joinToString("  ·  ")
                if (meta.isNotBlank()) Text(meta, color = Palette.text.copy(alpha = 0.85f), fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
                if (!info?.plot.isNullOrBlank()) Text(
                    info!!.plot!!, color = Palette.dim, fontSize = 15.sp, lineHeight = 22.sp, maxLines = 5,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp).fillMaxWidth(0.85f),
                )
                info?.director?.let { Text("Director: $it", color = Palette.faint, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(top = 10.dp)) }
                info?.cast?.let { Text("Cast: $it", color = Palette.faint, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
                Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (resume != null) {
                        PillButton(
                            "Resume from ${fmtClock(resume.positionMs)}", { play(resume.positionMs) }, Modifier.focusRequester(first),
                            icon = { Icon(Icons.Rounded.PlayArrow, null, tint = it) }, primary = true,
                        )
                        PillButton("Start over", { play(0) }, icon = { Icon(Icons.Rounded.Replay, null, tint = it) })
                    } else {
                        PillButton(
                            "Play", { play(0) }, Modifier.focusRequester(first),
                            icon = { Icon(Icons.Rounded.PlayArrow, null, tint = it) }, primary = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SeriesDetailScreen(series: Series, state: AppState) {
    val info by produceState<SeriesInfo?>(null, series.id) { value = Repo.seriesInfo(series) }
    val recents by Repo.prefs.recents.collectAsState()
    var season by remember { mutableIntStateOf(-1) }
    val playFocus = remember { FocusRequester() }
    val episodeList = rememberLazyListState()

    val seasons = info?.seasons.orEmpty()
    val allEpisodes = remember(seasons) { seasons.values.flatten() }
    val lastWatched = remember(recents, allEpisodes) {
        recents.firstNotNullOfOrNull { r -> allEpisodes.firstOrNull { it.id == r.id }?.let { it to r.positionMs } }
    }
    if (season == -1 && seasons.isNotEmpty()) season = lastWatched?.first?.season ?: seasons.keys.first()
    val episodes = seasons[season].orEmpty()

    LaunchedEffect(info != null) {
        delay(50); runCatching { playFocus.requestFocus() }
    }

    fun play(ep: Episode, from: Long) {
        val idx = allEpisodes.indexOf(ep)
        val queue = allEpisodes.drop(idx + 1).take(30).map { it.toRequest(series, 0) }
        state.lastEpisodeKey = ep.id
        state.push(Screen.Player(ep.toRequest(series, from).copy(queue = queue)))
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(info?.backdrop ?: series.backdrop ?: series.cover)
        Row(Modifier.fillMaxSize().padding(start = 56.dp, end = 32.dp, top = 40.dp)) {
            Column(Modifier.weight(0.45f).padding(end = 28.dp)) {
                Text(series.name, color = Palette.text, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 36.sp)
                val meta = listOfNotNull(
                    info?.year ?: series.year, info?.genre ?: series.genre,
                    if (seasons.isNotEmpty()) "${seasons.size} season" + (if (seasons.size == 1) "" else "s") else null,
                    series.rating?.let { "★ ${it.take(3)}" },
                ).joinToString("  ·  ")
                if (meta.isNotBlank()) Text(meta, color = Palette.text.copy(alpha = 0.85f), fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                val plot = info?.plot ?: series.plot
                if (!plot.isNullOrBlank()) Text(
                    plot, color = Palette.dim, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 5,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp),
                )
                Spacer(Modifier.height(22.dp))
                when {
                    info == null -> Text("Loading episodes…", color = Palette.dim, fontSize = 14.sp)
                    allEpisodes.isEmpty() -> Text("No episodes found", color = Palette.dim, fontSize = 14.sp)
                    lastWatched != null -> PillButton(
                        "Resume S${lastWatched.first.season} E${lastWatched.first.number}",
                        { play(lastWatched.first, lastWatched.second) }, Modifier.focusRequester(playFocus),
                        icon = { Icon(Icons.Rounded.PlayArrow, null, tint = it) }, primary = true,
                    )
                    else -> PillButton(
                        "Play S${allEpisodes.first().season} E${allEpisodes.first().number}",
                        { play(allEpisodes.first(), 0) }, Modifier.focusRequester(playFocus),
                        icon = { Icon(Icons.Rounded.PlayArrow, null, tint = it) }, primary = true,
                    )
                }
                if (seasons.size > 1) {
                    LazyRow(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(seasons.keys.toList()) { s ->
                            val sel = s == season
                            FocusBox(
                                onClick = { season = s },
                                shape = RoundedCornerShape(50),
                                color = if (sel) Palette.accent.copy(alpha = 0.25f) else Palette.surface2,
                                focusedColor = Palette.focus, ring = false,
                                onFocus = { if (it) season = s },
                            ) { f ->
                                Text(
                                    "Season $s", color = if (f) Palette.onFocus else Palette.text, fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                )
                            }
                        }
                    }
                }
            }
            LazyColumn(
                state = episodeList,
                modifier = Modifier.weight(0.55f).fillMaxHeight(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 40.dp, end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(episodes, key = { it.id }) { ep ->
                    val r = recents.firstOrNull { it.id == ep.id }
                    EpisodeRow(
                        ep = ep,
                        progress = r?.let { if (it.durationMs > 0) it.positionMs.toFloat() / it.durationMs else null },
                        restore = state.restoreFocus && state.lastEpisodeKey == ep.id,
                        onRestored = { state.restoreFocus = false },
                        onClick = { play(ep, r?.positionMs ?: 0) },
                    )
                }
            }
        }
    }
    // Coming back from the player: jump to the episode that was playing.
    LaunchedEffect(state.restoreFocus, episodes) {
        if (state.restoreFocus) {
            val idx = episodes.indexOfFirst { it.id == state.lastEpisodeKey }
            if (idx >= 0) episodeList.scrollToItem(idx) else state.restoreFocus = false
        }
    }
}

private fun Episode.toRequest(series: Series, from: Long) = PlayRequest.Vod(
    resumeId = id,
    title = series.name,
    subtitle = "S$season E$number · $title",
    image = image ?: series.cover,
    url = url,
    startMs = from,
)

@Composable
private fun EpisodeRow(ep: Episode, progress: Float?, restore: Boolean, onRestored: () -> Unit, onClick: () -> Unit) {
    val fr = remember { FocusRequester() }
    if (restore) LaunchedEffect(Unit) { delay(80); runCatching { fr.requestFocus() }; onRestored() }
    FocusBox(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(96.dp).focusRequester(fr),
        shape = RoundedCornerShape(10.dp),
        color = Palette.surface.copy(alpha = 0.85f),
        focusedColor = Palette.surface2,
        focusedScale = 1.02f,
    ) { _ ->
        Row(Modifier.fillMaxSize().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(142.dp).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(Palette.cell)) {
                if (ep.image != null) AsyncImage(ep.image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Rounded.PlayArrow, null, tint = Palette.dim, modifier = Modifier.align(Alignment.Center).size(28.dp))
                progress?.let { p ->
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = 0.6f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0f, 1f)).background(Palette.accent))
                    }
                }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${ep.number}. ${ep.title}", color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (ep.durationSecs > 0) Text(
                        fmtDuration(ep.durationSecs * 1000L), color = Palette.dim, fontSize = 13.sp,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                if (!ep.plot.isNullOrBlank()) Text(
                    ep.plot, color = Palette.dim, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
