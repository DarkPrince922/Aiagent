package app.jarvis.data

import app.jarvis.net.ApiMessage

/**
 * Что делать с ходом, в котором модель ответила текстом и ничего не вызвала.
 *
 * Раньше здесь стоял счётчик: три таких хода подряд — задача останавливалась как застрявшая,
 * шестьдесят шагов всего — тоже. Счётчик убран: он обрывал длинную работу на полпути, а на
 * простую просьбу давал три одинаковых ответа подряд вместо одного.
 *
 * Причина тех трёх ответов не в счётчике, а в том, что задача закрывалась только через
 * `finish_task`: на простую просьбу модель отвечает текстом и вызывать его не считает нужным,
 * приложение говорит «продолжай», и так по кругу. Поэтому текстовый ответ теперь сам по себе
 * может быть результатом задачи.
 */
object AgentAnswerPolicy {

    enum class Decision {
        /** Ответ и есть результат: задача завершается этим текстом. */
        FINISH,

        /** Работа не закончена — один раз просим продолжить. */
        CONTINUE
    }

    /**
     * Сколько ходов подряд модель отвечала только текстом.
     *
     * Подсказки «продолжай» между ответами счёт не прерывают: их дописывает само приложение.
     * Любое действие — вызов инструмента, его результат, слово человека — обнуляет счётчик.
     */
    fun textStreak(messages: List<ApiMessage>, continuePrompt: String): Int {
        var streak = 0
        for (message in messages.asReversed()) {
            when {
                message.role == "assistant" && message.toolCalls.isEmpty() -> streak++
                message.role == "user" && message.content?.trim() == continuePrompt.trim() -> Unit
                else -> return streak
            }
        }
        return streak
    }

    /**
     * @param planning первый ход задачи идёт без инструментов: там модель отвечает планом,
     *   и завершать задачу этим ответом нельзя — работа ещё не начиналась.
     */
    fun decide(messages: List<ApiMessage>, continuePrompt: String, planning: Boolean): Decision {
        if (planning) return Decision.CONTINUE
        val usedTools = messages.any { it.role == "tool" }
        // Инструменты были доступны, но не понадобились — значит, задача была на ответ.
        if (!usedTools) return Decision.FINISH
        // Инструменты работали: один текстовый ход между действиями — обычное рассуждение,
        // а второй подряд означает, что делать модель больше ничего не собирается.
        return if (textStreak(messages, continuePrompt) >= 2) Decision.FINISH else Decision.CONTINUE
    }
}
