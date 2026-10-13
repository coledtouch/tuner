package ai.ciprari.tuner.ui

import android.view.KeyEvent as AndroidKeyEvent
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.ui.PlayerView
import ai.ciprari.tuner.R
import ai.ciprari.tuner.data.Channel
import ai.ciprari.tuner.data.PlayRequest
import ai.ciprari.tuner.data.Program
import ai.ciprari.tuner.data.Recent
import ai.ciprari.tuner.data.Repo
import ai.ciprari.tuner.player.PlayerHolder
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun VideoSurface(modifier: Modifier, texture: Boolean) {
    AndroidView(
        factory = { ctx ->
            (LayoutInflater.from(ctx).inflate(
                if (texture) R.layout.player_texture else R.layout.player_surface, null, false,
            ) as PlayerView).apply { player = PlayerHolder.player }
        },
        modifier = modifier,
        onRelease = { it.player = null },
    )
}

/** Tracks ExoPlayer state as Compose state. */
private class PlaybackUi {
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(false)
    var ended by mutableIntStateOf(0)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var tracksVersion by mutableIntStateOf(0)
}

@Composable
private fun rememberPlaybackUi(): PlaybackUi {
    val ui = remember { PlaybackUi() }
    val p = PlayerHolder.player
    DisposableEffect(p) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { ui.playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) {
                ui.buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) ui.ended++
            }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { ui.tracksVersion++ }
        }
        p.addListener(l)
        ui.playing = p.isPlaying; ui.buffering = p.playbackState == Player.STATE_BUFFERING
        onDispose { p.removeListener(l) }
    }
    LaunchedEffect(p) {
        while (true) {
            ui.position = p.currentPosition
            ui.duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
            delay(500)
        }
    }
    return ui
}

@Composable
fun PlayerScreen(request: PlayRequest, onExit: (Channel?) -> Unit) {
    when (request) {
        is PlayRequest.Live -> LivePlayer(request, onExit)
        is PlayRequest.Vod -> VodPlayer(request) { onExit(null) }
    }
}

// =================================================================== live

