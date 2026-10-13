package ai.ciprari.tuner.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ai.ciprari.tuner.data.Channel
import ai.ciprari.tuner.data.PlayRequest
import ai.ciprari.tuner.data.Program
import ai.ciprari.tuner.data.Repo
import ai.ciprari.tuner.player.PlayerHolder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest

const val CAT_ALL = "__all"
const val CAT_FAV = "__fav"
const val CAT_HOT = "__hot"

private const val MIN = 60_000L
private const val SLOT = 30 * MIN
private const val HOUR = 60 * MIN
private const val WINDOW_MIN = 120          // visible guide width, in minutes
private const val HORIZON = 24 * HOUR       // how far ahead you can scroll
private val ROW_H = 52.dp

fun floor30(t: Long) = t - t % SLOT

/** Guide position that survives leaving the guide (detail pages, full-screen player). */
class GuideState {
    var category by mutableStateOf(CAT_ALL)
    var row by mutableIntStateOf(0)
    /** Start of the focused program (clamped to the window). 0 means "whatever is on now". */
    var focusTime by mutableLongStateOf(0L)
    var windowStart by mutableLongStateOf(0L)
    val list = LazyListState()
    var pendingChannelKey: String? = null
    var initialized = false
    /** Set right before going full screen so leaving the guide doesn't stop the shared player. */
    var handoff = false

    fun reset() {
        category = CAT_ALL; row = 0; focusTime = 0; windowStart = 0
        pendingChannelKey = null; initialized = false
    }

    fun focusChannel(c: Channel) { pendingChannelKey = c.key; focusTime = 0; windowStart = 0 }
}

/** Per-channel timelines with gaps filled by hour-long placeholder blocks, cached per guide refresh. */
private class Timelines {
    private var version = -1
    private var base = 0L
    private val map = HashMap<String, List<Program>>()

    fun get(c: Channel, from: Long, epgVersion: Int): List<Program> {
        if (version != epgVersion || base != from) { map.clear(); version = epgVersion; base = from }
        return map.getOrPut(c.key) { build(c, from, from + HORIZON) }
    }

    private fun build(c: Channel, from: Long, to: Long): List<Program> {
        val raw = Repo.programsFor(c).orEmpty()
        val out = ArrayList<Program>()
        fun fill(a: Long, b: Long) {
            var t = a
            while (t < b) {
                val next = minOf(b, t - t % HOUR + HOUR)
                out += Program(t, next, c.name, placeholder = true)
                t = next
            }
        }
        var cursor = from
        for (p in raw) {
            if (p.end <= cursor || p.start >= to) continue
            if (p.start > cursor) fill(cursor, p.start)
            out += if (p.start < cursor && out.isNotEmpty()) p.copy(start = cursor) else p
            cursor = maxOf(cursor, p.end)
        }
        if (cursor < to) fill(cursor, to)
        return out
    }
}

