package com.nononsenseapps.feeder.model.playlists

import com.nononsenseapps.feeder.db.room.PlaylistCandidate
import com.nononsenseapps.feeder.model.youtube.youtubeVideoId
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Playlists to listen to, for example while driving, like a podcast app's episode filters.
 *
 * Smart playlists keep rules (folders and feeds, type, status, published within, length) and show
 * whatever matches right now, so played items drop off by themselves. My playlists are filled by
 * hand ("Playlist" on any article, episode or video) and put in order. Articles are read aloud;
 * podcast episodes and saved YouTube videos play as audio. Kept on this device only.
 */
@Serializable
enum class ItemKind { PODCAST, YOUTUBE, ARTICLE }

@Serializable
enum class PlayStatus { UNREAD, IN_PROGRESS, READ }

@Serializable
enum class PlaylistSort { NEWEST, OLDEST, SHORTEST, LONGEST }

@Serializable
data class SmartRules(
    /** Folder names; with [feedIds], an item matches when it is in either. Both empty = every feed. */
    val folders: List<String> = emptyList(),
    val feedIds: List<Long> = emptyList(),
    /** Empty = every type. */
    val kinds: List<ItemKind> = emptyList(),
    /** Empty = any status. */
    val statuses: List<PlayStatus> = emptyList(),
    val savedOnly: Boolean = false,
    val downloadedOnly: Boolean = false,
    /** Published in the last this many days; null = any time. */
    val withinDays: Int? = null,
    /** Length bounds in minutes; null = no bound. Articles count as their read-aloud time. */
    val minMinutes: Int? = null,
    val maxMinutes: Int? = null,
)

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    /** A short emoji shown on the colored tile. */
    val icon: String,
    /** ARGB color of the tile. */
    val color: Long,
    val smart: Boolean,
    val rules: SmartRules = SmartRules(),
    /** My playlists: the items in order. Unused for smart playlists. */
    val itemIds: List<Long> = emptyList(),
    val sort: PlaylistSort = PlaylistSort.NEWEST,
    val createdAt: Long = 0L,
)

val PLAYLIST_COLORS: List<Long> = listOf(0xFF1D4ED8, 0xFF7C3AED, 0xFF0E7490, 0xFFBE123C, 0xFFA16207, 0xFF15803D, 0xFFC2410C, 0xFF9D174D)
val PLAYLIST_ICONS: List<String> = listOf("🚗", "🎙", "📰", "▶", "★", "☕", "🏃", "🌙", "🎧", "📚", "🧠", "✈")

/** At most this many items in a smart playlist, so a broad rule still plays sensibly. */
const val SMART_LIMIT = 200

/** Characters the read-aloud voice speaks per second at 1x (about 140 words a minute). */
private const val CHARS_PER_SECOND = 14.0

private val AUDIO_EXT = Regex("\\.(mp3|m4a|m4b|aac|ogg|oga|opus|wav|flac)(\\?|#|$)", RegexOption.IGNORE_CASE)
private val NOT_AUDIO_EXT = Regex("\\.(jpe?g|png|gif|webp|avif|svg|pdf)(\\?|#|$)", RegexOption.IGNORE_CASE)

fun isPlayableEnclosure(
    link: String?,
    type: String?,
): Boolean {
    if (link.isNullOrBlank()) return false
    val t = type.orEmpty().lowercase()
    if (t.startsWith("audio/") || t.startsWith("video/")) return true
    if (t.startsWith("image/")) return false
    return AUDIO_EXT.containsMatchIn(link) || !NOT_AUDIO_EXT.containsMatchIn(link)
}

/** A YouTube video (its link points at one), a podcast episode (audio attachment), or an article. */
fun itemKind(
    link: String?,
    enclosureLink: String?,
    enclosureType: String?,
): ItemKind =
    when {
        youtubeVideoId(link) != null -> ItemKind.YOUTUBE
        isPlayableEnclosure(enclosureLink, enclosureType) -> ItemKind.PODCAST
        else -> ItemKind.ARTICLE
    }

val PlaylistCandidate.kind: ItemKind get() = itemKind(link, enclosureLink, enclosureType)

/** About how long reading [words] aloud takes, in seconds. */
fun readAloudSeconds(
    words: Int,
    speed: Float = 1f,
): Long = max(30L, (words * 6 / CHARS_PER_SECOND / speed.coerceAtLeast(0.25f)).roundToLong())

