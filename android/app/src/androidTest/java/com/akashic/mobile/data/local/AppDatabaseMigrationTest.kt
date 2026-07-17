package com.akashic.mobile.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate1To2RequiresCatalogBeforeDispatchingOldSessions() {
        helper.createDatabase(DATABASE_NAME, 1).apply {
            execSQL(
                "INSERT INTO server_profiles VALUES(" +
                    "'server', '电脑', 'device', 'alias', 'pin', '[]', '[]', '[]', 1)",
            )
            execSQL("INSERT INTO conversations VALUES('mobile:test', 'server', '旧会话', 2)")
            close()
        }

        helper.runMigrationsAndValidate(
            DATABASE_NAME,
            2,
            true,
            AppDatabase.MIGRATION_1_2,
        ).use { database ->
            database.query(
                "SELECT remoteState FROM conversations WHERE sessionId = 'mobile:test'",
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(ConversationRemoteState.UNKNOWN, cursor.getString(0))
            }
        }
    }

    private companion object {
        const val DATABASE_NAME = "migration-session-ownership-1-2"
    }
}
