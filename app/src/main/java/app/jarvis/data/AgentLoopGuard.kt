package app.jarvis.data

import app.jarvis.net.ApiMessage

/**
 * Условия принудительной остановки автономной задачи.
 *
 * Единственной защитой от зацикливания была проверка повторяющихся вызовов инструментов.
 * Модель, которая отвечает только текстом и не вызывает ничего, под неё не попадала: шаг
 * заканчивался, приложение дописывало «продолжай», модель снова отвечала текстом — и так
 * всю ночь, сотнями обращений к серверу.
 */
object AgentLoopGuard {

    /**
     * Сколько ходов подряд агент отвечал текстом, ничего не делая.
     *
     * Подсказки «продолжай» между ответами не прерывают счёт: их дописывает само приложение,
     * а не пользователь. Любое действие — вызов инструмента, его результат, указание
     * человека — обнуляет счётчик.
     */
    fun idleStreak(messages: List<ApiMessage>, continuePrompt: String): Int {
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
     * Топчется ли агент на месте.
     *
     * Порог не единица: один текстовый ход между действиями — это нормальное рассуждение,
     * а вот несколько подряд означают, что работать агент уже не собирается.
     */
    fun isIdleLoop(messages: List<ApiMessage>, continuePrompt: String): Boolean =
        idleStreak(messages, continuePrompt) >= MAX_IDLE_STEPS

    /** Исчерпан ли общий лимит шагов задачи. */
    fun isExhausted(step: Int): Boolean = step >= MAX_TOTAL_STEPS

    fun idleReason(streak: Int): String =
        "Агент $streak хода подряд отвечал текстом, не выполняя действий и не вызывая finish_task. " +
            "Задача остановлена, чтобы не гонять запросы вхолостую. Уточните цель и возобновите её."

    fun exhaustedReason(step: Int): String =
        "Достигнут предел в $step шагов. Задача остановлена: столько шагов обычно означает, что " +
            "цель недостижима в текущей формулировке. Уточните её и возобновите задачу."

    const val MAX_IDLE_STEPS = 3

    /**
     * Общий потолок шагов задачи.
     *
     * «Без лимита» означало буквально без лимита: задача, которая не может достичь цели,
     * работала до вмешательства человека — а человек мог и спать.
     */
    const val MAX_TOTAL_STEPS = 60
}
