package ai.ciprari.tuner.ui

import android.view.KeyEvent as AndroidKeyEvent
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.ui.PlayerView
import ai.ciprari.tuner.R
import ai.ciprari.tuner.data.Category
import ai.ciprari.tuner.data.Channel
import ai.ciprari.tuner.data.LoadStatus
import ai.ciprari.tuner.data.Repo
import ai.ciprari.tuner.player.TwoChannelPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Choices and layout survive tab navigation. Stream ownership belongs to TwoChannelPlayer. */
class MultiViewUiState {
    var firstKey by mutableStateOf<String?>(null)
    var secondKey by mutableStateOf<String?>(null)
    var pictureInPicture by mutableStateOf(false)
    var focusedSlot by mutableIntStateOf(0)
    var focusedControl by mutableStateOf("pick-0")
    fun choose(slot: Int, channel: Channel) { if (slot == 0) firstKey = channel.key else secondKey = channel.key }
    fun reset() { firstKey = null; secondKey = null; focusedSlot = 0; focusedControl = "pick-0" }
}

private val sportsChannelName = Regex("\\b(?:ESPN|NESN|SPORTS|SPORTSNET|NFL|NBA|NHL|MLB|NCAA|GOLF|TENNIS|FANDUEL|BALLY|FOOTBALL|SOCCER|DAZN|UFC|PPV)\\b", RegexOption.IGNORE_CASE)

