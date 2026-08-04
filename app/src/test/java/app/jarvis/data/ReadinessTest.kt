package app.jarvis.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessTest {
    @Test fun localEngineNeedsAModelNotAnApiKey() {
        // Основной баг: без ключа локальный режим отказывался отправлять сообщения,
        // хотя в облако он не ходит вовсе.
        val ready = ProviderSettings(engine = LlmEngine.LOCAL, apiKey = "", localModelPath = "/models/qwen.gguf")
        assertNull(ready.readinessError)
    }

    @Test fun localEngineWithoutModelExplainsWhatToDo() {
        val error = ProviderSettings(engine = LlmEngine.LOCAL, apiKey = "sk-key", localModelPath = "").readinessError
        assertNotNull(error)
        assertTrue("Сообщение не должно требовать ключ: $error", !error!!.contains("ключ"))
    }

    @Test fun cloudEngineStillRequiresTheKey() {
        assertEquals(
            "Добавьте API-ключ в настройках",
            ProviderSettings(engine = LlmEngine.CLOUD, apiKey = "", localModelPath = "/models/qwen.gguf").readinessError
        )
        assertNull(ProviderSettings(engine = LlmEngine.CLOUD, apiKey = "sk-key").readinessError)
    }

    @Test fun cloudIsTheDefaultEngine() {
        assertEquals(LlmEngine.CLOUD, ProviderSettings().engine)
        assertNotNull(ProviderSettings().readinessError)
    }
}
