package app.jarvis.tools

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Инструмент, у которого есть обработчик, но нет схемы, для модели не существует.
 *
 * Именно так и потерялись файловые инструменты: `read_file` был реализован, попал в каталог
 * и в LOCAL_TOOLS, но `put(schema("read_file", ...))` никто не написал — и модель честно
 * отвечала «такого инструмента в моей сессии нет». Обычным unit-тестом это не ловится:
 * ToolRegistry требует Context, а схемы приватные. Поэтому сверяем исходник.
 */
class ToolSchemaCoverageTest {
    /**
     * Рабочий каталог unit-тестов задаёт Gradle, и полагаться на него нельзя — поэтому
     * поднимаемся вверх, пока не найдём модуль. Не нашли — тест пропускается, а не роняет
     * сборку из-за расположения файлов.
     */
    private val source: File? = generateSequence(File("").absoluteFile) { it.parentFile }
        .take(5)
        .flatMap { root ->
            sequenceOf("", "app/").map { File(root, "${it}src/main/java/app/jarvis/tools/ToolRegistry.kt") }
        }
        .firstOrNull { it.isFile }

    /** Имена веток `when (name)` внутри execute(), включая ветки на несколько имён. */
    private fun handledNames(text: String): Set<String> {
        val body = text.substringAfter("fun execute(").substringBefore("catch (error: CancellationException)")
        return Regex("""^\s+((?:"[a-z_]+",\s*)*"[a-z_]+")\s*->""", RegexOption.MULTILINE)
            .findAll(body)
            .flatMap { Regex("\"([a-z_]+)\"").findAll(it.groupValues[1]) }
            .map { it.groupValues[1] }
            .toSet()
    }

    private fun declaredNames(text: String): Set<String> {
        val body = text.substringAfter("private fun allSchemas").substringBefore("fun sshContext")
        return Regex("""put\(\s*schema\(\s*"([a-z_]+)"""").findAll(body).map { it.groupValues[1] }.toSet()
    }

    @Test fun everyExecutableToolIsDeclaredToTheModel() {
        assumeTrue("Исходник ToolRegistry.kt не найден относительно ${File("").absolutePath}", source != null)
        val text = source!!.readText()
        val missing = handledNames(text) - declaredNames(text) - IGNORED
        assertTrue("Нет схемы, модель не увидит эти инструменты: ${missing.sorted()}", missing.isEmpty())
    }

    @Test fun everyDeclaredToolHasAnImplementation() {
        assumeTrue(source != null)
        val text = source!!.readText()
        val orphans = declaredNames(text) - handledNames(text)
        assertTrue("Схема есть, обработчика нет: ${orphans.sorted()}", orphans.isEmpty())
    }

    @Test fun theRegressionItselfIsCovered() {
        assumeTrue(source != null)
        val declared = declaredNames(source!!.readText())
        listOf("read_file", "search_file", "list_files", "write_file", "send_file", "start_autonomous_task")
            .forEach { assertTrue("$it снова пропал из схем", it in declared) }
    }

    private companion object {
        /** Заглушка «только для координатора»: схема появляется лишь в автономном режиме. */
        val IGNORED = setOf("record_progress", "finish_task")
    }
}