@Composable
private fun LivePlayer(req: PlayRequest.Live, onExit: (Channel?) -> Unit) {
    val lineup = req.lineup.ifEmpty { listOf(req.channel) }
    var index by remember { mutableIntStateOf(lineup.indexOfFirst { it.key == req.channel.key }.coerceAtLeast(0)) }
    var previous by remember { mutableIntStateOf(index) }
    val channel = lineup[index]
    var bannerTick by remember { mutableIntStateOf(1) }
    var bannerVisible by remember { mutableStateOf(true) }
    var panelOpen by remember { mutableStateOf(false) }
    var digits by remember { mutableStateOf("") }
    var firstTune by remember { mutableStateOf(true) }
    val rootFocus = remember { FocusRequester() }
    val ui = rememberPlaybackUi()
    val error by PlayerHolder.error.collectAsState()
    val epgVersion by Repo.epgVersion.collectAsState()
    val now = rememberNow(15_000)

    fun showBanner() { bannerVisible = true; bannerTick++ }
    fun zap(to: Int) {
        val n = lineup.size
        val target = ((to % n) + n) % n
        if (target != index) { previous = index; index = target }
        showBanner()
    }

    // Tune. Zapping is debounced so holding up/down flips through banners without opening every stream.
    LaunchedEffect(channel.key) {
        if (!firstTune) delay(320)
        firstTune = false
        PlayerHolder.play(channel.url, live = true)
        Repo.prefs.lastChannelKey = channel.key
    }
    LaunchedEffect(bannerTick) { delay(5000); if (!panelOpen) bannerVisible = false }
    LaunchedEffect(panelOpen) { if (!panelOpen) { delay(30); runCatching { rootFocus.requestFocus() } } }
    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1400)
        val n = digits.toIntOrNull(); digits = ""
        val idx = lineup.indexOfFirst { it.number == n }
        if (idx >= 0) zap(idx)
    }

    BackHandler {
        if (panelOpen) panelOpen = false else onExit(channel)
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { e ->
                if (panelOpen || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (val code = e.key.nativeKeyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_PAGE_UP -> { zap(index + 1); true }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_PAGE_DOWN -> { zap(index - 1); true }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> {
                        if (error != null) PlayerHolder.retry()
                        else if (bannerVisible) panelOpen = true else showBanner()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_MENU -> { panelOpen = true; true }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_INFO -> { showBanner(); true }
                    AndroidKeyEvent.KEYCODE_LAST_CHANNEL -> { zap(previous); true }
                    AndroidKeyEvent.KEYCODE_GUIDE -> { onExit(channel); true }
                    in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> {
                        if (digits.length < 5) digits += (code - AndroidKeyEvent.KEYCODE_0).toString()
                        showBanner(); true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        VideoSurface(Modifier.fillMaxSize(), texture = false)
        if (ui.buffering && error == null) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.align(Alignment.Center).size(44.dp))
        }
        error?.let { ErrorCard(it, "Press OK to retry, or change channel", Modifier.align(Alignment.Center)) }

        AnimatedVisibility(bannerVisible && !panelOpen, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            androidx.compose.runtime.key(epgVersion) { LiveBanner(channel, now) }
        }
        if (digits.isNotEmpty()) {
            Text(
                digits, color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(32.dp).clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }
        AnimatedVisibility(
            panelOpen, Modifier.align(Alignment.CenterStart),
            enter = slideInHorizontally { -it } + fadeIn(), exit = slideOutHorizontally { -it } + fadeOut(),
        ) {
            ChannelPanel(lineup, index, now, onPick = { i -> zap(i); panelOpen = false })
        }
    }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }
}

private fun nowNext(c: Channel, now: Long): Pair<Program?, Program?> {
    val list = Repo.programsFor(c).orEmpty()
    val i = list.indexOfFirst { it.isOnAt(now) }
    return if (i >= 0) list[i] to list.getOrNull(i + 1) else null to list.firstOrNull { it.start > now }
}

@Composable
private fun LiveBanner(c: Channel, now: Long) {
    val (cur, next) = nowNext(c, now)
    Box(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f))))
            .padding(start = 48.dp, end = 48.dp, top = 60.dp, bottom = 36.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(110.dp)) {
                Text(c.number.toString(), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                ChannelLogo(c, Modifier.padding(top = 4.dp).size(80.dp, 44.dp))
            }
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Text(c.name, color = Palette.dim, fontSize = 15.sp, maxLines = 1)
                Text(
                    cur?.title?.ifBlank { null } ?: c.name, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (cur != null) {
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(fmtRange(cur.start, cur.end), color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
                        val frac = ((now - cur.start).toFloat() / (cur.end - cur.start)).coerceIn(0f, 1f)
                        Box(Modifier.padding(start = 14.dp).width(260.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.2f))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(Palette.accent))
                        }
                        Text(
                            "${((cur.end - now) / 60_000).coerceAtLeast(0)} min left", color = Palette.dim, fontSize = 13.sp,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                    if (!cur.desc.isNullOrBlank()) Text(
                        cur.desc, color = Palette.dim, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        lineHeight = 19.sp, modifier = Modifier.padding(top = 6.dp).fillMaxWidth(0.8f),
                    )
                }
                if (next != null) Text(
                    "Next  ${fmtTime(next.start)}  ${next.title}", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ChannelPanel(lineup: List<Channel>, current: Int, now: Long, onPick: (Int) -> Unit) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (current - 3).coerceAtLeast(0))
    val currentFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { currentFocus.requestFocus() } }
    Box(
        Modifier.width(420.dp).fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.95f), Color.Black.copy(alpha = 0.8f)))),
    ) {
        LazyColumn(
            state = list, contentPadding = PaddingValues(vertical = 28.dp, horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(lineup, key = { _, c -> c.key }) { i, c ->
                val cur = Repo.programsFor(c)?.firstOrNull { it.isOnAt(now) }
                FocusBox(
                    onClick = { onPick(i) },
                    modifier = Modifier.fillMaxWidth().height(58.dp).then(if (i == current) Modifier.focusRequester(currentFocus) else Modifier),
                    shape = RoundedCornerShape(8.dp),
                    color = if (i == current) Palette.surface2 else Color.Transparent,
                    focusedColor = Palette.focus, ring = false, focusedScale = 1.0f,
                ) { f ->
                    val fg = if (f) Palette.onFocus else Palette.text
                    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(c.number.toString(), color = fg.copy(alpha = 0.7f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(44.dp))
                        ChannelLogo(c, Modifier.size(44.dp, 28.dp))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(c.name, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (cur != null) Text(cur.title, color = fg.copy(alpha = 0.65f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

// =================================================================== VOD

@Composable
private fun VodPlayer(req: PlayRequest.Vod, onExit: () -> Unit) {
    var current by remember { mutableStateOf(req) }
    var queue by remember { mutableStateOf(req.queue) }
    var controlsTick by remember { mutableIntStateOf(1) }
    var controlsVisible by remember { mutableStateOf(true) }
    var trackDialog by remember { mutableStateOf<Int?>(null) }
    var optionsFocused by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    val optionsFocus = remember { FocusRequester() }
    val ui = rememberPlaybackUi()
    val error by PlayerHolder.error.collectAsState()
    val player = PlayerHolder.player

    fun showControls() { controlsVisible = true; controlsTick++ }
    fun save() {
        val pos = player.currentPosition
        val dur = player.duration.takeIf { it != C.TIME_UNSET } ?: 0
        if (pos > 0) Repo.prefs.saveProgress(
            Recent(current.resumeId, current.title, current.subtitle, current.image, current.url, pos, dur, System.currentTimeMillis()),
        )
    }
    fun seekBy(ms: Long) {
        val dur = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + ms).coerceIn(0, dur))
        ui.position = player.currentPosition
        showControls()
    }
    fun playNext() {
        val next = queue.firstOrNull() ?: return onExit()
        save()
        queue = queue.drop(1)
        current = next
    }

    LaunchedEffect(current.url) { PlayerHolder.play(current.url, live = false, startMs = current.startMs) }
    LaunchedEffect(Unit) { while (true) { delay(10_000); save() } }
    DisposableEffect(Unit) { onDispose { save() } }
    LaunchedEffect(ui.ended) { if (ui.ended > 0) playNext() }
    LaunchedEffect(controlsTick, ui.playing, optionsFocused) {
        delay(4500)
        if (ui.playing && !optionsFocused && trackDialog == null) controlsVisible = false
    }
    LaunchedEffect(trackDialog) { if (trackDialog == null) { delay(30); runCatching { rootFocus.requestFocus() } } }

    BackHandler {
        when {
            trackDialog != null -> trackDialog = null
            optionsFocused -> runCatching { rootFocus.requestFocus() }
            else -> onExit()
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || trackDialog != null || optionsFocused) return@onPreviewKeyEvent false
                val repeat = e.nativeKeyEvent.repeatCount
                val step = 10_000L * (1 + repeat / 4).coerceAtMost(12)
                when (e.key.nativeKeyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_SPACE -> {
                        if (error != null) PlayerHolder.retry()
                        else if (player.isPlaying) player.pause() else player.play()
                        showControls(); true
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> { player.play(); showControls(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); showControls(); true }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { seekBy(-step); true }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { seekBy(step); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-30_000); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(30_000); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> { playNext(); true }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_MENU -> {
                        showControls(); runCatching { optionsFocus.requestFocus() }; true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_INFO -> { showControls(); true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        VideoSurface(Modifier.fillMaxSize(), texture = false)
        if (ui.buffering && error == null) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.align(Alignment.Center).size(44.dp))
        }
        error?.let { ErrorCard(it, "Press OK to retry", Modifier.align(Alignment.Center)) }

        AnimatedVisibility(controlsVisible || !ui.playing, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxWidth().height(140.dp)
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent))),
                ) {
                    Column(Modifier.padding(start = 48.dp, top = 32.dp)) {
                        Text(current.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        current.subtitle?.let { Text(it, color = Palette.dim, fontSize = 15.sp, maxLines = 1) }
                    }
                }
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
                        .padding(start = 48.dp, end = 48.dp, top = 60.dp, bottom = 30.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (ui.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null,
                            tint = Color.White, modifier = Modifier.size(30.dp),
                        )
                        Text(fmtClock(ui.position), color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp, end = 14.dp))
                        val frac = if (ui.duration > 0) ui.position.toFloat() / ui.duration else 0f
                        Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.22f))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(frac.coerceIn(0f, 1f)).background(Palette.accent))
                        }
                        Text(
                            if (ui.duration > 0) "-" + fmtClock(ui.duration - ui.position) else "--:--",
                            color = Palette.dim, fontSize = 14.sp, modifier = Modifier.padding(start = 14.dp),
                        )
                    }
                    Row(
                        Modifier.padding(top = 16.dp).onFocusChanged { optionsFocused = it.hasFocus },
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PillButton(
                            "Audio", { trackDialog = C.TRACK_TYPE_AUDIO }, Modifier.focusRequester(optionsFocus),
                            icon = { Icon(Icons.Rounded.GraphicEq, null, tint = it, modifier = Modifier.size(18.dp)) },
                        )
                        PillButton(
                            "Subtitles", { trackDialog = C.TRACK_TYPE_TEXT },
                            icon = { Icon(Icons.Rounded.ClosedCaption, null, tint = it, modifier = Modifier.size(18.dp)) },
                        )
                        if (queue.isNotEmpty()) PillButton(
                            "Next: ${queue.first().subtitle ?: queue.first().title}", { playNext() },
                            icon = { Icon(Icons.Rounded.SkipNext, null, tint = it, modifier = Modifier.size(18.dp)) },
                        )
                    }
                }
            }
        }
        trackDialog?.let { type ->
            TrackDialog(type, ui.tracksVersion, onClose = { trackDialog = null })
        }
    }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }
}

