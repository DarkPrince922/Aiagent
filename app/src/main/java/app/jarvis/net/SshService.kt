package app.jarvis.net

import app.jarvis.data.SshProfile
import app.jarvis.data.SshProfileStore
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayOutputStream

data class SshResult(val output: String, val exitCode: Int, val fingerprint: String)

class SshService(private val profiles: SshProfileStore) {
    fun execute(profile: SshProfile, command: String): SshResult {
        require(command.isNotBlank()) { "SSH-команда пуста" }
        require(command.length <= 8_000) { "SSH-команда слишком длинная" }
        val jsch = JSch()
        if (profile.privateKey.isNotBlank()) {
            jsch.addIdentity(
                "jarvis-${profile.id}",
                profile.privateKey.toByteArray(),
                null,
                profile.passphrase.takeIf { it.isNotEmpty() }?.toByteArray()
            )
        }
        if (profile.fingerprint.isNotBlank()) jsch.hostKeyRepository = FingerprintRepository(jsch, profile.fingerprint)
        val session = jsch.getSession(profile.username, profile.host, profile.port).apply {
            if (profile.password.isNotBlank()) setPassword(profile.password.toByteArray())
            setConfig("StrictHostKeyChecking", if (profile.fingerprint.isBlank()) "no" else "yes")
            setConfig("PreferredAuthentications", if (profile.privateKey.isNotBlank()) "publickey,password,keyboard-interactive" else "password,keyboard-interactive")
            timeout = 20_000
            serverAliveInterval = 10_000
            serverAliveCountMax = 2
        }
        try {
            session.connect(20_000)
            val fingerprint = session.hostKey.getFingerPrint(jsch)
            if (profile.fingerprint.isBlank()) profiles.trust(profile.id, fingerprint)
            val channel = session.openChannel("exec") as ChannelExec
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            channel.setCommand(command)
            channel.outputStream = stdout
            channel.setErrStream(stderr)
            channel.connect(15_000)
            val started = System.currentTimeMillis()
            while (!channel.isClosed && System.currentTimeMillis() - started < 60_000) Thread.sleep(100)
            if (!channel.isClosed) {
                channel.disconnect()
                throw JSchException("Команда превысила лимит 60 секунд")
            }
            val exit = channel.exitStatus
            channel.disconnect()
            val text = buildString {
                append(stdout.toString(Charsets.UTF_8.name()))
                val errors = stderr.toString(Charsets.UTF_8.name())
                if (errors.isNotBlank()) append(if (isEmpty()) "" else "\n").append(errors)
            }.take(64_000)
            return SshResult(text.ifBlank { "Команда завершилась без вывода" }, exit, fingerprint)
        } finally {
            session.disconnect()
        }
    }

    private class FingerprintRepository(private val jsch: JSch, private val expected: String) : HostKeyRepository {
        override fun check(host: String, key: ByteArray): Int = try {
            if (HostKey(host, key).getFingerPrint(jsch).equals(expected, true)) HostKeyRepository.OK else HostKeyRepository.CHANGED
        } catch (_: Exception) { HostKeyRepository.CHANGED }
        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
        override fun remove(host: String?, type: String?) = Unit
        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
        override fun getKnownHostsRepositoryID() = "Jarvis pinned fingerprint"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }
}
