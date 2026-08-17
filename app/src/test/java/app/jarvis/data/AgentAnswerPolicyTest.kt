package app.jarvis.data

import app.jarvis.net.ApiMessage
import app.jarvis.net.ApiToolCall
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * На простую просьбу задача отвечала трижды: закрыть её можно было только через `finish_task`,
 * модель его не вызывала, приложение говорило «продолжай» — и так до срабатывания счётчика,
 * который потом объявлял задачу застрявшей. Счётчик убран, вместо него текстовый ответ сам
 * может быть результатом.
 */
class AgentAnswerPolicyTest {
    private val continuePrompt = "Задача ещё не завершена. Продолжай."

    private fun assistant(text: String) = ApiMessage("assistant", text)
    private fun withCall(name: String) =
        ApiMessage("assistant", "", toolCalls = listOf(ApiToolCall("1", name, JSONObject())))
    private fun keepGoing() = ApiMessage("user", continuePrompt)
    private fun toolResult() = ApiMessage("tool", "готово", toolCallId = "1")

    private fun decide(messages: List<ApiMessage>, planning: Boolean = false) =
        AgentAnswerPolicy.decide(messages, continuePrompt, planning)

    /** План — не результат: работа ещё не начиналась, инструментов в том запросе не было. */
    @Test fun theOpeningPlanNeverEndsTheTask() {
        val messages = listOf(ApiMessage("user", "цель"), assistant("Понял, сделаю так-то."))
        assertEquals(AgentAnswerPolicy.Decision.CONTINUE, decide(messages, planning = true))
    }

    /** Инструменты были под рукой и не понадобились — просьба была на ответ. */
    @Test fun aRequestThatNeedsNoToolsIsDoneWithTheFirstAnswer() {
        val messages = listOf(
            ApiMessage("user", "цель"),
            assistant("План."), keepGoing(),
            assistant("Вот ответ.")
        )
        assertEquals(AgentAnswerPolicy.Decision.FINISH, decide(messages))
    }

    /** Один текстовый ход между действиями — обычное рассуждение, работа продолжается. */
    @Test fun oneThoughtBetweenActionsIsNotTheEnd() {
        val messages = listOf(
            withCall("ssh_exec"), toolResult(),
            assistant("Сервис поднят, дальше проверю логи.")
        )
        assertEquals(AgentAnswerPolicy.Decision.CONTINUE, decide(messages))
    }

    /** Второй текстовый ход подряд означает, что делать модель больше ничего не будет. */
    @Test fun theSecondTextTurnInARowEndsTheTask() {
        val messages = listOf(
            withCall("ssh_exec"), toolResult(),
            assistant("Готово."), keepGoing(),
            assistant("Больше делать нечего.")
        )
        assertEquals(2, AgentAnswerPolicy.textStreak(messages, continuePrompt))
        assertEquals(AgentAnswerPolicy.Decision.FINISH, decide(messages))
    }

    /** Три ответа подряд — ровно то, на что жаловался пользователь: этого больше не бывает. */
    @Test fun aThirdAnswerIsNeverReached() {
        var messages = listOf(ApiMessage("user", "цель"), assistant("План."))
        assertEquals(AgentAnswerPolicy.Decision.CONTINUE, decide(messages, planning = true))
        messages = messages + keepGoing() + assistant("Ответ.")
        assertEquals(AgentAnswerPolicy.Decision.FINISH, decide(messages))
    }

    /** Слово человека — событие: после него у агента снова есть право подумать вслух. */
    @Test fun aUserInstructionResetsTheStreak() {
        val messages = listOf(
            withCall("ssh_exec"), toolResult(),
            assistant("а"), keepGoing(),
            ApiMessage("user", "смени подход"),
            assistant("хорошо")
        )
        assertEquals(1, AgentAnswerPolicy.textStreak(messages, continuePrompt))
        assertEquals(AgentAnswerPolicy.Decision.CONTINUE, decide(messages))
    }
}
