package com.nononsenseapps.feeder.ui.compose.discover

import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.viewModelScope
import com.nononsenseapps.feeder.FeederApplication
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.background.runOnceRssSync
import com.nononsenseapps.feeder.base.DIAwareViewModel
import com.nononsenseapps.feeder.db.room.Feed
import com.nononsenseapps.feeder.db.room.FeedDao
import com.nononsenseapps.feeder.model.FeedParser
import com.nononsenseapps.feeder.model.USER_AGENT_STRING
import com.nononsenseapps.feeder.model.UserAgentInterceptor
import com.nononsenseapps.feeder.util.sloppyLinkToStrictURLOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.kodein.di.DI
import org.kodein.di.instance
import java.net.URL
import java.time.Instant

data class Added(
    val feedId: Long,
    val tag: String,
)

data class DiscoverState(
    val tab: DiscoverKind = DiscoverKind.TEXT,
    val query: String = "",
    val submitted: String = "",
    val topic: String = "",
    val genre: Int = 0,
    val country: String = "us",
    val results: List<DiscoverResult> = emptyList(),
    val loading: Boolean = false,
    val note: String = "",
    val added: Map<String, Added> = emptyMap(),
    val adding: Set<String> = emptySet(),
    val failed: Map<String, String> = emptyMap(),
)

