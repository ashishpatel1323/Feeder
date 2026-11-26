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
import com.nononsenseapps.feeder.dropbox.DropboxClient
import com.nononsenseapps.feeder.dropbox.DropboxLink
import com.nononsenseapps.feeder.model.audiobooks.AudioPos
import com.nononsenseapps.feeder.model.audiobooks.AudioPositions
import com.nononsenseapps.feeder.model.ebooks.BookPositions
import com.nononsenseapps.feeder.model.history.HistoryEvent
import com.nononsenseapps.feeder.model.history.ItemHistory
import com.nononsenseapps.feeder.model.highlights.Highlight
import com.nononsenseapps.feeder.model.highlights.HighlightStore
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
 *   highlight (key: highlight id) [highlightValue], or {"deleted": true} after deleting it
 *   bookpos (key: ebook id)       { cfi, f }: where an ebook was left (newest wins)
 *   audiopos (key: audiobook id) { i, h, ms, f }: where an audiobook was left (newest wins):
 *     file index and content hash, ms into that file, 0–1 through the book
 *   dropbox "main"                [DropboxLink], or {"deleted": true} after signing out
 *   history (key: event id)       [HistoryEvent.toValue]: one play, pause, open or close. Never
 *     changes, so events from every device add up; not kept in sync2_known ([HistoryEvent.synced])
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
    private val highlightStore: HighlightStore by instance()
    private val bookPositions: BookPositions by instance()
    private val audioPositions: AudioPositions by instance()
    private val history: ItemHistory by instance()
    private val app: android.app.Application by instance()
    private val thisDevice by lazy { ThisDevice.get(app) }

    // Synced positions whose episode isn't on this phone yet (see copyWaitingEpisodePositionsIn)
    private val waitingPositions = mutableSetOf<String>()
    private val dropbox: DropboxClient by instance()
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
        history.markAllUnsynced()
        configured = true
    }

    /** Stops syncing on this device. Feeds and articles stay; other devices are not touched. */
    suspend fun leave() {
        repository.replaceWithDefaultSyncRemote()
        resetKnown(Sync2State())
        history.markAllUnsynced()
        configured = false
    }

    /** Sends everything this device has again and pulls everything. */
    suspend fun forceSync() {
        mutex.withLock {
            // Unsubscribes are sent again with the time they happened, so a feed subscribed to again
            // on another device since then stays
            val keep =
                (dao.getKnownWithPrefix("feed\n").filter { isDeleted(parse(it.v)) } + dao.getKnownWithPrefix("hidden\n"))
                    .map { if (it.k.startsWith("feed\n")) Sync2Known(it.k, PLACEHOLDER, it.ts) else Sync2Known(it.k, "false", 0) }
            resetKnown(getState().copy(cursor = 0))
            dao.upsertKnown(keep)
            history.markAllUnsynced()
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
            repository.getRemotelySeenFeeds().map { Sync2Known(knownKey("feed", it.toString()), PLACEHOLDER, 0) },
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
            val sent = pending.subList(i, minOf(i + BATCH, pending.size))
            dao.upsertKnown(sent.filter { it.kind != HISTORY }.map { p -> Sync2Known(knownKey(p.kind, p.key), canonical(p.v), p.ts) })
            history.markSynced(sent.filter { it.kind == HISTORY }.map { it.key })
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
                val by = playlistStore.positionDevices.value[k.id] ?: thisDevice.stamp()
                if (synced == null || synced.ts < ts) videoPositions.applyRemote(key, t.toDouble(), d.toDouble(), ts, by)
            }
        }
    }

    /**
     * After feeds were fetched: positions that arrived before their episode was here (a feed new on
     * this phone, a new episode) go to the podcast player's store now. Kept ones are skipped there.
     */
    suspend fun copyWaitingEpisodePositionsIn() {
        val keys = synchronized(waitingPositions) { waitingPositions.toSet().also { waitingPositions.clear() } }
        if (keys.isNotEmpty()) copyEpisodePositions(keys)
    }

    /** Positions from other devices for episodes this device has go to the podcast player's store. */
    private suspend fun copyEpisodePositionsIn() {
        val known = dao.getKnown(EPISODES_COPIED_KEY)
        // The first time, every position synced so far (the web app has always sent episodes)
        val keys = if (known == null) videoPositions.all.value.keys else arrivedPositions.toSet()
        copyEpisodePositions(keys)
        if (known == null) dao.upsertKnown(listOf(Sync2Known(EPISODES_COPIED_KEY, "true", System.currentTimeMillis())))
        arrivedPositions.clear()
    }

    private suspend fun copyEpisodePositions(keys: Set<String>) {
        for (key in keys) {
            val pos = videoPositions.get(key) ?: continue
            val cut = key.indexOf('\n')
            if (cut <= 0) continue
            val id = dao.getEpisodeId(key.substring(0, cut), key.substring(cut + 1))
            if (id == null) {
                synchronized(waitingPositions) { if (waitingPositions.size < 500) waitingPositions += key }
                continue
            }
            playlistStore.applyRemotePosition(id, pos.t.toLong(), pos.d.toLong(), pos.ts, pos.by, thisDevice.id)
        }
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
            val knV = kn?.let { parse(it.v) }
            if (kn == null || knV != v) {
                val ts =
                    if (kn == null || isPlaceholder(knV)) {
                        FIRST_CONTACT_TS
                    } else {
                        maxOf(feed.whenModified.toEpochMilli().takeIf { it > 0 } ?: now, kn.ts + 1)
                    }
                out += Pending("feed", feed.url.toString(), v, ts)
            }
        }
        val local = feeds.map { it.url.toString() }.toSet()
        for ((url, kn) in knownFeeds) {
            val knV = parse(kn.v)
            if (url !in local && !isDeleted(knV)) {
                out += Pending("feed", url, mapOf("deleted" to true), if (isPlaceholder(knV)) maxOf(kn.ts, 1) else maxOf(now, kn.ts + 1))
            }
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
                // Saved before this device ever exchanged it (a restored backup): other devices' state wins
                val ts = d.savedTs?.let { maxOf(now, it + 1) } ?: FIRST_CONTACT_TS
                out += Pending("saved", key, d.bookmarked, ts, article)
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

        // Highlights: new, recolored or noted (newest edit wins) and deleted ones
        val knownHl = dao.getKnownWithPrefix("highlight\n").associateBy { it.k.removePrefix("highlight\n") }
        val highlights = highlightStore.highlights.value
        for (h in highlights) {
            val kn = knownHl[h.id]
            if (kn == null || h.modifiedAt > kn.ts) {
                out += Pending("highlight", h.id, highlightValue(h), maxOf(h.modifiedAt, (kn?.ts ?: 0) + 1, 1L))
            }
        }
        val localHl = highlights.map { it.id }.toSet()
        for ((id, kn) in knownHl) {
            if (id !in localHl && !isDeleted(parse(kn.v))) out += Pending("highlight", id, mapOf("deleted" to true), maxOf(now, kn.ts + 1))
        }

        // Where videos and episodes were left, saved here since they were last exchanged
        val knownVideos = dao.getKnownWithPrefix("video\n").associateBy { it.k.removePrefix("video\n") }
        for ((key, pos) in videoPositions.all.value) {
            val kn = knownVideos[key]
            val value = linkedMapOf<String, Any?>("t" to pos.t, "d" to pos.d).putStamp(pos.by)
            if (kn == null || pos.ts > kn.ts) {
                out += Pending("video", key, value, maxOf(pos.ts, (kn?.ts ?: 0) + 1))
            }
        }

        // Where ebooks were left, saved here since they were last exchanged
        val knownBooks = dao.getKnownWithPrefix("bookpos\n").associateBy { it.k.removePrefix("bookpos\n") }
        for ((id, pos) in bookPositions.all.value) {
            val kn = knownBooks[id]
            if (kn == null || pos.ts > kn.ts) {
                out += Pending("bookpos", id, linkedMapOf<String, Any?>("cfi" to pos.cfi, "f" to pos.f).apply { if (pos.n > 0) putAll(listOf("p" to pos.p, "n" to pos.n)) }.putStamp(pos.by), maxOf(pos.ts, (kn?.ts ?: 0) + 1))
            }
        }

        // Where audiobooks were left, saved here since they were last exchanged
        val knownAudio = dao.getKnownWithPrefix("audiopos\n").associateBy { it.k.removePrefix("audiopos\n") }
        for ((id, pos) in audioPositions.all.value) {
            val kn = knownAudio[id]
            if (kn == null || pos.ts > kn.ts) {
                val value = linkedMapOf<String, Any?>("i" to pos.i, "h" to pos.h, "ms" to pos.ms, "f" to pos.f).putStamp(pos.by)
                out += Pending("audiopos", id, value, maxOf(pos.ts, (kn?.ts ?: 0) + 1))
            }
        }

        // History events noted here and not sent yet
        for (e in history.unsynced()) out += Pending(HISTORY, e.id, e.toValue(), maxOf(1L, e.at))

        // The Dropbox sign-in (signing in or out on this phone)
        val link = dropbox.link.value
        val linkValue: Any? = link?.let { dropboxValue(it) } ?: mapOf("deleted" to true)
        val kd = dao.getKnown(knownKey("dropbox", DROPBOX_KEY))
        if (if (kd == null) link != null else parse(kd.v) != parse(canonical(linkValue))) {
            out += Pending("dropbox", DROPBOX_KEY, linkValue, maxOf(dropbox.changedAt.takeIf { it > 0 } ?: now, (kd?.ts ?: 0) + 1))
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
        if (kind == HISTORY) {
            HistoryEvent.fromValue(key, v, ts)?.let { history.applyRemote(listOf(it)) }
            return
        }
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
                            videoPositions.applyRemote(key, t, (v["d"] as? Number)?.toDouble() ?: 0.0, ts, DeviceStamp.fromMap(v))
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

                "highlight" -> {
                    if (isDeleted(v)) {
                        highlightStore.deleteFromSync(key)
                    } else if (v is Map<*, *>) {
                        highlightFromValue(key, v, ts)?.let { highlightStore.putFromSync(it) }
                    }
                }

                "bookpos" -> {
                    if (v is Map<*, *>) {
                        val cfi = v["cfi"] as? String
                        if (!cfi.isNullOrBlank()) bookPositions.applyRemote(key, cfi, (v["f"] as? Number)?.toDouble() ?: 0.0, ts, DeviceStamp.fromMap(v), (v["p"] as? Number)?.toInt() ?: 0, (v["n"] as? Number)?.toInt() ?: 0)
                    }
                }

                "audiopos" -> {
                    val ms = (v as? Map<*, *>)?.let { (it["ms"] as? Number)?.toLong() }
                    if (v is Map<*, *> && ms != null) {
                        val by = DeviceStamp.fromMap(v)
                        val i = (v["i"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0
                        val f = (v["f"] as? Number)?.toDouble()?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
                        audioPositions.applyRemote(key, AudioPos(i, v["h"] as? String ?: "", ms.coerceAtLeast(0), f, ts, by.id, by.name))
                    }
                }

                "dropbox" -> {
                    if (key == DROPBOX_KEY) {
                        if (isDeleted(v) || v == null) {
                            dropbox.applyFromSync(null, ts)
                        } else if (v is Map<*, *>) {
                            val appKey = v["appKey"] as? String
                            val refresh = v["refreshToken"] as? String
                            if (!appKey.isNullOrBlank() && !refresh.isNullOrBlank()) {
                                dropbox.applyFromSync(DropboxLink(appKey, refresh, v["name"] as? String ?: "", v["email"] as? String ?: ""), ts)
                            }
                        }
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

        /**
         * The time sent for a feed or Saved mark this device has never exchanged: after a reinstall
         * restored an old backup, an OPML import, joining, or Force sync. It is older than anything
         * another device synced, so their value wins and this one only fills in what the server lacks.
         * Its clock can't tell an old copy from a new edit (OPML imports and Feeder News have no edit
         * time, and Full article by default stamped every feed with the day it was turned on).
         */
        const val FIRST_CONTACT_TS = 1L

        /** Known value of a record seen but never exchanged (moving over, Force sync). */
        private const val PLACEHOLDER = """{"known":true}"""

        fun isPlaceholder(v: Any?): Boolean = v is Map<*, *> && v["known"] == true

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

        /** A highlight as synced, in the same field order as the web app's HighlightValue. */
        fun highlightValue(h: Highlight): Map<String, Any?> =
            linkedMapOf(
                "item" to h.itemKey,
                "title" to h.itemTitle,
                "link" to h.itemLink,
                "feed" to h.feedTitle,
                "image" to h.imageUrl,
                "text" to h.text,
                "start" to h.start,
                "prefix" to h.prefix,
                "color" to h.color,
                "note" to h.note,
                "createdAt" to h.createdAt,
            ).apply { h.cfi?.takeIf { it.isNotBlank() }?.let { put("cfi", it) } }

        const val DROPBOX_KEY = "main"
        const val HISTORY = "history"

        /** The Dropbox sign-in as synced, in the same field order as the web app's DropboxLink. */
        fun dropboxValue(l: DropboxLink): Map<String, Any?> =
            linkedMapOf("appKey" to l.appKey, "refreshToken" to l.refreshToken, "name" to l.name, "email" to l.email)

        /** A highlight from another device; null when it has no article or text. */
        fun highlightFromValue(
            id: String,
            v: Map<*, *>,
            ts: Long,
        ): Highlight? {
            val item = v["item"] as? String ?: return null
            val text = v["text"] as? String ?: return null
            if (item.isBlank() || text.isBlank()) return null
            return Highlight(
                id = id,
                itemKey = item,
                itemTitle = v["title"] as? String ?: "",
                itemLink = v["link"] as? String,
                feedTitle = v["feed"] as? String ?: "",
                imageUrl = v["image"] as? String,
                text = text,
                start = (v["start"] as? Number)?.toInt() ?: 0,
                prefix = v["prefix"] as? String ?: "",
                color =
                    com.nononsenseapps.feeder.model.highlights.HighlightColor
                        .of(v["color"] as? String)
                        .id,
                note = v["note"] as? String ?: "",
                createdAt = (v["createdAt"] as? Number)?.toLong() ?: ts,
                modifiedAt = ts,
                cfi = (v["cfi"] as? String)?.takeIf { it.isNotBlank() },
            )
        }

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
                // Only when on, so feeds that aren't music keep the value older versions send
            ).apply { if (f.youtubeMusic) put("music", true) }

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
                youtubeMusic = v["music"] as? Boolean ?: false,
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