@Composable
fun MultiViewScreen(state: AppState) {
    val ui = state.multiView
    val context = LocalContext.current
    val channels by Repo.channels.collectAsState()
    val trending by Repo.trending.collectAsState()
    val slots by TwoChannelPlayer.states.collectAsState()
    val active by TwoChannelPlayer.active.collectAsState()
    val audioSlot by TwoChannelPlayer.audioSlot.collectAsState()
    val lineup = remember(channels, trending) { (channels + trending).distinctBy { it.key } }
    val selected = remember(lineup, ui.firstKey, ui.secondKey, slots) {
        listOf(ui.firstKey, ui.secondKey).mapIndexed { i, key -> lineup.firstOrNull { it.key == key } ?: slots.getOrNull(i)?.channel }
    }
    var pickerSlot by remember { mutableIntStateOf(-1) }
    val controls = remember { listOf("pick-0", "pick-1", "watch", "layout", "stop", "pane-0", "pane-1", "retry-0", "retry-1").associateWith { FocusRequester() } }
    val scope = rememberCoroutineScope()
    fun visibleControl(requested: String): String = when (requested) {
        "stop" -> if (active) requested else "pick-${ui.focusedSlot}"
        "retry-0" -> if (slots[0].status == TwoChannelPlayer.Status.ERROR) requested else "pick-0"
        "retry-1" -> if (slots[1].status == TwoChannelPlayer.Status.ERROR) requested else "pick-1"
        else -> requested.takeIf { it in controls } ?: "pick-${ui.focusedSlot}"
    }
    fun returnFocus() {
        ui.focusedControl = visibleControl(ui.focusedControl)
        scope.launch { requestAttachedFocus(controls.getValue(ui.focusedControl)) }
    }
    fun choose(slot: Int) { ui.focusedSlot = slot; ui.focusedControl = "pick-$slot"; pickerSlot = slot }
    fun listen(slot: Int) {
        ui.focusedSlot = slot
        if (active && slots[slot].status == TwoChannelPlayer.Status.ERROR) choose(slot)
        else if (active) { if (!TwoChannelPlayer.selectAudio(slot)) Toasts.show("This channel's audio is unavailable. Try again.") }
        else choose(slot)
    }
    fun tune(slot: Int, channel: Channel): Boolean {
        if (!TwoChannelPlayer.play(slot, channel)) return false
        ui.choose(slot, channel)
        Repo.prefs.lastChannelKey = channel.key
        return true
    }
    fun leave() { TwoChannelPlayer.stop(); state.tab = Tab.GUIDE; state.requestNavigation() }

    DisposableEffect(Unit) {
        TwoChannelPlayer.init(context)
        onDispose { TwoChannelPlayer.stop() }
    }
    LaunchedEffect(state.restoreFocus, active, slots.map { it.status }) {
        val target = visibleControl(ui.focusedControl)
        if (state.restoreFocus || (target != ui.focusedControl && !state.navigationFocused && pickerSlot < 0)) {
            ui.focusedControl = target
            if (requestAttachedFocus(controls.getValue(target))) state.restoreFocus = false
        }
    }
    BackHandler(enabled = pickerSlot < 0 && !state.navigationFocused) { leave() }

    BoxWithConstraints(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        val code = event.nativeKeyEvent.keyCode
        if (pickerSlot < 0 && code in listOf(AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_PAGE_UP, AndroidKeyEvent.KEYCODE_PAGE_DOWN)) {
            if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                val slot = ui.focusedSlot
                if (!active) choose(slot)
                else {
                    val current = slots.getOrNull(slot)?.channel
                    val other = slots.getOrNull(1 - slot)?.channel
                    val choices = lineup.filter { it.key != other?.key && it.url != other?.url }
                    if (choices.isNotEmpty()) {
                        val index = choices.indexOfFirst { it.key == current?.key }.coerceAtLeast(0)
                        val next = choices[Math.floorMod(index + if (code == AndroidKeyEvent.KEYCODE_CHANNEL_UP || code == AndroidKeyEvent.KEYCODE_PAGE_UP) 1 else -1, choices.size)]
                        tune(slot, next)
                    }
                }
            }
            true
        } else false
    }) {
        val compact = maxWidth < 720.dp
        Column(Modifier.fillMaxSize().padding(horizontal = if (compact) 20.dp else 28.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Two games. One screen.", color = Palette.text, fontSize = if (compact) 20.sp else 23.sp, fontWeight = FontWeight.Bold)
                    Text("Choose two channels. Hear one at a time.", color = Palette.dim, fontSize = 12.sp)
                }
                PillButton(if (ui.pictureInPicture) "Picture in Picture" else "Side by side", {
                    ui.pictureInPicture = !ui.pictureInPicture
                }, Modifier.focusRequester(controls.getValue("layout")), onFocus = { if (it) ui.focusedControl = "layout" })
                if (active) PillButton("Stop", { TwoChannelPlayer.stop(); ui.focusedControl = "watch"; returnFocus() },
                    Modifier.focusRequester(controls.getValue("stop")), onFocus = { if (it) ui.focusedControl = "stop" })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(2) { slot ->
                    FocusBox(
                        onClick = { choose(slot) }, modifier = Modifier.weight(1f).height(44.dp).focusRequester(controls.getValue("pick-$slot")).testTag("dual-pick-$slot"),
                        color = Palette.surface2, focusedColor = Palette.focus, focusedScale = 1.02f,
                        onFocus = { if (it) { ui.focusedSlot = slot; ui.focusedControl = "pick-$slot" } },
                    ) { focused ->
                        Column(Modifier.padding(horizontal = 12.dp).align(Alignment.CenterStart)) {
                            Text("CHANNEL ${slot + 1}", color = if (focused) Palette.onFocus else Palette.dim, fontSize = 9.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold)
                            Text(selected[slot]?.name ?: "Choose a channel", color = if (focused) Palette.onFocus else Palette.text, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                PillButton(if (active) "Audio: Game ${audioSlot + 1}" else "Watch both", {
                    if (active) {
                        listen(1 - audioSlot); ui.focusedControl = "pane-${ui.focusedSlot}"; returnFocus()
                        return@PillButton
                    }
                    val first = selected[0]; val second = selected[1]
                    when {
                        first == null -> choose(0)
                        second == null -> choose(1)
                        first.key == second.key || first.url == second.url -> Toasts.show("Choose two different channels.")
                        else -> {
                            if (TwoChannelPlayer.play(0, first) && TwoChannelPlayer.play(1, second)) {
                                Repo.prefs.lastChannelKey = if (audioSlot == 0) first.key else second.key
                                ui.focusedControl = "pane-${ui.focusedSlot}"; returnFocus()
                            } else Toasts.show("The selected channels could not start. Try another channel.")
                        }
                    }
                }, Modifier.focusRequester(controls.getValue("watch")).testTag("dual-watch"), primary = true, onFocus = { if (it) ui.focusedControl = "watch" })
            }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                if (!ui.pictureInPicture) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        repeat(2) { slot ->
                            MultiViewPane(slot, selected[slot], slots[slot], active, audioSlot == slot, false,
                                Modifier.weight(1f).aspectRatio(16f / 9f).focusRequester(controls.getValue("pane-$slot")),
                                onClick = { listen(slot) }, onFocus = { if (it) { ui.focusedSlot = slot; ui.focusedControl = "pane-$slot" } })
                        }
                    }
                } else {
                    val stageWidth = minOf(maxWidth, maxHeight * 16f / 9f)
                    Box(Modifier.width(stageWidth).aspectRatio(16f / 9f).align(Alignment.Center)) {
                        MultiViewPane(0, selected[0], slots[0], active, audioSlot == 0, false,
                            Modifier.fillMaxSize().focusRequester(controls.getValue("pane-0")).focusProperties {
                                right = controls.getValue("pane-1"); down = controls.getValue("pane-1")
                            },
                            onClick = { listen(0) }, onFocus = { if (it) { ui.focusedSlot = 0; ui.focusedControl = "pane-0" } })
                        MultiViewPane(1, selected[1], slots[1], active, audioSlot == 1, true,
                            Modifier.width(stageWidth * 0.34f).aspectRatio(16f / 9f).align(Alignment.BottomEnd).padding(8.dp).focusRequester(controls.getValue("pane-1")).focusProperties {
                                left = controls.getValue("pane-0"); up = controls.getValue("pane-0")
                            },
                            onClick = { listen(1) }, onFocus = { if (it) { ui.focusedSlot = 1; ui.focusedControl = "pane-1" } })
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (active) "OK  Listen to a game  ·  CH +/−  Change focused channel" else "Choose channels, then Watch both", color = Palette.dim, fontSize = 11.sp, modifier = Modifier.weight(1f))
                slots.forEachIndexed { slot, playback ->
                    if (playback.status == TwoChannelPlayer.Status.ERROR) PillButton("Retry ${slot + 1}", { TwoChannelPlayer.retry(slot) },
                        Modifier.focusRequester(controls.getValue("retry-$slot")), onFocus = { if (it) { ui.focusedSlot = slot; ui.focusedControl = "retry-$slot" } })
                }
                Text("Back  Live TV", color = Palette.dim, fontSize = 11.sp)
            }
        }
    }
    if (pickerSlot >= 0) {
        val slot = pickerSlot
        MultiViewChannelPicker(slot, selected[slot], selected[1 - slot], onChoose = { channel ->
            if (!active) ui.choose(slot, channel)
            else if (!tune(slot, channel)) Toasts.show("This channel could not start. Choose another.")
            pickerSlot = -1; returnFocus()
        }, onClose = { pickerSlot = -1; returnFocus() })
    }
}

