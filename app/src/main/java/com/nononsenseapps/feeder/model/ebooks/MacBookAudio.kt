package com.nononsenseapps.feeder.model.ebooks

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.core.content.getSystemService
import com.nononsenseapps.feeder.background.BackgroundJobId
import com.nononsenseapps.feeder.background.FeederJobService
import com.nononsenseapps.feeder.dropbox.DropboxClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

/** mac.json, written by the Feeder Mac app (web/src/lib/macBookAudio.ts). */
@Serializable
data class MacStatus(
    val seen: Long = 0,
    val voice: String = "",
    val working: MacWorking? = null,
    val books: List<MacBook> = emptyList(),
)

@Serializable
data class MacWorking(
    val book: String = "",
    val title: String = "",
    val section: Int = -1,
    val progress: Float = 0f,
)

@Serializable
data class MacBook(
    val id: String = "",
    val title: String = "",
    val current: Int = -1,
    val ready: List<Int> = emptyList(),
    val planned: List<Int> = emptyList(),
    val bytes: Long = 0,
)

@Serializable
private data class PhonePrefs(
    val ahead: Int,
    val at: Long,
)

/** What the Mac has for the book being read, for the Offline audio sheet. */
@Immutable
data class MacAudioState(
    val status: MacStatus? = null,
    val bookId: String = "",
    /** Chapters complete in the book's Dropbox folder */
    val inDropbox: Set<Int> = emptySet(),
)

/** The Mac counts as on when it was seen this recently. */
val MAC_ACTIVE_MILLIS: Long = TimeUnit.MINUTES.toMillis(15)

/** Chapters with both their audio and meta in a book's Dropbox folder. */
internal fun macReadyChapters(names: Collection<String>): Set<Int> {
    val set = names.toSet()
    return set
        .mapNotNull { Regex("""^s(\d+)\.json$""").find(it)?.groupValues?.get(1) }
        .filter { "s$it.m4a" in set }
        .map { it.toInt() }
        .toSet()
}

/** True when the Mac was seen within [MAC_ACTIVE_MILLIS] of [now]. */
internal fun MacStatus?.isActive(now: Long): Boolean = this != null && seen > 0 && now - seen < MAC_ACTIVE_MILLIS

/**
 * True when the phone should leave chapter [index] of [bookId] to the Mac instead of making it:
 * it's in Dropbox already (downloaded on Wi-Fi), or the Mac is on and plans to make it.
 */
internal fun macCovers(
    status: MacStatus?,
    inDropbox: Set<Int>,
    bookId: String,
    index: Int,
    now: Long,
): Boolean {
    if (index in inDropbox) return true
    if (!status.isActive(now)) return false
    return status!!.books.firstOrNull { it.id == bookId }?.planned?.contains(index) == true
}

/**
 * Ebook chapter audio the Feeder Mac app made with the natural voice and put in Dropbox
 * (/Ebooks/.feeder-audio/<book>/s<N>.m4a + s<N>.json, plus mac.json). The phone downloads it on
 * Wi-Fi into its own book audio ([BookAudio.installFromMac]), where it replaces what the phone
 * made itself. Chapters the Mac is about to make are left to it; when the Mac hasn't been seen
 * for [MAC_ACTIVE_MILLIS], the phone makes them with its own voice as before.
 */