@Composable
fun GuideScreen(state: AppState) {
    val g = state.guide
    val all by Repo.channels.collectAsState()
    val categories by Repo.liveCategories.collectAsState()
    val favorites by Repo.prefs.favorites.collectAsState()
    val trending by Repo.trending.collectAsState()
    val epgVersion by Repo.epgVersion.collectAsState()
    val status by Repo.liveStatus.collectAsState()
    val now = rememberNow(30_000)
    val minTime = floor30(now)
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val timelines = remember { Timelines() }
    val gridFocus = remember { FocusRequester() }
    var gridFocused by remember { mutableStateOf(false) }
    var digits by remember { mutableStateOf("") }
    var details by remember { mutableStateOf<Pair<Channel, Program>?>(null) }
    var longPressed by remember { mutableStateOf(false) }
    var previewChannelKey by remember { mutableStateOf<String?>(null) }
    val base = remember { floor30(System.currentTimeMillis()) - 6 * HOUR }

    val lineup = remember(all, g.category, favorites, trending) {
        when (g.category) {
            CAT_ALL -> all
            CAT_HOT -> trending
            CAT_FAV -> (all + trending).filter { it.key in favorites }.distinctBy { it.key }
            else -> all.filter { g.category in it.categoryIds }
        }
    }

    if (g.windowStart < minTime) g.windowStart = minTime
    if (g.row > lineup.lastIndex) g.row = lineup.lastIndex.coerceAtLeast(0)
    val focusAt = if (g.focusTime < minTime) now else g.focusTime

    fun timeline(c: Channel) = timelines.get(c, minTime, epgVersion)
    fun programAt(c: Channel, t: Long): Program? {
        val tl = timeline(c)
        return tl.firstOrNull { it.isOnAt(t) } ?: tl.lastOrNull { it.start <= t } ?: tl.firstOrNull()
    }

    val focusedChannel = lineup.getOrNull(g.row)
    val focusedProgram = focusedChannel?.let { programAt(it, focusAt) }
    val nextProgram = focusedChannel?.let { channel ->
        focusedProgram?.let { program -> timeline(channel).firstOrNull { it.start >= program.end && !it.placeholder } }
    }

    // Restore last-watched channel the first time the guide has channels.
    LaunchedEffect(all.isNotEmpty()) {
        if (all.isNotEmpty() && !g.initialized) {
            g.initialized = true
            if (g.pendingChannelKey == null) g.pendingChannelKey = Repo.prefs.lastChannelKey
        }
    }
    LaunchedEffect(lineup, g.pendingChannelKey) {
        val key = g.pendingChannelKey ?: return@LaunchedEffect
        var idx = lineup.indexOfFirst { it.key == key }
        if (idx < 0 && all.any { it.key == key } && g.category != CAT_ALL) { g.category = CAT_ALL; return@LaunchedEffect }
        if (idx < 0 && trending.any { it.key == key } && g.category != CAT_HOT) { g.category = CAT_HOT; return@LaunchedEffect }
        if (idx < 0) idx = 0
        g.pendingChannelKey = null
        g.row = idx
        g.list.scrollToItem((idx - 2).coerceAtLeast(0))
    }
    LaunchedEffect(state.restoreFocus, lineup.isNotEmpty()) {
        if (state.restoreFocus && lineup.isNotEmpty()) {
            delay(40); runCatching { gridFocus.requestFocus() }; state.restoreFocus = false
        }
    }

    // Live preview: start the focused channel once the highlight rests on it.
    val previewOn = Repo.prefs.guidePreview
    LaunchedEffect(focusedChannel?.key, gridFocused, previewOn) {
        val c = focusedChannel
        if (c == null) {
            previewChannelKey = null
            if (previewOn && PlayerHolder.isLive) PlayerHolder.stop()
            return@LaunchedEffect
        }
        if (!previewOn || !gridFocused) return@LaunchedEffect
        if (PlayerHolder.currentUrl == c.url) { previewChannelKey = c.key; return@LaunchedEffect }
        delay(650)
        PlayerHolder.play(c.url, live = true)
        previewChannelKey = c.key
        Repo.prefs.lastChannelKey = c.key
    }
    DisposableEffect(Unit) {
        onDispose {
            if (!g.handoff && PlayerHolder.isLive) PlayerHolder.stop()
            g.handoff = false
        }
    }

    // Fill guide gaps for whatever rows are on screen.
    LaunchedEffect(lineup, epgVersion) {
        snapshotFlow { g.list.firstVisibleItemIndex }.collectLatest { firstVisible ->
            delay(350)
            if (lineup.isNotEmpty()) Repo.requestShortEpg(lineup.subList(firstVisible.coerceAtMost(lineup.lastIndex), (firstVisible + 12).coerceAtMost(lineup.size)))
        }
    }

    // Number keys: type a channel number, it tunes after a short pause.
    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1400)
        val n = digits.toIntOrNull(); digits = ""
        val idx = lineup.indexOfFirst { it.number == n }
        if (idx >= 0) { g.row = idx; g.focusTime = 0; g.windowStart = minTime; g.list.scrollToItem((idx - 2).coerceAtLeast(0)) }
        else if (n != null) Toasts.show("Channel $n isn't in this list")
    }

    fun tune(c: Channel) {
        g.handoff = true
        Repo.prefs.lastChannelKey = c.key
        state.push(Screen.Player(PlayRequest.Live(c, lineup)))
    }

    fun toggleFavorite(c: Channel) {
        val added = Repo.prefs.toggleFavorite(c.key)
        Toasts.show(if (added) "${c.name} added to Favorites" else "${c.name} removed from Favorites")
    }

    fun returnToNow() {
        g.focusTime = 0
        g.windowStart = minTime
        runCatching { gridFocus.requestFocus() }
    }

    val density = LocalDensity.current
    fun scrollToRow(row: Int, fast: Boolean) {
        scope.launch {
            val info = g.list.layoutInfo
            val rowPx = info.visibleItemsInfo.firstOrNull()?.size?.toFloat() ?: with(density) { ROW_H.toPx() }
            val fit = (info.viewportSize.height / rowPx).toInt().coerceAtLeast(3)
            val first = g.list.firstVisibleItemIndex
            val target = when {
                row < first + 1 -> (row - 1).coerceAtLeast(0)
                row > first + fit - 2 -> row - fit + 2
                else -> return@launch
            }
            if (fast) g.list.scrollToItem(target) else g.list.animateScrollToItem(target)
        }
    }

    fun moveRow(delta: Int, fast: Boolean) {
        if (lineup.isEmpty()) return
        val r = (g.row + delta).coerceIn(0, lineup.lastIndex)
        if (r == g.row) return
        g.row = r
        scrollToRow(r, fast)
    }

    fun focusProgram(p: Program) {
        g.focusTime = maxOf(p.start, minTime)
        val winEnd = g.windowStart + WINDOW_MIN * MIN
        if (p.start >= winEnd - SLOT) g.windowStart = maxOf(minTime, floor30(p.start) - SLOT)
        else if (p.start < g.windowStart) g.windowStart = maxOf(minTime, floor30(p.start))
        g.windowStart = minOf(g.windowStart, minTime + HORIZON - WINDOW_MIN * MIN)
    }

    fun openProgram(index: Int, c: Channel, p: Program) {
        g.row = index
        focusProgram(p)
        runCatching { gridFocus.requestFocus() }
        if (p.isOnAt(now) || p.placeholder) tune(c) else details = c to p
    }

    fun onKey(code: Int, repeat: Int, longPress: Boolean, down: Boolean): Boolean {
        // Read position from state, not from the last composition: several key events can land in one frame.
        val c = lineup.getOrNull(g.row)
        val cur = c?.let { programAt(it, if (g.focusTime < minTime) now else g.focusTime) }
        if (details != null) return false
        if (!down) {
            if (code == AndroidKeyEvent.KEYCODE_DPAD_CENTER || code == AndroidKeyEvent.KEYCODE_ENTER) {
                if (longPressed) { longPressed = false; return true }
                if (c != null && cur != null) {
                    if (cur.start <= now || cur.placeholder) tune(c) else details = c to cur
                }
                return true
            }
            return false
        }
        val fast = repeat > 0
        when (code) {
            AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> {
                if (longPress && c != null && !longPressed) {
                    longPressed = true
                    toggleFavorite(c)
                }
                return true
            }
            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { moveRow(1, fast); return true }
            AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                if (g.row == 0) return false
                moveRow(-1, fast); return true
            }
            AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_PAGE_DOWN -> { moveRow(6, true); return true }
            AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_PAGE_UP -> { moveRow(-6, true); return true }
            AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                val p = cur ?: return true
                val next = timeline(c!!).firstOrNull { it.start >= p.end } ?: return true
                focusProgram(next); return true
            }
            AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                val p = cur ?: return true
                val prev = timeline(c!!).lastOrNull { it.end <= p.start && it.end > minTime } ?: return true
                focusProgram(prev); return true
            }
            AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                g.windowStart = minOf(minTime + HORIZON - WINDOW_MIN * MIN, g.windowStart + 2 * HOUR)
                g.focusTime = g.windowStart; return true
            }
            AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> {
                g.windowStart = maxOf(minTime, g.windowStart - 2 * HOUR)
                g.focusTime = if (g.windowStart == minTime) 0 else g.windowStart; return true
            }
            in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> {
                if (digits.length < 5) digits += (code - AndroidKeyEvent.KEYCODE_0).toString()
                return true
            }
        }
        return false
    }

    BackHandler(enabled = details == null && (gridFocused || g.focusTime > minTime || g.windowStart > minTime)) {
        if (g.focusTime > minTime || g.windowStart > minTime) returnToNow()
        else focusManager.moveFocus(FocusDirection.Up)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 720.dp || maxHeight < 400.dp
        val edge = if (compact) 18.dp else 28.dp
        val headerHeight = if (compact) 116.dp else 144.dp
        val previewWidth = if (compact) 180.dp else 232.dp
        Column(Modifier.fillMaxSize()) {
            // ---------- preview header ----------
            Row(
                Modifier.fillMaxWidth().height(headerHeight).padding(start = edge, end = edge, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProgramInfo(
                    focusedChannel, focusedProgram, nextProgram, now, focusedChannel?.key in favorites,
                    compact, Modifier.weight(1f).fillMaxHeight(),
                    onToggleFavorite = { focusedChannel?.let(::toggleFavorite) },
                )
                Spacer(Modifier.width(if (compact) 16.dp else 28.dp))
                PreviewWindow(
                    focusedChannel, previewOn, previewChannelKey == focusedChannel?.key,
                    Modifier.width(previewWidth).height(previewWidth * 9f / 16f),
                    onWatch = { focusedChannel?.let(::tune) },
                )
            }
            // ---------- category chips ----------
            CategoryChips(
                categories = categories.map { it.id to it.name },
                selected = g.category,
                hasFavorites = favorites.isNotEmpty(),
                hasTrending = trending.isNotEmpty(),
                edge = edge,
                onSelect = { id ->
                    g.category = id; g.row = 0; g.focusTime = 0; g.windowStart = minTime
                    scope.launch { g.list.scrollToItem(0) }
                    runCatching { gridFocus.requestFocus() }
                },
            )
            // ---------- grid ----------
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .focusRequester(gridFocus)
                    .onFocusChanged { gridFocused = it.isFocused }
                    .onPreviewKeyEvent { e ->
                        val ne = e.nativeKeyEvent
                        onKey(e.key.nativeKeyCode, ne.repeatCount, ne.isLongPress, e.type == KeyEventType.KeyDown)
                    }
                    .focusable(),
            ) {
                when {
                    lineup.isEmpty() && all.isEmpty() && status is ai.ciprari.tuner.data.LoadStatus.Loading -> LoadingMessage("Loading channels…")
                    lineup.isEmpty() && all.isEmpty() && status is ai.ciprari.tuner.data.LoadStatus.Error ->
                        EmptyMessage("Couldn't load channels", (status as ai.ciprari.tuner.data.LoadStatus.Error).message)
                    lineup.isEmpty() && g.category == CAT_FAV ->
                        EmptyMessage("No favorites yet", "Hold OK on any channel in the guide to add it here.")
                    lineup.isEmpty() && all.isNotEmpty() -> EmptyMessage("No channels in this category", "Choose All Channels or try another category.")
                    lineup.isEmpty() -> EmptyMessage("No matching USA channels", "Only USA / English channels with provider region labels are shown. Check your playlist in Settings.")
                    else -> GuideGrid(
                        lineup = lineup, g = g, base = base, now = now,
                        gridFocused = gridFocused, focusAt = focusAt, favorites = favorites,
                        compact = compact, edge = edge, timeline = ::timeline,
                        onProgramClick = ::openProgram,
                        onChannelClick = { index, channel ->
                            g.row = index; returnToNow(); tune(channel)
                        },
                        onFavorite = ::toggleFavorite,
                    )
                }
                if (digits.isNotEmpty()) {
                    Text(
                        digits, color = Palette.text, fontSize = 40.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                            .clip(RoundedCornerShape(12.dp)).background(Palette.surface2).padding(horizontal = 18.dp, vertical = 6.dp),
                    )
                }
            }
            if (lineup.isNotEmpty()) GuideFooter(
                row = g.row, total = lineup.size, future = g.windowStart > minTime || g.focusTime > now,
                compact = compact, edge = edge, onNow = ::returnToNow,
            )
        }
        details?.let { (c, p) ->
            ProgramDetailsDialog(c, p, onWatch = { details = null; tune(c) }, onClose = { details = null; runCatching { gridFocus.requestFocus() } })
        }
    }
}

