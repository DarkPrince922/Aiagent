package app.jarvis.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Знакомство — это лишние запросы к серверу, и они обязаны быть однократными.
 *
 * Провал не запоминался, поэтому падающий ход повторялся на каждом сообщении: пользователь
 * видел по пять-семь обращений на один вопрос и ошибки 500 на ровном месте.
 */
class HandshakeCacheTest {
    private class FakeStorage : AckStorage {
        private val answers = mutableMapOf<String, String>()
        private val tried = mutableSetOf<String>()
        override fun get(conversationId: String, slot: PromptAckStore.Slot, fingerprint: String) =
            answers[key(conversationId, slot, fingerprint)]
        override fun attempted(conversationId: String, slot: PromptAckStore.Slot, fingerprint: String) =
            key(conversationId, slot, fingerprint) in tried
        override fun save(conversationId: String, slot: PromptAckStore.Slot, fingerprint: String, ack: String) {
            answers[key(conversationId, slot, fingerprint)] = ack
            tried += key(conversationId, slot, fingerprint)
        }
        override fun markUnavailable(conversationId: String, slot: PromptAckStore.Slot, fingerprint: String) {
            tried += key(conversationId, slot, fingerprint)
        }
        private fun key(id: String, slot: PromptAckStore.Slot, fingerprint: String) = "$id|$slot|$fingerprint"
    }

    private val storage = FakeStorage()
    private val slot = PromptAckStore.Slot.INSTRUCTION
    private var calls = 0

    private fun ask(answer: String?): String? {
        calls++
        return answer
    }

    @Test fun theAnswerIsTakenOnceAndThenReused() {
        assertEquals("Принял.", storage.handshake("chat", slot, "fp") { ask("Принял.") })
        repeat(5) { assertEquals("Принял.", storage.handshake("chat", slot, "fp") { ask("Принял.") }) }
        assertEquals("Ответ должен браться один раз", 1, calls)
    }

    /** Главный случай: сервер отвечает ошибкой, и повторять запрос каждый раз нельзя. */
    @Test fun aFailedHandshakeIsNotRetriedOnEveryMessage() {
        repeat(7) { assertNull(storage.handshake("chat", slot, "fp") { ask(null) }) }
        assertEquals("Неудачная попытка должна быть одна", 1, calls)
    }

    @Test fun anEmptyAnswerCountsAsFailureAndAlsoStopsRepeating() {
        assertNull(storage.handshake("chat", slot, "fp") { ask(null) })
        assertNull(storage.handshake("chat", slot, "fp") { ask("Принял.") })
        assertEquals(1, calls)
    }

    @Test fun editingTheInstructionMakesANewAttempt() {
        assertNull(storage.handshake("chat", slot, "fp-1") { ask(null) })
        assertEquals("Принял.", storage.handshake("chat", slot, "fp-2") { ask("Принял.") })
        assertEquals(2, calls)
    }

    @Test fun conversationsDoNotShareTheirOutcome() {
        assertEquals("Принял.", storage.handshake("chat-1", slot, "fp") { ask("Принял.") })
        assertEquals("Принял.", storage.handshake("chat-2", slot, "fp") { ask("Принял.") })
        assertEquals(2, calls)
    }

    @Test fun theTwoRoundsAreRememberedSeparately() {
        assertEquals("A", storage.handshake("chat", PromptAckStore.Slot.INSTRUCTION, "fp") { ask("A") })
        assertEquals("B", storage.handshake("chat", PromptAckStore.Slot.SERVICE, "fp") { ask("B") })
        assertEquals(2, calls)
    }

    /** Один обмен на диалог: десять сообщений не должны стоить десяти знакомств. */
    @Test fun tenMessagesCostOneHandshake() {
        repeat(10) {
            storage.handshake("chat", PromptAckStore.Slot.INSTRUCTION, "fp") { ask("A") }
            storage.handshake("chat", PromptAckStore.Slot.SERVICE, "fp") { ask("B") }
        }
        assertEquals(2, calls)
    }
}
