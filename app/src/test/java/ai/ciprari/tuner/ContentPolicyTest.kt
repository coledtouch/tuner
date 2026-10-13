package ai.ciprari.tuner

import ai.ciprari.tuner.data.Category
import ai.ciprari.tuner.data.Channel
import ai.ciprari.tuner.data.ContentPolicy
import ai.ciprari.tuner.data.M3u
import ai.ciprari.tuner.data.Movie
import ai.ciprari.tuner.data.Series
import ai.ciprari.tuner.data.Source
import ai.ciprari.tuner.data.SourceType
import ai.ciprari.tuner.data.Xtream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContentPolicyTest {
    private val categories = listOf(
        Category("us", "USA | Entertainment"), Category("uk", "UK | Entertainment"),
        Category("en", "English Movies"), Category("es", "USA | Spanish / Latino"),
        Category("genre", "Action"), Category("uken", "UK | English Series"),
    )
    private fun channel(name: String = "CNN", ids: List<String> = listOf("genre"), country: String? = null, language: String? = null, epg: String? = null) =
        Channel(name, 100, name, null, ids.firstOrNull().orEmpty(), epg, null, "http://example/live/1.ts", ids, country, language)
    private fun movie(name: String = "A Movie", ids: List<String> = listOf("genre"), country: String? = null, language: String? = null) =
        Movie(name, name, null, null, ids.firstOrNull().orEmpty(), "http://example/movie/1.mp4", categoryIds = ids, country = country, language = language)
    private fun series(name: String = "A Series", ids: List<String> = listOf("genre"), country: String? = null, language: String? = null) =
        Series(name, name, null, null, null, ids.firstOrNull().orEmpty(), null, null, null, categoryIds = ids, country = country, language = language)
    private fun temp(text: String): File = File.createTempFile("tuner-policy", ".txt").apply { writeText(text); deleteOnExit() }

    @Test fun unknownContentAndOrdinaryLatinTitlesStayHidden() {
        assertTrue(ContentPolicy.live(listOf(channel()), categories).items.isEmpty())
        assertTrue(ContentPolicy.movies(listOf(movie("The English Patient"), movie("Us (2019)"), movie("The Last of Us: Part One")), categories).items.isEmpty())
        assertTrue(ContentPolicy.series(listOf(series("English Teacher")), categories).items.isEmpty())
    }

    @Test fun liveRequiresUsRatherThanEnglishAlone() {
        val items = listOf(channel("USA CNN", listOf("us")), channel("BBC", listOf("uken")), channel("English feed", language = "en"))
        assertEquals(listOf("USA CNN"), ContentPolicy.live(items, categories).items.map { it.name })
    }

    @Test fun ordinaryWordsInsideRegionLabelsDoNotBecomeForeignCountries() {
        val categories = listOf(Category("us", "US Sports in English"))
        assertEquals(1, ContentPolicy.live(listOf(channel(ids = listOf("us"))), categories).items.size)
    }

    @Test fun markedUsPrefixesAndEpgSuffixArePositiveEvidence() {
        val items = listOf("|US| CNN", "[USA] NBC", "US: CBS", "USA - ABC").map { channel(it) } + channel("FOX", epg = "FOX.us")
        assertEquals(5, ContentPolicy.live(items, categories).items.size)
    }

    @Test fun countryAndEnglishMetadataAliasesAreSupported() {
        val countries = listOf("US", "USA", "United States", "United States of America", "U.S.A.")
        val items = countries.mapIndexed { index, country -> channel("Channel $index", country = country, language = listOf("en", "eng", "English", "en-US", "EN_gb")[index]) }
        assertEquals(5, ContentPolicy.live(items, categories).items.size)
    }

    @Test fun foreignItemMetadataOrLabelsOverrideUsCategory() {
        val items = listOf(
            channel("CNN Canada", listOf("us"), country = "CA"), channel("CNN Español", listOf("us"), language = "es"),
            channel("[UK] BBC", listOf("us")), channel("[uk] BBC lowercase", listOf("us")), channel("[ca] CNN", listOf("us")), channel("[ES] ABC", listOf("us")),
            channel("Foreign EPG", listOf("us"), epg = "bbc.uk"), channel("Conflicting language", listOf("us"), language = "en;spa"),
            channel("Conflicting country", listOf("us"), country = "US;GB"),
        )
        assertTrue(ContentPolicy.live(items, categories).items.isEmpty())
    }

    @Test fun spanishUsNetworksNeverEnterTheGuide() {
        val names = listOf("Telemundo52", "Univisión", "UniMás", "Galavision", "ESPN Deportes", "FOX Deportes", "Universo", "TUDN", "Estrella TV")
        assertTrue(ContentPolicy.live(names.map { channel(it, listOf("us"), country = "US") }, categories).items.isEmpty())
    }

    @Test fun mixedCategoryMembershipsKeepOnlySafeCategories() {
        val result = ContentPolicy.live(listOf(channel(ids = listOf("uk", "us", "es", "genre"))), categories)
        assertEquals(listOf("us", "genre"), result.items.single().categoryIds)
        assertEquals("us", result.items.single().categoryId)
        assertEquals(setOf("us", "genre"), result.categories.map { it.id }.toSet())
    }

    @Test fun lowerCaseForeignCategoryMembershipCannotLeakFromMixedLists() {
        val categories = listOf(Category("us", "USA News"), Category("uk", "uk | Entertainment"))
        val result = ContentPolicy.live(listOf(channel(ids = listOf("uk", "us"))), categories)
        assertEquals(listOf("us"), result.items.single().categoryIds)
        assertEquals(listOf("us"), result.categories.map { it.id })
    }

    @Test fun safeMetadataGetsFallbackWhenEveryCategoryIsForeign() {
        val result = ContentPolicy.live(listOf(channel(ids = listOf("uk", "es"), country = "US", language = "English")), categories)
        assertEquals("USA · English", result.categories.single().name)
        assertEquals(result.categories.single().id, result.items.single().categoryId)
    }

    @Test fun englishVodIncludesUkButExcludesConflictingLanguage() {
        val items = listOf(
            movie("English UK movie", country = "GB", language = "eng"), movie("Provider English label", listOf("uken")),
            movie("US provider movie", listOf("us")), movie("Spanish movie", listOf("en"), language = "Spanish"),
            movie("Unknown-language UK movie", listOf("uk")), movie("[ES] Dubbed movie", listOf("en")),
        )
        assertEquals(listOf("English UK movie", "Provider English label", "US provider movie"), ContentPolicy.movies(items, categories).items.map { it.name })
    }

    @Test fun markedEnglishVodLabelsWorkWithoutGuessingFromTitle() {
        val items = listOf(movie("[EN] Movie"), movie("English: Movie"), movie("Movie (English)"), movie("[US] Movie"))
        assertEquals(4, ContentPolicy.movies(items, categories).items.size)
    }

    @Test fun seriesUsesTheSameEnglishPolicyAndPrunesForeignCategories() {
        val result = ContentPolicy.series(listOf(series(ids = listOf("es", "uk", "en", "genre")), series("Spanish series", listOf("en"), language = "es")), categories)
        assertEquals(1, result.items.size)
        assertEquals(listOf("en", "genre"), result.items.single().categoryIds)
        assertEquals(setOf("en", "genre"), result.categories.map { it.id }.toSet())
    }

    @Test fun metadataUnknownLanguageIsHiddenEvenInEnglishCategory() {
        assertTrue(ContentPolicy.movies(listOf(movie(ids = listOf("en"), language = "und")), categories).items.isEmpty())
        assertTrue(ContentPolicy.live(listOf(channel(ids = listOf("us"), language = "unknown")), categories).items.isEmpty())
    }

    @Test fun trendingPolicyCannotAdmitUnknownOrForeignFeeds() {
        val items = listOf(channel("Unlabelled featured event"), channel("[UK] Featured event"), channel("[US] Featured event"))
        assertEquals(listOf("[US] Featured event"), ContentPolicy.live(items, emptyList()).items.map { it.name })
    }

    @Test fun m3uCountryAndLanguageAttributesReachThePolicy() {
        val parsed = M3u.parse(temp("""#EXTM3U
#EXTINF:-1 tvg-country="US" tvg-language="en-US" group-title="News",CNN
http://example/live/1.ts
#EXTINF:-1 tvg-country="US" tvg-language="Spanish" group-title="News",Another feed
http://example/live/2.ts
#EXTINF:-1 tvg-country="GB" tvg-language="eng" group-title="Movies",An English movie
http://example/movie/3.mp4
"""))
        assertEquals("US", parsed.channels.first().country)
        assertEquals("en-US", parsed.channels.first().language)
        assertEquals(listOf("CNN"), ContentPolicy.live(parsed.channels, parsed.liveCategories).items.map { it.name })
        assertEquals(1, ContentPolicy.movies(parsed.movies, parsed.movieCategories).items.size)
    }

    @Test fun xtreamDuplicateMetadataConflictsArePreservedAndRejected() {
        val x = Xtream(Source("test", "Test", SourceType.XTREAM, "http://example", "user", "pass"))
        val parsed = x.parseLive(temp("""[
            {"stream_id":1,"name":"CNN","category_id":"us","country":"US","language":"en"},
            {"stream_id":1,"name":"CNN","category_id":"uk","country":"GB","language":"en"},
            {"stream_id":2,"name":"NBC","category_id":"us","country_code":"USA","audio_language":"eng"}
        ]"""), "ts")
        assertEquals("US;GB", parsed.first().country)
        assertEquals(listOf("NBC"), ContentPolicy.live(parsed, categories).items.map { it.name })
        val movies = x.parseMovies(temp("""[{"stream_id":1,"name":"A Movie","category_id":"en","original_language":"en","audio_language":"es"}]"""))
        assertTrue(ContentPolicy.movies(movies, categories).items.isEmpty())
    }
}