// ------------------------------------------------------------------ grid

@Composable
private fun GuideGrid(
    lineup: List<Channel>,
    g: GuideState,
    base: Long,
    now: Long,
    gridFocused: Boolean,
    focusAt: Long,
    favorites: Set<String>,
    compact: Boolean,
    edge: Dp,
    timeline: (Channel) -> List<Program>,
    onProgramClick: (Int, Channel, Program) -> Unit,
    onChannelClick: (Int, Channel) -> Unit,
    onFavorite: (Channel) -> Unit,
) {
    val winMin by animateFloatAsState(
        ((g.windowStart - base) / MIN).toFloat(), tween(280, easing = FastOutSlowInEasing), label = "window",
    )
    BoxWithConstraints(Modifier.fillMaxSize().padding(end = edge)) {
        val channelW = if (compact) 168.dp else 210.dp
        val areaW = (maxWidth - channelW - 8.dp).coerceAtLeast(1.dp)
        val dpPerMin = areaW / WINDOW_MIN
        val visStart = base + (winMin * MIN).toLong()

        Column(Modifier.fillMaxSize()) {
            TimeHeader(visStart, winMin, base, channelW, areaW, dpPerMin, now, edge)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(state = g.list, modifier = Modifier.fillMaxSize().focusProperties { canFocus = false }) {
                    itemsIndexed(lineup, key = { _, c -> c.key }) { idx, c ->
                        val isRow = idx == g.row
                        GuideRow(
                            channel = c,
                            programs = timeline(c),
                            focusedRow = isRow,
                            gridFocused = gridFocused,
                            focusAt = focusAt,
                            winMin = winMin,
                            base = base,
                            areaW = areaW,
                            dpPerMin = dpPerMin,
                            now = now,
                            favorite = c.key in favorites,
                            channelW = channelW,
                            rowHeight = if (compact) 46.dp else ROW_H,
                            compact = compact,
                            edge = edge,
                            onProgramClick = { p -> onProgramClick(idx, c, p) },
                            onChannelClick = { onChannelClick(idx, c) },
                            onFavorite = { onFavorite(c) },
                        )
                    }
                }
                // "Now" line across the grid.
                val nowOff = (now - base) / MIN.toFloat() - winMin
                if (nowOff in 0f..WINDOW_MIN.toFloat()) {
                    Box(
                        Modifier
                            .offset(x = channelW + 8.dp + dpPerMin * nowOff)
                            .width(1.dp).fillMaxHeight()
                            .background(Palette.live.copy(alpha = 0.65f)),
                    )
                }
                // Fade at the bottom edge so rows slide in rather than cut off.
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(28.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Palette.bg))),
                )
            }
        }
    }
}

