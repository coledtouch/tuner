package ai.ciprari.tuner.data

import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

const val DEFAULT_UA = "VLC/3.0.21 LibVLC/3.0.21"

object Net {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    fun request(url: String, ua: String) = Request.Builder().url(url).header("User-Agent", ua.ifBlank { DEFAULT_UA }).build()

    suspend fun getString(url: String, ua: String): String = withContext(Dispatchers.IO) {
        client.newCall(request(url, ua)).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Server returned ${r.code}")
            r.body?.string() ?: ""
        }
    }

    /**
     * Downloads [url] into [file], reusing the existing copy when it is younger than [maxAgeMs].
     * Writes to a temp file first so a failed download never clobbers a good cache.
     */
    suspend fun download(url: String, ua: String, file: File, maxAgeMs: Long, force: Boolean): File =
        withContext(Dispatchers.IO) {
            if (!force && file.exists() && file.length() > 0 &&
                System.currentTimeMillis() - file.lastModified() < maxAgeMs
            ) return@withContext file
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".part")
            try {
                client.newCall(request(url, ua)).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("Server returned ${r.code}")
                    val body = r.body ?: throw IOException("Empty response")
                    tmp.outputStream().use { out -> body.byteStream().copyTo(out, 64 * 1024) }
                }
                if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
                file
            } catch (e: Exception) {
                tmp.delete()
                // A stale cache beats nothing when the provider is flaky.
                if (file.exists() && file.length() > 0) file else throw e
            }
        }

    /** Opens a file, transparently gunzipping it when it starts with the gzip magic bytes. */
    fun openMaybeGzip(file: File): InputStream {
        val raw = BufferedInputStream(file.inputStream(), 64 * 1024)
        raw.mark(2)
        val b1 = raw.read(); val b2 = raw.read()
        raw.reset()
        return if (b1 == 0x1f && b2 == 0x8b) BufferedInputStream(GZIPInputStream(raw, 64 * 1024), 64 * 1024) else raw
    }
}

// ---------------- lenient streaming JSON helpers ----------------

typealias JMap = Map<String, Any?>

fun JsonReader.readAny(): Any? = when (peek()) {
    JsonToken.STRING -> nextString()
    JsonToken.NUMBER -> nextString()
    JsonToken.BOOLEAN -> nextBoolean().toString()
    JsonToken.NULL -> { nextNull(); null }
    JsonToken.BEGIN_ARRAY -> {
        val l = ArrayList<Any?>(); beginArray(); while (hasNext()) l.add(readAny()); endArray(); l
    }
    JsonToken.BEGIN_OBJECT -> {
        val m = LinkedHashMap<String, Any?>(); beginObject(); while (hasNext()) m[nextName()] = readAny(); endObject(); m
    }
    else -> { skipValue(); null }
}

/**
 * Streams a JSON file whose top level is either an array of objects or an object of objects
 * (some panels return the latter) and hands each object to [fn] without holding the whole list.
 */
fun forEachJsonObject(file: File, fn: (JMap) -> Unit) {
    JsonReader(InputStreamReader(Net.openMaybeGzip(file), Charsets.UTF_8)).use { r ->
        r.isLenient = true
        when (r.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                r.beginArray()
                while (r.hasNext()) {
                    @Suppress("UNCHECKED_CAST")
                    if (r.peek() == JsonToken.BEGIN_OBJECT) fn(r.readAny() as JMap) else r.skipValue()
                }
                r.endArray()
            }
            JsonToken.BEGIN_OBJECT -> {
                r.beginObject()
                while (r.hasNext()) {
                    r.nextName()
                    @Suppress("UNCHECKED_CAST")
                    if (r.peek() == JsonToken.BEGIN_OBJECT) fn(r.readAny() as JMap) else r.skipValue()
                }
                r.endObject()
            }
            else -> Unit
        }
    }
}

fun parseJson(text: String): Any? = JsonReader(text.reader()).use { it.isLenient = true; it.readAny() }

fun JMap.str(key: String): String? {
    val v = this[key]
    val s = when (v) {
        is String -> v
        is List<*> -> v.firstOrNull { it is String && it.isNotBlank() } as? String
        null -> null
        else -> v.toString()
    }?.trim()
    return if (s.isNullOrEmpty() || s == "null") null else s
}

@Suppress("UNCHECKED_CAST")
fun JMap.obj(key: String): JMap? = this[key] as? JMap