/** What the rules look at besides the items: where episodes were left, saved files, speed. */
data class PlaylistContext(
    /** Item id to (position, length) in seconds, for episodes and videos started here. */
    val positions: Map<Long, Pair<Long, Long>> = emptyMap(),
    /** Items with a saved video or episode file on the phone. */
    val downloaded: Set<Long> = emptySet(),
    /** Learned lengths (seconds) of saved videos and episodes. */
    val knownLengths: Map<Long, Long> = emptyMap(),
    val speed: Float = 1f,
    val now: Long = System.currentTimeMillis(),
)

fun PlaylistCandidate.seconds(ctx: PlaylistContext): Long? =
    when (kind) {
        ItemKind.ARTICLE -> readAloudSeconds(if (fullTextByDefault && wordCountFull > 0) wordCountFull else wordCount, ctx.speed)
        else -> ctx.knownLengths[id] ?: enclosureDuration?.takeIf { it > 0 } ?: ctx.positions[id]?.second?.takeIf { it > 0 }
    }

/** The last this many seconds count as played. */
const val PLAYED_TAIL_SECONDS = 30L

fun PlaylistCandidate.status(ctx: PlaylistContext): PlayStatus {
    val pos = ctx.positions[id]
    if (!read && pos != null && pos.first > 0 && (pos.second <= 0 || pos.first < pos.second - PLAYED_TAIL_SECONDS)) return PlayStatus.IN_PROGRESS
    return if (read) PlayStatus.READ else PlayStatus.UNREAD
}

fun PlaylistCandidate.isDownloaded(ctx: PlaylistContext): Boolean = offline || id in ctx.downloaded

fun PlaylistCandidate.matches(
    r: SmartRules,
    ctx: PlaylistContext,
): Boolean {
    if (r.folders.isNotEmpty() || r.feedIds.isNotEmpty()) {
        if (feedId !in r.feedIds && !(feedTag.isNotEmpty() && feedTag in r.folders)) return false
    }
    if (r.kinds.isNotEmpty() && kind !in r.kinds) return false
    if (r.statuses.isNotEmpty() && status(ctx) !in r.statuses) return false
    if (r.savedOnly && !bookmarked) return false
    if (r.downloadedOnly && !isDownloaded(ctx)) return false
    if (r.withinDays != null && sortTime < ctx.now - r.withinDays * 86_400_000L) return false
    if (r.minMinutes != null || r.maxMinutes != null) {
        // An episode of unknown length can't be said to fit
        val s = seconds(ctx) ?: return false
        if (r.minMinutes != null && s < r.minMinutes * 60L) return false
        if (r.maxMinutes != null && s > r.maxMinutes * 60L) return false
    }
    return true
}

fun List<PlaylistCandidate>.sortedFor(
    sort: PlaylistSort,
    ctx: PlaylistContext,
): List<PlaylistCandidate> =
    when (sort) {
        PlaylistSort.NEWEST -> sortedByDescending { it.sortTime }
        PlaylistSort.OLDEST -> sortedBy { it.sortTime }
        PlaylistSort.SHORTEST -> sortedWith(compareBy<PlaylistCandidate> { it.seconds(ctx) ?: Long.MAX_VALUE }.thenByDescending { it.sortTime })
        PlaylistSort.LONGEST -> sortedWith(compareByDescending<PlaylistCandidate> { it.seconds(ctx) ?: -1L }.thenByDescending { it.sortTime })
    }

/** The items of [playlist] right now, in play order. */
fun Playlist.items(
    all: List<PlaylistCandidate>,
    ctx: PlaylistContext,
    byId: Map<Long, PlaylistCandidate> = all.associateBy { it.id },
): List<PlaylistCandidate> =
    if (!smart) {
        itemIds.mapNotNull { byId[it] }
    } else {
        all.filter { it.matches(rules, ctx) }.sortedFor(sort, ctx).take(SMART_LIMIT)
    }

/** Total listening seconds, and how many items have no known length. */
fun List<PlaylistCandidate>.totalSeconds(ctx: PlaylistContext): Pair<Long, Int> {
    var seconds = 0L
    var unknown = 0
    for (i in this) {
        val s = i.seconds(ctx)
        if (s == null) unknown++ else seconds += s
    }
    return seconds to unknown
}

/** "2 h 05 m", "48 min", "under a minute". */
fun formatTotal(seconds: Long): String {
    val m = (seconds / 60.0).roundToLong()
    if (m < 1) return "under a minute"
    if (m < 60) return "$m min"
    return "${m / 60} h ${(m % 60).toString().padStart(2, '0')} m"
}

