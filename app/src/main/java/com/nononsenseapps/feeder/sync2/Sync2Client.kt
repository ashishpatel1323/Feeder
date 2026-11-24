package com.nononsenseapps.feeder.sync2

import android.util.Log
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.crypto.AesCbcWithIntegrity
import com.nononsenseapps.feeder.crypto.SecretKeys
import com.nononsenseapps.feeder.db.room.Feed
import com.nononsenseapps.feeder.db.room.FeedItem
import com.nononsenseapps.feeder.db.room.HiddenItem
import com.nononsenseapps.feeder.db.room.Sync2Dao
import com.nononsenseapps.feeder.db.room.Sync2ItemKey
import com.nononsenseapps.feeder.db.room.Sync2Known
import com.nononsenseapps.feeder.model.offline.OfflineFiles
import com.nononsenseapps.feeder.model.playlists.PlaylistStore
import com.nononsenseapps.feeder.model.youtube.VideoPositions
import com.nononsenseapps.feeder.sharedlinks.LinkInfo
import com.nononsenseapps.feeder.sharedlinks.SHARED_FEED_URL_STRING
import com.nononsenseapps.feeder.sharedlinks.SharedLinks
import com.nononsenseapps.feeder.sharedlinks.isSharedFeed
import com.nononsenseapps.feeder.sharedlinks.siteOf
import com.nononsenseapps.feeder.sync.SyncRestClient
import com.nononsenseapps.feeder.util.logDebug
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import java.net.URL
import java.security.SecureRandom
import java.time.Instant

/** Feeder's own sync server: the web app's backend on Vercel (web/server/sync2.ts). */
const val SYNC2_SERVER = "https://feeder-web-snowy.vercel.app"

/**
 * Feeder's own sync (v2), shared with the web app (web/src/lib/sync2.ts, which documents the
 * format). Every fact is one encrypted last-writer-wins record:
 *
 *   feed    feed URL            synced feed fields, or {"deleted": true} after unsubscribing
 *   read    feedUrl \n guid     true / false
 *   saved   feedUrl \n guid     true / false (+ a small copy of the article in "a")
 *   hidden  feedUrl \n guid     true = deleted for good on every device
 *   layout  "main"              feed/folder order (web only; kept, not used here)
 *   video   feedUrl \n guid     { t, d }: where a video or podcast episode was left, in seconds
 *   playlist (key: playlist id) [SyncedPlaylist], or {"deleted": true} after deleting it
 *
 * The device remembers the last value exchanged for every record (sync2_known) and sends
 * whatever differs locally, then applies what other devices changed. Unknown kinds (Read later
 * links in a later version) are remembered and left alone.
 */