@Composable
private fun TrackDialog(type: Int, version: Int, onClose: () -> Unit) {
    val player = PlayerHolder.player
    data class Option(val label: String, val selected: Boolean, val apply: () -> Unit)
    val options = remember(type, version) {
        val out = mutableListOf<Option>()
        val params = player.trackSelectionParameters
        if (type == C.TRACK_TYPE_TEXT) {
            out += Option("Off", C.TRACK_TYPE_TEXT in params.disabledTrackTypes) {
                player.trackSelectionParameters = params.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            }
        }
        var n = 0
        player.currentTracks.groups.filter { it.type == type }.forEach { g ->
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                n++
                val f = g.getTrackFormat(i)
                val lang = f.language?.takeIf { it != "und" }?.let { Locale(it).displayLanguage.ifBlank { it } }
                val extra = if (type == C.TRACK_TYPE_AUDIO && f.channelCount > 0)
                    when (f.channelCount) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "${f.channelCount}ch" } else null
                val label = listOfNotNull(f.label ?: lang ?: "Track $n", extra).joinToString(" · ")
                out += Option(label, g.isTrackSelected(i) && !(type == C.TRACK_TYPE_TEXT && C.TRACK_TYPE_TEXT in params.disabledTrackTypes)) {
                    player.trackSelectionParameters = params.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                        .build()
                }
            }
        }
        out
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(40); runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().dimBackground(0.5f), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier.width(380.dp).fillMaxHeight().background(Palette.surface).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (type == C.TRACK_TYPE_AUDIO) "Audio" else "Subtitles", color = Palette.text, fontSize = 22.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp),
            )
            if (options.isEmpty()) Text("Nothing to choose from on this stream.", color = Palette.dim, fontSize = 14.sp)
            options.forEachIndexed { i, o ->
                FocusBox(
                    onClick = { o.apply(); onClose() },
                    modifier = Modifier.fillMaxWidth().height(48.dp).then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                    shape = RoundedCornerShape(8.dp), color = Palette.surface2, focusedColor = Palette.focus,
                    ring = false, focusedScale = 1.0f,
                ) { f ->
                    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(o.label, color = if (f) Palette.onFocus else Palette.text, fontSize = 15.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        if (o.selected) Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(Palette.accent))
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, hint: String, modifier: Modifier) {
    Column(
        modifier.width(460.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.8f)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.Refresh, null, tint = Palette.dim, modifier = Modifier.size(30.dp))
        Text(message, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
        Text(hint, color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    }
}
