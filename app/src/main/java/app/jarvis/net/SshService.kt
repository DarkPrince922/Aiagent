package app.jarvis.net

import app.jarvis.data.SshProfile
import app.jarvis.data.SshProfileStore
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

enum class SshPhase { COMPLETED, RUNNING, UNKNOWN }

data class SshResult(
    val output: String,
    val exitCode: Int,
    val fingerprint: String,
    val phase: SshPhase = SshPhase.COMPLETED
)

class SshService(private val profiles: SshProfileStore) {
    fun execute(
        profile: SshProfile,
        command: String,
        operationId: String? = null,
        shouldContinue: () -> Boolean = { true }
    ): SshResult {
        require(command.isNotBlank()) { "SSH-команда пуста" }
        require(command.length <= MAX_COMMAND_CHARS) { "SSH-команда слишком длинная" }
        return if (operationId == null) {
            executeDirect(profile, command, shouldContinue)
        } else {
            executeTracked(profile, command, operationId, shouldContinue)
        }
    }

    private fun executeDirect(
        profile: SshProfile,
        command: String,
        shouldContinue: () -> Boolean
    ): SshResult {
        val connection = connectWithRetry(profile, shouldContinue)
        return try {
            val response = runChannel(connection.session, command, COMMAND_TIMEOUT_MS, shouldContinue)
            if (response.timedOut) throw JSchException("Команда превысила лимит 60 секунд")
            SshResult(
                output = combineOutput(response),
                exitCode = response.exitCode,
                fingerprint = connection.fingerprint
            )
        } finally {
            connection.session.disconnect()
        }
    }