@Composable
private fun MultiViewPane(
    slot: Int, selected: Channel?, playback: TwoChannelPlayer.SlotState, active: Boolean, audible: Boolean, small: Boolean,
    modifier: Modifier, onClick: () -> Unit, onFocus: (Boolean) -> Unit,
) {
    val channel = playback.channel ?: selected
    FocusBox(onClick = onClick, modifier = modifier.testTag("dual-pane-$slot").semantics {
        contentDescription = "Channel ${slot + 1}, ${channel?.name ?: "choose a channel"}. ${if (active && audible) "Audio selected" else if (active) "Press OK to listen" else "Press OK to choose"}"
    }, shape = RoundedCornerShape(12.dp), color = Color.Black, focusedScale = 1f, onFocus = onFocus) { _ ->
        Box(Modifier.fillMaxSize()) {
            if (playback.channel != null && active) MultiViewVideo(slot, Modifier.fillMaxSize())
            else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                if (channel != null) ChannelLogo(channel, Modifier.size(if (small) 54.dp else 96.dp, if (small) 32.dp else 56.dp))
                Text(if (channel == null) "Choose channel ${slot + 1}" else "Ready to watch", color = Color.White, fontSize = if (small) 10.sp else 15.sp)
            }
            if (playback.status == TwoChannelPlayer.Status.LOADING) CircularProgressIndicator(Modifier.size(if (small) 20.dp else 32.dp).align(Alignment.Center), color = Color.White, strokeWidth = 2.dp)
            if (playback.status == TwoChannelPlayer.Status.ERROR) Text(
                playback.error ?: "This channel is unavailable. Retry or choose another.", color = Color.White, fontSize = if (small) 10.sp else 13.sp,
                maxLines = if (small) 2 else 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.85f)).padding(if (small) 8.dp else 16.dp),
            )
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(42.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent))))
            Text("${slot + 1}  ${channel?.name ?: "Choose a channel"}", color = Color.White, fontSize = if (small) 10.sp else 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.TopStart).padding(horizontal = 10.dp, vertical = 8.dp))
            Row(Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.8f)).padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (active && audible) Icon(Icons.Rounded.VolumeUp, null, tint = Palette.accent, modifier = Modifier.size(if (small) 12.dp else 16.dp))
                Text(if (active && audible) "Audio selected" else if (active) "OK  Listen" else "OK  Choose", color = Color.White, fontSize = if (small) 9.sp else 11.sp)
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun MultiViewVideo(slot: Int, modifier: Modifier) {
    val player = TwoChannelPlayer.player(slot)
    AndroidView(factory = { context ->
        (LayoutInflater.from(context).inflate(R.layout.player_texture, null, false) as PlayerView).apply {
            useController = false; isFocusable = false; isFocusableInTouchMode = false; keepScreenOn = true
        }
    }, update = { view -> if (view.player !== player) view.player = player }, onRelease = { it.player = null }, modifier = modifier)
}

