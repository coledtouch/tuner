package ai.ciprari.tuner.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

sealed interface LoadStatus {
    data object Idle : LoadStatus
    data class Loading(val message: String) : LoadStatus
    data object Ready : LoadStatus
    data class Error(val message: String) : LoadStatus
}

/** Single source of truth for the active playlist: channels, VOD, series and guide data. */
object Repo {
    lateinit var prefs: Prefs
        private set
    private lateinit var cacheRoot: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    @Volatile private var generation = 0L

    val liveCategories = MutableStateFlow<List<Category>>(emptyList())
    val channels = MutableStateFlow<List<Channel>>(emptyList())
    val movieCategories = MutableStateFlow<List<Category>>(emptyList())
    val movies = MutableStateFlow<List<Movie>>(emptyList())
    val seriesCategories = MutableStateFlow<List<Category>>(emptyList())
    val series = MutableStateFlow<List<Series>>(emptyList())

    /** Channels from the source's optional "trending" playlist, in the provider's order. */
    val trending = MutableStateFlow<List<Channel>>(emptyList())

    val liveStatus = MutableStateFlow<LoadStatus>(LoadStatus.Idle)
    val vodStatus = MutableStateFlow<LoadStatus>(LoadStatus.Idle)
    val seriesStatus = MutableStateFlow<LoadStatus>(LoadStatus.Idle)
    val epgStatus = MutableStateFlow<LoadStatus>(LoadStatus.Idle)

    /** Guide data keyed by [Channel.key]. Bump [epgVersion] whenever it changes so the UI redraws. */
    private val epg = ConcurrentHashMap<String, List<Program>>()
    private val _epgVersion = MutableStateFlow(0)
    val epgVersion: StateFlow<Int> = _epgVersion
    private val shortEpgTried: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    private val shortEpgGate = Semaphore(4)

    private val movieInfoCache = ConcurrentHashMap<String, MovieInfo>()
    private val seriesInfoCache = ConcurrentHashMap<String, SeriesInfo>()

