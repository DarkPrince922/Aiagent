package app.jarvis.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Команды подсматривания — это обычный shell, и проверять его надо shell'ом.
 *
 * Каталог операций и `tmux` подменяются заглушками: так видно и разбор вывода, и то, что
 * отсутствие tmux остаётся пустым списком, а не ошибкой на весь экран.
 */
class TerminalProbeTest {

    /**
     * Запускает скрипт настоящим `/bin/sh`.
     *
     * Скрипт передаётся файлом, а не аргументом `-c`: аргументы процесса JVM кодирует
     * системной локалью, и на ASCII-локали кириллица в командах превратилась бы в «?» —
     * проверка ловила бы кодировку сборочной машины вместо самого скрипта. Байты файла
     * и чтение вывода задаются явным UTF-8.
     */
    private fun sh(script: String, home: File, path: String? = null): Pair<Int, String> {
        val file = File(home, "probe-${System.nanoTime()}.sh")
        file.writeText(script, Charsets.UTF_8)
        val builder = ProcessBuilder("/bin/sh", file.absolutePath)
        builder.environment()["HOME"] = home.absolutePath
        if (path != null) builder.environment()["PATH"] = path
        builder.redirectErrorStream(true)
        val process = builder.start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        return process.waitFor() to output
    }

    private fun operation(home: File, id: String, command: String, stdout: String, exit: String?): File {
        val dir = File(home, ".cache/jarvis-agent/ops/$id").apply { mkdirs() }
        File(dir, "command.sh").writeText(command, Charsets.UTF_8)
        File(dir, "stdout").writeText(stdout, Charsets.UTF_8)
        File(dir, "stderr").writeText("", Charsets.UTF_8)
        exit?.let { File(dir, "exit").writeText(it, Charsets.UTF_8) }
        return dir
    }

    @Test fun quotingSurvivesEverythingAShellCaresAbout() {
        listOf("ls -la", "echo 'привет'", "rm -rf /; echo нет", "\$(whoami)", "a\"b", "it's fine")
            .forEach { raw ->
                val (code, out) = sh("printf '%s' ${TerminalProbe.quote(raw)}", createTempDir())
                assertEquals(0, code)
                assertEquals("Аргумент должен дойти дословно", raw, out)
            }
    }

    /** Кавычим так, что подстановка не выполняется, а печатается. */
    @Test fun substitutionInsideAnArgumentIsNotExecuted() {
        val (_, out) = sh("printf '%s' ${TerminalProbe.quote("\$(id -u)")}", createTempDir())
        assertEquals("\$(id -u)", out)
    }

    @Test fun theOperationListingReportsStateForEachDirectory() {
        val home = createTempDir()
        operation(home, "aaaa1111", "echo первая\n", "готово\n", exit = "0")
        operation(home, "bbbb2222", "sleep 100", "", exit = null)
        val (code, out) = sh(TerminalProbe.OPERATIONS, home)
        assertEquals(0, code)
        val entries = TerminalProbe.parse(out)
        assertEquals(2, entries.size)
        val states = entries.associate { it.id to it.detail }
        assertTrue("Завершённая операция должна показывать код: ${states["aaaa1111"]}", states["aaaa1111"]!!.contains("код 0"))
        // Ни файла exit, ни живого pid — операция не дожила до конца.
        assertTrue("Осиротевшая операция должна быть видна: ${states["bbbb2222"]}", states["bbbb2222"]!!.contains("прервана"))
        assertTrue("В строке должна быть команда: ${states["aaaa1111"]}", states["aaaa1111"]!!.contains("echo первая"))
    }

    /** Живой процесс отличается от мёртвого: pid проверяется, а не принимается на слово. */
    @Test fun aRunningOperationIsReportedAsRunning() {
        val home = createTempDir()
        val dir = operation(home, "cccc3333", "sleep 100", "", exit = null)
        File(dir, "pid").writeText("${ProcessHandle.current().pid()}\n")
        val (_, out) = sh(TerminalProbe.OPERATIONS, home)
        assertTrue(out.contains("выполняется"))
    }

    @Test fun anEmptyLedgerIsNotAnError() {
        val (code, out) = sh(TerminalProbe.OPERATIONS, createTempDir())
        assertEquals(0, code)
        assertEquals(emptyList<RemoteEntry>(), TerminalProbe.parse(out))
    }

    @Test fun theOperationDumpShowsCommandThenOutput() {
        val home = createTempDir()
        operation(home, "dddd4444", "make build\n", "собрано\n", exit = "0")
        val (code, out) = sh(TerminalProbe.operation("dddd4444"), home)
        assertEquals(0, code)
        assertTrue(out.contains("=== КОМАНДА ==="))
        assertTrue(out.contains("make build"))
        assertTrue(out.contains("собрано"))
        assertTrue(out.indexOf("make build") < out.indexOf("собрано"))
    }

    /** Имя каталога приходит с сервера: в путь оно попадает только очищенным. */
    @Test fun aHostileOperationIdCannotEscapeTheLedger() {
        val script = TerminalProbe.operation("../../../../etc/passwd")
        assertTrue("Точки и слэши должны исчезнуть", !script.contains(".."))
        assertTrue(script.contains("ops/etcpasswd"))
    }

    /** Сервер без tmux обслуживается nohup-запуском: список пуст, ошибки нет. */
    @Test fun aMissingTmuxLeavesAnEmptyListInsteadOfAFailure() {
        val bare = createTempDir().apply { File(this, "bin").mkdirs() }
        val (code, out) = sh(TerminalProbe.SESSIONS, bare, path = File(bare, "bin").absolutePath)
        assertEquals(0, code)
        assertEquals(emptyList<RemoteEntry>(), TerminalProbe.parse(out))
    }

    @Test fun sessionsAreParsedWithTheirDetails() {
        val entries = TerminalProbe.parse("jarvis-a1b2\t1 окон\tотключена\njarvis-c3d4\t2 окон\tподключена\n")
        assertEquals(listOf("jarvis-a1b2", "jarvis-c3d4"), entries.map { it.id })
        assertEquals("1 окон · отключена", entries[0].detail)
    }

    @Test fun captureAsksForHistoryAndStaysWithinBounds() {
        assertTrue(TerminalProbe.capture("jarvis-a1", lines = 100).contains("-S -100"))
        assertTrue(TerminalProbe.capture("jarvis-a1", lines = 5).contains("-S -50"))
        assertTrue(TerminalProbe.capture("jarvis-a1", lines = 99_999).contains("-S -2000"))
        assertTrue(TerminalProbe.capture("a b; rm -rf /").contains("'a b; rm -rf /'"))
    }

    @Test fun sendKeysCarriesTheCommandAndPressesEnter() {
        val script = TerminalProbe.sendKeys("jarvis-a1", "tail -f log")
        assertTrue(script.startsWith("tmux send-keys -t 'jarvis-a1' 'tail -f log'"))
        assertTrue(script.endsWith(" Enter"))
    }

    /** Своё окно переиспользуется: второе нажатие не должно плодить сессии. */
    @Test fun ownSessionIsCreatedOnlyWhenMissing() {
        val script = TerminalProbe.createSession(TerminalProbe.USER_SESSION)
        assertTrue(script.startsWith("tmux has-session -t 'jarvis-term'"))
        assertTrue(script.contains("|| tmux new-session -d -s 'jarvis-term'"))
        // Без команды tmux запускает оболочку по умолчанию — именно она и нужна человеку.
        assertTrue(!script.trimEnd().endsWith("'jarvis-term' sh"))
    }

    private fun createTempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "jarvis-terminal-${System.nanoTime()}").apply { mkdirs() }
}
