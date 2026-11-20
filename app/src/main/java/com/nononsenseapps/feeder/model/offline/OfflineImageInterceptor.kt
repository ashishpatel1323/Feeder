package com.nononsenseapps.feeder.model.offline

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shows images downloaded for offline reading from their saved copy, so they don't depend on
 * the network or on the image caches, which drop old images.
 */
class OfflineImageInterceptor(
    private val offlineFiles: OfflineFiles,
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = chain.request.data as? String ?: return chain.proceed()
        if (!url.startsWith("http")) return chain.proceed()
        val saved =
            withContext(Dispatchers.IO) {
                offlineFiles.savedImage(url)
            } ?: return chain.proceed()
        return chain
            .withRequest(
                chain.request
                    .newBuilder()
                    .data(saved)
                    .build(),
            ).proceed()
    }
}