class Sync2Client(
    override val di: DI,
) : DIAware {
    private val repository: Repository by instance()
    private val dao: Sync2Dao by instance()
    private val okHttpClient: OkHttpClient by instance()
    private val oldClient: SyncRestClient by instance()
    private val videoPositions: VideoPositions by instance()
    private val sharedLinks by lazy { SharedLinks(di) }
    private val offlineFiles: OfflineFiles by instance()
    private val playlistStore: PlaylistStore by instance()
    private val mutex = Mutex()

    // Episode positions that arrived in this exchange, copied to the podcast player's store after it
    private val arrivedPositions = mutableSetOf<String>()

    @Volatile
    private var configured: Boolean? = null

    /** True when this device has a sync code and key. */
    val isConfigured: Boolean
        get() =
            configured ?: runBlocking { repository.getSyncRemote().hasSyncChain() }.also { configured = it }

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val mapAdapter: JsonAdapter<Map<String, Any?>> =
        moshi.adapter(Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))
    private val anyAdapter: JsonAdapter<Any?> = moshi.adapter(Any::class.java).serializeNulls()
    private val requestAdapter = moshi.adapter(ExchangeRequest::class.java)
    private val responseAdapter = moshi.adapter(ExchangeResponse::class.java)
    private val stateAdapter = moshi.adapter(Sync2State::class.java)

    data class Sync2State(
        val cursor: Long = 0,
        /** False until this device moved over from Feeder's old sync chain. */
        val migrated: Boolean = false,
        val lastSyncAt: Long = 0,
        val lastError: String? = null,
    )

    data class WireRecord(
        val id: String,
        val ts: Long,
        val data: String,
    )

    data class ExchangeRequest(
        val account: String,
        val auth: String,
        val since: Long,
        val push: List<WireRecord>,
    )

    data class ExchangeResponse(
        val cursor: Long,
        val more: Boolean,
        val changes: List<WireRecord>,
    )

    private data class Pending(
        val kind: String,
        val key: String,
        val v: Any?,
        val ts: Long,
        val a: Map<String, Any?>? = null,
    )

    // -----------------------------------------------------------------------------------------
    // State (kept in sync2_known under a key no record can have)

    suspend fun getState(): Sync2State = dao.getKnown(STATE_KEY)?.let { stateAdapter.fromJson(it.v) } ?: Sync2State()

    private suspend fun saveState(state: Sync2State) {
        dao.upsertKnown(listOf(Sync2Known(STATE_KEY, stateAdapter.toJson(state), 0)))
    }

    /** Forgets everything exchanged, keeping the given state. */
    private suspend fun resetKnown(state: Sync2State) {
        dao.deleteAllKnown()
        saveState(state)
    }

    // -----------------------------------------------------------------------------------------
    // Setting up. A sync is just a code and a key; the server account appears on first use.

    /** Starts a new sync with this device as the first one. Returns the sync code. */
    suspend fun create(): String {
        val code = randomSyncCode()
        start(code, AesCbcWithIntegrity.generateKey().toString())
        return code
    }

    suspend fun join(
        syncCode: String,
        secretKey: String,
    ) {
        AesCbcWithIntegrity.decodeKey(secretKey) // validates
        start(syncCode.lowercase(), secretKey)
    }

    private suspend fun start(
        syncCode: String,
        secretKey: String,
    ) {
        val remote = repository.getSyncRemote()
        repository.updateSyncRemote(
            remote.copy(syncChainId = syncCode, secretKey = secretKey, deviceId = 0L, latestMessageTimestamp = Instant.EPOCH),
        )
        resetKnown(Sync2State(migrated = true))
        configured = true
    }

    /** Stops syncing on this device. Feeds and articles stay; other devices are not touched. */
    suspend fun leave() {
        repository.replaceWithDefaultSyncRemote()
        resetKnown(Sync2State())
        configured = false
    }

    /** Sends everything this device has again and pulls everything. */
    suspend fun forceSync() {
        mutex.withLock {
            val keep =
                (dao.getKnownWithPrefix("feed\n").filter { isDeleted(parse(it.v)) } + dao.getKnownWithPrefix("hidden\n"))
                    .map { Sync2Known(it.k, if (it.k.startsWith("feed\n")) """{"known":true}""" else "false", 0) }
            resetKnown(getState().copy(cursor = 0))
            dao.upsertKnown(keep)
        }
        sync()
    }

    // -----------------------------------------------------------------------------------------
    // Syncing

    /** One full exchange. Returns false when sync isn't set up. */
    suspend fun sync(): Boolean =
        mutex.withLock {
            // Offline mode: changes wait here and go out with the first sync after it
            if (repository.offlineMode.value) return@withLock false
            val remote = repository.getSyncRemote()
            if (!remote.hasSyncChain() || remote.secretKey.isBlank()) return@withLock false
            var state = getState()
            if (!state.migrated) {
                if (!serverReady()) {
                    // Until the server has its database, keep using the old chain
                    runOldChain()
                    return@withLock true
                }
                moveFromOldChain()
                state = getState()
            }
            try {
                val secret = AesCbcWithIntegrity.decodeKey(remote.secretKey)
                val keys = Sync2Keys(remote.syncChainId, secret.integrityKey.encoded)
                exchange(keys, secret, state)
                saveState(getState().copy(lastSyncAt = System.currentTimeMillis(), lastError = null))
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Sync failed", e)
                saveState(getState().copy(lastError = e.message ?: e.toString()))
            }
            true
        }

    private suspend fun runOldChain() {
        try {
            oldClient.getFeeds()
            oldClient.getRead()
            repository.applyRemoteReadMarks()
            oldClient.sendUpdatedFeeds()
            oldClient.markAsRead()
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Old sync chain failed", e)
        }
    }

    /**
     * Moving over from Feeder's old sync chain, once, with nothing lost: one last pull from the
     * chain, then feeds the chain knew but this device removed become unsubscribes, and the first
     * exchange sends every feed, read mark and saved article this device has. The same sync code
     * and key name the new account, so no device needs re-pairing.
     */
    private suspend fun moveFromOldChain() {
        try {
            oldClient.getFeeds()
            oldClient.getRead()
            repository.applyRemoteReadMarks()
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Last pull from the old chain failed; moving over with what is here", e)
        }
        resetKnown(Sync2State(migrated = true))
        dao.upsertKnown(
            repository.getRemotelySeenFeeds().map { Sync2Known(knownKey("feed", it.toString()), """{"known":true}""", 0) },
        )
    }

    private suspend fun serverReady(): Boolean = withContext(Dispatchers.IO) { serverReadyBlocking() }

    private fun serverReadyBlocking(): Boolean =
        try {
            val request = Request.Builder().url("$SYNC2_SERVER/api/v2/status").build()
            okHttpClient.newCall(request).execute().use { res ->
                res.isSuccessful && res.body?.string()?.contains("\"store\":\"none\"") == false
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Sync server not reachable", e)
            false
        }

    private suspend fun exchange(
        keys: Sync2Keys,
        secret: SecretKeys,
        state: Sync2State,
    ) {
        arrivedPositions.clear()
        copyEpisodePositionsOut()
        val pending = collectChanges(System.currentTimeMillis())
        logDebug(LOG_TAG, "Sending ${pending.size} changes")
        val records =
            pending.map { p ->
                WireRecord(
                    id = keys.recordId(p.kind, p.key),
                    ts = p.ts,
                    data = AesCbcWithIntegrity.encryptString(payloadJson(p), secret),
                )
            }
        var cursor = state.cursor
        var i = 0
        do {
            val batch = records.subList(i, minOf(i + BATCH, records.size))
            var res = post(ExchangeRequest(keys.account, keys.auth, cursor, batch))
            dao.upsertKnown(
                pending.subList(i, minOf(i + BATCH, pending.size)).map { p -> Sync2Known(knownKey(p.kind, p.key), canonical(p.v), p.ts) },
            )
            while (true) {
                for (c in res.changes) {
                    val payload =
                        try {
                            mapAdapter.fromJson(AesCbcWithIntegrity.decryptString(c.data, secret))
                        } catch (e: Exception) {
                            Log.e(LOG_TAG, "Skipping unreadable sync record", e)
                            null
                        } ?: continue
                    val kind = payload["k"] as? String ?: continue
                    val key = payload["key"] as? String ?: continue
                    applyRemote(kind, key, payload["v"], c.ts, payload["a"] as? Map<*, *>)
                }
                cursor = res.cursor
                saveState(getState().copy(cursor = cursor))
                if (!res.more) break
                res = post(ExchangeRequest(keys.account, keys.auth, cursor, emptyList()))
            }
            i += BATCH
        } while (i < records.size)
        repository.applyRemoteReadMarks()
        copyEpisodePositionsIn()
    }

    // -----------------------------------------------------------------------------------------
    // Podcast episodes: the podcast player keeps where each was left by item id (PlaylistStore).
    // They travel as "video" records, keyed like the web app keys them, through VideoPositions.

    /** Episode positions saved here and newer than the synced copy go to [videoPositions]. */
    private suspend fun copyEpisodePositionsOut() {
        val stamped = playlistStore.stampedPositions().filterValues { it.third > 0 }
        if (stamped.isEmpty()) return
        for (chunk in stamped.keys.chunked(500)) {
            for (k in dao.getItemKeys(chunk)) {
                val (t, d, ts) = stamped[k.id] ?: continue
                val key = "${k.feedUrl}\n${k.guid}"
                val synced = videoPositions.get(key)
                if (synced == null || synced.ts < ts) videoPositions.applyRemote(key, t.toDouble(), d.toDouble(), ts)
            }
        }
    }

    /** Positions from other devices for episodes this device has go to the podcast player's store. */
    private suspend fun copyEpisodePositionsIn() {
        val known = dao.getKnown(EPISODES_COPIED_KEY)
        // The first time, every position synced so far (the web app has always sent episodes)
        val keys = if (known == null) videoPositions.all.value.keys else arrivedPositions.toSet()
        for (key in keys) {
            val pos = videoPositions.get(key) ?: continue
            val cut = key.indexOf('\n')
            if (cut <= 0) continue
            val id = dao.getEpisodeId(key.substring(0, cut), key.substring(cut + 1)) ?: continue
            playlistStore.applyRemotePosition(id, pos.t.toLong(), pos.d.toLong(), pos.ts)
        }
        if (known == null) dao.upsertKnown(listOf(Sync2Known(EPISODES_COPIED_KEY, "true", System.currentTimeMillis())))
        arrivedPositions.clear()
    }

    private suspend fun post(body: ExchangeRequest): ExchangeResponse =
        withContext(Dispatchers.IO) {
            postBlocking(body)
        }

    private fun postBlocking(body: ExchangeRequest): ExchangeResponse {
        val request =
            Request
                .Builder()
                .url("$SYNC2_SERVER/api/v2/sync")
                .post(requestAdapter.toJson(body).toRequestBody("application/json".toMediaType()))
                .build()
        okHttpClient.newCall(request).execute().use { res ->
            val text = res.body?.string() ?: ""
            if (!res.isSuccessful) throw IllegalStateException("Sync server: HTTP ${res.code} $text".take(300))
            return responseAdapter.fromJson(text) ?: throw IllegalStateException("Sync server: empty answer")
        }
    }

    // -----------------------------------------------------------------------------------------
    // Local changes

    private suspend fun collectChanges(now: Long): List<Pending> {
        val out = mutableListOf<Pending>()

        // Feeds: edits, new feeds, and unsubscribes (known but gone here)
        val feeds = repository.getFeedsOrderedByUrl()
        val knownFeeds = dao.getKnownWithPrefix("feed\n").associateBy { it.k.removePrefix("feed\n") }
        for (feed in feeds) {
            // Shared links are articles, not a feed to subscribe to
            if (isSharedFeed(feed.url)) continue
            val v = feedValue(feed)
            val kn = knownFeeds[feed.url.toString()]
            if (kn == null || parse(kn.v) != v) {
                out += Pending("feed", feed.url.toString(), v, maxOf(feed.whenModified.toEpochMilli().takeIf { it > 0 } ?: now, (kn?.ts ?: 0) + 1))
            }
        }
        val local = feeds.map { it.url.toString() }.toSet()
        for ((url, kn) in knownFeeds) {
            if (url !in local && !isDeleted(parse(kn.v))) out += Pending("feed", url, mapOf("deleted" to true), maxOf(now, kn.ts + 1))
        }

        // Articles whose read or saved state changed here
        for (d in dao.getItemDiffs()) {
            val key = "${d.feedUrl}\n${d.guid}"
            val read = d.readTime != null
            if (read != (d.readV == "true")) {
                out += Pending("read", key, read, maxOf(if (read) d.readTime!! else now, (d.readTs ?: 0) + 1))
            }
            if (d.bookmarked != (d.savedV == "true")) {
                val article =
                    if (d.bookmarked) {
                        mapOf("title" to d.title, "link" to d.link, "sortTime" to d.sortTime, "author" to d.author, "snippet" to d.snippet)
                    } else {
                        null
                    }
                out += Pending("saved", key, d.bookmarked, maxOf(now, (d.savedTs ?: 0) + 1), article)
            }
        }

        // Deleted articles, and deletions undone
        val hidden = dao.getHidden()
        val knownHidden = dao.getKnownWithPrefix("hidden\n").associateBy { it.k.removePrefix("hidden\n") }
        for (h in hidden) {
            val key = "${h.feedUrl}\n${h.guid}"
            val kn = knownHidden[key]
            if (kn?.v != "true") out += Pending("hidden", key, true, maxOf(h.ts, (kn?.ts ?: 0) + 1))
        }
        val hiddenHere = hidden.map { "${it.feedUrl}\n${it.guid}" }.toSet()
        for ((key, kn) in knownHidden) {
            if (kn.v == "true" && key !in hiddenHere) out += Pending("hidden", key, false, maxOf(now, kn.ts + 1))
        }

        // Playlists: new, edited (newest edit wins) and deleted ones
        out += collectPlaylists(now)

        // Where videos and episodes were left, saved here since they were last exchanged
        val knownVideos = dao.getKnownWithPrefix("video\n").associateBy { it.k.removePrefix("video\n") }
        for ((key, pos) in videoPositions.all.value) {
            val kn = knownVideos[key]
            val value = linkedMapOf<String, Any?>("t" to pos.t, "d" to pos.d)
            if (kn == null || pos.ts > kn.ts) {
                out += Pending("video", key, value, maxOf(pos.ts, (kn?.ts ?: 0) + 1))
            }
        }
        return out
    }

    private suspend fun itemIdOf(key: String): Long? {
        val cut = key.indexOf('\n')
        return if (cut > 0) dao.getItemId(key.substring(0, cut), key.substring(cut + 1)) else null
    }

    private suspend fun collectPlaylists(now: Long): List<Pending> {
        val out = mutableListOf<Pending>()
        val known = dao.getKnownWithPrefix("playlist\n").associateBy { it.k.removePrefix("playlist\n") }
        val before = playlistStore.playlists.value
        val adopted = SyncedPlaylist.adoptStarterIds(before, known.keys)
        if (adopted != before) {
            val renamed = before.zip(adopted).filter { (a, b) -> a.id != b.id }.associate { (a, b) -> a.id to b.id }
            playlistStore.replaceFromSync { list -> list.map { p -> renamed[p.id]?.let { p.copy(id = it) } ?: p } }
        }
        val lists = playlistStore.playlists.value
        val feeds = repository.getFeedsOrderedByUrl()
        val feedUrls = feeds.associate { it.id to it.url.toString() }
        val localFeedUrls = feedUrls.values.toSet()
        val itemKeys =
            lists
                .filterNot { it.smart }
                .flatMap { it.itemIds }
                .distinct()
                .chunked(500)
                .flatMap { dao.getItemKeys(it) }
                .associate { it.id to "${it.feedUrl}\n${it.guid}" }
        for (pl in lists) {
            val kn = known[pl.id]
            val last = kn?.let { parse(it.v) as? Map<*, *> }?.takeUnless { isDeleted(it) }?.let { SyncedPlaylist.fromMap(it) }
            val localKeys = pl.itemIds.mapNotNull { itemKeys[it] }.toSet()
            val unresolvedItems =
                last
                    ?.items
                    ?.withIndex()
                    ?.filter { (_, key) -> key !in localKeys && itemIdOf(key) == null }
                    .orEmpty()
            val v =
                SyncedPlaylist.of(
                    pl,
                    feedUrlOf = { feedUrls[it] },
                    itemKeyOf = { itemKeys[it] },
                    unresolvedFeeds = last?.feeds?.filter { it !in localFeedUrls }.orEmpty(),
                    unresolvedItems = unresolvedItems,
                )
            if (last != v) {
                val changedAt = pl.modifiedAt.takeIf { it > 0 } ?: pl.createdAt.takeIf { it > 0 } ?: 1L
                out += Pending("playlist", pl.id, v.toMap(), maxOf(changedAt, (kn?.ts ?: 0) + 1))
            }
        }
        val here = lists.map { it.id }.toSet()
        for ((id, kn) in known) {
            if (id !in here && !isDeleted(parse(kn.v))) out += Pending("playlist", id, mapOf("deleted" to true), maxOf(now, kn.ts + 1))
        }
        return out
    }

    // -----------------------------------------------------------------------------------------
    // Remote changes

    private suspend fun applyRemote(
        kind: String,
        key: String,
        v: Any?,
        ts: Long,
        article: Map<*, *>? = null,
    ) {
        val k = knownKey(kind, key)
        val kn = dao.getKnown(k)
        if (kn != null && (kn.ts > ts || (kn.ts == ts && parse(kn.v) == v))) return
        dao.upsertKnown(listOf(Sync2Known(k, canonical(v), ts)))
        val cut = key.indexOf('\n')
        val feedUrl = if (cut > 0) key.substring(0, cut) else key
        val guid = if (cut > 0) key.substring(cut + 1) else ""
        try {
            when (kind) {
                "feed" -> {
                    val url = URL(key)
                    val local = repository.getFeed(url)
                    if (isDeleted(v)) {
                        if (local != null) repository.deleteFeed(url)
                    } else if (v is Map<*, *>) {
                        val feed = applyFeedValue(v, local ?: Feed(url = url), ts)
                        repository.saveFeed(feed)
                        // Remember it as stored here, so fields an older device left out don't look like edits
                        dao.upsertKnown(listOf(Sync2Known(k, canonical(feedValue(feed)), ts)))
                    }
                }

                "read" -> {
                    val id = dao.getItemId(feedUrl, guid)
                    if (v == true) {
                        // Also covers articles not fetched yet (applied when they arrive)
                        repository.remoteMarkAsRead(feedUrl = URL(feedUrl), articleGuid = guid)
                    } else if (id != null) {
                        dao.setReadTime(id, null)
                    }
                }

                "saved" -> {
                    val id = dao.getItemId(feedUrl, guid)
                    if (id != null) {
                        dao.setBookmarked(id, v == true)
                    } else if (v == true && feedUrl == SHARED_FEED_URL_STRING && article != null) {
                        // A link shared on another device
                        val read = parse(dao.getKnown(knownKey("read", key))?.v ?: "null") == true
                        sharedLinks.addFromSync(
                            link = guid,
                            info =
                                LinkInfo(
                                    title = (article["title"] as? String)?.ifBlank { null } ?: siteOf(guid),
                                    snippet = article["snippet"] as? String ?: "",
                                    imageUrl = article["imageUrl"] as? String,
                                    author = article["author"] as? String,
                                ),
                            sortTime = (article["sortTime"] as? Number)?.toLong()?.let { Instant.ofEpochMilli(it) } ?: Instant.ofEpochMilli(ts),
                            read = read,
                        )
                    }
                }

                "hidden" -> {
                    if (v == true) {
                        val itemIds = listOfNotNull(dao.getItemId(feedUrl, guid))
                        dao.hide(listOf(HiddenItem(feedUrl, guid, ts)), itemIds)
                        // Deleted on another device: its downloads go too
                        offlineFiles.deleteDownloads(itemIds)
                    } else {
                        dao.deleteHidden(feedUrl, guid)
                    }
                }
                "video" -> {
                    if (v is Map<*, *>) {
                        val t = (v["t"] as? Number)?.toDouble()
                        if (t != null) {
                            videoPositions.applyRemote(key, t, (v["d"] as? Number)?.toDouble() ?: 0.0, ts)
                            arrivedPositions += key
                        }
                    }
                }

                "playlist" -> {
                    if (isDeleted(v)) {
                        playlistStore.replaceFromSync { list -> list.filterNot { it.id == key } }
                    } else if (v is Map<*, *>) {
                        val synced = SyncedPlaylist.fromMap(v)
                        val feedIds = repository.getFeedsOrderedByUrl().associate { it.url.toString() to it.id }
                        val itemIds = synced.items.associateWith { itemIdOf(it) }
                        val pl = synced.toPlaylist(key, ts, feedIdOf = { feedIds[it] }, itemIdOf = { itemIds[it] })
                        playlistStore.replaceFromSync { list -> if (list.any { it.id == key }) list.map { if (it.id == key) pl else it } else list + pl }
                        // Remember it as synced (with what this device doesn't have), so it isn't sent back
                        dao.upsertKnown(listOf(Sync2Known(k, canonical(synced.toMap()), ts)))
                    }
                }
                // layout and kinds from newer versions are only remembered
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to apply $kind", e)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Deleting articles

    /**
     * Deletes articles for good here; the next sync deletes them everywhere.
     * Returns what is needed to undo it with [unhideItems].
     */
    suspend fun hideItems(itemIds: List<Long>): DeletedArticles {
        val now = System.currentTimeMillis()
        val items = dao.getItemKeys(itemIds)
        val rows = dao.getFeedItems(itemIds)
        dao.hide(items.map { HiddenItem(it.feedUrl, it.guid, now) }, items.map { it.id })
        offlineFiles.deleteDownloads(items.map { it.id })
        // Undo brings the articles back without their downloads
        rows.forEach { it.offlineTime = null }
        return DeletedArticles(items, rows)
    }

    /** Undo for [hideItems]: brings the articles back here, and the next sync brings them back elsewhere. */
    suspend fun unhideItems(deleted: DeletedArticles) {
        dao.unhide(deleted.keys, deleted.rows)
    }

    // -----------------------------------------------------------------------------------------

    private fun payloadJson(p: Pending): String {
        val map = linkedMapOf<String, Any?>("k" to p.kind, "key" to p.key, "v" to p.v)
        if (p.a != null) map["a"] = p.a
        return anyAdapter.toJson(map)
    }

    private fun canonical(v: Any?): String = anyAdapter.toJson(v)

    private fun parse(json: String): Any? =
        try {
            anyAdapter.fromJson(json)
        } catch (e: Exception) {
            null
        }

    companion object {
        private const val LOG_TAG = "FEEDER_SYNC2"
        private const val STATE_KEY = "\u0000state"

        // Set once positions synced before this version were copied to the podcast player's store
        private const val EPISODES_COPIED_KEY = "\u0000episodes"
        private const val BATCH = 400

        fun knownKey(
            kind: String,
            key: String,
        ) = "$kind\n$key"

        /** True when a synced video value (numbers parse as doubles) says [t] of [d]. */
        fun sameVideoValue(
            v: Any?,
            t: Int,
            d: Int,
        ): Boolean {
            val m = v as? Map<*, *> ?: return false
            return (m["t"] as? Number)?.toDouble() == t.toDouble() && ((m["d"] as? Number)?.toDouble() ?: 0.0) == d.toDouble()
        }

        fun isDeleted(v: Any?): Boolean = v is Map<*, *> && v["deleted"] == true

        /** The synced fields of a feed, in the same order as the web app. */
        fun feedValue(f: Feed): Map<String, Any?> =
            linkedMapOf(
                "title" to f.title,
                "customTitle" to f.customTitle,
                "tag" to f.tag,
                "imageUrl" to f.imageUrl?.toString(),
                "fullTextByDefault" to f.fullTextByDefault,
                "openArticlesWith" to f.openArticlesWith,
                "alternateId" to f.alternateId,
                "fetchOgImages" to f.fetchOgImages,
                "blockRules" to f.blockRules,
                "allowRules" to f.allowRules,
            )

        fun applyFeedValue(
            v: Map<*, *>,
            base: Feed,
            ts: Long,
        ): Feed =
            base.copy(
                title = v["title"] as? String ?: base.title,
                customTitle = v["customTitle"] as? String ?: "",
                tag = v["tag"] as? String ?: "",
                imageUrl = (v["imageUrl"] as? String)?.let { runCatching { URL(it) }.getOrNull() },
                fullTextByDefault = v["fullTextByDefault"] as? Boolean ?: base.fullTextByDefault,
                openArticlesWith = v["openArticlesWith"] as? String ?: "",
                alternateId = v["alternateId"] as? Boolean ?: false,
                fetchOgImages = v["fetchOgImages"] as? Boolean ?: false,
                blockRules = v["blockRules"] as? String ?: "",
                allowRules = v["allowRules"] as? String ?: "",
                whenModified = Instant.ofEpochMilli(ts),
            )

        fun randomSyncCode(): String {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/** Articles deleted with [Sync2Client.hideItems], kept so Undo can bring them back as they were. */
class DeletedArticles(
    val keys: List<Sync2ItemKey>,
    val rows: List<FeedItem>,
)
