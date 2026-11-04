package io.github.ev80diag

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ev80diag.data.store.Ev80Database
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run on a device: ./gradlew :app:connectedDebugAndroidTest */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), Ev80Database::class.java)

    @Test fun v1ToV2_keepsSessions() {
        helper.createDatabase("migration-test", 1).use { db ->
            db.execSQL("INSERT INTO profile (id, name, createdMs) VALUES (1, 'ET', 0)")
            db.execSQL("INSERT INTO session (id, profileId, type, startMs, adapterKind, droppedFrames, framesTotal) VALUES (7, 1, 'CHARGE_DC', 1000, 'STN_BT', 0, 0)")
        }
        helper.runMigrationsAndValidate("migration-test", 2, true).use { db ->
            db.query("SELECT type, distanceKm FROM session WHERE id = 7").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("CHARGE_DC", c.getString(0))
                assertTrue(c.isNull(1))
            }
        }
    }
}
