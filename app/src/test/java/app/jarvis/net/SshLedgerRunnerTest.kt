package app.jarvis.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshLedgerRunnerTest {
    private val payload = SshLedgerProtocol.dispatchPayload("task-1-call-1", "ls")

    @Test fun commandRunsFromHomeNotFromLedgerDirectory() {
        // Раньше runner оставался в каталоге операции, и одна и та же команда давала разный
        // результат в чате (executeDirect из $HOME) и в автономной задаче.
        val switchedToHome = payload.indexOf("cd \"\$HOME\"")
        val ranCommand = payload.indexOf("sh \"\$dir/command.sh\"")
        assertTrue("Ожидался переход в \$HOME", switchedToHome > 0)
        assertTrue("Ожидался запуск команды по абсолютному пути", ranCommand > 0)
        assertTrue("Команда должна запускаться уже из \$HOME", switchedToHome < ranCommand)
        assertFalse("Команда не должна запускаться относительным путём", payload.contains("sh ./command.sh"))
    }

    @Test fun ledgerArtifactsStayInOperationDirectory() {
        listOf("\$dir/stdout", "\$dir/stderr", "\$dir/exit", "\$dir/running").forEach {
            assertTrue("Ожидался абсолютный путь $it", payload.contains(it))
        }
    }

    @Test fun oldOperationsArePruned() {
        // Без уборки каталог ~/.cache/jarvis-agent/ops рос на каждой операции бесконечно.
        assertTrue(payload.contains("find \"\$base\""))
        assertTrue(payload.contains("-mtime +7"))
    }
}
