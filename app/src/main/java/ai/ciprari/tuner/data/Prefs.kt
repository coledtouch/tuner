package ai.ciprari.tuner.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Small persistent store: sources, favorites, resume points and a few settings. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("tuner", Context.MODE_PRIVATE)

    private val _sources = MutableStateFlow(readSources())
    val sources: StateFlow<List<Source>> = _sources

    private val _activeId = MutableStateFlow(sp.getString("active", null))
    val activeId: StateFlow<String?> = _activeId

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites

    private val _recents = MutableStateFlow(readRecents())
    val recents: StateFlow<List<Recent>> = _recents

    var liveFormat: String
        get() = sp.getString("liveFormat", "ts") ?: "ts"
        set(v) = sp.edit().putString("liveFormat", v).apply()

    var guidePreview: Boolean
        get() = sp.getBoolean("guidePreview", true)
        set(v) = sp.edit().putBoolean("guidePreview", v).apply()

    /** epgshare01 region codes used to fill guide gaps. Empty list turns the fallback off. */
    var freeEpgRegions: List<String>
        get() = sp.getString("freeEpg", null)?.split(',')?.filter { it.isNotBlank() } ?: FreeEpg.defaults
        set(v) = sp.edit().putString("freeEpg", v.joinToString(",")).apply()

    var themeId: String?
        get() = sp.getString("theme", null)
        set(v) = sp.edit().putString("theme", v).apply()

    var lastChannelKey: String?
        get() = sp.getString("lastChannel_${_activeId.value}", null)
        set(v) = sp.edit().putString("lastChannel_${_activeId.value}", v).apply()

    init { reloadFavorites() }

    // ---- sources ----
    fun saveSource(s: Source) {
        val list = _sources.value.filterNot { it.id == s.id } + s
        writeSources(list)
    }

    fun deleteSource(id: String) {
        writeSources(_sources.value.filterNot { it.id == id })
        if (_activeId.value == id) setActive(_sources.value.firstOrNull()?.id)
    }

    fun setActive(id: String?) {
        sp.edit().putString("active", id).apply()
        _activeId.value = id
        reloadFavorites()
        _recents.value = readRecents()
    }

    private fun writeSources(list: List<Source>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id); put("name", s.name); put("type", s.type.name)
                put("server", s.server); put("username", s.username); put("password", s.password)
                put("m3uUrl", s.m3uUrl); put("epgUrl", s.epgUrl); put("userAgent", s.userAgent)
                put("hotUrl", s.hotUrl)
            })
        }
        sp.edit().putString("sources", arr.toString()).apply()
        _sources.value = list
    }

    private fun readSources(): List<Source> = runCatching {
        val arr = JSONArray(sp.getString("sources", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Source(
                id = o.getString("id"), name = o.optString("name"),
                type = SourceType.valueOf(o.optString("type", "XTREAM")),
                server = o.optString("server"), username = o.optString("username"),
                password = o.optString("password"), m3uUrl = o.optString("m3uUrl"),
                epgUrl = o.optString("epgUrl"), userAgent = o.optString("userAgent"),
                hotUrl = o.optString("hotUrl"),
            )
        }
    }.getOrDefault(emptyList())

    // ---- favorites (per source) ----
    private fun favKey() = "fav_${_activeId.value}"
    private fun reloadFavorites() {
        _favorites.value = sp.getStringSet(favKey(), emptySet())?.toSet() ?: emptySet()
    }

    fun toggleFavorite(channelKey: String): Boolean {
        val set = _favorites.value.toMutableSet()
        val added = if (channelKey in set) { set.remove(channelKey); false } else { set.add(channelKey); true }
        sp.edit().putStringSet(favKey(), set).apply()
        _favorites.value = set
        return added
    }

    // ---- resume / continue watching (per source) ----
    private fun recentsKey() = "recents_${_activeId.value}"

    fun resumePosition(id: String): Long = _recents.value.firstOrNull { it.id == id }?.positionMs ?: 0L

    fun saveProgress(r: Recent) {
        val finished = r.durationMs > 0 && r.positionMs > r.durationMs * 0.95
        val list = _recents.value.filterNot { it.id == r.id }.toMutableList()
        if (!finished && r.positionMs > 15_000) list.add(0, r)
        val trimmed = list.take(40)
        val arr = JSONArray()
        trimmed.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("title", it.title); put("subtitle", it.subtitle ?: "")
                put("image", it.image ?: ""); put("url", it.url); put("pos", it.positionMs)
                put("dur", it.durationMs); put("updated", it.updated)
            })
        }
        sp.edit().putString(recentsKey(), arr.toString()).apply()
        _recents.value = trimmed
    }

    private fun readRecents(): List<Recent> = runCatching {
        val arr = JSONArray(sp.getString(recentsKey(), "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Recent(
                id = o.getString("id"), title = o.optString("title"),
                subtitle = o.optString("subtitle").ifBlank { null },
                image = o.optString("image").ifBlank { null }, url = o.getString("url"),
                positionMs = o.optLong("pos"), durationMs = o.optLong("dur"), updated = o.optLong("updated"),
            )
        }
    }.getOrDefault(emptyList())
}
