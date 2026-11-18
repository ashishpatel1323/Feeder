package com.nononsenseapps.feeder.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.nononsenseapps.feeder.FeederApplication
import com.nononsenseapps.feeder.archmodel.Repository
import com.nononsenseapps.feeder.db.room.ID_SAVED_ARTICLES
import org.kodein.di.instance

/**
 * Marks everything as read in the feed or tag which the widget is showing.
 * This matches the "mark all as read" action in the app.
 */
class MarkAllAsReadAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val app = context.applicationContext as FeederApplication
        val repository: Repository by app.di.instance()
        val (feedId, tag) = repository.currentWidgetFeedAndTag.value
        // The saved articles list has no feed or tag, so marking it would mark every article as read.
        if (feedId == ID_SAVED_ARTICLES) return
        repository.markAllAsReadInFeedOrTag(feedId, tag)
    }
}
