package com.nononsenseapps.feeder.db.room

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.nononsenseapps.feeder.FeederApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.android.closestDI
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@LargeTest
class TestMigrationFrom44To45 : DIAware {
    private val dbName = "testDb"
    private val feederApplication: FeederApplication = ApplicationProvider.getApplicationContext()
    override val di: DI by closestDI(feederApplication)

    @Rule
    @JvmField
    val testHelper: MigrationTestHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java,
            emptyList(),
            FrameworkSQLiteOpenHelperFactory(),
        )

    @Test
    fun migrate() {
        @Suppress("SimpleRedundantLet")
        testHelper.createDatabase(dbName, FROM_VERSION).let { oldDB ->
            oldDB.execSQL(
                """
                INSERT INTO feeds(id, title, url, custom_title, tag, notify, image_url, last_sync, response_hash, fulltext_by_default, open_articles_with, alternate_id, currently_syncing, when_modified, site_fetched, skip_duplicates, retry_after, summarize_on_open, fetch_og_images, block_rules, allow_rules)
                VALUES(1, 'feed', 'http://url', '', '', 0, null, 0, 666, 0, '', 0, 0, 0, 0, 0, 0, 0, 0, '', '')
                """.trimIndent(),
            )
            oldDB.execSQL(
                """
                INSERT INTO feed_items(id, guid, title, plain_title, plain_snippet, image_from_body, unread, notified, feed_id, first_synced_time, primary_sort_time, pinned, bookmarked, fulltext_downloaded, word_count, word_count_full)
                VALUES(8, 'guid', 'title', 'title', 'snippet', 0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0)
                """.trimIndent(),
            )
        }
        val db =
            testHelper.runMigrationsAndValidate(
                dbName,
                TO_VERSION,
                true,
                MigrationFrom44To45,
            )

        db
            .query("select youtube_music from feeds")
            .use {
                assert(it.count == 1)
                assert(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
        db.execSQL("INSERT INTO youtube_video_meta(video_id, duration, views, likes, is_short, is_live, checked_at) VALUES('abc', 61, 10, null, 1, 0, 5)")
        db
            .query("select duration, is_short from youtube_video_meta")
            .use {
                assert(it.moveToFirst())
                assertEquals(61, it.getInt(0))
                assertEquals(1, it.getInt(1))
            }
    }

    companion object {
        private const val FROM_VERSION = 44
        private const val TO_VERSION = 45
    }
}
