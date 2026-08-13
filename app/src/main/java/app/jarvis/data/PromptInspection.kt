package app.jarvis.data

/**
 * Итог проверки промта: что отправлено и что модель говорит о полученном.
 *
 * [verdict] — единственное, ради чего это делается: понять, доходит ли инструкция вообще,
 * или сервер подставляет модели свою.
 */
data class PromptInspection(val sent: String, val echo: String, val error: String? = null) {
    val delivered: Boolean
        get() = error == null && echo.isNotBlank() && overlap >= 0.5

    /** Доля значимых слов инструкции, которые модель воспроизвела. */
    private val overlap: Double
        get() {
            val words = sent.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 3 }.toSet()
            if (words.isEmpty()) return if (echo.isBlank()) 0.0 else 1.0
            val seen = echo.lowercase()
            return words.count { seen.contains(it) }.toDouble() / words.size
        }

    val verdict: String
        get() = when {
            error != null -> "Запрос не прошёл: $error"
            echo.isBlank() -> "Модель ничего не ответила — инструкцию проверить не удалось"
            delivered -> "Инструкция дошла: модель процитировала её"
            else -> "Инструкция НЕ дошла до модели. Скорее всего сервер подставляет собственный " +
                "системный промт вместо вашего — приложение здесь бессильно, правьте настройки сервера"
        }
}
