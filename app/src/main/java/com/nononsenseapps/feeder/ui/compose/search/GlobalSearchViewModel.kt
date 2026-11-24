package com.nononsenseapps.feeder.ui.compose.search

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.viewModelScope
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.base.DIAwareViewModel
import com.nononsenseapps.feeder.db.room.FeedItemDao
import com.nononsenseapps.feeder.db.room.PlaylistCandidate
import com.nononsenseapps.feeder.model.PodcastPlayerStateHolder
import com.nononsenseapps.feeder.model.TTSStateHolder
import com.nononsenseapps.feeder.model.highlights.Highlight
import com.nononsenseapps.feeder.model.highlights.HighlightStore
import com.nononsenseapps.feeder.model.playlists.ItemKind
import com.nononsenseapps.feeder.model.playlists.PLAYED_TAIL_SECONDS
import com.nononsenseapps.feeder.model.playlists.PlaylistContext
import com.nononsenseapps.feeder.model.playlists.PlaylistStore
import com.nononsenseapps.feeder.model.playlists.kind
import com.nononsenseapps.feeder.model.search.ItemHit
import com.nononsenseapps.feeder.model.search.ItemText
import com.nononsenseapps.feeder.model.search.SearchData
import com.nononsenseapps.feeder.model.search.SearchResults
import com.nononsenseapps.feeder.model.search.SearchScope
import com.nononsenseapps.feeder.model.search.TranscriptLine
import com.nononsenseapps.feeder.model.search.searchAll
import com.nononsenseapps.feeder.model.transcript.Transcripts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.kodein.di.DI
import org.kodein.di.instance

/** An episode or video you're part way through, for "Jump back in". */
data class InProgress(
    val item: PlaylistCandidate,
    val percent: Int,
)

data class GlobalSearchState(
    val data: SearchData = SearchData(),
    val results: SearchResults = SearchResults(),
    val ctx: PlaylistContext = PlaylistContext(),
    val inProgress: List<InProgress> = emptyList(),
    val loaded: Boolean = false,
)

/**
 * Global search on the phone: the same search as the web app's ⌘K palette (model/search), over
 * what the app already holds. Saved transcripts are read once when the screen opens.
 */
class GlobalSearchViewModel(
    di: DI,
) : DIAwareViewModel(di) {
    private val dao: FeedItemDao by instance()
    private val highlights: HighlightStore by instance()
    private val playlists: PlaylistStore by instance()
    private val transcripts: Transcripts by instance()
    private val repository: Repository by instance()
    private val podcast: PodcastPlayerStateHolder by instance()
    private val tts: TTSStateHolder by instance()
    private val prefs: SharedPreferences by instance()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _scope = MutableStateFlow(SearchScope.ALL)
    val scope: StateFlow<SearchScope> = _scope.asStateFlow()

    private val _recent = MutableStateFlow(loadRecent())
    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    private val transcriptLines = MutableStateFlow<Map<Long, List<TranscriptLine>>>(emptyMap())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            transcriptLines.value =
                runCatching { transcripts.storedLines() }
                    .getOrDefault(emptyMap())
                    .mapValues { (_, cues) -> cues.map { TranscriptLine(it.startMs, it.text) } }
        }
    }

    private val data =
        combine(
            dao.loadPlaylistCandidates(),
            dao.loadSearchTexts(),
            highlights.highlights,
            playlists.playlists,
            transcriptLines,
        ) { items, texts, hl, pl, lines ->
            SearchData(
                items = items,
                texts = texts.associate { it.id to ItemText(it.snippet, it.author) },
                highlights = hl,
                playlists = pl,
                transcripts = lines,
            )
        }.flowOn(Dispatchers.Default)

    @OptIn(FlowPreview::class)
    val state: StateFlow<GlobalSearchState> =
        combine(data, _query.debounce(120), playlists.positions) { d, q, positions ->
            val ctx = PlaylistContext(positions = positions)
            GlobalSearchState(
                data = d,
                results = searchAll(q, d.copy(now = System.currentTimeMillis())),
                ctx = ctx,
                inProgress = inProgress(d.items, positions),
                loaded = true,
            )
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GlobalSearchState())

    private fun inProgress(
        items: List<PlaylistCandidate>,
        positions: Map<Long, Pair<Long, Long>>,
    ): List<InProgress> {
        val byId = items.associateBy { it.id }
        val stamped = playlists.stampedPositions()
        return positions
            .asSequence()
            .filter { (id, p) ->
                val item = byId[id]
                item != null && item.kind != ItemKind.ARTICLE && p.first > 5 && (p.second <= 0 || p.first < p.second - PLAYED_TAIL_SECONDS)
            }.sortedByDescending { (id, _) -> stamped[id]?.third ?: 0L }
            .take(3)
            .map { (id, p) -> InProgress(byId.getValue(id), if (p.second > 0) (p.first * 100 / p.second).toInt().coerceIn(1, 99) else 0) }
            .toList()
    }

    fun setQuery(q: String) {
        _query.value = q
    }

    fun setScope(s: SearchScope) {
        _scope.value = s
    }

    /** Before opening a result: the reader's swipes go through the item results in this order. */
    fun openingItem(order: List<Long>) {
        remember()
        repository.setArticleSwipeList("search:${_query.value.trim()}", order)
    }

    /** A transcript match: the episode plays from that line in Feeder's player. */
    fun playFrom(hit: ItemHit) {
        val link = hit.item.enclosureLink ?: return
        val at = hit.atMs ?: return
        tts.stop()
        podcast.playLink(link, hit.item.id, startAtSeconds = at / 1000)
    }

    /** A highlight's article on this phone, by its sync name (feed URL + guid), or null. */
    fun itemIdOf(h: Highlight): Long? {
        val cut = h.itemKey.indexOf('\n')
        if (cut <= 0) return null
        val url = h.itemKey.substring(0, cut)
        val guid = h.itemKey.substring(cut + 1)
        return state.value.data.items
            .firstOrNull { it.feedUrl == url && it.guid == guid }
            ?.id
    }

    /** Keeps the query in Recent searches. */
    fun remember() {
        val q = _query.value.trim()
        if (q.isEmpty()) return
        save(listOf(q) + _recent.value.filterNot { it.equals(q, ignoreCase = true) })
    }

    fun forget(q: String) = save(_recent.value - q)

    private fun save(list: List<String>) {
        val next = list.take(RECENT_MAX)
        _recent.value = next
        prefs.edit { putString(RECENT_KEY, Json.encodeToString(ListSerializer(String.serializer()), next)) }
    }

    private fun loadRecent(): List<String> =
        runCatching { Json.decodeFromString(ListSerializer(String.serializer()), prefs.getString(RECENT_KEY, null) ?: "[]") }
            .getOrDefault(emptyList())
            .take(RECENT_MAX)

    companion object {
        private const val RECENT_KEY = "global_search_recent_v1"
        private const val RECENT_MAX = 6
    }
}
