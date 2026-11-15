package com.muunkk.connectanytime

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = ConnectApp::class, sdk = [35])
class ActivityLogTest {
    private val app get() = ApplicationProvider.getApplicationContext<ConnectApp>()
    private val temp get() = File(app.filesDir, "activity.tmp")
    private val file get() = File(app.filesDir, "activity.txt")

    @After fun cleanUp() {
        temp.deleteRecursively()
        file.delete()
    }

    @Test fun aFailedWriteDoesNotThrowAndTheNextAddRetriesAllLines() {
        val log = ActivityLog(app)
        assertTrue(temp.mkdir())
        log.add("one")
        log.flush()
        assertTrue(temp.delete())
        log.add("two")
        log.flush()
        assertEquals(listOf("one", "two"), file.readLines().map { it.substringAfterLast(' ') })
    }

    @Test fun clearEmptiesStateEvenWhenTheWriteFails() {
        val log = ActivityLog(app)
        assertTrue(temp.mkdir())
        log.add("one")
        log.clear()
        assertTrue(log.state.value.isEmpty())
    }
}
