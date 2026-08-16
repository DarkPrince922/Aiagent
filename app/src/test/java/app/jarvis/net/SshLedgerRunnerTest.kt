package app.jarvis.net

import org.junit.Assert.assertEquals
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

    /**
     * Команда живёт в tmux-сессии, поэтому переживает обрыв связи и доступна для attach.
     * Откат на nohup обязателен: tmux есть не на каждом сервере, и без него агент
     * перестал бы работать там, где раньше работал.
     */
    @Test fun theCommandRunsInsideTmuxWithAFallback() {
        assertTrue("Ожидался запуск в tmux", payload.contains("tmux new-session -d -s"))
        assertTrue("Ожидалась проверка наличия tmux", payload.contains("command -v tmux"))
        assertTrue("Ожидался откат на nohup", payload.contains("nohup \"\$op/runner.sh\""))
    }

    @Test fun theSessionNameIsStableAndReportedBack() {
        val other = SshLedgerProtocol.dispatchPayload("task-1-call-1", "ls")
        assertEquals("Одна операция — одно имя сессии", payload, other)
        val changed = SshLedgerProtocol.dispatchPayload("task-1-call-2", "ls")
        assertFalse("Разные операции не должны делить сессию", payload == changed)
        assertTrue("Имя сессии должно уходить в отчёт", payload.contains("|SESSION|"))
    }

    /** Без своего pid liveness-проверка сломалась бы: tmux-клиент завершается сразу. */
    @Test fun theRunnerRecordsItsOwnPid() {
        val runner = payload.substringAfter("JARVIS_RUNNER").substringBefore("JARVIS_RUNNER")
        assertTrue("Runner должен писать собственный pid", runner.contains("\$\$"))
        assertTrue(runner.contains("\$dir/pid.tmp"))
        assertFalse("Pid родителя от tmux бесполезен", payload.contains("runner_pid=\$!"))
    }

    @Test fun theDispatcherWaitsForThePidBeforeReporting() {
        // Иначе первый же отчёт видит «нет pid и нет exit» и объявляет операцию потерянной.
        assertTrue(payload.contains("while [ ! -f \"\$op/pid\" ]"))
    }
}
