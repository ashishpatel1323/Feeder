package com.nononsenseapps.feeder.background

import android.app.job.JobParameters
import android.content.Context
import com.nononsenseapps.feeder.model.ebooks.BookAudio

/** Downloads ebook chapter audio the Mac made (Dropbox) on Wi-Fi ([com.nononsenseapps.feeder.model.ebooks.MacBookAudio]). */
class BookAudioMacJob(
    private val context: Context,
    override val params: JobParameters,
) : BackgroundJob {
    override val jobId: Int = params.jobId

    override suspend fun doWork() {
        BookAudio.get(context).mac.run()
    }
}