private fun sportsChannels(channels: List<Channel>, categories: List<Category>): List<Channel> {
    val sportsIds = categories.filter { sportsChannelName.containsMatchIn(it.name) }.mapTo(HashSet()) { it.id }
    return channels.filter { it.categoryIds.any(sportsIds::contains) || sportsChannelName.containsMatchIn(it.name) }
}

private data class PickerListing(val query: String, val filter: String, val favorites: Set<String>, val channels: List<Channel>)

@Composable
private fun MultiViewChannelPicker(slot: Int, selected: Channel?, other: Channel?, onChoose: (Channel) -> Unit, onClose: () -> Unit) {
    val channels by Repo.channels.collectAsState()
    val trending by Repo.trending.collectAsState()
    val categories by Repo.liveCategories.collectAsState()
    val favorites by Repo.prefs.favorites.collectAsState()
    val watchedKeys by Repo.prefs.watchedChannelKeys.collectAsState()
    val status by Repo.liveStatus.collectAsState()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(if (favorites.isNotEmpty()) "Favorites" else "Suggested") }
    val queryFocus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val all = remember(channels, trending) { (channels + trending).distinctBy { it.key } }
    val mostWatched = remember(channels, trending, watchedKeys) { mostWatchedLineup(channels, trending, watchedKeys) }
    var focusResults by remember { mutableStateOf(false) }
    val listing by produceState(PickerListing("", "", emptySet(), emptyList()), all, mostWatched, categories, favorites, filter, query, other?.key, other?.url) {
        value = withContext(Dispatchers.Default) {
            val sports = sportsChannels(all, categories)
            val list = when (filter) {
                "Favorites" -> all.filter { it.key in favorites }
                "Most Watched" -> mostWatched
                "All channels" -> all
                else -> (all.filter { it.key in favorites } + mostWatched + sports).distinctBy { it.key }.ifEmpty { all }
            }
            val words = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
            PickerListing(query, filter, favorites, list.filter { it.key != other?.key && it.url != other?.url && words.all { word -> it.name.lowercase(Locale.ROOT).contains(word) } })
        }
    }
    val matches = listing.channels
    val filters = remember { listOf("Suggested", "Favorites", "Most Watched", "All channels") }
    LaunchedEffect(Unit) { requestAttachedFocus(queryFocus) }
    LaunchedEffect(filter, query) { listState.scrollToItem(0) }
    LaunchedEffect(listing, focusResults, query, filter, favorites) {
        if (focusResults && listing.query == query && listing.filter == filter && listing.favorites == favorites) {
            if (matches.isNotEmpty()) { listState.scrollToItem(0); requestAttachedFocus(firstFocus) }
            else requestAttachedFocus(queryFocus)
            focusResults = false
        }
    }
    fun toggleFavorite(channel: Channel) {
        Repo.prefs.toggleFavorite(channel.key)
        if (filter == "Favorites") focusResults = true
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.65f)), contentAlignment = Alignment.Center) {
            Column(Modifier.width(minOf(maxWidth - 48.dp, 700.dp)).height(minOf(maxHeight - 32.dp, 470.dp)).clip(RoundedCornerShape(18.dp)).background(Palette.surface).border(1.dp, Palette.line, RoundedCornerShape(18.dp)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Choose channel ${slot + 1}", color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Browsing keeps both games playing.", color = Palette.dim, fontSize = 12.sp)
                    }
                    PillButton("Cancel", onClose)
                }
                TvTextField("", query, { query = it }, placeholder = "Search a channel or team", keyboardType = KeyboardType.Text, focusRequester = queryFocus,
                    onDone = { focusResults = true })
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
                    items(filters, key = { it }) { name ->
                        FocusBox(onClick = { filter = name; focusResults = true }, shape = RoundedCornerShape(50), color = if (filter == name) Palette.accent.copy(alpha = 0.2f) else Palette.surface2, focusedColor = Palette.focus, ring = false) { focused ->
                            Text(name, color = if (focused) Palette.onFocus else if (filter == name) Palette.text else Palette.dim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                        }
                    }
                }
                if (matches.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(when { status is LoadStatus.Loading -> "Loading channels…"; query.isNotBlank() -> "No matches. Try All channels or a shorter name."; filter == "Favorites" -> "Save channels with their star in the guide, or choose Suggested."; filter == "Most Watched" -> "Channels you watch will appear here. Choose Suggested to start."; else -> "No channels here yet. Add or refresh your playlist in Settings." }, color = Palette.dim, fontSize = 14.sp)
                } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(matches, key = { it.key }) { channel ->
                        Row(Modifier.fillMaxWidth().focusGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FocusBox(onClick = { onChoose(channel) }, onLongClick = { toggleFavorite(channel) },
                                modifier = Modifier.weight(1f).height(48.dp).then(if (channel.key == matches.first().key) Modifier.focusRequester(firstFocus) else Modifier).testTag("dual-channel:${channel.key}"),
                                color = if (channel.key == selected?.key) Palette.accent.copy(alpha = 0.16f) else Palette.surface2, focusedColor = Palette.focus, focusedScale = 1.01f,
                            ) { focused ->
                                Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(channel.number.toString(), color = if (focused) Palette.onFocus else Palette.dim, fontSize = 11.sp, modifier = Modifier.width(32.dp))
                                    ChannelLogo(channel, Modifier.size(52.dp, 32.dp))
                                    Text(channel.name, color = if (focused) Palette.onFocus else Palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                }
                            }
                            FocusBox(onClick = { toggleFavorite(channel) }, modifier = Modifier.size(48.dp).semantics { contentDescription = if (channel.key in favorites) "Remove ${channel.name} from Favorites" else "Add ${channel.name} to Favorites" }, color = Palette.surface2, focusedColor = Palette.focus, focusedScale = 1.01f) { focused ->
                                Icon(if (channel.key in favorites) Icons.Rounded.Star else Icons.Rounded.StarBorder, null, tint = if (focused) Palette.onFocus else Palette.accent, modifier = Modifier.align(Alignment.Center).size(20.dp))
                            }
                        }
                    }
                }
                Text("OK  Choose  ·  Hold OK  Favorite  ·  Back  Cancel", color = Palette.dim, fontSize = 11.sp)
            }
        }
    }
}
