package com.nononsenseapps.feeder.model

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Loads each image URL once at a time: a second request for an image that is still downloading
 * (the same feed icon on every row, say) waits for it, then comes from the cache instead of
 * downloading it again.
 *
 * Different images load side by side. OkHttp already keeps it polite: at most 5 requests to one
 * host at a time. (This used to be one lock per host, shared by hash between all hosts, so the
 * whole app loaded about 8 images at a time, YouTube thumbnails one by one, and images already
 * in memory waited behind downloads.)
 */
object OneRequestPerImageInterceptor : Interceptor {
    private class InFlight {
        val mutex = Mutex()
        var users = 0
    }

    private val inFlight = HashMap<String, InFlight>()

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = chain.request.data as? String ?: return chain.proceed()
        // It can be a data url or a file
        if (!url.startsWith("http")) return chain.proceed()

        val entry =
            synchronized(inFlight) {
                inFlight.getOrPut(url) { InFlight() }.also { it.users++ }
            }
        try {
            return entry.mutex.withLock { chain.proceed() }
        } finally {
            synchronized(inFlight) {
                if (--entry.users == 0) inFlight.remove(url)
            }
        }
    }
}
