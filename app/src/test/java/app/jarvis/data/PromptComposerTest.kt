package app.jarvis.data

import app.jarvis.net.ApiMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Инструкция пользователя — единственное, что отличает его ассистента от любого другого.
 * Склеенная в один блок со служебными указаниями приложения, у небольшой своей модели она
 * читается наравне с ними и перестаёт работать: «отвечает не по промту».
 */
class PromptComposerTest {
    private val instruction = "Ты Jarvis. Отвечай кратко и по-русски."

    private fun conversation(messages: Int, size: Int = 100) = buildList {
        add(PromptComposer.instruction(instruction))
        repeat(messages) { add(ApiMessage("user", "x".repeat(size))) }
    }

    @Test fun instructionGoesOutVerbatimWithNothingAppended() {
        val message = PromptComposer.instruction(instruction)
        assertEquals("system", message.role)
        assertEquals(instruction, message.content)
    }

    @Test fun serviceBlockIsSeparateAndSubordinate() {
        val service = PromptComposer.service("Дата: сегодня", "SSH: нет")!!
        assertEquals("system", service.role)
        assertTrue(service.content!!.startsWith(PromptComposer.SERVICE_HEADER))
        assertTrue(service.content.contains("Дата: сегодня"))
        assertFalse("Инструкция не должна попадать в служебный блок", service.content.contains(instruction))
    }

    @Test fun emptyServiceBlockIsNotSentAtAll() {
        assertNull(PromptComposer.service(null, "", "   "))
    }

    @Test fun serviceIsRecognisedByHeaderNotByPosition() {
        assertTrue(PromptComposer.isService(PromptComposer.service("что-то")!!))
        assertFalse(PromptComposer.isService(PromptComposer.instruction(instruction)))
        assertFalse(PromptComposer.isService(ApiMessage("user", "привет")))
    }

    @Test fun openingWithoutAcknowledgementIsJustTheInstruction() {
        val opening = PromptComposer.opening(instruction, null)
        assertEquals(1, opening.size)
        assertEquals(instruction, opening.single().content)
    }

    @Test fun openingKeepsTheModelsOwnAcknowledgement() {
        val opening = PromptComposer.opening(instruction, "Принял, работаю кратко и по-русски.")
        assertEquals(3, opening.size)
        assertEquals(instruction, opening[0].content)
        assertEquals(PromptComposer.PRIMING_QUESTION, opening[1].content)
        assertEquals("assistant", opening[2].role)
        assertEquals("Принял, работаю кратко и по-русски.", opening[2].content)
    }

    @Test fun primingAsksWithoutInventingAnAnswer() {
        val priming = PromptComposer.priming(instruction)
        assertEquals(2, priming.size)
        assertEquals(instruction, priming[0].content)
        assertEquals("user", priming[1].role)
        assertTrue(priming.none { it.role == "assistant" })
    }

    @Test fun editingTheInstructionInvalidatesTheOldAcknowledgement() {
        assertEquals(PromptComposer.fingerprint(instruction), PromptComposer.fingerprint("  $instruction  "))
        assertFalse(PromptComposer.fingerprint(instruction) == PromptComposer.fingerprint("$instruction Ещё правило."))
    }

    @Test fun preludeCoversInstructionAcknowledgementAndService() {
        val messages = PromptComposer.opening(instruction, "Принял.") +
            PromptComposer.service("Дата: сегодня")!! +
            ApiMessage("user", "привет") +
            ApiMessage("assistant", "здравствуйте")
        assertEquals(4, PromptComposer.preludeSize(messages))
    }

    @Test fun preludeOfABareInstructionIsJustTheInstruction() {
        assertEquals(1, PromptComposer.preludeSize(conversation(3)))
    }

    @Test fun shortExchangeGetsNoReminder() {
        val messages = conversation(2)
        assertFalse(PromptComposer.needsReminder(messages))
        assertEquals(messages, PromptComposer.withReminder(messages, instruction))
    }

    @Test fun longExchangeGetsTheReminderLast() {
        val messages = conversation(8)
        val withReminder = PromptComposer.withReminder(messages, instruction)
        assertEquals(messages.size + 1, withReminder.size)
        assertEquals("system", withReminder.last().role)
        assertTrue(withReminder.last().content!!.contains(instruction))
    }

    /** Один большой результат инструмента отодвигает инструкцию не хуже десятка сообщений. */
    @Test fun oneHugeToolResultAlsoTriggersTheReminder() {
        val messages = listOf(
            PromptComposer.instruction(instruction),
            ApiMessage("user", "прочитай отчёт"),
            ApiMessage("tool", "y".repeat(200_000), toolCallId = "1")
        )
        assertTrue(PromptComposer.needsReminder(messages))
    }

    @Test fun forcedReminderIgnoresLength() {
        val forced = PromptComposer.withReminder(conversation(1), instruction, force = true)
        assertTrue(forced.last().content!!.contains(instruction))
    }

    @Test fun reminderIsSkippedWhenThereIsNoInstruction() {
        val messages = conversation(20)
        assertEquals(messages, PromptComposer.withReminder(messages, "   ", force = true))
    }

    @Test fun onlyTheServiceBlockIsEverClamped() {
        val service = PromptComposer.service("с".repeat(50_000))!!.content!!
        val clamped = PromptComposer.clampService(service, limit = 1_000)
        assertTrue(clamped.startsWith(PromptComposer.SERVICE_HEADER))
        assertTrue(clamped.contains("сокращена"))
        assertTrue(clamped.length < 1_200)
    }

    @Test fun clampLeavesShortBlocksUntouched() {
        val service = PromptComposer.service("Дата: сегодня")!!.content!!
        assertEquals(service, PromptComposer.clampService(service, limit = 24_000))
    }

    @Test fun anEmptyInstructionStillProducesAUsableSystemMessage() {
        assertTrue(PromptComposer.instruction("   ").content!!.isNotBlank())
    }
}
