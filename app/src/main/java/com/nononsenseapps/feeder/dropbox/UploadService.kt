package com.nononsenseapps.feeder.dropbox

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.nononsenseapps.feeder.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the app running while files go up to Dropbox (an audiobook takes many minutes), so
 * switching apps or turning the screen off does not stop the upload. Shows how far it is.
 */
class UploadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.dropbox_upload_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("", 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        scope.launch {
            val dropbox = DropboxClient.get(this@UploadService)
            combine(dropbox.uploads, dropbox.holds) { now, holds -> now to holds }.collectLatest { (now, holds) ->
                if (now.isEmpty() && holds == 0) {
                    stopSelf()
                    return@collectLatest
                }
                if (now.isEmpty()) {
                    stopSelf()
                    return@collectLatest
                }
                val done = now.values.sumOf { it.first }
                val total = now.values.sumOf { it.second }.coerceAtLeast(1)
                val name = now.keys.first().substringAfterLast('/')
                val percent = (done * 100 / total).toInt()
                runCatching { NotificationManagerCompat.from(this@UploadService).notify(NOTIFICATION_ID, notification(name, percent)) }
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int = START_NOT_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(
        name: String,
        percent: Int,
    ) = NotificationCompat
        .Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_f)
        .setContentTitle(getString(R.string.dropbox_upload_title))
        .setContentText(if (name.isEmpty()) null else getString(R.string.dropbox_upload_progress, name, percent))
        .setProgress(100, percent, percent == 0)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .build()

    companion object {
        private const val CHANNEL_ID = "feederDropboxUploads"
        private const val NOTIFICATION_ID = 42712

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, UploadService::class.java)) }
        }
    }
}
