package app.jarvis.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumePlanTest {
    @Test fun freshDownloadStartsFromZero() {
        val plan = ResumePlan.decide(existingBytes = 0, status = 200, contentLength = 2_500L, contentRange = null)
        assertEquals(0L, plan.startFrom)
        assertEquals(2_500L, plan.totalBytes)
        assertNull(plan.error)
    }

    @Test fun partialContentContinuesFromTheSameOffset() {
        val plan = ResumePlan.decide(
            existingBytes = 1_000,
            status = 206,
            contentLength = 1_500,
            contentRange = "bytes 1000-2499/2500"
        )
        assertEquals(1_000L, plan.startFrom)
        assertEquals(2_500L, plan.totalBytes)
        assertTrue(!plan.restarts)
    }

    @Test fun serverIgnoringRangeForcesRestart() {
        // Отдал 200 вместо 206 — значит прислал файл целиком, дописывать в хвост нельзя.
        val plan = ResumePlan.decide(existingBytes = 1_000, status = 200, contentLength = 2_500, contentRange = null)
        assertTrue(plan.restarts)
        assertEquals(2_500L, plan.totalBytes)
    }

    @Test fun wrongOffsetInContentRangeForcesRestart() {
        // Просили с 1000, дали с 500: склеивание дало бы битый файл.
        val plan = ResumePlan.decide(
            existingBytes = 1_000,
            status = 206,
            contentLength = 2_000,
            contentRange = "bytes 500-2499/2500"
        )
        assertTrue(plan.restarts)
        assertEquals(2_500L, plan.totalBytes)
    }

    @Test fun unsatisfiableRangeRestartsInsteadOfFailing() {
        val plan = ResumePlan.decide(existingBytes = 9_999, status = 416, contentLength = null, contentRange = null)
        assertTrue(plan.restarts)
        assertNull(plan.error)
    }

    @Test fun malformedContentRangeIsAnError() {
        val plan = ResumePlan.decide(existingBytes = 10, status = 206, contentLength = 5, contentRange = "garbage")
        assertNotNull(plan.error)
    }

    @Test fun unexpectedStatusIsReportedNotSilentlyRestarted() {
        val plan = ResumePlan.decide(existingBytes = 0, status = 403, contentLength = null, contentRange = null)
        assertNotNull(plan.error)
        assertTrue(plan.error!!.contains("403"))
    }

    @Test fun unknownTotalSizeIsTolerated() {
        val plan = ResumePlan.decide(
            existingBytes = 100,
            status = 206,
            contentLength = null,
            contentRange = "bytes 100-199/*"
        )
        assertEquals(100L, plan.startFrom)
        assertNull(plan.totalBytes)
        assertNull(plan.error)
    }
}
