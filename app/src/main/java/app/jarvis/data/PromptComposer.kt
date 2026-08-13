package app.jarvis.data

import app.jarvis.net.ApiMessage

/**
 * Сборка системного блока и удержание основной инструкции по ходу диалога.
 *
 * Инструкция пользователя лежит в самом первом сообщении, и чем длиннее ход, тем дальше
 * она от того места, где модель формирует ответ: после десятка результатов инструментов
 * на сотню килобайт каждый она перестаёт на неё влиять. Плюс итог и синтез шли отдельными
 * запросами, где сверху стояло «сформируй итог», а инструкция терялась совсем — именно там
 * её пропажа заметнее всего, потому что итог дублируется в чат.
 *
 * Поэтому: инструкция всегда стоит первой в системном блоке и повторяется напоминанием
 * перед ответом, когда диалог успел вырасти.
 */
object PromptComposer {

    /** Системный блок: инструкция пользователя первой, служебный контекст — после неё. */
    fun system(instruction: String, vararg sections: String?): String = buildString {
        append(instruction.trim().ifBlank { DEFAULT_FALLBACK })
        sections.filterNot { it.isNullOrBlank() }.forEach { append("\n\n").append(it!!.trim()) }
    }

    /**
     * Напоминание об основной инструкции.
     *
     * Роль `system`: это указание приложения, а не реплика пользователя, и в истории чата
     * оно не сохраняется — уходит только в запрос.
     */
    fun reminder(instruction: String): ApiMessage =
        ApiMessage("system", "$REMINDER_HEADER\n${instruction.trim()}")

    /**
     * Добавляет напоминание, когда диалог уже вырос настолько, что инструкция перестаёт
     * работать. Для короткого обмена это лишние токены без пользы, поэтому есть порог.
     *
     * @param force для запросов, формирующих видимый пользователю текст (итог, синтез):
     *   там инструкция нужна всегда, независимо от длины.
     */
    fun withReminder(
        messages: List<ApiMessage>,
        instruction: String,
        force: Boolean = false
    ): List<ApiMessage> {
        if (instruction.isBlank()) return messages
        if (!force && !needsReminder(messages)) return messages
        return messages + reminder(instruction)
    }

    /** Напоминать пора, когда после системного блока накопился заметный объём. */
    fun needsReminder(messages: List<ApiMessage>): Boolean {
        val body = messages.drop(1)
        return body.size >= REMIND_AFTER_MESSAGES ||
            body.sumOf { it.content?.length ?: 0 } >= REMIND_AFTER_CHARS
    }

    /**
     * Инструкция пользователя не подрезается вместе с остальным системным блоком.
     *
     * Подрезка по общему лимиту откусывала бы её хвост, а с ним — самую конкретную часть
     * указаний. Режем только служебные секции, которые приложение дописало само.
     */
    fun clampSystem(block: String, instruction: String, limit: Int): String {
        if (block.length <= limit) return block
        val keep = maxOf(limit, instruction.trim().length)
        if (block.length <= keep) return block
        return block.take(keep) + "\n[служебная часть сокращена]"
    }

    const val REMINDER_HEADER =
        "НАПОМИНАНИЕ. Основная инструкция ниже действует и в этом ответе — соблюдай её, " +
            "даже если выше много результатов инструментов:"

    private const val DEFAULT_FALLBACK = "Ты полезный ассистент."
    private const val REMIND_AFTER_MESSAGES = 6
    private const val REMIND_AFTER_CHARS = 12_000
}