@Composable
private fun TimeHeader(visStart: Long, winMin: Float, base: Long, channelW: Dp, areaW: Dp, dpPerMin: Dp, now: Long, edge: Dp) {
    Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        val today = fmtDay(now) == fmtDay(visStart + SLOT)
        Text(
            if (today) "Today" else fmtDay(visStart + SLOT),
            color = Palette.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(channelW + 8.dp).padding(start = edge),
        )
        Box(Modifier.width(areaW).fillMaxHeight().clipToBounds()) {
            var t = floor30(visStart)
            val end = visStart + WINDOW_MIN * MIN
            while (t <= end) {
                val off = (t - base) / MIN.toFloat() - winMin
                if (off > -40) {
                    Row(Modifier.offset(x = dpPerMin * off).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(1.dp).height(12.dp).background(Palette.line))
                        Text(fmtTime(t), color = Palette.dim, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                t += SLOT
            }
            val nowOff = (now - base) / MIN.toFloat() - winMin
            if (nowOff in 0f..WINDOW_MIN.toFloat()) Box(
                Modifier.offset(x = dpPerMin * nowOff - 3.dp).align(Alignment.BottomStart)
                    .size(7.dp).clip(RoundedCornerShape(50)).background(Palette.live),
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun GuideRow(
    channel: Channel,
    programs: List<Program>,
    focusedRow: Boolean,
    gridFocused: Boolean,
    focusAt: Long,
    winMin: Float,
    base: Long,
    areaW: Dp,
    dpPerMin: Dp,
    now: Long,
    favorite: Boolean,
    channelW: Dp,
    rowHeight: Dp,
    compact: Boolean,
    edge: Dp,
    onProgramClick: (Program) -> Unit,
    onChannelClick: () -> Unit,
    onFavorite: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(rowHeight).padding(vertical = 2.dp)) {
        // Channel cell
        Row(
            Modifier
                .width(channelW).fillMaxHeight()
                .padding(start = edge - 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (focusedRow) Palette.surface2 else Color.Transparent)
                .focusProperties { canFocus = false }
                .combinedClickable(onClick = onChannelClick, onLongClick = onFavorite)
                .semantics { contentDescription = "Channel ${channel.number}, ${channel.name}${if (favorite) ", favorite" else ""}" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(if (focusedRow && gridFocused) Palette.accent else Color.Transparent))
            Text(
                channel.number.toString(), color = if (focusedRow) Palette.text else Palette.dim,
                fontSize = if (compact) 12.sp else 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End,
                modifier = Modifier.width(if (compact) 32.dp else 40.dp),
            )
            ChannelLogo(channel, Modifier.padding(start = if (compact) 7.dp else 10.dp).size(width = if (compact) 32.dp else 40.dp, height = 28.dp))
            Text(
                channel.name, color = if (focusedRow) Palette.text else Palette.text.copy(alpha = 0.82f),
                fontSize = if (compact) 11.sp else 12.sp, fontWeight = if (focusedRow) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp,
                modifier = Modifier.weight(1f).padding(start = 7.dp, end = 4.dp),
            )
            if (favorite) Icon(Icons.Rounded.Star, null, tint = Color(0xFFFFC94D), modifier = Modifier.padding(end = 6.dp).size(13.dp))
        }
        Spacer(Modifier.width(8.dp))
        // Program cells
        Box(Modifier.width(areaW).fillMaxHeight().clipToBounds()) {
            for (p in programs) {
                val l = (p.start - base) / MIN.toFloat() - winMin
                val r = (p.end - base) / MIN.toFloat() - winMin
                if (r <= 0f || l >= WINDOW_MIN) continue
                val cl = l.coerceAtLeast(0f)
                val cr = r.coerceAtMost(WINDOW_MIN.toFloat())
                val w = dpPerMin * (cr - cl) - 3.dp
                if (w < 2.dp) continue
                val focused = focusedRow && p.isOnAt(focusAt)
                val airing = p.isOnAt(now)
                ProgramCell(
                    p = p, focused = focused, highlight = focused && gridFocused, airing = airing,
                    continuesLeft = l < 0f, continuesRight = r > WINDOW_MIN,
                    modifier = Modifier.offset(x = dpPerMin * cl).width(w).fillMaxHeight()
                        .focusProperties { canFocus = false }
                        .combinedClickable(onClick = { onProgramClick(p) }, onLongClick = onFavorite),
                )
            }
        }
    }
}

@Composable
private fun ProgramCell(
    p: Program,
    focused: Boolean,
    highlight: Boolean,
    airing: Boolean,
    continuesLeft: Boolean,
    continuesRight: Boolean,
    modifier: Modifier,
) {
    val bg = when {
        highlight -> Palette.focus
        focused -> Palette.line
        airing -> Palette.cellNow
        else -> Palette.cell
    }
    val fg = when {
        highlight -> Palette.onFocus
        p.placeholder -> Palette.dim
        else -> Palette.text
    }
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).background(bg)
            .border(if (highlight) 1.5.dp else 0.5.dp, if (highlight) Palette.focus else Palette.line.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (continuesLeft) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, null, tint = fg.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (p.placeholder) "Schedule unavailable" else p.title.ifBlank { "Untitled program" }, color = fg, fontSize = 13.sp,
                fontWeight = if (highlight || focused) FontWeight.SemiBold else FontWeight.Medium,
                lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val sub = if (p.placeholder) "Watch live" else p.subtitle ?: fmtRange(p.start, p.end)
            Text(
                sub, color = fg.copy(alpha = if (highlight) 0.8f else 0.72f), fontSize = 11.sp,
                lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (continuesRight) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = fg.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
    }
}

// ------------------------------------------------------------------ header pieces

@Composable
private fun ProgramInfo(
    c: Channel?, p: Program?, next: Program?, now: Long, favorite: Boolean,
    compact: Boolean, modifier: Modifier, onToggleFavorite: () -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.Center) {
        if (c == null) {
            Text("Your live TV guide", color = Palette.text, fontSize = if (compact) 22.sp else 28.sp, fontWeight = FontWeight.Bold)
            Text("Browse channels and see what's on next.", color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${c.number}  ${c.name}", color = Palette.dim, fontSize = if (compact) 12.sp else 13.sp,
                lineHeight = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            FocusBox(
                onClick = onToggleFavorite, shape = RoundedCornerShape(50),
                color = Color.Transparent, focusedColor = Palette.focus, ring = false,
                modifier = Modifier.padding(start = 8.dp).semantics { contentDescription = if (favorite) "Remove ${c.name} from Favorites" else "Add ${c.name} to Favorites" },
            ) { focused ->
                Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    val tint = if (focused) Palette.onFocus else if (favorite) Palette.accent else Palette.dim
                    Icon(if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, null, tint = tint, modifier = Modifier.size(15.dp))
                    if (!compact) Text(if (favorite) "Saved" else "Favorite", color = tint, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.padding(start = 5.dp))
                }
            }
        }
        Text(
            if (p == null || p.placeholder) "Watch ${c.name}" else p.title.ifBlank { c.name },
            color = Palette.text, fontSize = if (compact) 22.sp else 25.sp, fontWeight = FontWeight.Bold,
            lineHeight = if (compact) 26.sp else 30.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp),
        )
        if (p != null && !p.placeholder) {
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                if (p.isOnAt(now)) {
                    Text(
                        "ON NOW", color = Color.White, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Palette.live).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                } else {
                    Text("UPCOMING", color = Palette.accent, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    fmtRange(p.start, p.end) + (if (compact && p.isOnAt(now)) "  ·  ${((p.end - now + MIN - 1) / MIN).coerceAtLeast(1)} min left"
                        else if (!compact) "  ·  " + fmtDuration(p.end - p.start) else "") + (p.subtitle?.let { "  ·  $it" } ?: ""),
                    color = Palette.text.copy(alpha = 0.9f), fontSize = if (compact) 11.sp else 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp,
                )
            }
            if (p.isOnAt(now) && !compact) {
                val frac = ((now - p.start).toFloat() / (p.end - p.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(if (compact) 140.dp else 210.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Palette.line)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(Palette.accent))
                    }
                    Text(
                        "${((p.end - now + MIN - 1) / MIN).coerceAtLeast(1)} min left",
                        color = Palette.dim, fontSize = 10.sp, lineHeight = 13.sp, modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            if (!compact && !p.desc.isNullOrBlank()) Text(
                p.desc, color = Palette.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp),
            )
            if (next != null) Text(
                "Next  ${fmtTime(next.start)}  ·  ${next.title}",
                color = Palette.text.copy(alpha = 0.85f), fontSize = if (compact) 11.sp else 12.sp,
                lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Text("Schedule unavailable · This channel is ready to watch live.", color = Palette.dim, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun PreviewWindow(c: Channel?, previewOn: Boolean, ready: Boolean, modifier: Modifier, onWatch: () -> Unit) {
    val playing = previewOn && ready && c != null
    FocusBox(
        onClick = onWatch, modifier = modifier, shape = RoundedCornerShape(12.dp),
        color = Palette.surface, focusedScale = 1.02f,
    ) { focused ->
        Box(Modifier.fillMaxSize().border(1.dp, Palette.line, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            if (playing) VideoSurface(Modifier.fillMaxSize(), texture = true)
            else if (c != null) ChannelLogo(c, Modifier.size(86.dp, 48.dp))
            val err by PlayerHolder.error.collectAsState()
            if (playing && err != null) Text(
                "Preview unavailable", color = Color.White, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.75f)).padding(8.dp),
            )
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(40.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))))
            Row(
                Modifier.align(Alignment.BottomStart).padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(14.dp))
                Text(if (focused) "Watch live" else if (playing) "Live preview · OK to watch" else "Watch live · OK", color = Color.White, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun CategoryChips(
    categories: List<Pair<String, String>>,
    selected: String,
    hasFavorites: Boolean,
    hasTrending: Boolean,
    edge: Dp,
    onSelect: (String) -> Unit,
) {
    val items = remember(categories, hasFavorites, hasTrending) {
        buildList {
            add(CAT_ALL to "All Channels")
            if (hasTrending) add(CAT_HOT to "Trending")
            add(CAT_FAV to "Favorites")
            addAll(categories.filter { it.first !in setOf(CAT_ALL, CAT_HOT, CAT_FAV) }.distinctBy { it.first })
        }
    }
    LazyRow(
        Modifier.fillMaxWidth().height(44.dp),
        contentPadding = PaddingValues(horizontal = edge),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(items, key = { it.first }) { (id, name) ->
            val sel = id == selected
            FocusBox(
                onClick = { onSelect(id) },
                shape = RoundedCornerShape(50),
                color = if (sel) Palette.accent.copy(alpha = 0.25f) else Palette.surface,
                focusedColor = Palette.focus,
                ring = false,
            ) { f ->
                Text(
                    name, color = if (f) Palette.onFocus else if (sel) Palette.text else Palette.dim,
                    fontSize = 13.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
    }
}

@Composable
private fun GuideFooter(row: Int, total: Int, future: Boolean, compact: Boolean, edge: Dp, onNow: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(30.dp).padding(horizontal = edge),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${row + 1} / $total channels", color = Palette.dim, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        Text(
            if (compact) "OK Watch  ·  Hold OK Favorite" else "OK Watch / details  ·  Hold OK Favorite  ·  ← → Schedule",
            color = Palette.dim, fontSize = 10.sp,
        )
        if (future) {
            Spacer(Modifier.width(14.dp))
            FocusBox(onClick = onNow, color = Palette.accent.copy(alpha = 0.15f), focusedColor = Palette.focus, ring = false, shape = RoundedCornerShape(50)) { focused ->
                Text("Back to now", color = if (focused) Palette.onFocus else Palette.accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun ProgramDetailsDialog(c: Channel, p: Program, onWatch: () -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    val parentInput = LocalInputModeManager.current
    BackHandler { onClose() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
      // Keep the remote's input mode consistent when Compose creates a separate window owner.
      CompositionLocalProvider(LocalInputModeManager provides parentInput) {
      LaunchedEffect(Unit) { requestAttachedFocus(first) }
        Box(Modifier.fillMaxSize().dimBackground(0.7f), contentAlignment = Alignment.Center) {
            Column(
                Modifier.padding(24.dp).widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 420.dp)
                    .clip(RoundedCornerShape(18.dp)).background(Palette.surface).border(1.dp, Palette.line, RoundedCornerShape(18.dp))
                    .verticalScroll(rememberScrollState()).padding(24.dp),
            ) {
                Text("${c.number}  ${c.name}", color = Palette.dim, fontSize = 14.sp)
                Text(p.title, color = Palette.text, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                Text(
                    fmtDay(p.start) + "  ·  " + fmtRange(p.start, p.end) + (p.subtitle?.let { "  ·  $it" } ?: ""),
                    color = Palette.text.copy(alpha = 0.85f), fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp),
                )
                if (!p.desc.isNullOrBlank()) Text(
                    p.desc, color = Palette.dim, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 6,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp),
                )
                Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton("Watch channel now", onWatch, Modifier.focusRequester(first), primary = true)
                    PillButton("Close", onClose)
                }
      }
            }
        }
    }
}