class MacBookAudio internal constructor(
    private val context: Context,
    private val audio: BookAudio,
) {
    private val dropbox = DropboxClient.get(context)
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(MacAudioState())
    val state: StateFlow<MacAudioState> = _state.asStateFlow()
    private var checkedAt = 0L
    private var checkedBook = ""
    private var publishedAhead = -1

    private val linked get() = dropbox.link.value != null

    /** Looks again at what the Mac has for [bookId] (small files: any network), at most once a minute unless [force]. */
    fun refresh(
        bookId: String,
        force: Boolean = false,
    ) {
        scope.launch { runCatching { check(bookId, force) }.onFailure { Log.w(LOG_TAG, "Could not check Mac audio", it) } }
    }

    internal suspend fun check(
        bookId: String,
        force: Boolean,
    ) {
        if (!linked) return
        val now = System.currentTimeMillis()
        if (!force && bookId == checkedBook && now - checkedAt < 60_000) return
        checkedAt = now
        checkedBook = bookId
        val status = dropbox.readText("$FOLDER/mac.json")?.let { runCatching { json.decodeFromString(MacStatus.serializer(), it) }.getOrNull() }
        val names = dropbox.listFolder("$FOLDER/$bookId").map { it.name }
        _state.value = MacAudioState(status, bookId, macReadyChapters(names))
        audio.refresh()
        if (status != null) schedule()
    }

    /** True when chapter [index] is left to the Mac. */
    fun covers(
        bookId: String,
        index: Int,
    ): Boolean {
        val s = _state.value
        if (s.bookId != bookId) return false
        return macCovers(s.status, s.inDropbox, bookId, index, System.currentTimeMillis())
    }

    /** True when chapter [index] is in Dropbox and waits for Wi-Fi to be downloaded. */
    fun inDropbox(
        bookId: String,
        index: Int,
    ): Boolean = _state.value.let { it.bookId == bookId && index in it.inDropbox }

    private fun unmetered(): Boolean {
        val cm: ConnectivityManager = context.getSystemService() ?: return false
        return cm.activeNetwork != null && !cm.isActiveNetworkMetered
    }

    /** Downloads the Mac's chapters in [window] not here yet. Only on Wi-Fi (unmetered). */
    internal suspend fun download(
        bookId: String,
        window: List<Int>,
    ) {
        if (!linked || !unmetered()) return
        for (i in window) {
            if (!inDropbox(bookId, i) || audio.sourceOf(bookId, i) == SOURCE_MAC) continue
            val staging = audio.stagingFile(bookId, i)
            try {
                dropbox.download("$FOLDER/$bookId/s$i.m4a", staging)
                val meta = dropbox.readText("$FOLDER/$bookId/s$i.json") ?: continue
                audio.installFromMac(bookId, i, staging, meta)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Could not download Mac audio for chapter $i", e)
            } finally {
                staging.delete()
            }
        }
    }

    /** Tells the Mac the phone's Prepare ahead setting, so it keeps the same chapters ready. */
    fun publishAhead(ahead: Int) {
        if (!linked || ahead == publishedAhead) return
        scope.launch {
            runCatching {
                dropbox.putSmall("$FOLDER/phone.json", json.encodeToString(PhonePrefs.serializer(), PhonePrefs(ahead, System.currentTimeMillis())).toByteArray())
                publishedAhead = ahead
            }.onFailure { Log.w(LOG_TAG, "Could not tell the Mac Prepare ahead", it) }
        }
    }

    /** Checks again in a few minutes and downloads on Wi-Fi ([BookAudioMacJob]). */
    fun schedule(delayMillis: Long = 0) {
        if (!linked) return
        val scheduler: JobScheduler = context.getSystemService() ?: return
        val job =
            JobInfo
                .Builder(BackgroundJobId.BOOK_AUDIO_MAC.jobId, ComponentName(context, FeederJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setMinimumLatency(delayMillis)
                .build()
        runCatching { scheduler.schedule(job) }.onFailure { Log.w(LOG_TAG, "Could not schedule Mac audio", it) }
    }

    /** One run of [BookAudioMacJob]: check, download, and come back while the Mac is still making chapters. */
    internal suspend fun run() {
        val book = audio.activeBookId() ?: return
        check(book, force = true)
        val window = audio.windowWithCurrent(book)
        download(book, window)
        audio.refresh()
        audio.schedule()
        val s = _state.value
        val missing = window.any { audio.sourceOf(book, it) != SOURCE_MAC }
        if (missing && s.status.isActive(System.currentTimeMillis())) schedule(TimeUnit.MINUTES.toMillis(3))
    }

    companion object {
        private const val LOG_TAG = "FEEDER_MAC_AUDIO"
        const val FOLDER = "/Ebooks/.feeder-audio"
        const val SOURCE_MAC = "mac"

        /** Where a chapter is downloaded before it replaces the phone's own. */
        internal fun staging(
            dir: File,
            index: Int,
        ) = File(dir, "s$index.mac.m4a")
    }
}
