package com.nononsenseapps.feeder.ui.compose.books

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.edit
import com.nononsenseapps.feeder.ui.compose.shelf.ShelfAdd
import com.nononsenseapps.feeder.ui.compose.shelf.ShelfPrefs

/** The subtabs of Books, in order. */
enum class BookTab { EBOOKS, AUDIOBOOKS, ZIM, PLAYER }

/** Books opens on the subtab used last on this phone (not synced). */
object BooksTabPreference {
    private const val PREFS = "main_tabs"
    private const val KEY = "books_tab"

    fun get(context: Context): BookTab {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return BookTab.entries.firstOrNull { it.name == saved } ?: BookTab.EBOOKS
    }

    fun set(
        context: Context,
        tab: BookTab,
    ) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, tab.name) }
}

/**
 * What one shelf hands up to the Books header: its Add actions, Dropbox button, Check Dropbox and
 * Cards/List choice. Values the header draws are state; callbacks are plain fields read when used,
 * so a shelf recomposing doesn't recompose the header.
 */
@Stable
class BooksPageChrome {
    var registered by mutableStateOf(false)
    var count by mutableIntStateOf(0)
    var available by mutableStateOf(true)
    var hasRefresh by mutableStateOf(false)
    var refreshing by mutableStateOf(false)
    var refreshLabel by mutableStateOf("")
    var addLabels by mutableStateOf(emptyList<String>())
    var prefs: ShelfPrefs? by mutableStateOf(null)

    var onRefresh: (() -> Unit)? = null
    var addActions: List<ShelfAdd> = emptyList()
    var dropbox: (@Composable () -> Unit)? = null
}

/** Shared by the Books header and its three shelves. */
@Stable
class BooksHost {
    val pages: Map<BookTab, BooksPageChrome> = BookTab.entries.associateWith { BooksPageChrome() }

    /** Filters the shown shelf by title and author */
    var query by mutableStateOf("")
}

/** Which Books subtab a shelf is drawn in; null when it is shown on its own. */
class BooksPage(
    val tab: BookTab,
    val host: BooksHost,
) {
    val chrome: BooksPageChrome get() = host.pages.getValue(tab)
}

val LocalBooksPage = staticCompositionLocalOf<BooksPage?> { null }
