package app.jarvis.data

/**
 * Модель, которую приложение умеет скачать само.
 *
 * @param approxBytes ожидаемый размер: нужен, чтобы проверить свободное место до начала
 *   закачки, а не на втором гигабайте.
 */
data class ModelPreset(
    val id: String,
    val title: String,
    val description: String,
    val url: String,
    val approxBytes: Long,
    val recommendedContext: Int
)

/**
 * Готовые варианты для загрузки одной кнопкой.
 *
 * Ссылки — обычные HTTPS-адреса файлов на Hugging Face; если какая-то из них устареет,
 * загрузка сообщит об ошибке, а адрес можно ввести вручную в поле «Своя ссылка».
 */
object ModelCatalog {
    private const val GB = 1024L * 1024L * 1024L

    val presets = listOf(
        ModelPreset(
            id = "qwen3-4b-instruct-q4",
            title = "Qwen3 4B Instruct · Q4_K_M",
            description = "Рекомендуется. Лучший баланс качества вызова инструментов и скорости на телефоне",
            url = "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
            approxBytes = (2.5 * GB).toLong(),
            recommendedContext = 4_096
        ),
        ModelPreset(
            id = "qwen3-1_7b-q4",
            title = "Qwen3 1.7B · Q4_K_M",
            description = "Вдвое быстрее и легче, заметно слабее в многошаговых задачах",
            url = "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf",
            approxBytes = (1.1 * GB).toLong(),
            recommendedContext = 4_096
        ),
        ModelPreset(
            id = "qwen3-8b-q4",
            title = "Qwen3 8B · Q4_K_M",
            description = "Умнее остальных, но примерно вдвое медленнее; нужно 12+ ГБ оперативной памяти",
            url = "https://huggingface.co/unsloth/Qwen3-8B-GGUF/resolve/main/Qwen3-8B-Q4_K_M.gguf",
            approxBytes = 5 * GB,
            recommendedContext = 4_096
        )
    )

    fun find(id: String): ModelPreset? = presets.firstOrNull { it.id == id }
}
