package com.nononsenseapps.feeder.model.transcript

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.nononsenseapps.feeder.FeederApplication
import com.nononsenseapps.feeder.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.kodein.di.direct
import org.kodein.di.instance

/**
 * Keeps the app running while an episode is transcribed on the phone (it takes minutes), with a
 * notification that shows how far it is and a Stop button.
 */
class TranscriptionService : Service() {
    private val transcripts: Transcripts by lazy { (application as FeederApplication).di.direct.instance() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        @OptIn(ExperimentalCoroutinesApi::class)
        scope.launch {
            transcripts.generatingId
                .filterNotNull()
                .flatMapLatest { id -> transcripts.state(id) }
                .map { (it.progress * 100).toInt() }
                .distinctUntilChanged()
                .collectLatest { percent ->
                    runCatching { NotificationManagerCompat.from(this@TranscriptionService).notify(NOTIFICATION_ID, notification(percent)) }
                }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP) {
            transcripts.stopGenerating()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(percent: Int) =
        NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_f)
            .setContentTitle(getString(R.string.transcript_notification_title))
            .setContentText(getString(R.string.transcript_notification_progress, percent))
            .setProgress(100, percent, percent == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(
                0,
                getString(R.string.transcript_stop),
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, TranscriptionService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            ).build()

    private fun createChannel() {
        val channel =
            NotificationChannel(CHANNEL_ID, getString(R.string.transcript_notification_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "feederTranscripts"
        private const val NOTIFICATION_ID = 42711
        private const val ACTION_STOP = "com.nononsenseapps.feeder.transcript.STOP"

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, TranscriptionService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TranscriptionService::class.java))
        }
    }
}
