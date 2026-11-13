package com.nononsenseapps.feeder.model

import android.app.Application
import android.util.Log
import androidx.compose.ui.text.AnnotatedString
import com.nononsenseapps.feeder.ApplicationCoroutineScope
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.archmodel.Article
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.blob.blobFile
import com.nononsenseapps.feeder.blob.blobFullFile
import com.nononsenseapps.feeder.blob.blobFullInputStream
import com.nononsenseapps.feeder.blob.blobInputStream
import com.nononsenseapps.feeder.db.room.FeedItemForFetching
import com.nononsenseapps.feeder.model.html.HtmlLinearizer
import com.nononsenseapps.feeder.ui.compose.navigation.ArticleSwipeDirection
import com.nononsenseapps.feeder.ui.compose.readaloud.speechText
import com.nononsenseapps.feeder.util.FilePathProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance

/** The articles before and after the one being read aloud, in the list it was opened from. */
data class ReadAloudNeighbors(
    val previousId: Long? = null,
    val previousTitle: String = "",
    val nextId: Long? = null,
    val nextTitle: String = "",
)

/**
 * Moves read aloud to the previous or next article in the list, from the player, the media
 * controls (lock screen, control center, headphones) or automatically when an article ends.
 * The article is read in full text, and an open article screen follows along.
 */
class ReadAloudQueue(
    override val di: DI,
) : DIAware {
    private val repository: Repository by instance()
    private val ttsStateHolder: TTSStateHolder by instance()
    private val filePathProvider: FilePathProvider by instance()
    private val fullTextParser: FullTextParser by instance()
    private val application: Application by instance()
    private val scope: ApplicationCoroutineScope by instance()

    val autoContinue: StateFlow<Boolean> = repository.readAloudAutoContinue

    fun setAutoContinue(value: Boolean) = repository.setReadAloudAutoContinue(value)

    private val _neighbors = MutableStateFlow(ReadAloudNeighbors())
    val neighbors: StateFlow<ReadAloudNeighbors> = _neighbors.asStateFlow()

    private val _openArticle = MutableSharedFlow<Pair<Long, ArticleSwipeDirection>>(extraBufferCapacity = 1)

    /** Article screens open these, so the screen shows what is being read. */
    val openArticle: SharedFlow<Pair<Long, ArticleSwipeDirection>> = _openArticle.asSharedFlow()

    private val _fullTextArticleId = MutableStateFlow<Long?>(null)

    /** The article this queue reads in full text, so its screen shows the same version. */
    val fullTextArticleId: StateFlow<Long?> = _fullTextArticleId.asStateFlow()

    private var job: Job? = null

    init {
        scope.launch {
            ttsStateHolder.progress
                .map { it.articleId }
                .distinctUntilChanged()
                .collectLatest { articleId ->
                    _neighbors.value = articleId?.let { loadNeighbors(it) } ?: ReadAloudNeighbors()
                }
        }
        ttsStateHolder.articleNavigator =
            object : TTSStateHolder.ArticleNavigator {
                override fun hasPrevious(): Boolean = _neighbors.value.previousId != null

                override fun hasNext(): Boolean = _neighbors.value.nextId != null

                override fun previous() = skip(next = false)

                override fun next() = skip(next = true)

                override fun onArticleFinished(articleId: Long) {
                    if (autoContinue.value && _neighbors.value.nextId != null) skip(next = true)
                }
            }
    }

    private suspend fun loadNeighbors(articleId: Long): ReadAloudNeighbors {
        val (previous, next) = repository.getArticleSwipeNeighbors(articleId)
        return ReadAloudNeighbors(
            previousId = previous,
            previousTitle = previous?.let { repository.getArticle(it)?.title }.orEmpty(),
            nextId = next,
            nextTitle = next?.let { repository.getArticle(it)?.title }.orEmpty(),
        )
    }

    fun skip(next: Boolean) {
        val current = ttsStateHolder.progress.value.articleId ?: return
        job?.cancel()
        job =
            scope.launch {
                val (previous, following) = repository.getArticleSwipeNeighbors(current)
                val target = (if (next) following else previous) ?: return@launch
                val article = repository.getArticle(target) ?: return@launch
                // Stop the old article at once; the new one may need its full text downloaded first
                ttsStateHolder.pause()
                _fullTextArticleId.value = target
                _openArticle.tryEmit(target to if (next) ArticleSwipeDirection.NEXT else ArticleSwipeDirection.PREVIOUS)
                repository.markAsReadAndNotified(target)
                val text =
                    loadSpeechText(article, fullText = true)
                        ?: loadSpeechText(article, fullText = false).also { _fullTextArticleId.value = null }
                        ?: return@launch
                ttsStateHolder.tts(
                    textArray = listOf(AnnotatedString(text)),
                    useDetectLanguage = repository.useDetectLanguage.value,
                    articleId = target,
                    title = article.title,
                    author = article.author.orEmpty(),
                    feedTitle = article.feedDisplayTitle,
                )
            }
    }

    /** The same text the article screen shows and reads, so highlights match when it is opened. */
    private suspend fun loadSpeechText(
        article: Article,
        fullText: Boolean,
    ): String? =
        withContext(Dispatchers.IO) {
            try {
                val linearizer =
                    HtmlLinearizer(
                        tooLargeText = application.getString(R.string.failed_to_fetch_full_article_too_large),
                        openInBrowserText = application.getString(R.string.open_in_web_view),
                        articleLink = article.link ?: "",
                    )
                val linear =
                    if (fullText) {
                        if (!blobFullFile(article.id, filePathProvider.fullArticleDir).isFile) {
                            fullTextParser.parseFullArticleIfMissing(
                                object : FeedItemForFetching {
                                    override val id = article.id
                                    override val link = article.link
                                },
                            )
                        }
                        if (!blobFullFile(article.id, filePathProvider.fullArticleDir).isFile) return@withContext null
                        blobFullInputStream(article.id, filePathProvider.fullArticleDir).use {
                            linearizer.linearize(inputStream = it, baseUrl = article.feedUrl ?: "")
                        }
                    } else {
                        if (!blobFile(article.id, filePathProvider.articleDir).isFile) return@withContext null
                        blobInputStream(article.id, filePathProvider.articleDir).use {
                            linearizer.linearize(inputStream = it, baseUrl = article.feedUrl ?: "")
                        }
                    }
                linear.speechText().takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Could not load article ${article.id} to read aloud", e)
                null
            }
        }

    companion object {
        private const val LOG_TAG = "FEEDER_READALOUDQUEUE"
    }
}
