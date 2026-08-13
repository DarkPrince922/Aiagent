package app.jarvis.data

import app.jarvis.net.ApiMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Основная инструкция — единственное, что отличает ассистента пользователя от любого другого.
 * Когда она размывается длинным ходом или теряется в отдельном запросе за итогом, это видно
 * сразу: ассистент «забывает», кто он.
 */
class PromptComposerTest {
    private val instruction = "Ты Jarvis. Отвечай кратко и по-русски."

    private fun conversation(messages: Int, size: Int = 100) = buildList {
        add(ApiMessage("system", instruction))
        repeat(messages) { add(ApiMessage("user", "x".repeat(size))) }
    }

    @Test fun instructionComesFirstInTheSystemBlock() {
        val block = PromptComposer.system(instruction, "Дата: сегодня", "SSH: нет")
        assertTrue(block.startsWith(instruction))
        assertTrue(block.contains("Дата: сегодня"))
        assertTrue(block.contains("SSH: нет"))
    }

    @Test fun blankSectionsDoNotLeaveEmptyGaps() {
        val block = PromptComposer.system(instruction, null, "", "Дата: сегодня")
        assertFalse(block.contains("\n\n\n"))
        assertEquals("$instruction\n\nДата: сегодня", block)
    }

    @Test fun anEmptyInstructionStillProducesAUsableSystemBlock() {
        assertTrue(PromptComposer.system("   ").isNotBlank())
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
        val last = withReminder.last()
        assertEquals("system", last.role)
        assertTrue(last.content!!.contains(instruction))
    }

    /** Один большой результат инструмента отодвигает инструкцию не хуже десятка сообщений. */
    @Test fun oneHugeToolResultAlsoTriggersTheReminder() {
        val messages = listOf(
            ApiMessage("system", instruction),
            ApiMessage("user", "прочитай отчёт"),
            ApiMessage("tool", "y".repeat(200_000), toolCallId = "1")
        )
        assertTrue(PromptComposer.needsReminder(messages))
    }

    @Test fun forcedReminderIgnoresLength() {
        val messages = conversation(1)
        val forced = PromptComposer.withReminder(messages, instruction, force = true)
        assertEquals(messages.size + 1, forced.size)
        assertTrue(forced.last().content!!.contains(instruction))
    }

    @Test fun reminderIsSkippedWhenThereIsNoInstruction() {
        val messages = conversation(20)
        assertEquals(messages, PromptComposer.withReminder(messages, "   ", force = true))
    }

    @Test fun clampKeepsTheWholeInstructionEvenBelowTheLimit() {
        val long = "и".repeat(5_000)
        val block = PromptComposer.system(long, "служебное".repeat(2_000))
        val clamped = PromptComposer.clampSystem(block, long, limit = 1_000)
        assertTrue("Инструкция обрезана", clamped.startsWith(long))
        assertTrue(clamped.length < block.length)
    }

    @Test fun clampLeavesShortBlocksUntouched() {
        val block = PromptComposer.system(instruction, "Дата: сегодня")
        assertEquals(block, PromptComposer.clampSystem(block, instruction, limit = 24_000))
    }

    @Test fun clampTrimsTheServicePartWhenTheInstructionIsSmall() {
        val block = PromptComposer.system(instruction, "с".repeat(50_000))
        val clamped = PromptComposer.clampSystem(block, instruction, limit = 1_000)
        assertTrue(clamped.startsWith(instruction))
        assertTrue(clamped.contains("сокращена"))
        assertTrue(clamped.length < 1_200)
    }
}
