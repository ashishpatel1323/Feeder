package com.nononsenseapps.feeder.di

import com.nononsenseapps.feeder.model.FeedParser
import com.nononsenseapps.feeder.model.FullTextParser
import com.nononsenseapps.feeder.model.RssLocalSync
import com.nononsenseapps.feeder.model.offline.DownloadsPlayback
import com.nononsenseapps.feeder.model.offline.DownloadsPlaylist
import com.nononsenseapps.feeder.model.offline.OfflineDownloader
import com.nononsenseapps.feeder.model.offline.OfflineFiles
import com.nononsenseapps.feeder.model.offline.OfflineVideos
import com.nononsenseapps.feeder.model.transcript.Transcripts
import com.nononsenseapps.feeder.sync.SyncRestClient
import com.nononsenseapps.feeder.sync2.Sync2Client
import com.nononsenseapps.feeder.sync2.SyncNudge
import com.nononsenseapps.jsonfeed.Feed
import com.nononsenseapps.jsonfeed.JsonFeedParser
import com.nononsenseapps.jsonfeed.feedAdapter
import com.squareup.moshi.JsonAdapter
import okhttp3.OkHttpClient
import org.kodein.di.DI
import org.kodein.di.bind
import org.kodein.di.direct
import org.kodein.di.instance
import org.kodein.di.provider
import org.kodein.di.singleton

val networkModule =
    DI.Module(name = "network") {
        // Parsers can carry state so safer to use providers
        bind<JsonAdapter<Feed>>() with provider { feedAdapter() }
        bind<JsonFeedParser>() with provider { JsonFeedParser(instance<OkHttpClient>(), instance()) }
        bind<FeedParser>() with provider { FeedParser(di) }
        // These don't have state issues
        bind<SyncRestClient>() with singleton { SyncRestClient(di) }
        bind<Sync2Client>() with singleton { Sync2Client(di) }
        bind<RssLocalSync>() with singleton { RssLocalSync(di) }
        bind<FullTextParser>() with singleton { FullTextParser(di) }
        bind<OfflineFiles>() with singleton { OfflineFiles(instance()) }
        bind<OfflineDownloader>() with singleton { OfflineDownloader(di) }
        bind<OfflineVideos>() with singleton { OfflineVideos(di) }
        bind<DownloadsPlaylist>() with singleton { DownloadsPlaylist(instance()) }
        bind<DownloadsPlayback>() with singleton { DownloadsPlayback(di) }
        bind<SyncNudge>() with singleton { SyncNudge(di) }
        bind<com.nononsenseapps.feeder.model.playlists.PlaylistStore>() with
            singleton {
                val nudge = instance<SyncNudge>()
                val d = di
                com.nononsenseapps.feeder.model.playlists
                    .PlaylistStore(instance())
                    .also { store ->
                        store.onChanged = { positionOnly -> nudge.changed(positionOnly) }
                        // History follows where episodes were left
                        store.onPositionSaved = { id, seconds, length ->
                            d.direct.instance<com.nononsenseapps.feeder.model.history.CentralHistoryRecorder>().itemProgress(id, seconds, length)
                        }
                    }
            }
        bind<com.nononsenseapps.feeder.model.highlights.HighlightStore>() with
            singleton {
                val nudge = instance<SyncNudge>()
                val d = di
                com.nononsenseapps.feeder.model.highlights
                    .HighlightStore(instance())
                    .also { store ->
                        store.onChanged = { nudge.changed(false) }
                        store.onEdit = { before, after ->
                            d.direct.instance<com.nononsenseapps.feeder.model.history.CentralHistoryRecorder>().highlightChanged(before, after)
                        }
                    }
            }
        bind<com.nononsenseapps.feeder.model.ebooks.BookPositions>() with
            singleton {
                val nudge = instance<SyncNudge>()
                com.nononsenseapps.feeder.model.ebooks.BookPositions
                    .get(instance<android.app.Application>())
                    .also { store -> store.onChanged = { nudge.changed(true) } }
            }
        bind<com.nononsenseapps.feeder.model.audiobooks.AudioPositions>() with
            singleton {
                val nudge = instance<SyncNudge>()
                com.nononsenseapps.feeder.model.audiobooks.AudioPositions
                    .get(instance<android.app.Application>())
                    .also { store -> store.onChanged = { nudge.changed(true) } }
            }
        bind<com.nononsenseapps.feeder.model.history.ItemHistory>() with
            singleton {
                val nudge = instance<SyncNudge>()
                val d = di
                com.nononsenseapps.feeder.model.history.ItemHistory
                    .get(instance<android.app.Application>())
                    .also { store ->
                        store.onChanged = { nudge.changed(true) }
                        // Audiobook plays and ebook opens also land in History
                        store.onRecorded = { ev ->
                            d.direct.instance<com.nononsenseapps.feeder.model.history.CentralHistoryRecorder>().fromEvent(ev)
                        }
                    }
            }
        bind<com.nononsenseapps.feeder.model.history.CentralHistory>() with
            singleton {
                val nudge = instance<SyncNudge>()
                com.nononsenseapps.feeder.model.history.CentralHistory
                    .get(instance<android.app.Application>())
                    .also { store -> store.onChanged = { positionOnly -> nudge.changed(positionOnly) } }
            }
        bind<com.nononsenseapps.feeder.model.history.CentralHistoryRecorder>() with
            singleton {
                com.nononsenseapps.feeder.model.history
                    .CentralHistoryRecorder(di)
            }
        bind<com.nononsenseapps.feeder.model.audiobooks.AudiobookPlayer>() with
            singleton {
                com.nononsenseapps.feeder.model.audiobooks
                    .AudiobookPlayer(di)
            }
        bind<com.nononsenseapps.feeder.dropbox.DropboxClient>() with
            singleton {
                com.nononsenseapps.feeder.dropbox.DropboxClient
                    .get(instance<android.app.Application>())
            }
        bind<com.nononsenseapps.feeder.model.playlists.PlaylistPlayer>() with
            singleton {
                com.nononsenseapps.feeder.model.playlists
                    .PlaylistPlayer(di)
            }
        bind<com.nononsenseapps.feeder.ui.compose.playlists.AddToPlaylistRequests>() with
            singleton {
                com.nononsenseapps.feeder.ui.compose.playlists
                    .AddToPlaylistRequests()
            }
        bind<Transcripts>() with singleton { Transcripts(di) }
    }
