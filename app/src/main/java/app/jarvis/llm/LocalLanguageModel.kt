package app.jarvis.llm

import app.jarvis.data.ProviderSettings
import app.jarvis.net.ApiAnswer
import app.jarvis.net.ApiMessage
import app.jarvis.net.ChatFailure
import app.jarvis.net.ConnectionCheck
import app.jarvis.net.LanguageModel
import org.json.JSONArray
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Локальный движок поверх llama.cpp.
 *
 * Отдаёт наружу тот же ApiAnswer, что и облако, поэтому агент-цикл ничего не знает о том,
 * что ответ посчитан на устройстве. Вызовы инструментов ограничены GBNF-грамматикой:
 * четырёхмиллиардная модель иначе регулярно выдумывает имена инструментов и ломает JSON.
 */
class LocalLanguageModel : LanguageModel {
    private val lock = Any()
    private var handle: Long = 0
    private var loadedPath: String = ""
    private var loadedContext: Int = 0
    private val callCounter = AtomicLong()

    override fun complete(settings: ProviderSettings, messages: List<ApiMessage>, toolSchemas: JSONArray): ApiAnswer =
        synchronized(lock) {
            val session = session(settings)
            val schemas = if (settings.toolsEnabled) toolSchemas else JSONArray()
            val grammar = if (schemas.length() > 0) GbnfGrammar.forToolCalls(schemas) else null
            val prompt = fittingPrompt(session, messages, schemas, settings)
            val raw = LlamaBridge.generate(
                handle = session,
                prompt = prompt,
                grammar = grammar,
                maxTokens = settings.localMaxTokens.coerceIn(64, 4_096),
                temperature = 0.3f,
                topP = 0.9f,
                topK = 40,
                seed = System.nanoTime().toInt()
            ) ?: throw ChatFailure.Protocol("Запрос не поместился в контекст локальной модели")
            if (raw.isBlank()) throw ChatFailure.Protocol("Локальная модель вернула пустой ответ")
            ToolCallParser.parse(raw, "local_${callCounter.incrementAndGet()}_")
        }

    override fun check(settings: ProviderSettings): ConnectionCheck {
        val path = settings.localModelPath.trim()
        if (path.isBlank()) return ConnectionCheck(false, "Укажите путь к файлу модели (.gguf)")
        val file = File(path)
        if (!file.isFile) return ConnectionCheck(false, "Файл модели не найден: $path")
        if (!file.canRead()) return ConnectionCheck(false, "Нет доступа к файлу модели; скопируйте его в память приложения")
        return synchronized(lock) {
            runCatching { session(settings) }.fold(
                onSuccess = {
                    val size = file.length() / (1024 * 1024)
                    ConnectionCheck(true, "Модель загружена: ${file.name}, $size МБ, контекст ${LlamaBridge.contextTokens(it)} токенов")
                },
                onFailure = { ConnectionCheck(false, it.message ?: "Не удалось загрузить модель") }
            )
        }
    }

    /** Освобождает память: модель занимает гигабайты, держать её при выключенном движке незачем. */
    fun unload() = synchronized(lock) {
        if (handle != 0L) {
            LlamaBridge.release(handle)
            handle = 0
            loadedPath = ""
        }
    }

    /** Прерывает текущую генерацию; вызывается из другого потока по кнопке «Стоп». */
    fun cancel() {
        val current = handle
        if (current != 0L) LlamaBridge.cancel(current)
    }

    private fun session(settings: ProviderSettings): Long {
        if (!LlamaBridge.ensureLoaded()) {
            throw ChatFailure.Protocol("Нативная библиотека llama.cpp недоступна на этом устройстве")
        }
        val path = settings.localModelPath.trim()
        if (path.isBlank()) throw ChatFailure.Protocol("Не указан файл локальной модели")
        val context = settings.localContextTokens.coerceIn(512, 32_768)
        if (handle != 0L && path == loadedPath && context == loadedContext) return handle
        if (handle != 0L) {
            LlamaBridge.release(handle)
            handle = 0
        }
        val created = LlamaBridge.load(path, context, threads(settings))
        if (created == 0L) throw ChatFailure.Protocol("llama.cpp не смог открыть ${File(path).name}")
        handle = created
        loadedPath = path
        loadedContext = context
        return created
    }

    private fun threads(settings: ProviderSettings): Int {
        if (settings.localThreads > 0) return settings.localThreads.coerceAtMost(16)
        // Больше потоков, чем больших ядер, только вредит: малые ядра тормозят весь батч.
        return (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)
    }

    /**
     * Локальный контекст на порядок меньше облачного, поэтому историю подрезаем здесь же:
     * системная инструкция и хвост диалога сохраняются, выпадает середина.
     */
    private fun fittingPrompt(
        session: Long,
        messages: List<ApiMessage>,
        schemas: JSONArray,
        settings: ProviderSettings
    ): String {
        val budget = LlamaBridge.contextTokens(session) - settings.localMaxTokens.coerceIn(64, 4_096) - 32
        var current = messages
        while (true) {
            val prompt = ChatMlPrompt.render(current, schemas)
            if (budget <= 0 || LlamaBridge.countTokens(session, prompt) <= budget) return prompt
            val dropIndex = current.indexOfFirst { it.role != "system" }
            // Осталась только системная инструкция — дальше резать нечего.
            if (dropIndex < 0 || current.size <= 2) return prompt
            // Вместе с ходом ассистента выбрасываем и результаты его вызовов: осиротевший
            // <tool_response> без соответствующего <tool_call> только путает модель.
            var dropEnd = dropIndex + 1
            while (dropEnd < current.size && current[dropEnd].role == "tool") dropEnd++
            current = current.filterIndexed { index, _ -> index < dropIndex || index >= dropEnd }
        }
    }
}
