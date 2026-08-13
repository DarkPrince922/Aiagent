package app.jarvis.data

import app.jarvis.net.ApiMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Знакомство идёт тремя ходами: инструкция и ответ на неё, служебные правила и ответ на них,
 * и только потом задание. Слипание первых двух в один блок — та самая причина, по которой
 * своя небольшая модель отвечает «не по промту».
 */
class PromptComposerTest {
    private val instruction = "Ты Jarvis. Отвечай кратко и по-русски."
    private val service = PromptComposer.service("Дата: сегодня", "SSH: нет")

    @Test fun instructionGoesOutVerbatimWithNothingAppended() {
        val message = PromptComposer.instruction(instruction)
        assertEquals("system", message.role)
        assertEquals(instruction, message.content)
    }

    /** Ни служебного текста, ни приписки «прочитай и ответь» — модель ответит и так. */
    @Test fun theFirstRoundIsTheInstructionAndNothingElse() {
        val round = PromptComposer.instructionRound(instruction)
        assertEquals(listOf(PromptComposer.instruction(instruction)), round)
        assertEquals(instruction, round.single().content)
    }

    @Test fun theSecondRoundAddsServiceOnlyAfterTheInstructionWasAnswered() {
        val round = PromptComposer.serviceRound(instruction, "Принял.", service)
        assertEquals(3, round.size)
        assertEquals(instruction, round[0].content)
        assertEquals("assistant", round[1].role)
        assertTrue(PromptComposer.isService(round[2]))
        assertTrue("Приписок от приложения быть не должно", round.none { it.role == "user" })
    }

    @Test fun serviceBlockIsSeparateAndSubordinate() {
        assertTrue(service!!.content!!.startsWith(PromptComposer.SERVICE_HEADER))
        assertFalse("Инструкция не должна попадать в служебный блок", service.content.contains(instruction))
    }

    @Test fun emptyServiceBlockIsNotSentAtAll() {
        assertNull(PromptComposer.service(null, "", "   "))
    }

    @Test fun serviceIsRecognisedByHeaderNotByPosition() {
        assertTrue(PromptComposer.isService(service!!))
        assertFalse(PromptComposer.isService(PromptComposer.instruction(instruction)))
        assertFalse(PromptComposer.isService(ApiMessage("user", "привет")))
    }

    @Test fun fullOpeningKeepsBothAnswersInOrder() {
        val opening = PromptComposer.opening(instruction, "Принял инструкцию.", service, "Принял правила.")
        assertEquals(4, opening.size)
        assertEquals(instruction, opening[0].content)
        assertEquals("Принял инструкцию.", opening[1].content)
        assertTrue(PromptComposer.isService(opening[2]))
        assertEquals("Принял правила.", opening[3].content)
        assertEquals(4, PromptComposer.preludeSize(opening))
        assertTrue("В переписку не должно попадать ни одного сообщения от приложения", opening.none { it.role == "user" })
    }

    /** Знакомство не состоялось — уходит то же, что и раньше, без выдуманных реплик. */
    @Test fun withoutAnswersTheOpeningIsJustInstructionAndService() {
        val opening = PromptComposer.opening(instruction, null, service, null)
        assertEquals(2, opening.size)
        assertEquals(instruction, opening[0].content)
        assertTrue(PromptComposer.isService(opening[1]))
        assertTrue(opening.none { it.role == "assistant" })
    }

    @Test fun aMissingServiceAnswerIsNeverInvented() {
        val opening = PromptComposer.opening(instruction, "Принял.", service, null)
        assertTrue(PromptComposer.isService(opening.last()))
        assertEquals(1, opening.count { it.role == "assistant" })
    }

    @Test fun openingWithoutAnyServiceIsJustTheFirstRound() {
        val opening = PromptComposer.opening(instruction, "Принял.", null, null)
        assertEquals(2, opening.size)
        assertEquals("Принял.", opening[1].content)
    }

    @Test fun editingTheInstructionInvalidatesTheOldAnswer() {
        assertEquals(PromptComposer.fingerprint(instruction), PromptComposer.fingerprint("  $instruction  "))
        assertFalse(PromptComposer.fingerprint(instruction) == PromptComposer.fingerprint("$instruction Ещё правило."))
    }

    @Test fun theServiceFingerprintAlsoFollowsTheToolSet() {
        val a = PromptComposer.fingerprint(instruction, "[read_file]", "true")
        val b = PromptComposer.fingerprint(instruction, "[read_file,web_search]", "true")
        assertFalse(a == b)
    }

    @Test fun preludeStopsAtTheFirstUserMessage() {
        val messages = PromptComposer.opening(instruction, "Принял.", service, "Принял.") +
            ApiMessage("user", "привет") + ApiMessage("assistant", "здравствуйте")
        assertEquals(4, PromptComposer.preludeSize(messages))
    }

    @Test fun reminderCountsTheConversationNotThePrelude() {
        val opening = PromptComposer.opening(instruction, "Принял.", service, "Принял.")
        assertFalse("Само знакомство не повод напоминать", PromptComposer.needsReminder(opening))
        val long = opening + List(8) { ApiMessage("user", "x") }
        assertTrue(PromptComposer.needsReminder(long))
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
        val forced = PromptComposer.withReminder(PromptComposer.instructionRound(instruction), instruction, force = true)
        assertTrue(forced.last().content!!.contains(instruction))
    }

    @Test fun reminderIsSkippedWhenThereIsNoInstruction() {
        val messages = listOf(ApiMessage("user", "x"))
        assertEquals(messages, PromptComposer.withReminder(messages, "   ", force = true))
    }

    @Test fun onlyTheServiceBlockIsEverClamped() {
        val long = PromptComposer.service("с".repeat(50_000))!!.content!!
        val clamped = PromptComposer.clampService(long, limit = 1_000)
        assertTrue(clamped.startsWith(PromptComposer.SERVICE_HEADER))
        assertTrue(clamped.contains("сокращена"))
        assertTrue(clamped.length < 1_200)
    }

    @Test fun clampLeavesShortBlocksUntouched() {
        assertEquals(service!!.content, PromptComposer.clampService(service.content!!, limit = 24_000))
    }

    /** Обход для серверов, подменяющих системное сообщение своим. */
    @Test fun theDuplicateCarriesTheInstructionUnchangedAsAUserTurn() {
        val round = PromptComposer.instructionRound(instruction, alsoAsUser = true)
        assertEquals(2, round.size)
        assertEquals("system", round[0].role)
        assertEquals("user", round[1].role)
        assertEquals("Дубль должен быть дословным", instruction, round[1].content)
    }

    @Test fun theDuplicateDoesNotCutTheIntroductionShortWhenTrimming() {
        val opening = PromptComposer.opening(instruction, "Принял.", service, "Принял.", alsoAsUser = true)
        val messages = opening + ApiMessage("user", "привет")
        assertEquals(opening.size, PromptComposer.preludeSize(messages, instruction))
    }

    @Test fun aRealUserMessageStillEndsThePrelude() {
        val messages = PromptComposer.instructionRound(instruction, alsoAsUser = true) + ApiMessage("user", "привет")
        assertEquals(2, PromptComposer.preludeSize(messages, instruction))
    }

    @Test fun anEmptyInstructionStillProducesAUsableSystemMessage() {
        assertTrue(PromptComposer.instruction("   ").content!!.isNotBlank())
    }
}
