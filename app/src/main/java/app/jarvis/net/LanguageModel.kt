package app.jarvis.net

import app.jarvis.data.ProviderSettings
import org.json.JSONArray

/**
 * Источник ответов модели.
 *
 * Агент-цикл в ChatRepository и AutonomousAgentManager не должен знать, пришёл ответ из
 * облака или от локального llama.cpp: контракт один и тот же, включая tool calling.
 */
interface LanguageModel {
    fun complete(settings: ProviderSettings, messages: List<ApiMessage>, toolSchemas: JSONArray): ApiAnswer

    /** Проверка готовности движка: связь с API или загруженный файл модели. */
    fun check(settings: ProviderSettings): ConnectionCheck
}
