package com.nononsenseapps.feeder.model.shelf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.nononsenseapps.feeder.model.audiobooks.AudiobookLibrary
import com.nononsenseapps.feeder.model.ebooks.EbookLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** One picture a cover search found: [url] is the big one, [thumb] the one to show in the grid. */
data class CoverCandidate(
    val url: String,
    val thumb: String,
    val label: String,
)

/**
 * "Cover from the Internet" (as in the Voice app) for ebooks and audiobooks: searches Apple Books,
 * Open Library and Google Books for covers, and puts a picked one on the book. Same sources and
 * order as web/src/lib/bookCovers.ts.
 */
object BookCovers {
    private val client by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Covers for [query] (title and author), audiobook covers first for an audiobook. [onPartial]
     * gets the list so far each time a source answers, so a slow one doesn't hold up the rest.
     */
    suspend fun search(
        query: String,
        audiobook: Boolean,
        onPartial: (List<CoverCandidate>) -> Unit = {},
    ): List<CoverCandidate> =
        withContext(Dispatchers.IO) {
            val q = Uri.encode(query.trim())
            if (q.isEmpty()) return@withContext emptyList()
            val sources =
                listOfNotNull(
                    "https://itunes.apple.com/search?term=$q&media=${if (audiobook) "audiobook" else "ebook"}&limit=15" to ::parseItunes,
                    if (audiobook) "https://itunes.apple.com/search?term=$q&media=ebook&limit=10" to ::parseItunes else null,
                    "https://openlibrary.org/search.json?q=$q&fields=cover_i,title,author_name&limit=15" to ::parseOpenLibrary,
                    "https://www.googleapis.com/books/v1/volumes?q=$q&maxResults=15&printType=books" to ::parseGoogleBooks,
                )
            val slots = Array(sources.size) { emptyList<CoverCandidate>() }
            val lock = Any()
            fun merged() = synchronized(lock) { slots.toList().flatten().distinctBy { it.url } }
            coroutineScope {
                sources
                    .mapIndexed { n, (url, parse) ->
                        async {
                            val found = runCatching { parse(get(url)) }.getOrNull() ?: return@async
                            synchronized(lock) { slots[n] = found }
                            onPartial(merged())
                        }
                    }.awaitAll()
            }
            merged()
        }

    /** Downloads [url] and puts it on the book [key] ("ebook:<id>" or "audiobook:<id>"). */
    suspend fun apply(
        context: Context,
        key: String,
        url: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val bytes = runCatching { bytes(url) }.getOrNull() ?: return@withContext false
            val id = key.substringAfter(':')
            when (key.substringBefore(':')) {
                ShelfStatusRules.EBOOK -> EbookLibrary.get(context).setCover(id, bytes)
                ShelfStatusRules.AUDIOBOOK -> AudiobookLibrary.get(context).setCover(id, bytes)
                else -> false
            }
        }

    /** [apply] for a cover picked on another device. */
    fun fetchInBackground(
        context: Context,
        key: String,
        url: String,
    ) {
        scope.launch { apply(context, key, url) }
    }

    /** At most 480 px on the long side, as JPEG. */
    fun small(bytes: ByteArray): ByteArray? {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val side = minOf(480, maxOf(bmp.width, bmp.height))
        val scale = side.toFloat() / maxOf(bmp.width, bmp.height)
        val small = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
        return ByteArrayOutputStream().use { out ->
            small.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
    }

    private fun get(url: String): JsonElement =
        client.newCall(Request.Builder().url(url).header("User-Agent", "Feeder").build()).execute().use { res ->
            check(res.isSuccessful) { "HTTP ${res.code}" }
            json.parseToJsonElement(res.body.string())
        }

    private fun bytes(url: String): ByteArray =
        client.newCall(Request.Builder().url(url).header("User-Agent", "Feeder").build()).execute().use { res ->
            check(res.isSuccessful) { "HTTP ${res.code}" }
            res.body.bytes()
        }

    private fun JsonElement?.str(): String? = (this as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    internal fun parseItunes(root: JsonElement): List<CoverCandidate> =
        ((root as? JsonObject)?.get("results") as? JsonArray).orEmpty().mapNotNull { r ->
            val o = r as? JsonObject ?: return@mapNotNull null
            val art = o["artworkUrl100"].str() ?: o["artworkUrl60"].str() ?: return@mapNotNull null
            val name = o["collectionName"].str() ?: o["trackName"].str() ?: ""
            val who = o["artistName"].str() ?: ""
            CoverCandidate(
                url = art.replace(Regex("/\\d+x\\d+(bb)?\\.(jpg|png)$"), "/600x600bb.jpg"),
                thumb = art.replace(Regex("/\\d+x\\d+(bb)?\\.(jpg|png)$"), "/200x200bb.jpg"),
                label = listOf(name, who).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Apple Books" },
            )
        }

    internal fun parseOpenLibrary(root: JsonElement): List<CoverCandidate> =
        ((root as? JsonObject)?.get("docs") as? JsonArray).orEmpty().mapNotNull { d ->
            val o = d as? JsonObject ?: return@mapNotNull null
            val id = o["cover_i"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val who = (o["author_name"] as? JsonArray)?.firstOrNull().str() ?: ""
            CoverCandidate(
                url = "https://covers.openlibrary.org/b/id/$id-L.jpg",
                thumb = "https://covers.openlibrary.org/b/id/$id-M.jpg",
                label = listOf(o["title"].str() ?: "", who).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Open Library" },
            )
        }

    internal fun parseGoogleBooks(root: JsonElement): List<CoverCandidate> =
        ((root as? JsonObject)?.get("items") as? JsonArray).orEmpty().mapNotNull { it0 ->
            val info = ((it0 as? JsonObject)?.get("volumeInfo") as? JsonObject) ?: return@mapNotNull null
            val links = info["imageLinks"] as? JsonObject ?: return@mapNotNull null
            val thumb = (links["thumbnail"].str() ?: links["smallThumbnail"].str())?.replace("http://", "https://") ?: return@mapNotNull null
            val who = (info["authors"] as? JsonArray)?.firstOrNull().str() ?: ""
            CoverCandidate(
                url = thumb.replace("&edge=curl", "").replace(Regex("zoom=\\d"), "zoom=0"),
                thumb = thumb.replace("&edge=curl", ""),
                label = listOf(info["title"].str() ?: "", who).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Google Books" },
            )
        }
}
