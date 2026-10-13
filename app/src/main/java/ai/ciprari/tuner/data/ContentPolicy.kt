package ai.ciprari.tuner.data

import java.util.Locale

/** Live TV stays in the USA lineup. Movies, series and audio have no language restrictions. */
object ContentPolicy {
    data class Catalogue<T>(val items: List<T>, val categories: List<Category>)
    private data class Labels(val us: Boolean, val foreign: Boolean, val english: Boolean, val otherLanguage: Boolean)

    private val countryAliases = buildMap {
        Locale.getISOCountries().forEach { code ->
            put(code.lowercase(Locale.ROOT), code)
            put(Locale("", code).getDisplayCountry(Locale.ENGLISH).lowercase(Locale.ROOT), code)
            runCatching { Locale("", code).isO3Country.lowercase(Locale.ROOT) }.getOrNull()?.let { put(it, code) }
        }
        put("usa", "US"); put("u.s.", "US"); put("u.s.a.", "US"); put("u.s", "US"); put("u.s.a", "US")
        put("united states of america", "US"); put("uk", "GB"); put("britain", "GB")
        put("great britain", "GB"); put("united kingdom", "GB")
        // Tuvalu's code is "TV": "Tubi TV (USA)" and "Reality-TV" are not Tuvaluan.
        remove("tv")
    }
    /** Sports leagues and US streaming services: a category named after one of these is US programming. */
    private val usLeagues = Regex(
        "(?<![a-z])(?:nfl|nba|wnba|nhl|mlb|milb|mls|ncaa|espn|ppv|peacock|paramount|flo ?sports|flo ?college|flo ?racing)(?![a-z])",
        RegexOption.IGNORE_CASE,
    )
    /** Streaming services that put a country after their name: "(PLUTO Denmark)", "(PLUTO USA)". */
    private val services = setOf("pluto", "samsung", "stv", "roku", "tubi", "plex", "xumo", "rakuten", "prime")
    private val countryLabels = Regex(
        "(?<![a-z0-9])(?:" + countryAliases.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")(?![a-z0-9])",
        RegexOption.IGNORE_CASE,
    )
    private val englishLabel = Regex("(?<![a-z])(?:english|anglophone|eng|en)(?![a-z])", RegexOption.IGNORE_CASE)
    private val otherLanguageLabel = Regex(
        "(?<![a-z])(?:spanish|espa[nñ]ol|espanol|latino|latina|latam|latin|es|spa|french|fran[cç]ais|fr|fra|german|deutsch|de|deu|italian|italiano|it|ita|portuguese|portugu[eê]s|pt|por|arabic|ar|ara|hindi|hin|urdu|punjabi|bengali|tamil|telugu|malayalam|kannada|marathi|russian|rus|ru|polish|polski|pl|turkish|t[uü]rk[cç]e|tr|dutch|nederlands|nl|chinese|mandarin|cantonese|zh|japanese|ja|korean|ko|vietnamese|thai|indonesian|malay|filipino|tagalog|greek|hebrew|persian|farsi|ukrainian|romanian|hungarian|czech|slovak|serbian|croatian|bulgarian|swedish|norwegian|danish|finnish|multilingual|asian|taiwanese)(?![a-z])",
        RegexOption.IGNORE_CASE,
    )
    private val itemLabels = Regex("\\[([^]]+)\\]|\\(([^)]+)\\)|\\|([^|]+)\\|")
    private val itemPrefix = Regex("^\\s*([^:|]+?)\\s*[:|]\\s*|^\\s*([A-Za-z]{2,3})\\s*-\\s*")
    private val epgCountry = Regex("\\.([a-z]{2})$", RegexOption.IGNORE_CASE)
    private val metadataSeparators = Regex("[,;|/]+")
    private val englishMetadata = Regex("^(?:en|eng)(?:[-_][a-z0-9]{2,8})+$")
    private val englishAliases = setOf("english", "eng", "en")
    private val ambiguousCountryCodes = setOf("IN", "TO", "AS", "AT", "BE", "IS", "IT", "NO", "DO", "ME", "SO", "BY", "FI")
    private val markerSeparators = Regex("[\\s/+|,;]+")

    private fun countryLabel(match: MatchResult, value: String): String? {
        val code = countryAliases[match.value.lowercase(Locale.ROOT)] ?: return null
        if (match.value.length <= 3 && code != "US") {
            if (code in ambiguousCountryCodes && value.trim() != match.value && match.range.first > 0 &&
                value.getOrNull(match.range.first - 1) !in listOf('[', '(', '|', ':', '/')) return null
        }
        return code
    }

