package com.nononsenseapps.feeder.background

import android.app.job.JobParameters
import android.content.Context
import com.nononsenseapps.feeder.model.ebooks.BookAudio

/** Speaks the ebook chapters ahead of the one being read into audio files ([BookAudio]). */
class BookAudioJob(
    private val context: Context,
    override val params: JobParameters,
) : BackgroundJob {
    override val jobId: Int = params.jobId

    override suspend fun doWork() {
        val audio = BookAudio.get(context)
        audio.jobRunning = true
        try {
            audio.makeMissing()
        } finally {
            audio.jobRunning = false
        }
    }
}
