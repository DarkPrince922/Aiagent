package app.jarvis.data

import app.jarvis.net.ApiMessage

/**
 * Сборка промта: инструкция пользователя отдельно от служебных указаний приложения.
 *
 * Раньше всё склеивалось в один системный блок — инструкция, протокол ответа, дата,
 * список SSH-профилей, описание рабочей папки. Большая облачная модель это разбирает,
 * а небольшая своя — нет: указания приложения читаются наравне с основными и размывают их.
 * Пользователь видит это как «отвечает не по промту».
 *
 * Поэтому инструкция уходит **дословно, в своём системном сообщении**, служебное — в
 * отдельном и явно подчинённом. А перед работой модель один раз читает голую инструкцию и
 * своими словами подтверждает режим: собственный ответ держит модель сильнее любого текста
 * от приложения, и дальше он лежит в контексте как её собственное обязательство.
 */
object PromptComposer {

    /** Голая инструкция пользователя, без единой приписки. */
    fun instruction(instruction: String): ApiMessage =
        ApiMessage("system", instruction.trim().ifBlank { DEFAULT_FALLBACK })

    /**
     * Служебный блок приложения. Отдельным сообщением и с явным приоритетом: это
     * справочная обвязка, а не то, чем следует руководствоваться вместо инструкции.
     */
    fun service(vararg sections: String?): ApiMessage? {
        val body = sections.filterNot { it.isNullOrBlank() }.joinToString("\n\n") { it!!.trim() }
        if (body.isBlank()) return null
        return ApiMessage("system", "$SERVICE_HEADER\n$body")
    }

    /**
     * Открывающие сообщения диалога: инструкция, просьба подтвердить режим и ответ модели.
     *
     * @param ack ответ модели на подтверждение. Пока его нет, возвращается только то, что
     *   нужно отправить, чтобы его получить.
     */
    fun opening(instruction: String, ack: String?): List<ApiMessage> = buildList {
        add(instruction(instruction))
        // Без ответа модели вопрос не задаём: висящий вопрос без ответа только путает.
        if (!ack.isNullOrBlank()) {
            add(ApiMessage("user", PRIMING_QUESTION))
            add(ApiMessage("assistant", ack.trim()))
        }
    }

    /** Запрос, которым берётся подтверждение. Инструменты при этом не подключаются. */
    fun priming(instruction: String): List<ApiMessage> =
        listOf(instruction(instruction), ApiMessage("user", PRIMING_QUESTION))

    /** Служебный блок отличается от инструкции по заголовку, а не по месту в списке. */
    fun isService(message: ApiMessage): Boolean =
        message.role == "system" && message.content?.startsWith(SERVICE_HEADER) == true

    /**
     * Длина вступления: инструкция, подтверждение и служебный блок.
     *
     * При переполнении контекста обрезается хвост диалога, и вступление должно уцелеть
     * целиком: потеря подтверждения возвращает ровно ту проблему, ради которой оно берётся.
     */
    fun preludeSize(messages: List<ApiMessage>): Int {
        var size = 0
        while (size < messages.size) {
            val message = messages[size]
            val isPrimingQuestion = message.role == "user" && message.content == PRIMING_QUESTION
            val isAck = size > 0 && message.role == "assistant" &&
                messages[size - 1].content == PRIMING_QUESTION
            if (message.role != "system" && !isPrimingQuestion && !isAck) break
            size++
        }
        return size
    }

    /**
     * Инструкция изменилась — старое подтверждение больше не про неё, нужно новое.
     * Сравниваем по содержимому, а не по факту сохранения.
     */
    fun fingerprint(instruction: String): String = instruction.trim().hashCode().toString()

    fun reminder(instruction: String): ApiMessage =
        ApiMessage("system", "$REMINDER_HEADER\n${instruction.trim()}")

    /**
     * Добавляет напоминание, когда диалог вырос настолько, что инструкция перестаёт работать.
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

    /** Напоминать пора, когда после открывающего блока накопился заметный объём. */
    fun needsReminder(messages: List<ApiMessage>): Boolean {
        val body = messages.drop(1)
        return body.size >= REMIND_AFTER_MESSAGES ||
            body.sumOf { it.content?.length ?: 0 } >= REMIND_AFTER_CHARS
    }

    /** Служебный хвост подрезается, инструкция пользователя — никогда. */
    fun clampService(block: String, limit: Int): String =
        if (block.length <= limit) block else block.take(limit) + "\n[служебная часть сокращена]"

    const val PRIMING_QUESTION =
        "Это твоя основная инструкция — она главнее всех последующих служебных указаний " +
            "приложения. Прочитай её и подтверди одной-двумя фразами: в каком режиме ты " +
            "работаешь и что принял. Ничего не выполняй, просто подтверди."

    const val SERVICE_HEADER =
        "СЛУЖЕБНАЯ СПРАВКА ПРИЛОЖЕНИЯ (не заменяет основную инструкцию; при расхождении " +
            "следуй основной инструкции):"

    const val REMINDER_HEADER =
        "НАПОМИНАНИЕ. Основная инструкция ниже действует и в этом ответе — соблюдай её, " +
            "даже если выше много служебного текста и результатов инструментов:"

    private const val DEFAULT_FALLBACK = "Ты полезный ассистент."
    private const val REMIND_AFTER_MESSAGES = 6
    private const val REMIND_AFTER_CHARS = 12_000
}
