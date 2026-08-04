package app.jarvis.llm

import app.jarvis.data.LlmEngine
import app.jarvis.data.ProviderSettings
import app.jarvis.net.ApiAnswer
import app.jarvis.net.ApiMessage
import app.jarvis.net.ChatApi
import app.jarvis.net.ConnectionCheck
import app.jarvis.net.LanguageModel
import org.json.JSONArray

/**
 * Выбирает движок на каждый запрос по текущим настройкам.
 *
 * Решение принимается здесь, а не в агент-цикле: переключение движка в настройках должно
 * подхватываться сразу, в том числе автономной задачей, которая уже выполняется.
 */
class LanguageModelRouter(
    private val cloud: ChatApi,
    private val local: LocalLanguageModel
) : LanguageModel {

    override fun complete(settings: ProviderSettings, messages: List<ApiMessage>, toolSchemas: JSONArray): ApiAnswer =
        engine(settings).complete(settings, messages, toolSchemas)

    override fun check(settings: ProviderSettings): ConnectionCheck = engine(settings).check(settings)

    /** Прерывает локальную генерацию: облачный запрос останавливается своим механизмом. */
    fun cancelLocal() = local.cancel()

    private fun engine(settings: ProviderSettings): LanguageModel = when (settings.engine) {
        LlmEngine.LOCAL -> local
        LlmEngine.CLOUD -> cloud
    }
}
