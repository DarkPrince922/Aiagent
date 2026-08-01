package app.jarvis.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatApiRetryPolicyTest {
    @Test fun conflictAndTransientStatusesAreRetryable() {
        listOf(408, 409, 425, 429, 500, 503).forEach { status ->
            assertTrue("Expected HTTP $status to be retryable", ChatApi.isRetryableHttpStatus(status))
        }
    }

    @Test fun authenticationAndRequestErrorsAreNotRetried() {
        listOf(400, 401, 403, 404, 422).forEach { status ->
            assertFalse("Expected HTTP $status to be terminal", ChatApi.isRetryableHttpStatus(status))
        }
    }
}
