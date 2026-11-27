package com.nononsenseapps.feeder.background

import android.app.job.JobParameters
import android.content.Context
import com.nononsenseapps.feeder.model.playlists.PlaylistAudio
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.android.closestDI
import org.kodein.di.instance

/** Speaks the playlist articles asked for with "Get next 20 articles ready offline" ([PlaylistAudio]). */
class PlaylistAudioJob(
    context: Context,
    override val params: JobParameters,
) : BackgroundJob,
    DIAware {
    override val di: DI by closestDI(context)
    private val audio: PlaylistAudio by instance()
    override val jobId: Int = params.jobId

    override suspend fun doWork() {
        audio.jobRunning = true
        try {
            audio.makeMissing()
        } finally {
            audio.jobRunning = false
            audio.refresh()
        }
    }
}
