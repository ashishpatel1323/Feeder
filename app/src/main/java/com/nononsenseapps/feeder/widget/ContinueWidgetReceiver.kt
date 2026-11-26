package com.nononsenseapps.feeder.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class ContinueWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget get() = ContinueWidget()

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        ContinueWidgetUpdater.start(context)
    }
}

/**
 * Redraws the Continue widget when what it shows changes (something played, paused or opened)
 * while the app runs. Started when a widget is drawn or the app starts with one placed.
 */
object ContinueWidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    @OptIn(FlowPreview::class)
    fun start(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (job?.isActive == true) return
            job =
                scope.launch {
                    try {
                        continueWidgetChanges(app)
                            .drop(1)
                            .debounce(500)
                            .collect { ContinueWidget().updateAll(app) }
                    } catch (e: Exception) {
                        Log.e("FEEDER_WIDGET", "Continue widget updates stopped", e)
                    }
                }
        }
    }

    /** At app start: only when a Continue widget is on the home screen. */
    fun startIfPlaced(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val placed = runCatching { GlanceAppWidgetManager(app).getGlanceIds(ContinueWidget::class.java).isNotEmpty() }.getOrDefault(false)
            if (placed) {
                start(app)
                // What it showed may be old: the app wasn't running to follow along
                runCatching { ContinueWidget().updateAll(app) }
            }
        }
    }
}
