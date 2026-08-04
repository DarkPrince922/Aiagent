package app.jarvis.data

import app.jarvis.net.UrlPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    @Test fun presetsAreUsableAsDownloadTargets() {
        assertTrue(ModelCatalog.presets.isNotEmpty())
        ModelCatalog.presets.forEach { preset ->
            assertEquals("Дубликат id ${preset.id}", 1, ModelCatalog.presets.count { it.id == preset.id })
            assertTrue("${preset.id}: адрес должен быть HTTPS", preset.url.startsWith("https://"))
            assertTrue("${preset.id}: ссылка должна вести на .gguf", preset.url.endsWith(".gguf"))
            assertTrue("${preset.id}: нужен ожидаемый размер для проверки места", preset.approxBytes > 0)
            assertTrue("${preset.id}: контекст вне разумных границ", preset.recommendedContext in 512..32_768)
            // Тот же фильтр, что применяется перед постановкой в очередь.
            assertTrue("${preset.id}: не проходит UrlPolicy", runCatching { UrlPolicy.parse(preset.url) }.isSuccess)
        }
    }

    @Test fun fileNameComesFromTheUrl() {
        assertEquals(
            "Qwen3-4B-Q4_K_M.gguf",
            ModelStore.fileNameFor("https://huggingface.co/x/y/resolve/main/Qwen3-4B-Q4_K_M.gguf")
        )
    }

    @Test fun queryStringDoesNotLeakIntoTheFileName() {
        assertEquals(
            "model.gguf",
            ModelStore.fileNameFor("https://example.com/model.gguf?download=true")
        )
    }

    @Test fun pathTraversalCannotEscapeTheModelsDirectory() {
        val name = ModelStore.fileNameFor("https://example.com/%2e%2e%2f%2e%2e%2fetc/passwd")
        assertTrue("Имя не должно содержать разделителей: $name", !name.contains('/'))
        assertTrue(!name.contains(".."))
        assertTrue(name.endsWith(".gguf"))
    }

    @Test fun urlWithoutFileNameStillGetsAStableName() {
        val first = ModelStore.fileNameFor("https://example.com/")
        val second = ModelStore.fileNameFor("https://example.com/")
        // Имя должно совпадать между запусками, иначе докачка не найдёт свой .part.
        assertEquals(first, second)
        assertTrue(first.endsWith(".gguf"))
    }

    @Test fun presetFileNamesAreDistinct() {
        val names = ModelCatalog.presets.map { ModelStore.fileNameFor(it.url) }
        assertEquals(names.size, names.distinct().size)
    }
}
