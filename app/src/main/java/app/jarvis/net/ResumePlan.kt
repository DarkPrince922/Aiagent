package app.jarvis.net

/**
 * Решает, что делать с уже скачанным куском файла, увидев ответ сервера.
 *
 * Логика вынесена отдельно и не трогает сеть: докачка на мобильном интернете — самая
 * вероятная и самая дорогая ошибка (повторно тянуть два с половиной гигабайта), а
 * поведение серверов на Range-запросы предсказуемо ровно настолько, насколько описано здесь.
 */
object ResumePlan {
    data class Decision(
        /** Со скольки байт продолжать писать в файл. */
        val startFrom: Long,
        /** Полный размер файла, если сервер его сообщил. */
        val totalBytes: Long?,
        val error: String? = null
    ) {
        val restarts: Boolean get() = startFrom == 0L
    }

    /**
     * @param existingBytes сколько уже лежит в .part
     * @param status код ответа на запрос с `Range: bytes=existingBytes-`
     * @param contentLength заголовок Content-Length ответа
     * @param contentRange заголовок Content-Range, например `bytes 100-999/1000`
     */
    fun decide(existingBytes: Long, status: Int, contentLength: Long?, contentRange: String?): Decision = when {
        // Сервер отдал весь файл целиком, Range проигнорирован — начинаем заново.
        status == 200 -> Decision(0, contentLength)

        status == 206 -> {
            val range = parseContentRange(contentRange)
            when {
                range == null -> Decision(0, contentLength, "Сервер прислал 206 без корректного Content-Range")
                // Отдали не тот кусок, который просили: дописывать в файл нельзя.
                range.first != existingBytes -> Decision(0, range.third)
                else -> Decision(existingBytes, range.third)
            }
        }

        // Диапазон вне файла: либо всё уже скачано, либо .part испорчен. Безопаснее заново.
        status == 416 -> Decision(0, null)

        else -> Decision(0, null, "Сервер ответил HTTP $status")
    }

    /** `bytes 100-999/1000` → (100, 999, 1000). Возвращает null, если формат не тот. */
    private fun parseContentRange(value: String?): Triple<Long, Long, Long?>? {
        val match = CONTENT_RANGE.matchEntire(value?.trim().orEmpty()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].takeIf { it != "*" }?.toLongOrNull()
        return Triple(start, end, total)
    }

    private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)
}
