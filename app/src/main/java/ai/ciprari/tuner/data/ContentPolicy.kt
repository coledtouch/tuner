package ai.ciprari.tuner.data

import java.util.Locale

/** Provider evidence is required. Unlabelled content never enters the displayed catalogue. */
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
    }
    private val countryLabels = Regex(
        "(?<![a-z0-9])(?:" + countryAliases.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")(?![a-z0-9])",
        RegexOption.IGNORE_CASE,
    )
    private val englishLabel = Regex("(?<![a-z])(?:english|anglophone|eng|en)(?![a-z])", RegexOption.IGNORE_CASE)
    private val otherLanguageLabel = Regex(
        "(?<![a-z])(?:spanish|espa[nñ]ol|espanol|latino|latina|latam|latin|es|spa|french|fran[cç]ais|fr|fra|german|deutsch|de|deu|italian|italiano|it|ita|portuguese|portugu[eê]s|pt|por|arabic|ar|ara|hindi|hin|urdu|punjabi|bengali|tamil|telugu|malayalam|kannada|marathi|russian|rus|ru|polish|polski|pl|turkish|t[uü]rk[cç]e|tr|dutch|nederlands|nl|chinese|mandarin|cantonese|zh|japanese|ja|korean|ko|vietnamese|thai|indonesian|malay|filipino|tagalog|greek|hebrew|persian|farsi|ukrainian|romanian|hungarian|czech|slovak|serbian|croatian|bulgarian|swedish|norwegian|danish|finnish|multilingual)(?![a-z])",
        RegexOption.IGNORE_CASE,
    )
    private val itemLabels = Regex("\\[([^]]+)\\]|\\(([^)]+)\\)|\\|([^|]+)\\|")
    private val itemPrefix = Regex("^\\s*([^:|]+?)\\s*[:|]\\s*|^\\s*([A-Za-z]{2,3})\\s*-\\s*")
    private val spanishNetworks = Regex(
        "\\b(?:telemundo|univisi[oó]n|unimas|unim[aá]s|galavisi[oó]n|estrella\\s*tv|azteca|tudn|espn\\s+deportes|fox\\s+deportes|universo|cnn\\s+en\\s+espa[nñ]ol|discovery\\s+en\\s+espa[nñ]ol|history\\s+en\\s+espa[nñ]ol|hola\\s*tv|vix)(?:\\b|(?=\\d))",
        RegexOption.IGNORE_CASE,
    )
    private val epgCountry = Regex("\\.([a-z]{2})$", RegexOption.IGNORE_CASE)
    private val metadataSeparators = Regex("[,;|/]+")
    private val englishMetadata = Regex("^(?:en|eng)(?:[-_][a-z0-9]{2,8})+$")
    private val englishAliases = setOf("english", "eng", "en")
    private val ambiguousCountryCodes = setOf("IN", "TO", "AS", "AT", "BE", "IS", "IT", "NO", "DO", "ME", "SO", "BY")
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
        return Labels("US" in countries, countries.any { it != "US" }, englishLabel.containsMatchIn(value), otherLanguageLabel.containsMatchIn(value))
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
                if (tokens.isNotEmpty() && tokens.all { it in countryAliases || isEnglish(it) || otherLanguageLabel.matches(it) })
                    tokens.joinToString(" | ") { countryAliases[it] ?: if (isEnglish(it)) "English" else it }
                else null
            }
        }
        return labels(recognized.joinToString(" | "))
    }

    private fun metadataParts(value: String?) = value?.split(metadataSeparators)?.map { it.trim().lowercase(Locale.ROOT) }?.filter { it.isNotBlank() }.orEmpty()
    private fun countryIsUs(country: String?): Boolean = metadataParts(country).let { values -> values.isNotEmpty() && values.all { countryAliases[it] == "US" } }
    private fun conflictingCountry(country: String?): Boolean = metadataParts(country).any { countryAliases[it] != "US" }
    private fun isEnglish(value: String): Boolean = value in englishAliases || englishMetadata.matches(value)
    private fun languageIsEnglish(language: String?): Boolean = metadataParts(language).let { values -> values.isNotEmpty() && values.all(::isEnglish) }
    private fun conflictingLanguage(language: String?): Boolean = metadataParts(language).any { !isEnglish(it) }

    fun live(items: List<Channel>, categories: List<Category>): Catalogue<Channel> {
        val categoriesById = categories.associate { it.id to labels(it.name) }
        val accepted = items.mapNotNull { channel ->
            val named = nameLabels(channel.name)
            val epgRegion = channel.epgId?.let { epgCountry.find(it)?.groupValues?.get(1)?.lowercase(Locale.ROOT) }?.let(countryAliases::get)
            if (conflictingCountry(channel.country) || conflictingLanguage(channel.language) || named.foreign || named.otherLanguage ||
                (epgRegion != null && epgRegion != "US") || spanishNetworks.containsMatchIn(channel.name)) return@mapNotNull null
            val memberships = channel.categoryIds.filter { id -> categoriesById[id]?.let { !it.foreign && !it.otherLanguage } == true }.distinct()
            val positive = countryIsUs(channel.country) || named.us || epgRegion == "US" || memberships.any { categoriesById[it]?.us == true }
            if (!positive) return@mapNotNull null
            val ids = memberships.ifEmpty { listOf("__tuner_us_english") }
            channel.copy(categoryId = ids.first(), categoryIds = ids)
        }.distinctBy { it.key }
        return Catalogue(accepted, usedCategories(accepted.flatMap { it.categoryIds }, categories, "__tuner_us_english", "USA · English"))
    }

    private fun vodMemberships(name: String, country: String?, language: String?, ids: List<String>, categories: Map<String, Labels>): List<String>? {
        val named = nameLabels(name)
        if (conflictingLanguage(language) || named.otherLanguage) return null
        val memberships = ids.filter { id -> categories[id]?.let { !it.otherLanguage && (!it.foreign || it.english) } == true }.distinct()
        val english = languageIsEnglish(language) || named.english || memberships.any { categories[it]?.english == true }
        val us = !conflictingCountry(country) && !named.foreign && (countryIsUs(country) || named.us || memberships.any { categories[it]?.us == true })
        if (!english && !us) return null
        return memberships.ifEmpty { listOf("__tuner_english") }
    }

    fun movies(items: List<Movie>, categories: List<Category>): Catalogue<Movie> {
        val labels = categories.associate { it.id to labels(it.name) }
        val accepted = items.mapNotNull { movie ->
            val ids = vodMemberships(movie.name, movie.country, movie.language, movie.categoryIds, labels) ?: return@mapNotNull null
            movie.copy(categoryId = ids.first(), categoryIds = ids)
        }.distinctBy { it.id }
        return Catalogue(accepted, usedCategories(accepted.flatMap { it.categoryIds }, categories, "__tuner_english", "English"))
    }

    fun series(items: List<Series>, categories: List<Category>): Catalogue<Series> {
        val labels = categories.associate { it.id to labels(it.name) }
        val accepted = items.mapNotNull { series ->
            val ids = vodMemberships(series.name, series.country, series.language, series.categoryIds, labels) ?: return@mapNotNull null
            series.copy(categoryId = ids.first(), categoryIds = ids)
        }.distinctBy { it.id }
        return Catalogue(accepted, usedCategories(accepted.flatMap { it.categoryIds }, categories, "__tuner_english", "English"))
    }

    private fun usedCategories(ids: List<String>, categories: List<Category>, fallbackId: String, fallbackName: String): List<Category> {
        val used = ids.toHashSet()
        return categories.filter { it.id in used && it.id != fallbackId }.distinctBy { it.id } + if (fallbackId in used) listOf(Category(fallbackId, fallbackName)) else emptyList()
    }
}