    private fun executeTracked(
        profile: SshProfile,
        command: String,
        operationId: String,
        shouldContinue: () -> Boolean
    ): SshResult {
        val dispatchPayload = SshLedgerProtocol.dispatchPayload(operationId, command)
        val pollPayload = SshLedgerProtocol.pollPayload(operationId, command)
        var connection = connectWithRetry(profile, shouldContinue)
        val deadline = System.currentTimeMillis() + COMMAND_TIMEOUT_MS
        var payload = dispatchPayload
        var reconnects = 0
        try {
            while (true) {
                ensureContinues(shouldContinue)
                val response = try {
                    // Once channel.connect starts, the remote shell may have accepted the operation.
                    runChannel(connection.session, payload, LEDGER_CHANNEL_TIMEOUT_MS, shouldContinue)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (reconnects >= LEDGER_RECONNECT_ATTEMPTS) {
                        return uncertain(connection.fingerprint, error.message)
                    }
                    val previousFingerprint = connection.fingerprint
                    connection.session.disconnect()
                    connection = try {
                        connectWithRetry(profile, shouldContinue)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (connectError: Exception) {
                        return uncertain(previousFingerprint, connectError.message ?: error.message)
                    }
                    reconnects++
                    payload = dispatchPayload
                    continue
                }
                val status = if (response.timedOut) null else {
                    SshLedgerProtocol.parse(response.stdout, connection.fingerprint)
                }
                if (status == null) {
                    val detail = if (response.timedOut) {
                        "нет ответа от журнала операции"
                    } else {
                        response.stderr.ifBlank { "сервер не вернул статус операции" }
                    }
                    if (reconnects >= LEDGER_RECONNECT_ATTEMPTS) {
                        return uncertain(connection.fingerprint, detail)
                    }
                    val previousFingerprint = connection.fingerprint
                    connection.session.disconnect()
                    connection = try {
                        connectWithRetry(profile, shouldContinue)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        return uncertain(previousFingerprint, error.message ?: detail)
                    }
                    reconnects++
                    payload = dispatchPayload
                    continue
                }
                if (status.phase != SshPhase.RUNNING) return status
                if (System.currentTimeMillis() >= deadline) return status
                waitFor(POLL_INTERVAL_MS, shouldContinue)
                payload = pollPayload
            }
        } finally {
            connection.session.disconnect()
        }
    }

    private fun connectWithRetry(profile: SshProfile, shouldContinue: () -> Boolean): SshConnection {
        var lastError: JSchException? = null
        repeat(CONNECT_ATTEMPTS) { attempt ->
            ensureContinues(shouldContinue)
            val jsch = createJsch(profile)
            val session = createSession(jsch, profile)
            try {
                runCancellableConnect(shouldContinue, session::disconnect) {
                    session.connect(CONNECT_TIMEOUT_MS)
                }
                ensureContinues(shouldContinue)
                val fingerprint = session.hostKey.getFingerPrint(jsch)
                if (profile.fingerprint.isBlank()) profiles.trust(profile.id, fingerprint)
                return SshConnection(session, fingerprint)
            } catch (error: CancellationException) {
                session.disconnect()
                throw error
            } catch (error: JSchException) {
                session.disconnect()
                lastError = error
                if (attempt == CONNECT_ATTEMPTS - 1 || !isRetryableSshConnectFailure(error.message.orEmpty())) {
                    throw error
                }
                waitFor(CONNECT_RETRY_BASE_MS * (attempt + 1), shouldContinue)
            }
        }
        throw lastError ?: JSchException("Не удалось подключиться к SSH-серверу")
    }

    private fun createJsch(profile: SshProfile) = JSch().apply {
        if (profile.privateKey.isNotBlank()) {
            addIdentity(
                "jarvis-${profile.id}",
                profile.privateKey.toByteArray(),
                null,
                profile.passphrase.takeIf { it.isNotEmpty() }?.toByteArray()
            )
        }
        if (profile.fingerprint.isNotBlank()) {
            hostKeyRepository = FingerprintRepository(this, profile.fingerprint)
        }
    }

    private fun createSession(jsch: JSch, profile: SshProfile) =
        jsch.getSession(profile.username, profile.host, profile.port).apply {
            if (profile.password.isNotBlank()) setPassword(profile.password.toByteArray())
            setConfig("StrictHostKeyChecking", if (profile.fingerprint.isBlank()) "no" else "yes")
            setConfig(
                "PreferredAuthentications",
                if (profile.privateKey.isNotBlank()) {
                    "publickey,password,keyboard-interactive"
                } else {
                    "password,keyboard-interactive"
                }
            )
            timeout = CONNECT_TIMEOUT_MS
            serverAliveInterval = 10_000
            serverAliveCountMax = 2
        }

    private fun runChannel(
        session: Session,
        command: String,
        timeoutMs: Long,
        shouldContinue: () -> Boolean
    ): ChannelResponse {
        ensureContinues(shouldContinue)
        val channel = session.openChannel("exec") as ChannelExec
        val stdout = BoundedOutputStream(CHANNEL_CAPTURE_BYTES)
        val stderr = BoundedOutputStream(CHANNEL_CAPTURE_BYTES)
        channel.setCommand(command)
        channel.outputStream = stdout
        channel.setErrStream(stderr)
        return try {
            runCancellableConnect(shouldContinue, channel::disconnect) {
                channel.connect(CHANNEL_CONNECT_TIMEOUT_MS)
            }
            ensureContinues(shouldContinue)
            val started = System.currentTimeMillis()
            while (!channel.isClosed) {
                ensureContinues(shouldContinue)
                if (System.currentTimeMillis() - started >= timeoutMs) {
                    return ChannelResponse(
                        stdout.text(), stderr.text(), channel.exitStatus,
                        timedOut = true, truncated = stdout.truncated || stderr.truncated
                    )
                }
                sleepChecking(CHANNEL_POLL_MS)
            }
            ChannelResponse(
                stdout.text(), stderr.text(), channel.exitStatus,
                timedOut = false, truncated = stdout.truncated || stderr.truncated
            )
        } finally {
            channel.disconnect()
        }
    }

    private fun combineOutput(response: ChannelResponse): String = buildString {
        append(response.stdout)
        if (response.stderr.isNotBlank()) append(if (isEmpty()) "" else "\n").append(response.stderr)
        if (response.truncated) append(if (isEmpty()) "" else "\n").append("[вывод сокращён]")
    }.take(MAX_OUTPUT_CHARS).ifBlank { "Команда завершилась без вывода" }

    private fun uncertain(fingerprint: String, detail: String?): SshResult = SshResult(
        output = buildString {
            append("Статус SSH-операции неизвестен. Не повторяйте команду с новым operationId; повторный вызов с тем же ID безопасно проверит журнал.")
            detail?.takeIf { it.isNotBlank() }?.let { append(" ").append(it.take(500)) }
        },
        exitCode = -1,
        fingerprint = fingerprint,
        phase = SshPhase.UNKNOWN
    )

    private data class SshConnection(val session: Session, val fingerprint: String)

    private data class ChannelResponse(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val timedOut: Boolean,
        val truncated: Boolean
    )

    private class BoundedOutputStream(private val limit: Int) : OutputStream() {
        private val buffer = ByteArrayOutputStream(limit.coerceAtMost(8_192))
        var truncated = false
            private set

        override fun write(value: Int) {
            if (buffer.size() < limit) buffer.write(value) else truncated = true
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            val available = (limit - buffer.size()).coerceAtLeast(0)
            val accepted = length.coerceAtMost(available)
            if (accepted > 0) buffer.write(bytes, offset, accepted)
            if (accepted < length) truncated = true
        }

        fun text(): String = buffer.toString(Charsets.UTF_8.name())
    }

    private class FingerprintRepository(private val jsch: JSch, private val expected: String) : HostKeyRepository {
        override fun check(host: String, key: ByteArray): Int = try {
            if (HostKey(host, key).getFingerPrint(jsch).equals(expected, true)) {
                HostKeyRepository.OK
            } else {
                HostKeyRepository.CHANGED
            }
        } catch (_: Exception) {
            HostKeyRepository.CHANGED
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID() = "Jarvis pinned fingerprint"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    private companion object {
        const val MAX_COMMAND_CHARS = 8_000
        const val MAX_OUTPUT_CHARS = 64_000
        const val CHANNEL_CAPTURE_BYTES = 96_000
        const val CONNECT_ATTEMPTS = 3
        const val LEDGER_RECONNECT_ATTEMPTS = 2
        const val CONNECT_TIMEOUT_MS = 20_000
        const val CHANNEL_CONNECT_TIMEOUT_MS = 15_000
        const val COMMAND_TIMEOUT_MS = 60_000L
        const val LEDGER_CHANNEL_TIMEOUT_MS = 15_000L
        const val CONNECT_RETRY_BASE_MS = 500L
        const val POLL_INTERVAL_MS = 750L
        const val CHANNEL_POLL_MS = 100L
    }
}

internal fun isRetryableSshConnectFailure(message: String): Boolean {
    val normalized = message.lowercase()
    return listOf(
        "auth fail",
        "auth cancel",
        "reject hostkey",
        "hostkey has been changed",
        "unknownhostkey",
        "algorithm negotiation fail",
        "invalid privatekey"
    ).none(normalized::contains)
}

private fun ensureContinues(shouldContinue: () -> Boolean) {
    if (!shouldContinue()) throw CancellationException("Остановлено пользователем")
}

private fun waitFor(durationMs: Long, shouldContinue: () -> Boolean) {
    var remaining = durationMs
    while (remaining > 0) {
        ensureContinues(shouldContinue)
        val delay = remaining.coerceAtMost(100L)
        sleepChecking(delay)
        remaining -= delay
    }
}

private fun runCancellableConnect(
    shouldContinue: () -> Boolean,
    cancel: () -> Unit,
    connect: () -> Unit
) {
    ensureContinues(shouldContinue)
    val failure = AtomicReference<Throwable?>()
    val finished = CountDownLatch(1)
    val thread = Thread({
        try {
            connect()
        } catch (error: Throwable) {
            failure.set(error)
        } finally {
            finished.countDown()
        }
    }, "jarvis-ssh-connect").apply {
        isDaemon = true
        start()
    }
    while (!awaitConnect(finished, cancel, thread)) {
        if (!shouldContinue()) {
            runCatching(cancel)
            thread.interrupt()
            throw CancellationException("Остановлено пользователем")
        }
    }
    ensureContinues(shouldContinue)
    failure.get()?.let { throw it }
}

private fun awaitConnect(finished: CountDownLatch, cancel: () -> Unit, thread: Thread): Boolean = try {
    finished.await(100, TimeUnit.MILLISECONDS)
} catch (error: InterruptedException) {
    runCatching(cancel)
    thread.interrupt()
    Thread.currentThread().interrupt()
    throw CancellationException("Выполнение прервано").apply { initCause(error) }
}

private fun sleepChecking(durationMs: Long) {
    try {
        Thread.sleep(durationMs)
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw CancellationException("Выполнение прервано").apply { initCause(error) }
    }
}

internal object SshLedgerProtocol {
    private const val MARKER = "__JARVIS_LEDGER_V1__"
    private const val REMOTE_OUTPUT_BYTES = 32
    private const val LEDGER_RETENTION_DAYS = 7

    fun operationKey(operationId: String): String {
        require(operationId.isNotBlank()) { "operationId пуст" }
        require(operationId.toByteArray(Charsets.UTF_8).size <= MAX_OPERATION_ID_BYTES) {
            "operationId слишком длинный"
        }
        return sha256(operationId)
    }

    fun commandHash(command: String): String = sha256(command)

    fun dispatchPayload(operationId: String, command: String): String {
        val operationKey = operationKey(operationId)
        val expectedHash = commandHash(command)
        val encodedCommand = Base64.getEncoder().encodeToString(command.toByteArray(Charsets.UTF_8))
        return """
            umask 077
            base="${'$'}HOME/.cache/jarvis-agent/ops"
            op="${'$'}base/$operationKey"
            expected="$expectedHash"
            marker="$MARKER"
            emit_state() { printf '%s|STATE|%s\n' "${'$'}marker" "${'$'}1"; }
            emit_detail() { printf '%s|DETAIL|%s\n' "${'$'}marker" "${'$'}1"; }
            report() {
              if [ -f "${'$'}op/exit" ]; then
                emit_state COMPLETED
                exit_value=${'$'}(tr -cd '0-9' < "${'$'}op/exit")
                printf '%s|EXIT|%s\n' "${'$'}marker" "${'$'}{exit_value:--1}"
                printf '%s|STDOUT|' "${'$'}marker"
                dd if="${'$'}op/stdout" bs=1024 count=$REMOTE_OUTPUT_BYTES 2>/dev/null | base64 | tr -d '\r\n'
                printf '\n%s|STDERR|' "${'$'}marker"
                dd if="${'$'}op/stderr" bs=1024 count=$REMOTE_OUTPUT_BYTES 2>/dev/null | base64 | tr -d '\r\n'
                printf '\n'
                stdout_size=${'$'}(wc -c < "${'$'}op/stdout" 2>/dev/null || printf 0)
                stderr_size=${'$'}(wc -c < "${'$'}op/stderr" 2>/dev/null || printf 0)
                if [ "${'$'}stdout_size" -gt 32768 ] || [ "${'$'}stderr_size" -gt 32768 ]; then
                  printf '%s|TRUNCATED|1\n' "${'$'}marker"
                fi
              elif [ -f "${'$'}op/pid" ] && kill -0 "${'$'}(cat "${'$'}op/pid")" 2>/dev/null; then
                emit_state RUNNING
              else
                emit_state UNKNOWN
                emit_detail ORPHANED
              fi
            }
            if ! mkdir -p "${'$'}base"; then
              emit_state UNKNOWN
              emit_detail BASE_CREATE_FAILED
              exit 0
            fi
            find "${'$'}base" -mindepth 1 -maxdepth 1 -type d -mtime +$LEDGER_RETENTION_DAYS -exec rm -rf {} + 2>/dev/null || true
            if mkdir "${'$'}op" 2>/dev/null; then
              if ! printf '%s' '$encodedCommand' | base64 -d > "${'$'}op/command.sh.tmp"; then
                emit_state UNKNOWN
                emit_detail COMMAND_DECODE_FAILED
                exit 0
              fi
              mv "${'$'}op/command.sh.tmp" "${'$'}op/command.sh"
              chmod 700 "${'$'}op/command.sh"
              printf '%s\n' "${'$'}expected" > "${'$'}op/hash.tmp"
              mv "${'$'}op/hash.tmp" "${'$'}op/hash"
              cat > "${'$'}op/runner.sh.tmp" <<'JARVIS_RUNNER'
            #!/bin/sh
            umask 077
            dir=${'$'}(cd "${'$'}(dirname "${'$'}0")" && pwd) || exit 125
            : > "${'$'}dir/running"
            cd "${'$'}HOME" 2>/dev/null || cd / || exit 125
            sh "${'$'}dir/command.sh" > "${'$'}dir/stdout.tmp" 2> "${'$'}dir/stderr.tmp"
            code=${'$'}?
            mv "${'$'}dir/stdout.tmp" "${'$'}dir/stdout"
            mv "${'$'}dir/stderr.tmp" "${'$'}dir/stderr"
            printf '%s\n' "${'$'}code" > "${'$'}dir/exit.tmp"
            mv "${'$'}dir/exit.tmp" "${'$'}dir/exit"
            rm -f "${'$'}dir/running"
            exit 0
            JARVIS_RUNNER
              mv "${'$'}op/runner.sh.tmp" "${'$'}op/runner.sh"
              chmod 700 "${'$'}op/runner.sh"
              nohup "${'$'}op/runner.sh" >/dev/null 2>&1 </dev/null &
              runner_pid=${'$'}!
              printf '%s\n' "${'$'}runner_pid" > "${'$'}op/pid.tmp"
              mv "${'$'}op/pid.tmp" "${'$'}op/pid"
            fi
            if [ ! -f "${'$'}op/hash" ]; then
              emit_state UNKNOWN
              emit_detail INCOMPLETE_SETUP
              exit 0
            fi
            actual=${'$'}(cat "${'$'}op/hash")
            if [ "${'$'}actual" != "${'$'}expected" ]; then
              emit_state UNKNOWN
              emit_detail HASH_MISMATCH
              exit 0
            fi
            report
        """.trimIndent()
    }

    fun pollPayload(operationId: String, command: String): String {
        val operationKey = operationKey(operationId)
        val expectedHash = commandHash(command)
        return """
            umask 077
            op="${'$'}HOME/.cache/jarvis-agent/ops/$operationKey"
            expected="$expectedHash"
            marker="$MARKER"
            emit_state() { printf '%s|STATE|%s\n' "${'$'}marker" "${'$'}1"; }
            emit_detail() { printf '%s|DETAIL|%s\n' "${'$'}marker" "${'$'}1"; }
            if [ ! -f "${'$'}op/hash" ]; then
              emit_state UNKNOWN
              emit_detail MISSING_LEDGER
            elif [ "${'$'}(cat "${'$'}op/hash")" != "${'$'}expected" ]; then
              emit_state UNKNOWN
              emit_detail HASH_MISMATCH
            elif [ -f "${'$'}op/exit" ]; then
              emit_state COMPLETED
              exit_value=${'$'}(tr -cd '0-9' < "${'$'}op/exit")
              printf '%s|EXIT|%s\n' "${'$'}marker" "${'$'}{exit_value:--1}"
              printf '%s|STDOUT|' "${'$'}marker"
              dd if="${'$'}op/stdout" bs=1024 count=$REMOTE_OUTPUT_BYTES 2>/dev/null | base64 | tr -d '\r\n'
              printf '\n%s|STDERR|' "${'$'}marker"
              dd if="${'$'}op/stderr" bs=1024 count=$REMOTE_OUTPUT_BYTES 2>/dev/null | base64 | tr -d '\r\n'
              printf '\n'
              stdout_size=${'$'}(wc -c < "${'$'}op/stdout" 2>/dev/null || printf 0)
              stderr_size=${'$'}(wc -c < "${'$'}op/stderr" 2>/dev/null || printf 0)
              if [ "${'$'}stdout_size" -gt 32768 ] || [ "${'$'}stderr_size" -gt 32768 ]; then
                printf '%s|TRUNCATED|1\n' "${'$'}marker"
              fi
            elif [ -f "${'$'}op/pid" ] && kill -0 "${'$'}(cat "${'$'}op/pid")" 2>/dev/null; then
              emit_state RUNNING
            else
              emit_state UNKNOWN
              emit_detail ORPHANED
            fi
        """.trimIndent()
    }

    fun parse(raw: String, fingerprint: String): SshResult? {
        val values = raw.lineSequence().mapNotNull { line ->
            if (!line.startsWith("$MARKER|")) return@mapNotNull null
            val parts = line.split('|', limit = 3)
            if (parts.size != 3) null else parts[1] to parts[2]
        }.toMap()
        val phase = when (values["STATE"]) {
            "COMPLETED" -> SshPhase.COMPLETED
            "RUNNING" -> SshPhase.RUNNING
            "UNKNOWN" -> SshPhase.UNKNOWN
            else -> return null
        }
        if (phase == SshPhase.COMPLETED) {
            val exitCode = values["EXIT"]?.toIntOrNull() ?: return unknownResult(fingerprint, "INVALID_EXIT")
            val stdout = decode(values["STDOUT"]) ?: return unknownResult(fingerprint, "INVALID_STDOUT")
            val stderr = decode(values["STDERR"]) ?: return unknownResult(fingerprint, "INVALID_STDERR")
            val output = buildString {
                append(stdout)
                if (stderr.isNotBlank()) append(if (isEmpty()) "" else "\n").append(stderr)
                if (values["TRUNCATED"] == "1") append(if (isEmpty()) "" else "\n").append("[вывод сокращён]")
            }.take(64_000).ifBlank { "Команда завершилась без вывода" }
            return SshResult(output, exitCode, fingerprint, SshPhase.COMPLETED)
        }
        if (phase == SshPhase.RUNNING) {
            return SshResult("Операция выполняется на сервере", -1, fingerprint, phase)
        }
        return unknownResult(fingerprint, values["DETAIL"] ?: "UNKNOWN")
    }

    private fun unknownResult(fingerprint: String, detail: String) = SshResult(
        "Статус SSH-операции неизвестен: ${detail.take(200)}",
        -1,
        fingerprint,
        SshPhase.UNKNOWN
    )

    private fun decode(value: String?): String? = runCatching {
        String(Base64.getDecoder().decode(value.orEmpty()), Charsets.UTF_8)
    }.getOrNull()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private const val MAX_OPERATION_ID_BYTES = 4_096
}
