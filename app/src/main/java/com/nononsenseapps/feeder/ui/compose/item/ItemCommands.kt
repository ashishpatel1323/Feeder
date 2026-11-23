package com.nononsenseapps.feeder.ui.compose.item

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.nononsenseapps.feeder.ApplicationCoroutineScope
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.archmodel.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.kodein.di.compose.LocalDI
import org.kodein.di.instance

/**
 * Save, Mark read and Delete for rows outside the article list (playlists, downloads), done the
 * same way as in the list: synced, and Delete offers Undo.
 */
@Stable
class ItemCommands(
    private val repository: Repository,
    private val scope: ApplicationCoroutineScope,
    private val deletedText: (Int) -> String,
    private val undoText: String,
) {
    fun setSaved(
        itemId: Long,
        saved: Boolean,
    ) {
        scope.launch { repository.setBookmarked(itemId, saved) }
    }

    fun setRead(
        itemId: Long,
        read: Boolean,
    ) {
        scope.launch { if (read) repository.markAsReadAndNotified(itemId) else repository.markAsUnread(itemId) }
    }

    /** Deletes for good on every device; [snackbar] (when given) offers Undo. */
    fun delete(
        itemId: Long,
        snackbar: SnackbarHostState?,
    ) {
        scope.launch {
            val deleted = repository.deleteArticlesForGood(listOf(itemId))
            if (snackbar != null) {
                launch(Dispatchers.Main) {
                    snackbar.currentSnackbarData?.dismiss()
                    val result = snackbar.showSnackbar(deletedText(1), actionLabel = undoText, duration = SnackbarDuration.Short)
                    if (result == SnackbarResult.ActionPerformed) repository.undoDeleteArticles(deleted)
                }
            }
        }
    }
}

@Composable
fun rememberItemCommands(): ItemCommands {
    val di = LocalDI.current
    val repository: Repository by di.instance()
    val scope: ApplicationCoroutineScope by di.instance()
    val resources = LocalContext.current.resources
    val undo = resources.getString(R.string.undo_delete_article)
    return remember(repository, scope) {
        ItemCommands(
            repository = repository,
            scope = scope,
            deletedText = { n -> resources.getQuantityString(R.plurals.n_articles_deleted_everywhere, n, n) },
            undoText = undo,
        )
    }
}