    val activeSource: Source?
        get() = prefs.sources.value.firstOrNull { it.id == prefs.activeId.value }
    val userAgent: String get() = activeSource?.userAgent?.ifBlank { null } ?: DEFAULT_UA

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = Prefs(context)
        StreamingCatalog.init(context)
        cacheRoot = File(context.cacheDir, "sources")
        if (prefs.activeId.value == null) prefs.sources.value.firstOrNull()?.let { prefs.setActive(it.id) }
    }

    fun programsFor(c: Channel): List<Program>? = epg[c.key]

    /** Test hook: inject guide data without a network round trip. */
    fun putPrograms(data: Map<String, List<Program>>) { epg.putAll(data); _epgVersion.value++ }

    // ------------------------------------------------------------------ loading

    fun load(force: Boolean = false) {
        loadLogoIndex()
        val previous = job
        job = scope.launch {
            previous?.cancelAndJoin()
            clear()
            val src = activeSource ?: return@launch
            val dir = File(cacheRoot, src.id)
            if (force) dir.listFiles()?.forEach { it.delete() }
            when (src.type) {
                SourceType.XTREAM -> loadXtream(src, dir)
                SourceType.M3U -> loadM3u(src, dir)
            }
        }
    }

    private fun clear() {
        generation++
        liveCategories.value = emptyList(); channels.value = emptyList(); trending.value = emptyList(); merged = emptyMap()
        movieCategories.value = emptyList(); movies.value = emptyList()
        seriesCategories.value = emptyList(); series.value = emptyList()
        epg.clear(); epgIcons.clear(); shortEpgTried.clear(); movieInfoCache.clear(); seriesInfoCache.clear()
        _epgVersion.value++
        liveStatus.value = LoadStatus.Idle; vodStatus.value = LoadStatus.Idle
        seriesStatus.value = LoadStatus.Idle; epgStatus.value = LoadStatus.Idle
    }

    private const val LIST_AGE = 12 * 3600_000L
    // Event channels are renamed through the day as games get scheduled, so the live list is refreshed more often.
    private const val LIVE_AGE = 2 * 3600_000L
    private const val EPG_AGE = 6 * 3600_000L
    private const val FREE_EPG_AGE = 12 * 3600_000L
    private const val LOGOS_URL = "https://iptv-org.github.io/api/logos.json"

    private suspend fun loadXtream(src: Source, dir: File) = withContext(Dispatchers.IO) { supervisorScope {
        val x = Xtream(src)
        val ua = src.userAgent
        val live = launch {
            liveStatus.value = LoadStatus.Loading("Loading channels…")
            try {
                supervisorScope {
                    val catsF = async { Net.download(x.api("get_live_categories"), ua, File(dir, "live_cats.json"), LIVE_AGE, false) }
                    val listF = async { Net.download(x.api("get_live_streams"), ua, File(dir, "live.json"), LIVE_AGE, false) }
                    publishLive(ContentPolicy.live(x.parseLive(listF.await(), prefs.liveFormat), x.parseCategories(catsF.await())))
                }
                liveStatus.value = LoadStatus.Ready
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { liveStatus.value = LoadStatus.Error(friendly(e)) }
        }

        launch { live.join(); loadTrending(src, dir) }
        launch { live.join(); loadGuides(src.epgUrl.ifBlank { x.xmltv() }, ua, dir) }

        launch {
            vodStatus.value = LoadStatus.Loading("Loading movies…")
            try {
                val catalogue = supervisorScope {
                    val c = async { Net.download(x.api("get_vod_categories"), ua, File(dir, "vod_cats.json"), LIST_AGE, false) }
                    val l = async { Net.download(x.api("get_vod_streams"), ua, File(dir, "vod.json"), LIST_AGE, false) }
                    ContentPolicy.movies(x.parseMovies(l.await()), x.parseCategories(c.await()))
                }
                movieCategories.value = catalogue.categories
                movies.value = Cleanup.movies(catalogue.items)
                vodStatus.value = LoadStatus.Ready
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { vodStatus.value = LoadStatus.Error(friendly(e)) }
        }

        launch {
            seriesStatus.value = LoadStatus.Loading("Loading series…")
            try {
                val catalogue = supervisorScope {
                    val c = async { Net.download(x.api("get_series_categories"), ua, File(dir, "series_cats.json"), LIST_AGE, false) }
                    val l = async { Net.download(x.api("get_series"), ua, File(dir, "series.json"), LIST_AGE, false) }
                    ContentPolicy.series(x.parseSeries(l.await()), x.parseCategories(c.await()))
                }
                seriesCategories.value = catalogue.categories
                series.value = catalogue.items
                seriesStatus.value = LoadStatus.Ready
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { seriesStatus.value = LoadStatus.Error(friendly(e)) }
        }
    } }

    private suspend fun loadM3u(src: Source, dir: File) = withContext(Dispatchers.IO) {
        liveStatus.value = LoadStatus.Loading("Loading playlist…")
        vodStatus.value = LoadStatus.Loading("Loading playlist…")
        seriesStatus.value = LoadStatus.Ready
        try {
            val f = Net.download(src.m3uUrl, src.userAgent, File(dir, "playlist.m3u"), LIST_AGE, false)
            val r = M3u.parse(f)
            val vod = ContentPolicy.movies(r.movies, r.movieCategories)
            publishLive(ContentPolicy.live(r.channels, r.liveCategories))
            movieCategories.value = vod.categories
            movies.value = Cleanup.movies(vod.items)
            liveStatus.value = LoadStatus.Ready
            vodStatus.value = LoadStatus.Ready
            launch { loadTrending(src, dir) }
            val epgUrl = src.epgUrl.ifBlank { r.epgUrl ?: "" }
            launch { loadGuides(epgUrl, src.userAgent, dir) }
        } catch (e: Exception) {
            liveStatus.value = LoadStatus.Error(friendly(e))
            vodStatus.value = LoadStatus.Error(friendly(e))
        }
    }

    /** Copies folded into another channel by [Cleanup.lineup]: folded key to the key of the channel that kept it. */
    @Volatile private var merged: Map<String, String> = emptyMap()
    private val deadNow: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    /** Cleans the lineup (names, duplicates, empty event slots, channels that recently failed) and publishes it. */
    private fun publishLive(catalogue: ContentPolicy.Catalogue<Channel>) {
        val hidden = prefs.deadChannels()
        deadNow.clear(); deadNow.addAll(hidden)
        val lineup = Cleanup.lineup(catalogue.items, catalogue.categories, hidden = hidden)
        merged = lineup.merged
        prefs.remapChannelKeys(lineup.merged)
        val used = lineup.channels.flatMapTo(HashSet()) { it.categoryIds }
        liveCategories.value = catalogue.categories.filter { it.id in used }
        channels.value = lineup.channels
    }

    /**
     * A channel failed on its main source and every backup. It leaves the guide now and stays hidden for
     * [Prefs.DEAD_FOR_MS], together with the copies that were folded into it, then gets another chance.
     */
    fun reportDead(key: String) {
        deadNow += key
        prefs.markDead(merged.filterValues { it == key }.keys + key)
        channels.value = channels.value.filterNot { it.key == key }
        trending.value = trending.value.filterNot { it.key == key }
    }

    fun isDead(key: String): Boolean = key in deadNow

    /** Brings back every channel hidden for failing (Settings). */
    fun restoreHiddenChannels() {
        prefs.clearDead()
        deadNow.clear()
        load()
    }

    /**
     * Loads the optional trending M3U and maps each entry onto a channel we already have (same
     * stream id), so favorites, numbers and guide data carry over. Event feeds that are only in
     * the trending list get appended as extra channels.
     */
    private suspend fun loadTrending(src: Source, dir: File) {
        if (src.hotUrl.isBlank()) return
        runCatching {
            val f = Net.download(src.hotUrl, src.userAgent, File(dir, "trending.m3u"), 20 * 60_000L, false)
            val playlist = M3u.parse(f)
            val now = System.currentTimeMillis()
            val dead = prefs.deadChannels(now)
            val parsed = ContentPolicy.live(playlist.channels, playlist.liveCategories).items
                .filterNot { Cleanup.isIdleEvent(it.name) || Cleanup.isOffAir(it.name, now) }
                .sortedBy { it.key.substringAfterLast(':').toIntOrNull() ?: 0 }
            fun sid(url: String) = url.substringBefore('?').substringAfterLast('/').substringBefore('.')
            // A trending entry may point at a copy that was folded into another channel: map it to that channel.
            val known = HashMap<String, Channel>()
            channels.value.forEach { c -> c.backups.forEach { known.getOrPut(sid(it)) { c } } }
            channels.value.forEach { c -> known[c.streamId ?: sid(c.url)] = c }
            var extra = (channels.value.maxOfOrNull { it.number } ?: 0) + 1
            trending.value = parsed.map { t ->
                known[sid(t.url)] ?: t.copy(key = "h:" + sid(t.url), name = Cleanup.displayName(t.name), number = extra++,
                    categoryId = "__hot", categoryIds = listOf("__hot"))
            }.distinctBy { it.key }.filterNot { it.key in dead }
        }
    }

    /** Provider guide first, then free regional guides for whatever is still missing. */
    private suspend fun loadGuides(providerUrl: String?, ua: String, dir: File) {
        epgStatus.value = LoadStatus.Loading("Loading guide…")
        var providerError: String? = null
        if (!providerUrl.isNullOrBlank()) {
            try {
                applyGuide(Net.download(providerUrl, ua, File(dir, "epg.xml"), EPG_AGE, false), onlyMissing = false)
            } catch (e: Exception) {
                providerError = friendly(e)
            }
        }
        loadFreeGuides()
        val have = channels.value.count { epg.containsKey(it.key) }
        epgStatus.value = if (have > 0) LoadStatus.Ready
        else LoadStatus.Error(providerError ?: "No guide data found for these channels")
    }

    private suspend fun loadFreeGuides() {
        for (code in prefs.freeEpgRegions) {
            if (channels.value.all { epg.containsKey(it.key) }) break
            epgStatus.value = LoadStatus.Loading("Finding free guide data…")
            runCatching {
                val f = Net.download(FreeEpg.url(code), DEFAULT_UA, File(cacheRoot, "free/$code.xml.gz"), FREE_EPG_AGE, false)
                applyGuide(f, onlyMissing = true)
            }
        }
    }

    /** Matches an XMLTV file against our channels by id, then by fuzzy name. Returns channels matched. */
    private fun applyGuide(f: File, onlyMissing: Boolean): Int {
        val chans = channels.value.filter { !onlyMissing || !epg.containsKey(it.key) }
        if (chans.isEmpty()) return 0
        val wanted = chans.mapNotNullTo(HashSet()) { it.epgId?.lowercase() }
        val keys = HashSet<String>()
        chans.forEach { c ->
            keys += ChannelMatch.key(c.name)
            c.epgId?.let { keys += ChannelMatch.key(it); keys += ChannelMatch.idKey(it) }
        }
        keys.removeAll { it.length < 2 }
        val now = System.currentTimeMillis()
        val res = Xmltv.parse(f, wanted, keys, now - 4 * 3600_000L, now + 3 * 24 * 3600_000L)
        var matched = 0
        var newIcons = false
        for (c in chans) {
            val id = c.epgId?.lowercase()
            val xid = id?.takeIf { res.programs.containsKey(it) || res.icons.containsKey(it) }
                ?: id?.let { res.nameToId[ChannelMatch.key(it)] ?: res.nameToId[ChannelMatch.idKey(it)] }
                ?: ChannelMatch.key(c.name).takeIf { it.length >= 2 }?.let { res.nameToId[it] }
                ?: continue
            res.programs[xid]?.takeIf { it.isNotEmpty() }?.let { if (!onlyMissing || !epg.containsKey(c.key)) { epg[c.key] = it; matched++ } }
            res.icons[xid]?.let { if (epgIcons.putIfAbsent(c.key, it) == null) newIcons = true }
        }
        _epgVersion.value++
        if (newIcons) _logoVersion.value++
        return matched
    }

    // ------------------------------------------------------------------ logos

    private val logoIndex = ConcurrentHashMap<String, String>()
    private val epgIcons = ConcurrentHashMap<String, String>()
    private val _logoVersion = MutableStateFlow(0)
    val logoVersion: StateFlow<Int> = _logoVersion
    private var logoJob: Job? = null

    /** Extra logo URLs to try, best first, when the provider's logo is missing or broken. */
    fun fallbackLogos(c: Channel): List<String> = listOfNotNull(
        epgIcons[c.key],
        logoIndex[ChannelMatch.key(c.name)],
        c.epgId?.let { logoIndex[ChannelMatch.idKey(it)] },
    ).distinct()

    /** iptv-org's open logo database (~35k channels), refreshed weekly. */
    private fun loadLogoIndex() {
        if (logoJob != null) return
        logoJob = scope.launch(Dispatchers.IO) {
            runCatching {
                val f = Net.download(LOGOS_URL, DEFAULT_UA, File(cacheRoot, "logos/logos.json"), 7 * 24 * 3600_000L, false)
                val usKeys = HashSet<String>()
                forEachJsonObject(f) { o ->
                    val id = o.str("channel") ?: return@forEachJsonObject
                    val url = o.str("url") ?: return@forEachJsonObject
                    if (o.str("in_use") == "false") return@forEachJsonObject
                    val k = ChannelMatch.idKey(id)
                    if (k.length < 2) return@forEachJsonObject
                    val us = id.endsWith(".us", ignoreCase = true)
                    if (!logoIndex.containsKey(k) || (us && k !in usKeys)) {
                        logoIndex[k] = url
                        if (us) usKeys += k
                    }
                }
                _logoVersion.value++
            }.onFailure { logoJob = null }
        }
    }

    /** Fills gaps for visible Xtream channels that the XMLTV feed did not cover. */
    fun requestShortEpg(visible: List<Channel>) {
        val src = activeSource ?: return
        val requestedGeneration = generation
        if (src.type != SourceType.XTREAM) return
        val x = Xtream(src)
        visible.forEach { c ->
            val sid = c.streamId ?: return@forEach
            if (epg.containsKey(c.key) || !shortEpgTried.add(c.key)) return@forEach
            scope.launch(Dispatchers.IO) {
                shortEpgGate.withPermit {
                    runCatching {
                        val list = x.parseShortEpg(Net.getString(x.api("get_short_epg", "&stream_id=$sid&limit=24"), src.userAgent))
                        if (requestedGeneration == generation && activeSource?.id == src.id && list.isNotEmpty() && !epg.containsKey(c.key)) {
                            epg[c.key] = list; _epgVersion.value++
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ details

    suspend fun movieInfo(m: Movie): MovieInfo? {
        movieInfoCache[m.id]?.let { return it }
        val src = activeSource ?: return null
        val requestedGeneration = generation
        if (src.type != SourceType.XTREAM || !m.id.startsWith("m:")) return null
        val x = Xtream(src)
        return runCatching {
            x.parseMovieInfo(Net.getString(x.api("get_vod_info", "&vod_id=${m.id.removePrefix("m:")}"), src.userAgent))
        }.getOrNull()?.takeIf { requestedGeneration == generation && activeSource?.id == src.id }?.also { movieInfoCache[m.id] = it }
    }

    suspend fun seriesInfo(s: Series): SeriesInfo? {
        seriesInfoCache[s.id]?.let { return it }
        val src = activeSource ?: return null
        val requestedGeneration = generation
        val x = Xtream(src)
        return runCatching {
            x.parseSeriesInfo(Net.getString(x.api("get_series_info", "&series_id=${s.id}"), src.userAgent))
        }.getOrNull()?.takeIf { requestedGeneration == generation && activeSource?.id == src.id }?.also { seriesInfoCache[s.id] = it }
    }

    /** Checks Xtream credentials and returns a one-line account summary. */
    suspend fun testXtream(src: Source): Result<String> = runCatching {
        val text = Net.getString(Xtream(src).api(), src.userAgent)
        @Suppress("UNCHECKED_CAST")
        val root = parseJson(text) as? JMap ?: error("The server did not return account info")
        val ui = root.obj("user_info") ?: error("The server did not return account info")
        if (ui.str("auth") != "1") error("Login failed. Check the username and password.")
        val parts = mutableListOf(ui.str("status") ?: "Active")
        ui.str("exp_date")?.toLongOrNull()?.let {
            parts += "expires " + SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(it * 1000))
        }
        ui.str("max_connections")?.let { parts += "$it connection" + if (it == "1") "" else "s" }
        parts.joinToString(" · ")
    }.recoverCatching { throw Exception(friendly(it)) }

    fun clearCache() {
        cacheRoot.deleteRecursively()
        logoJob = null
    }

    /** Re-run only the free-guide pass (after changing regions in Settings). */
    fun reloadFreeGuides() {
        scope.launch(Dispatchers.IO) {
            loadFreeGuides()
            val have = channels.value.count { epg.containsKey(it.key) }
            epgStatus.value = if (have > 0) LoadStatus.Ready else LoadStatus.Error("No guide data found for these channels")
        }
    }

    fun friendly(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "Can't reach the server. Check the URL."
        is java.net.SocketTimeoutException -> "The server took too long to respond."
        is java.net.ConnectException -> "Connection refused by the server."
        else -> e.message ?: e.javaClass.simpleName
    }
}