fun withinLabel(days: Int?): String =
    when (days) {
        null -> "Any time"
        1 -> "Last 24 hours"
        7 -> "Last week"
        14 -> "Last 2 weeks"
        30 -> "Last month"
        else -> "Last $days days"
    }

val ItemKind.label: String
    get() =
        when (this) {
            ItemKind.PODCAST -> "Podcast"
            ItemKind.YOUTUBE -> "YouTube"
            ItemKind.ARTICLE -> "Article"
        }

val PlayStatus.label: String
    get() =
        when (this) {
            PlayStatus.UNREAD -> "Unread"
            PlayStatus.IN_PROGRESS -> "In progress"
            PlayStatus.READ -> "Read"
        }

val PlaylistSort.label: String
    get() =
        when (this) {
            PlaylistSort.NEWEST -> "Newest"
            PlaylistSort.OLDEST -> "Oldest"
            PlaylistSort.SHORTEST -> "Shortest"
            PlaylistSort.LONGEST -> "Longest"
        }

/** Short text for each rule that is set, for the chips at the top of a smart playlist. */
fun SmartRules.describe(feedTitle: (Long) -> String): List<String> {
    val out = mutableListOf<String>()
    val names = folders + feedIds.map(feedTitle)
    if (names.isNotEmpty()) out += "From ${names.joinToString(", ")}"
    if (kinds.isNotEmpty()) out += kinds.joinToString(" · ") { it.label }
    if (statuses.isNotEmpty()) out += statuses.joinToString(" or ") { it.label }
    if (savedOnly) out += "Saved only"
    if (downloadedOnly) out += "Downloaded only"
    if (withinDays != null) out += withinLabel(withinDays)
    when {
        minMinutes != null && maxMinutes != null -> out += "$minMinutes–$maxMinutes min"
        maxMinutes != null -> out += "Under $maxMinutes min"
        minMinutes != null -> out += "Over $minMinutes min"
    }
    return out
}

fun Playlist.withItems(ids: List<Long>): Playlist = copy(itemIds = (itemIds + ids).distinct())

fun Playlist.withoutItems(ids: Collection<Long>): Playlist = copy(itemIds = itemIds.filterNot { it in ids })

fun Playlist.moveItem(
    id: Long,
    to: Int,
): Playlist {
    val list = itemIds.filterNot { it == id }.toMutableList()
    list.add(to.coerceIn(0, list.size), id)
    return copy(itemIds = list)
}

fun newPlaylistId(now: Long = System.currentTimeMillis()): String = "pl-${now.toString(36)}-${(Math.random() * 1_000_000).toLong().toString(36)}"

fun newPlaylist(
    name: String,
    smart: Boolean,
    existing: List<Playlist>,
    icon: String = if (smart) "⚡" else "★",
    rules: SmartRules = SmartRules(),
    itemIds: List<Long> = emptyList(),
): Playlist {
    val used = existing.map { it.color }.toSet()
    return Playlist(
        id = newPlaylistId(),
        name = name,
        icon = icon,
        color = PLAYLIST_COLORS.firstOrNull { it !in used } ?: PLAYLIST_COLORS[existing.size % PLAYLIST_COLORS.size],
        smart = smart,
        rules = rules,
        itemIds = itemIds,
        createdAt = System.currentTimeMillis(),
    )
}

/** The playlists a new user starts with, so the screen isn't empty. */
fun starterPlaylists(): List<Playlist> {
    val list = mutableListOf<Playlist>()
    list +=
        newPlaylist(
            "Morning drive",
            smart = true,
            existing = list,
            icon = "🚗",
            rules = SmartRules(kinds = listOf(ItemKind.PODCAST, ItemKind.ARTICLE), statuses = listOf(PlayStatus.UNREAD, PlayStatus.IN_PROGRESS), withinDays = 2, maxMinutes = 40),
        )
    list +=
        newPlaylist(
            "New podcast episodes",
            smart = true,
            existing = list,
            icon = "🎙",
            rules = SmartRules(kinds = listOf(ItemKind.PODCAST), statuses = listOf(PlayStatus.UNREAD, PlayStatus.IN_PROGRESS), withinDays = 7),
        )
    list += newPlaylist("Short reads", smart = true, existing = list, icon = "📰", rules = SmartRules(kinds = listOf(ItemKind.ARTICLE), statuses = listOf(PlayStatus.UNREAD), maxMinutes = 8))
    return list
}
