package app.jarvis.data

import app.jarvis.net.ApiMessage
import app.jarvis.net.ApiToolCall
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Задача, которая отвечает текстом и ничего не делает, крутилась всю ночь: шаг заканчивался,
 * приложение дописывало «продолжай», модель прощалась, и так сотни раз. Проверка повторяющихся
 * вызовов инструментов этот случай не ловила — вызовов не было вовсе.
 */
class AgentLoopGuardTest {
    private val continuePrompt = "Задача ещё не завершена. Продолжай."

    private fun assistant(text: String) = ApiMessage("assistant", text)
    private fun withCall(name: String) =
        ApiMessage("assistant", "", toolCalls = listOf(ApiToolCall("1", name, JSONObject())))
    private fun keepGoing() = ApiMessage("user", continuePrompt)
    private fun toolResult() = ApiMessage("tool", "готово", toolCallId = "1")

    @Test fun aFreshTaskIsNotIdle() {
        assertEquals(0, AgentLoopGuard.idleStreak(emptyList(), continuePrompt))
        assertFalse(AgentLoopGuard.isIdleLoop(listOf(ApiMessage("system", "инструкция")), continuePrompt))
    }

    @Test fun oneThoughtBetweenActionsIsNormal() {
        val messages = listOf(withCall("ssh_exec"), toolResult(), assistant("вижу, что сервис поднят"))
        assertEquals(1, AgentLoopGuard.idleStreak(messages, continuePrompt))
        assertFalse(AgentLoopGuard.isIdleLoop(messages, continuePrompt))
    }

    /** Ровно тот случай из ночного прогона: прощание за прощанием. */
    @Test fun repeatedTextOnlyRepliesAreCaught() {
        val messages = listOf(
            assistant("Принято."), keepGoing(),
            assistant("С теплом."), keepGoing(),
            assistant("Всего тебе.")
        )
        assertEquals(3, AgentLoopGuard.idleStreak(messages, continuePrompt))
        assertTrue(AgentLoopGuard.isIdleLoop(messages, continuePrompt))
    }

    /** Подсказку дописывает приложение — она не считается признаком работы. */
    @Test fun theAppsOwnPromptDoesNotResetTheStreak() {
        val messages = listOf(assistant("а"), keepGoing(), assistant("б"), keepGoing(), assistant("в"))
        assertEquals(3, AgentLoopGuard.idleStreak(messages, continuePrompt))
    }

    @Test fun aToolCallResetsTheStreak() {
        val messages = listOf(
            assistant("а"), keepGoing(), assistant("б"), keepGoing(),
            withCall("ssh_exec"), toolResult(), assistant("в")
        )
        assertEquals(1, AgentLoopGuard.idleStreak(messages, continuePrompt))
        assertFalse(AgentLoopGuard.isIdleLoop(messages, continuePrompt))
    }

    /** Указание человека — тоже событие: после него агент заслуживает новых попыток. */
    @Test fun aUserInstructionResetsTheStreak() {
        val messages = listOf(
            assistant("а"), keepGoing(), assistant("б"),
            ApiMessage("user", "смени подход, посмотри логи"),
            assistant("хорошо")
        )
        assertEquals(1, AgentLoopGuard.idleStreak(messages, continuePrompt))
    }

    @Test fun theStepCeilingStopsAnEndlessTask() {
        assertFalse(AgentLoopGuard.isExhausted(0))
        assertFalse(AgentLoopGuard.isExhausted(AgentLoopGuard.MAX_TOTAL_STEPS - 1))
        assertTrue(AgentLoopGuard.isExhausted(AgentLoopGuard.MAX_TOTAL_STEPS))
        assertTrue(AgentLoopGuard.isExhausted(500))
    }

    @Test fun reasonsNameTheActualNumbers() {
        assertTrue(AgentLoopGuard.idleReason(4).contains("4"))
        assertTrue(AgentLoopGuard.exhaustedReason(60).contains("60"))
    }

    /** Порог должен быть выше единицы, иначе обычное рассуждение обрывает задачу. */
    @Test fun theThresholdLeavesRoomForThinking() {
        assertTrue(AgentLoopGuard.MAX_IDLE_STEPS >= 2)
        assertTrue(AgentLoopGuard.MAX_TOTAL_STEPS > AgentLoopGuard.MAX_IDLE_STEPS)
    }

    /**
     * Защита от ночного цикла обрывала и длинную работу на полпути. Нулевой порог —
     * это выключенная настройкой остановка: решает человек, а не счётчик.
     */
    @Test fun aZeroThresholdMeansTheTaskIsNeverStoppedByTheGuard() {
        val messages = listOf(
            assistant("а"), keepGoing(), assistant("б"), keepGoing(),
            assistant("в"), keepGoing(), assistant("г")
        )
        assertEquals(4, AgentLoopGuard.idleStreak(messages, continuePrompt))
        assertFalse(AgentLoopGuard.isIdleLoop(messages, continuePrompt, maxIdleSteps = 0))
        assertFalse(AgentLoopGuard.isExhausted(10_000, limit = 0))
        assertFalse(AgentLoopGuard.isExhausted(10_000, limit = -1))
    }

    @Test fun aCustomCeilingIsRespected() {
        assertFalse(AgentLoopGuard.isExhausted(149, limit = 150))
        assertTrue(AgentLoopGuard.isExhausted(150, limit = 150))
        assertTrue(AgentLoopGuard.isIdleLoop(listOf(assistant("а")), continuePrompt, maxIdleSteps = 1))
    }

    /** Подсказка вместо остановки бесполезна, если не называет оба выхода из положения. */
    @Test fun theNudgeNamesTheNumberAndBothWaysOut() {
        val nudge = AgentLoopGuard.nudge(5)
        assertTrue(nudge.contains("5"))
        assertTrue(nudge.contains("finish_task"))
        assertTrue(nudge.contains("инструмент"))
    }

    /** Настраиваемый потолок обязан включать значение по умолчанию, иначе слайдер его теряет. */
    @Test fun theDefaultCeilingFitsInsideTheAdjustableRange() {
        assertTrue(AgentLoopGuard.MIN_TASK_STEPS <= AgentLoopGuard.MAX_TOTAL_STEPS)
        assertTrue(AgentLoopGuard.MAX_TOTAL_STEPS <= AgentLoopGuard.MAX_TASK_STEPS)
        assertTrue(AgentLoopGuard.MIN_TASK_STEPS > AgentLoopGuard.MAX_IDLE_STEPS)
    }
}
