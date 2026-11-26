package com.nononsenseapps.feeder.model.youtube

import com.nononsenseapps.feeder.ApplicationCoroutineScope
import com.nononsenseapps.feeder.db.room.YouTubeVideoMeta
import com.nononsenseapps.feeder.db.room.YouTubeVideoMetaDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import java.util.concurrent.ConcurrentHashMap

/**
 * Fills in what channel feeds leave out (length, views, Short or not) for the YouTube tab's rows,
 * like Flow's SubscriptionDurationEnricher: a few lookups at a time, only for rows asked for, kept
 * in the database so each video is looked up once.
 */
class YouTubeMetaStore(
    override val di: DI,
) : DIAware {
    private val dao: YouTubeVideoMetaDao by instance()
    private val innerTube: InnerTube by instance()
    private val scope: ApplicationCoroutineScope by instance()

    val meta: StateFlow<Map<String, YouTubeVideoMeta>> by lazy {
        dao
            .all()
            .map { rows -> rows.associateBy { it.videoId } }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())
    }

    // Tried in this run of the app: a failed lookup waits for the next start
    private val attempted = ConcurrentHashMap.newKeySet<String>()
    private val wanted = LinkedHashSet<String>()
    private var job: Job? = null

    /** Looks up the videos (id to link) not known yet, newest asked first. */
    @Synchronized
    fun want(videos: List<Pair<String, String?>>) {
        val known = meta.value
        val links = videos.toMap()
        videos
            .map { it.first }
            .filter { it !in known && it !in attempted }
            .forEach { wanted += it }
        pendingLinks.putAll(links.filterValues { it != null }.mapValues { it.value!! })
        if (wanted.isNotEmpty() && job?.isActive != true) {
            job = scope.launch(Dispatchers.IO) { drain() }
        }
    }

    private val pendingLinks = ConcurrentHashMap<String, String>()

    @Synchronized
    private fun take(n: Int): List<String> {
        val batch = wanted.take(n)
        wanted.removeAll(batch.toSet())
        attempted.addAll(batch)
        return batch
    }

    private suspend fun drain() {
        while (true) {
            val batch = take(BATCH)
            if (batch.isEmpty()) return
            val found =
                supervisorScope {
                    batch
                        .map { id ->
                            async {
                                runCatching { lookup(id, pendingLinks.remove(id)) }.getOrNull()
                            }
                        }.awaitAll()
                        .filterNotNull()
                }
            if (found.isNotEmpty()) dao.upsert(found)
        }
    }

    private suspend fun lookup(
        id: String,
        link: String?,
    ): YouTubeVideoMeta? {
        val m = innerTube.player(id) ?: return null
        // Shorts are at most 3 minutes; the feed links some to /shorts/ already
        val short = link?.contains("/shorts/") == true || (m.duration in 1..180 && !m.isLive && innerTube.isShort(id))
        return YouTubeVideoMeta(
            videoId = id,
            duration = m.duration,
            views = m.views,
            likes = m.likes,
            isShort = short,
            isLive = m.isLive,
            checkedAt = System.currentTimeMillis(),
        )
    }

    companion object {
        private const val BATCH = 3
    }
}
