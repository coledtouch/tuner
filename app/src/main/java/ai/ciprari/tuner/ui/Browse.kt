package ai.ciprari.tuner.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.ciprari.tuner.data.Category
import ai.ciprari.tuner.data.LoadStatus
import ai.ciprari.tuner.data.Movie
import ai.ciprari.tuner.data.PlayRequest
import ai.ciprari.tuner.data.Recent
import ai.ciprari.tuner.data.Repo
import ai.ciprari.tuner.data.Series
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

data class BrowseItem(
    val key: String,
    val title: String,
    val image: String?,
    val badge: String? = null,
    val progress: Float? = null,
    val onClick: () -> Unit,
    /** Optional extra info for the hero, resolved lazily when the item has focus. */
    val heroPlot: String? = null,
    val heroBackdrop: String? = null,
    val heroMeta: String? = null,
    val movie: Movie? = null,
)

data class BrowseRow(val key: String, val title: String, val items: List<BrowseItem>)

/** TV-style scrolling: keep the focused row/card near a fixed pivot instead of at the edge. */
@OptIn(ExperimentalFoundationApi::class)
private fun pivotSpec(fraction: Float) = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        // Always line the focused item up with the pivot; the list clamps at its ends.
        return offset - containerSize * fraction
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowseScreen(
    state: AppState,
    prefix: String,
    rows: List<BrowseRow>,
    listState: LazyListState,
    status: LoadStatus,
    emptyTitle: String,
) {
    var heroItem by remember { mutableStateOf<BrowseItem?>(null) }
    val firstCard = remember { FocusRequester() }

    LaunchedEffect(state.restoreFocus, rows.isNotEmpty()) {
        if (!state.restoreFocus || rows.isEmpty()) return@LaunchedEffect
        delay(300)
        if (state.restoreFocus) {
            listState.scrollToItem(0)
            delay(30)
            runCatching { firstCard.requestFocus() }
            state.restoreFocus = false
        }
    }

    if (rows.isEmpty()) {
        when (status) {
            is LoadStatus.Loading -> LoadingMessage(status.message)
            is LoadStatus.Error -> EmptyMessage("Couldn't load", status.message)
            else -> EmptyMessage(emptyTitle)
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Hero(heroItem ?: rows.first().items.firstOrNull())
        CompositionLocalProvider(LocalBringIntoViewSpec provides pivotSpec(0.0f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 60.dp),
            ) {
                itemsIndexed(rows.distinctBy { it.key }, key = { _, r -> r.key }) { rowIdx, row ->
                    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp)) {
                        SectionTitle(row.title, Modifier.padding(start = 28.dp, bottom = 10.dp))
                        CompositionLocalProvider(LocalBringIntoViewSpec provides pivotSpec(0.06f)) {
                            LazyRow(
                                state = state.rowState("$prefix:${row.key}"),
                                contentPadding = PaddingValues(horizontal = 28.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                itemsIndexed(row.items.distinctBy { it.key }, key = { _, it -> it.key }) { idx, item ->
                                    val cardKey = "$prefix:${row.key}:${item.key}"
                                    PosterCard(
                                        item = item,
                                        modifier = if (rowIdx == 0 && idx == 0) Modifier.focusRequester(firstCard) else Modifier,
                                        restore = state.restoreFocus && state.lastFocusedKey == cardKey,
                                        onRestored = { state.restoreFocus = false },
                                        onFocused = { heroItem = item; state.lastFocusedKey = cardKey },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PosterCard(
    item: BrowseItem,
    modifier: Modifier,
    restore: Boolean,
    onRestored: () -> Unit,
    onFocused: () -> Unit,
    width: Dp = 116.dp,
) {
    val fr = remember { FocusRequester() }
    if (restore) LaunchedEffect(Unit) { delay(60); runCatching { fr.requestFocus() }; onRestored() }
    FocusBox(
        onClick = item.onClick,
        modifier = modifier.focusRequester(fr).width(width).height(width * 1.5f),
        shape = RoundedCornerShape(8.dp),
        color = Palette.surface2,
        focusedScale = 1.08f,
        onFocus = { if (it) onFocused() },
    ) { f ->
        if (item.image != null) {
            AsyncImage(item.image, item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (item.image == null || f) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = if (item.image == null) 0.2f else 0.85f)),
                        startY = 100f,
                    ),
                ),
            )
            Text(
                item.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, lineHeight = 15.sp,
                modifier = Modifier.align(if (item.image == null) Alignment.Center else Alignment.BottomCenter).padding(8.dp),
            )
        }
        item.badge?.let {
            Text(
                it, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        item.progress?.let { p ->
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.6f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0f, 1f)).background(Palette.accent))
            }
        }
    }
}

@Composable
private fun Hero(item: BrowseItem?) {
    // Movies only carry plot/backdrop in their detail call; fetch it once focus rests on a poster.
    val info by produceState<ai.ciprari.tuner.data.MovieInfo?>(null, item?.key) {
        value = null
        val m = item?.movie ?: return@produceState
        delay(650)
        value = Repo.movieInfo(m)
    }
    val backdrop = item?.heroBackdrop ?: info?.backdrop
    val plot = item?.heroPlot ?: info?.plot
    val meta = listOfNotNull(
        item?.heroMeta ?: info?.year,
        info?.duration?.takeIf { it.isNotBlank() && it != "00:00:00" },
        info?.genre,
        (item?.badge ?: info?.rating)?.let { "★ $it" },
    ).joinToString("  ·  ")

    Box(Modifier.fillMaxWidth().height(206.dp)) {
        Crossfade(backdrop ?: item?.image, label = "hero") { img ->
            if (img != null) Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    img, null, contentScale = ContentScale.Crop, alpha = if (backdrop != null) 0.55f else 0.22f,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.62f),
                )
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.35f to Palette.bg, 0.75f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Palette.bg)))
        if (item != null) Column(Modifier.fillMaxWidth(0.55f).padding(start = 28.dp, top = 14.dp)) {
            Text(item.title, color = Palette.text, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 32.sp)
            if (meta.isNotBlank()) Text(meta, color = Palette.text.copy(alpha = 0.8f), fontSize = 14.sp, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
            if (!plot.isNullOrBlank()) Text(
                plot, color = Palette.dim, fontSize = 14.sp, lineHeight = 19.sp, maxLines = 3,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ Movies & Series tabs

private fun <T> groupRows(
    prefix: String,
    items: List<T>,
    categories: List<Category>,
    categoriesOf: (T) -> List<String>,
    toItem: (T) -> BrowseItem,
): List<BrowseRow> {
    val byCat = LinkedHashMap<String, MutableList<T>>()
    items.forEach { t -> categoriesOf(t).ifEmpty { listOf("") }.forEach { c -> byCat.getOrPut(c) { mutableListOf() } += t } }
    val known = categories.map { it.id }.toSet()
    val rows = categories.mapNotNull { c ->
        byCat[c.id]?.takeIf { it.isNotEmpty() }?.let { BrowseRow("$prefix${c.id}", c.name, it.map(toItem)) }
    }
    val orphans = byCat.filterKeys { it !in known }.values.flatten().distinct()
    return if (orphans.isEmpty()) rows else rows + BrowseRow("${prefix}other", "More", orphans.map(toItem))
}

fun recentsRow(recents: List<Recent>, kindPrefix: List<String>, state: AppState): BrowseRow? {
    val list = recents.filter { r -> kindPrefix.any { r.id.startsWith(it) } }
    if (list.isEmpty()) return null
    return BrowseRow("continue", "Continue Watching", list.map { r ->
        BrowseItem(
            key = r.id, title = r.title + (r.subtitle?.let { " · $it" } ?: ""), image = r.image,
            progress = if (r.durationMs > 0) r.positionMs.toFloat() / r.durationMs else null,
            onClick = {
                state.push(Screen.Player(PlayRequest.Vod(r.id, r.title, r.subtitle, r.image, r.url, r.positionMs)))
            },
        )
    })
}

@Composable
fun MoviesScreen(state: AppState) {
    val movies by Repo.movies.collectAsState()
    val cats by Repo.movieCategories.collectAsState()
    val status by Repo.vodStatus.collectAsState()
    val recents by Repo.prefs.recents.collectAsState()

    val rows = remember(movies, cats, recents) {
        fun item(m: Movie) = BrowseItem(
            key = m.id, title = m.name, image = m.poster, badge = m.rating?.take(3),
            movie = m, onClick = { state.push(Screen.MovieDetail(m)) },
        )
        buildList {
            recentsRow(recents, listOf("m:", "mu:"), state)?.let { add(it) }
            if (movies.any { it.added > 0 }) {
                add(BrowseRow("recent", "Recently Added", movies.sortedByDescending { it.added }.take(40).map(::item)))
            }
            addAll(groupRows("c", movies, cats, { it.categoryIds }, ::item))
        }
    }
    BrowseScreen(state, "movies", rows, state.moviesList, status, "No movies in this playlist")
}

@Composable
fun SeriesScreen(state: AppState) {
    val series by Repo.series.collectAsState()
    val cats by Repo.seriesCategories.collectAsState()
    val status by Repo.seriesStatus.collectAsState()
    val recents by Repo.prefs.recents.collectAsState()

    val rows = remember(series, cats, recents) {
        fun item(s: Series) = BrowseItem(
            key = s.id, title = s.name, image = s.cover, badge = s.rating?.take(3),
            heroPlot = s.plot, heroBackdrop = s.backdrop,
            heroMeta = listOfNotNull(s.year, s.genre).joinToString("  ·  ").ifBlank { null },
            onClick = { state.push(Screen.SeriesDetail(s)) },
        )
        buildList {
            recentsRow(recents, listOf("e:"), state)?.let { add(it) }
            if (series.any { it.added > 0 }) {
                add(BrowseRow("recent", "Recently Updated", series.sortedByDescending { it.added }.take(40).map(::item)))
            }
            addAll(groupRows("c", series, cats, { it.categoryIds }, ::item))
        }
    }
    BrowseScreen(state, "series", rows, state.seriesList, status, "No series in this playlist")
}
