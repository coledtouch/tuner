package ai.ciprari.tuner.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import ai.ciprari.tuner.data.Net
import ai.ciprari.tuner.data.Repo
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * One ExoPlayer for the whole app. The guide preview and the full-screen player share it,
 * so pressing OK on a channel you are previewing goes full screen with no re-buffering.
 */
@OptIn(UnstableApi::class)
object PlayerHolder {
    private lateinit var appContext: Context
    private var exo: ExoPlayer? = null
    private var dataSourceUa: String? = null

    var currentUrl: String? = null
        private set
    var isLive = false
        private set
    private var triedHls = false

    /** Human-readable playback error, or null while things are fine. */
    val error = MutableStateFlow<String?>(null)

    fun init(context: Context) { appContext = context.applicationContext }

    val player: ExoPlayer
        get() {
            val ua = Repo.userAgent
            exo?.let { if (dataSourceUa == ua) return it; it.release() }
            dataSourceUa = ua
            val http = OkHttpDataSource.Factory(Net.client).setUserAgent(ua)
            val extractors = DefaultExtractorsFactory()
                .setTsExtractorFlags(
                    DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                        DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
                )
                .setConstantBitrateSeekingEnabled(true)
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 60_000, 1_200, 2_500)
                .build()
            val p = ExoPlayer.Builder(appContext)
                .setRenderersFactory(DefaultRenderersFactory(appContext).setEnableDecoderFallback(true))
                .setMediaSourceFactory(DefaultMediaSourceFactory(http, extractors))
                .setLoadControl(loadControl)
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                    true,
                )
                .build()
            p.addListener(object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) {
                    val url = currentUrl
                    // Some panels hand out HLS without a .m3u8 extension; retry once as HLS.
                    if (url != null && !triedHls && !url.contains(".m3u8")) {
                        triedHls = true
                        p.setMediaItem(item(url, forceHls = true))
                        p.prepare(); p.play()
                        return
                    }
                    if (isLive && e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                        p.seekToDefaultPosition(); p.prepare(); return
                    }
                    error.value = describe(e)
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) error.value = null
                }
            })
            exo = p
            return p
        }

    private fun item(url: String, forceHls: Boolean): MediaItem {
        val b = MediaItem.Builder().setUri(url)
        if (forceHls || url.contains(".m3u8")) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        return b.build()
    }

    fun play(url: String, live: Boolean, startMs: Long = 0) {
        val p = player
        if (url == currentUrl && p.playbackState != Player.STATE_IDLE && error.value == null) {
            if (!p.playWhenReady) p.play()
            return
        }
        currentUrl = url
        isLive = live
        triedHls = false
        error.value = null
        if (startMs > 0) p.setMediaItem(item(url, forceHls = false), startMs) else p.setMediaItem(item(url, forceHls = false))
        p.prepare()
        p.play()
    }

    fun retry() {
        val url = currentUrl ?: return
        currentUrl = null
        play(url, isLive)
    }

    fun stop() {
        exo?.stop()
        exo?.clearMediaItems()
        currentUrl = null
        error.value = null
    }

    fun onAppBackground() {
        val p = exo ?: return
        if (isLive) { p.stop() } else p.pause()
    }

    fun onAppForeground() {
        val p = exo ?: return
        if (isLive && currentUrl != null && p.playbackState == Player.STATE_IDLE) { p.prepare(); p.play() }
    }

    private fun describe(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The provider refused the stream. You may be over your connection limit."
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Can't reach the stream. Check your connection."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "This stream format isn't supported. Try switching Live format in Settings."
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "This device can't decode the stream's video or audio."
        else -> "Playback failed (${e.errorCodeName})"
    }
}
