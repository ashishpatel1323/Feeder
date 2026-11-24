package com.nononsenseapps.feeder.sync2

import com.nononsenseapps.feeder.ApplicationCoroutineScope
import com.nononsenseapps.feeder.background.runOnceSyncChainSendRead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance

/**
 * Sends playlist edits and where an episode was left to other devices, so another device can
 * carry on from here. Edits go out right away. Playing positions (saved every few seconds) go out
 * a few seconds after they stop changing, which is when playback pauses or ends, and at least
 * every [MAX_WAIT_MS] while something keeps playing.
 */
class SyncNudge(
    override val di: DI,
) : DIAware {
    private val scope: ApplicationCoroutineScope by instance()
    private var waiting: Job? = null
    private var firstUnsent = 0L

    @Synchronized
    fun changed(positionOnly: Boolean) {
        val now = System.currentTimeMillis()
        if (firstUnsent == 0L) firstUnsent = now
        waiting?.cancel()
        waiting = null
        if (!positionOnly || now - firstUnsent >= MAX_WAIT_MS) {
            send()
        } else {
            waiting =
                scope.launch {
                    delay(QUIET_MS)
                    synchronized(this@SyncNudge) { send() }
                }
        }
    }

    private fun send() {
        firstUnsent = 0L
        scope.launch(Dispatchers.IO) { runOnceSyncChainSendRead(di) }
    }

    companion object {
        const val QUIET_MS = 8_000L
        const val MAX_WAIT_MS = 90_000L
    }
}
