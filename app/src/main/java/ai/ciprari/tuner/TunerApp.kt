package ai.ciprari.tuner

import android.app.Application
import ai.ciprari.tuner.data.Net
import ai.ciprari.tuner.data.Repo
import ai.ciprari.tuner.player.PlayerHolder
import ai.ciprari.tuner.player.TwoChannelPlayer
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.decode.SvgDecoder
import coil.memory.MemoryCache
import ai.ciprari.tuner.ui.Palette
import ai.ciprari.tuner.ui.Themes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private const val BROWSER_UA =
    "Mozilla/5.0 (Linux; Android 12; BRAVIA 4K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

class TunerApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        Palette.theme = Themes.byId(Repo.prefs.themeId)
        PlayerHolder.init(this)
        TwoChannelPlayer.init(this)
        // Editing or switching provider credentials closes both previous provider streams.
        var previousSource = Repo.activeSource
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            combine(Repo.prefs.sources, Repo.prefs.activeId) { sources, activeId -> sources.firstOrNull { it.id == activeId } }
                .collect { source ->
                    if (source != previousSource) { PlayerHolder.stop(); TwoChannelPlayer.stop(); previousSource = source }
                }
        }
    }

    // Posters and logos come from the provider; share the app's HTTP client and cache them on disk.
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        // Some logo CDNs reject non-browser clients, so images go out with a browser user agent.
        .okHttpClient(
            Net.client.newBuilder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", BROWSER_UA).build())
            }.build(),
        )
        .components { add(SvgDecoder.Factory()) }
        .crossfade(180)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(150L * 1024 * 1024).build() }
        .respectCacheHeaders(false)
        .build()
}