class DiscoverViewModel(
    di: DI,
) : DIAwareViewModel(di) {
    private val repository: Repository by instance()
    private val feedDao: FeedDao by instance()
    private val feedParser: FeedParser by instance()
    private val okHttpClient: OkHttpClient by instance()
    private val prefs: SharedPreferences by instance()

    /** Feeder's own client, but YouTube's search page only lists channels when asked as a browser. */
    private val client: OkHttpClient by lazy {
        okHttpClient
            .newBuilder()
            .apply { networkInterceptors().removeAll { it === UserAgentInterceptor } }
            .addNetworkInterceptor { chain ->
                val req = chain.request()
                val ua = if (req.url.host.endsWith("youtube.com")) BROWSER_USER_AGENT else USER_AGENT_STRING
                chain.proceed(req.newBuilder().header("User-Agent", ua).build())
            }.build()
    }

    val topics: List<CuratedTopic> by lazy {
        try {
            getApplication<FeederApplication>().assets.open("discover-feeds.json").bufferedReader().use { parseCurated(it.readText()) }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "No bundled topics", e)
            emptyList()
        }
    }

    private val _state =
        MutableStateFlow(
            DiscoverState(
                tab = runCatching { DiscoverKind.valueOf(prefs.getString(PREF_TAB, null) ?: "") }.getOrDefault(DiscoverKind.TEXT),
                country = prefs.getString(PREF_COUNTRY, null) ?: defaultCountry(),
            ),
        )
    val state: StateFlow<DiscoverState> = _state.asStateFlow()

    val followed: StateFlow<List<String>> =
        feedDao.loadHomeFeeds().map { rows -> rows.map { it.url } }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val folders: StateFlow<List<String>> =
        repository.allTags.map { tags -> tags.filter { it.isNotBlank() }.sortedWith(String.CASE_INSENSITIVE_ORDER) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val lastFolder: String get() = prefs.getString(PREF_LAST_FOLDER, "") ?: ""

    private var job: Job? = null
    private val cache = HashMap<String, List<DiscoverResult>>()

    init {
        _state.update { it.copy(topic = topics.firstOrNull()?.name ?: "") }
        load()
    }

    fun setTab(tab: DiscoverKind) {
        prefs.edit { putString(PREF_TAB, tab.name) }
        _state.update { it.copy(tab = tab, query = "", submitted = "") }
        load()
    }

    fun setQuery(q: String) {
        _state.update { it.copy(query = q, submitted = if (q.isBlank()) "" else it.submitted) }
        if (q.isBlank()) load()
    }

    fun search(q: String = _state.value.query) {
        _state.update { it.copy(query = q, submitted = q.trim()) }
        load()
    }

    fun setTopic(t: String) {
        _state.update { it.copy(topic = t) }
        load()
    }

    fun setGenre(g: Int) {
        _state.update { it.copy(genre = g) }
        load()
    }

    fun setCountry(c: String) {
        prefs.edit { putString(PREF_COUNTRY, c) }
        _state.update { it.copy(country = c) }
        load()
    }

    private fun load() {
        job?.cancel()
        val s = _state.value
        val q = s.submitted.trim()
        fun done(
            list: List<DiscoverResult>,
            note: String = "",
        ) = _state.update { it.copy(results = list, note = note, loading = false) }

        if (q.isNotEmpty() && looksLikeUrl(q)) {
            val url = if (q.startsWith("http", ignoreCase = true)) q else "https://$q"
            return done(listOf(DiscoverResult(s.tab, url.replace(Regex("^https?://(www\\.)?"), ""), url, site = url, meta = "Feeder looks for the feed on this page", needsFinder = true)))
        }
        if (s.tab == DiscoverKind.YOUTUBE && q.startsWith("@") && !q.contains(' ')) {
            val url = "https://www.youtube.com/$q"
            return done(listOf(DiscoverResult(DiscoverKind.YOUTUBE, q, url, site = url, meta = "YouTube channel", needsFinder = true)))
        }
        if (s.tab == DiscoverKind.TEXT && q.isEmpty()) return done(topics.firstOrNull { it.name == s.topic }?.feeds ?: emptyList())
        if (s.tab == DiscoverKind.YOUTUBE && q.isEmpty()) return done(emptyList())

        _state.update { it.copy(loading = true, results = emptyList(), note = "") }
        job =
            viewModelScope.launch {
                try {
                    when (s.tab) {
                        DiscoverKind.TEXT -> {
                            val local = searchCurated(topics, q)
                            val remote =
                                try {
                                    searchFeedly(q)
                                } catch (e: Exception) {
                                    Log.w(LOG_TAG, "Feedly", e)
                                    null
                                }
                            val seen = local.map { it.feedUrl }.toSet()
                            val list = local + (remote ?: emptyList()).filter { it.feedUrl !in seen } + googleNewsResult(q)
                            done(
                                list,
                                when {
                                    remote == null -> "Feedly search did not answer. Showing bundled feeds and Google News."
                                    local.isEmpty() && remote.isEmpty() -> "No feeds found. Try the site’s name (like “livemint”) or paste its address. Google News can follow any topic:"
                                    else -> ""
                                },
                            )
                        }
                        DiscoverKind.PODCAST -> {
                            val list = if (q.isNotEmpty()) cachedGet("itunes:${s.country}:$q") { parseItunes(get(itunesSearchUrl(q, s.country))) } else topPodcasts(s.genre, s.country)
                            done(list, if (list.isEmpty()) "No podcasts found." else "")
                        }
                        DiscoverKind.YOUTUBE -> {
                            val list =
                                cachedGet("yt:$q") {
                                    parseYouTubeChannels(get(youtubeSearchUrl(q))).ifEmpty { parseYouTubeChannels(get(youtubeSearchUrl(q, "&hl=en"))) }
                                }
                            done(list, if (list.isEmpty()) "No channels found. Try another name, or paste the channel’s address or @handle." else "")
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Discover", e)
                    done(emptyList(), "That search did not work (${e.message ?: e.javaClass.simpleName}).")
                }
            }
    }

    private suspend fun searchFeedly(q: String): List<DiscoverResult> {
        for (query in feedlyQueries(q)) {
            val found = cachedGet("feedly:$query") { parseFeedly(get(feedlySearchUrl(query))) }
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    private suspend fun topPodcasts(
        genre: Int,
        country: String,
    ): List<DiscoverResult> =
        cachedGet("chart:$country:$genre") {
            val ids = parseChartIds(get(chartUrl(genre, country)))
            if (ids.isEmpty()) {
                emptyList()
            } else {
                val found = parseItunesWithIds(get(lookupUrl(ids, country))).toMap()
                ids.mapNotNull { found[it] }
            }
        }

    private suspend fun cachedGet(
        key: String,
        load: suspend () -> List<DiscoverResult>,
    ): List<DiscoverResult> {
        cache[key]?.let { return it }
        val v = load()
        if (v.isNotEmpty()) cache[key] = v
        return v
    }

    private suspend fun get(url: String): String =
        withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url(url).header("Accept-Language", "en").build()).execute().use { res ->
                if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
                res.body.string()
            }
        }

    /** Subscribes to a result in a folder ("" = none) and fetches it now. */
    fun add(
        r: DiscoverResult,
        tag: String,
    ) {
        if (r.feedUrl in _state.value.adding) return
        _state.update { it.copy(adding = it.adding + r.feedUrl, failed = it.failed - r.feedUrl) }
        viewModelScope.launch {
            try {
                val feedUrl = if (r.needsFinder) findFeed(r.feedUrl) else r.feedUrl
                val url = URL(feedUrl)
                val existing = repository.getFeed(url)
                val feedId =
                    existing?.id ?: repository.saveFeed(
                        Feed(
                            url = url,
                            title = if (r.needsFinder) "" else r.title,
                            tag = tag,
                            imageUrl = r.image?.let { sloppyLinkToStrictURLOrNull(it) },
                            whenModified = Instant.now(),
                        ),
                    )
                prefs.edit { putString(PREF_LAST_FOLDER, tag) }
                runOnceRssSync(di = di, feedId = feedId, triggeredByUser = false)
                _state.update { it.copy(added = it.added + (r.feedUrl to Added(feedId, tag)), adding = it.adding - r.feedUrl) }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Add ${r.feedUrl}", e)
                _state.update { it.copy(adding = it.adding - r.feedUrl, failed = it.failed + (r.feedUrl to (e.message ?: "Could not add"))) }
            }
        }
    }

    /** A website or channel page: its first feed link, or the address itself when it is a feed. */
    private suspend fun findFeed(page: String): String {
        val url = URL(page)
        val meta = feedParser.getSiteMetaData(url)
        meta.getOrNull()?.alternateFeedLinks?.firstOrNull()?.let { return it.link.toString() }
        feedParser.parseFeedPreviewUrl(url).getOrNull()?.let { return it.feed_url ?: page }
        throw IllegalStateException("No feed found on that page")
    }

    companion object {
        private const val LOG_TAG = "FEEDER_DISCOVER"
        private const val PREF_TAB = "discover_tab"
        private const val PREF_COUNTRY = "discover_country"
        private const val PREF_LAST_FOLDER = "discover_last_folder"
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }
}
