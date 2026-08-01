package app.jarvis.net

import app.jarvis.data.SshProfile
import app.jarvis.data.publicSummary
import app.jarvis.data.resetTrustIfEndpointChanged
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SshLedgerProtocolTest {
    @Test fun operationKeyIsStableAndSanitizesArbitraryIdsByHashing() {
        val first = SshLedgerProtocol.operationKey("task-42:step_3")
        val second = SshLedgerProtocol.operationKey("task-42:step_3")
        val arbitrary = SshLedgerProtocol.operationKey("../../unsafe\nвызов/инструмента")

        assertEquals(first, second)
        assertEquals(64, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(64, arbitrary.length)
        assertNotEquals(first, arbitrary)
        assertThrows(IllegalArgumentException::class.java) {
            SshLedgerProtocol.operationKey("   ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SshLedgerProtocol.operationKey("x".repeat(4_097))
        }
    }

    @Test fun dispatchPayloadDoesNotEmbedRawCommandOrOperationId() {
        val operationId = "task-42:secret-step"
        val command = "printf 'top secret; ${'$'}(uname)'"
        val payload = SshLedgerProtocol.dispatchPayload(operationId, command)

        assertFalse(payload.contains(operationId))
        assertFalse(payload.contains(command))
        assertTrue(payload.contains(SshLedgerProtocol.operationKey(operationId)))
        assertTrue(payload.contains(SshLedgerProtocol.commandHash(command)))
        assertTrue(payload.contains("mkdir \"${'$'}op\""))
        assertTrue(payload.contains("nohup"))
    }

    @Test fun completedResponseIsDecodedAndBounded() {
        val stdout = Base64.getEncoder().encodeToString("ready".toByteArray())
        val stderr = Base64.getEncoder().encodeToString("warning".toByteArray())
        val response = """
            shell banner
            __JARVIS_LEDGER_V1__|STATE|COMPLETED
            __JARVIS_LEDGER_V1__|EXIT|2
            __JARVIS_LEDGER_V1__|STDOUT|$stdout
            __JARVIS_LEDGER_V1__|STDERR|$stderr
            __JARVIS_LEDGER_V1__|TRUNCATED|1
        """.trimIndent()

        val result = SshLedgerProtocol.parse(response, "SHA256:test")!!

        assertEquals(SshPhase.COMPLETED, result.phase)
        assertEquals(2, result.exitCode)
        assertEquals("SHA256:test", result.fingerprint)
        assertTrue(result.output.contains("ready"))
        assertTrue(result.output.contains("warning"))
        assertTrue(result.output.contains("сокращён"))
        assertTrue(result.output.length <= 64_000)
    }

    @Test fun runningAndUnknownResponsesRemainDistinct() {
        val running = SshLedgerProtocol.parse(
            "__JARVIS_LEDGER_V1__|STATE|RUNNING",
            "fingerprint"
        )!!
        val unknown = SshLedgerProtocol.parse(
            "__JARVIS_LEDGER_V1__|STATE|UNKNOWN\n__JARVIS_LEDGER_V1__|DETAIL|ORPHANED",
            "fingerprint"
        )!!

        assertEquals(SshPhase.RUNNING, running.phase)
        assertEquals(-1, running.exitCode)
        assertEquals(SshPhase.UNKNOWN, unknown.phase)
        assertTrue(unknown.output.contains("ORPHANED"))
    }

    @Test fun connectRetryClassifierDoesNotRetryCredentialsOrHostKeyFailures() {
        assertFalse(isRetryableSshConnectFailure("Auth fail"))
        assertFalse(isRetryableSshConnectFailure("reject HostKey: server"))
        assertFalse(isRetryableSshConnectFailure("invalid privatekey"))
        assertTrue(isRetryableSshConnectFailure("session is down: Broken pipe"))
        assertTrue(isRetryableSshConnectFailure("timeout: socket is not established"))
    }

    @Test fun endpointChangeClearsFingerprintAndPublicSummaryOmitsSecrets() {
        val original = SshProfile(
            id = "server-id",
            name = "Test",
            host = "old.example.com",
            port = 22,
            username = "deploy",
            password = "password-secret",
            privateKey = "private-key-secret",
            passphrase = "passphrase-secret",
            fingerprint = "SHA256:trusted"
        )

        val unchanged = original.copy(host = "OLD.EXAMPLE.COM")
            .resetTrustIfEndpointChanged(original)
        val changed = original.copy(port = 2222)
            .resetTrustIfEndpointChanged(original)
        val summaryText = original.publicSummary().toString() + original.publicSummary().agentContext()

        assertEquals(original.fingerprint, unchanged.fingerprint)
        assertEquals("", changed.fingerprint)
        assertFalse(summaryText.contains(original.password))
        assertFalse(summaryText.contains(original.privateKey))
        assertFalse(summaryText.contains(original.passphrase))
    }
}
