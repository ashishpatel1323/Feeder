package com.nononsenseapps.feeder.ui

import androidx.lifecycle.viewModelScope
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.base.DIAwareViewModel
import com.nononsenseapps.feeder.model.history.CentralHistoryRecorder
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.instance

class NavigationDeepLinkViewModel(
    di: DI,
) : DIAwareViewModel(di) {
    private val repository: Repository by instance()
    private val history: CentralHistoryRecorder by instance()

    /**
     * [freshList]: the list was just opened (not returned to from an article), so articles
     * read before now leave the Unread list.
     */
    fun setCurrentFeedAndTag(
        feedId: Long,
        tag: String,
        freshList: Boolean = false,
    ) {
        if (freshList) {
            repository.openFeedList(feedId, tag)
        } else {
            repository.setCurrentFeedAndTag(feedId, tag)
        }
        // Should open feed in portrait
        repository.setIsArticleOpen(false)
    }

    fun setCurrentArticle(itemId: Long) {
        viewModelScope.launch {
            repository.markAsReadAndNotified(itemId)
        }
        history.itemOpened(itemId)
        repository.setCurrentArticle(itemId)
        // Should open article in portrait
        repository.setIsArticleOpen(true)
    }
}