    private fun labels(value: String): Labels {
        val countries = countryLabels.findAll(value).mapNotNull { countryLabel(it, value) }.toList()
        return Labels("US" in countries || usLeagues.containsMatchIn(value), countries.any { it != "US" }, englishLabel.containsMatchIn(value), otherLanguageLabel.containsMatchIn(value))
    }

    /** Only marked item labels are evidence; a film's ordinary title cannot establish its language. */
    private fun nameLabels(name: String): Labels {
        val marked = itemLabels.findAll(name).map { match -> match.groupValues.drop(1).first { it.isNotEmpty() } }.toMutableList()
        itemPrefix.find(name)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.let { marked += it }
        val recognized = marked.mapNotNull { value ->
            val clean = value.trim().lowercase(Locale.ROOT)
            if (clean in countryAliases) countryAliases[clean]
            else if (isEnglish(clean)) "English"
            else if (otherLanguageLabel.matches(clean)) value
            else {
                val tokens = clean.split(markerSeparators).filter { it.isNotEmpty() }
                // "(PLUTO Denmark)": the service name is not evidence, the country after it is.
                val labelled = if (tokens.size > 1 && tokens.first() in services) tokens.drop(1) else tokens
                if (labelled.isNotEmpty() && labelled.all { it in countryAliases || isEnglish(it) || otherLanguageLabel.matches(it) })
                    labelled.joinToString(" | ") { countryAliases[it] ?: if (isEnglish(it)) "English" else it }
                else null
            }
        }
        return labels(recognized.joinToString(" | "))
    }

    private fun metadataParts(value: String?) = value?.split(metadataSeparators)?.map { it.trim().lowercase(Locale.ROOT) }?.filter { it.isNotBlank() }.orEmpty()
    private fun countryIsUs(country: String?): Boolean = metadataParts(country).let { values -> values.isNotEmpty() && values.all { countryAliases[it] == "US" } }
    private fun conflictingCountry(country: String?): Boolean = metadataParts(country).any { countryAliases[it] != "US" }
    private fun isEnglish(value: String): Boolean = value in englishAliases || englishMetadata.matches(value)

    fun live(items: List<Channel>, categories: List<Category>): Catalogue<Channel> {
        val categoriesById = categories.associate { it.id to labels(it.name) }
        val accepted = items.mapNotNull { channel ->
            val named = nameLabels(channel.name)
            val epgRegion = channel.epgId?.let { epgCountry.find(it)?.groupValues?.get(1)?.lowercase(Locale.ROOT) }?.let(countryAliases::get)
            if (conflictingCountry(channel.country) || named.foreign ||
                (epgRegion != null && epgRegion != "US")) return@mapNotNull null
            val memberships = channel.categoryIds.filter { id -> categoriesById[id]?.let { !it.foreign } == true }.distinct()
            val positive = countryIsUs(channel.country) || named.us || epgRegion == "US" || memberships.any { categoriesById[it]?.us == true }
            if (!positive) return@mapNotNull null
            val ids = memberships.ifEmpty { listOf("__tuner_us") }
            channel.copy(categoryId = ids.first(), categoryIds = ids)
        }.distinctBy { it.key }
        return Catalogue(accepted, usedCategories(accepted.flatMap { it.categoryIds }, categories, "__tuner_us", "USA"))
    }

    fun movies(items: List<Movie>, categories: List<Category>): Catalogue<Movie> {
        val accepted = items.distinctBy { it.id }.map { movie ->
            val ids = movie.categoryIds.distinct().ifEmpty { listOf("__tuner_movies") }
            movie.copy(categoryId = ids.first(), categoryIds = ids)
        }
        return Catalogue(accepted, usedCategories(accepted.flatMap { it.categoryIds }, categories, "__tuner_movies", "Movies"))
    }

    fun series(items: List<Series>, categories: List<Category>): Catalogue<Series> {
        val accepted = items.distinctBy { it.id }.map { series ->
            val ids = series.categoryIds.distinct().ifEmpty { listOf("__tuner_series") }
            series.copy(categoryId = ids.first(), categoryIds = ids)
        }
        return Catalogue(accepted, usedCategories(accepted.flatMap { it.categoryIds }, categories, "__tuner_series", "Series"))
    }

    private fun usedCategories(ids: List<String>, categories: List<Category>, fallbackId: String, fallbackName: String): List<Category> {
        val used = ids.toHashSet()
        return categories.filter { it.id in used && it.id != fallbackId }.distinctBy { it.id } + if (fallbackId in used) listOf(Category(fallbackId, fallbackName)) else emptyList()
    }
}
