package app.jarvis.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SshProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Server",
    val host: String = "",
    val port: Int = 22,
    val username: String = "root",
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
    val fingerprint: String = ""
)

data class SshProfileSummary(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val hostKeyTrusted: Boolean
) {
    fun agentContext(): String = "$id: $name - $username@$host:$port; host_key_trusted=$hostKeyTrusted"
}

fun SshProfile.publicSummary() = SshProfileSummary(
    id = id,
    name = name,
    host = host,
    port = port,
    username = username,
    hostKeyTrusted = fingerprint.isNotBlank()
)

internal fun SshProfile.resetTrustIfEndpointChanged(previous: SshProfile?): SshProfile {
    if (previous == null) return this
    val sameHost = previous.host.equals(host, ignoreCase = true)
    return if (sameHost && previous.port == port) this else copy(fingerprint = "")
}

class SshProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("ssh_profiles", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)

    @Synchronized fun all(): List<SshProfile> {
        val array = runCatching { JSONArray(prefs.getString("profiles", "[]")) }.getOrDefault(JSONArray())
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            val id = item.getString("id")
            SshProfile(
                id = id,
                name = item.optString("name", "Server"),
                host = item.optString("host"),
                port = item.optInt("port", 22),
                username = item.optString("username", "root"),
                password = secrets.get("ssh_${id}_password"),
                privateKey = secrets.get("ssh_${id}_key"),
                passphrase = secrets.get("ssh_${id}_passphrase"),
                fingerprint = item.optString("fingerprint")
            )
        }
    }

    fun find(idOrName: String): SshProfile? = all().firstOrNull { it.id == idOrName || it.name.equals(idOrName, true) }

    @Synchronized fun save(profile: SshProfile) {
        require(profile.host.isNotBlank()) { "Укажите адрес сервера" }
        require(profile.port in 1..65535) { "Некорректный SSH-порт" }
        val current = all()
        val previous = current.firstOrNull { it.id == profile.id }
        val stored = profile.resetTrustIfEndpointChanged(previous)
        val profiles = current.filterNot { it.id == profile.id } + stored
        secrets.put("ssh_${profile.id}_password", profile.password)
        secrets.put("ssh_${profile.id}_key", profile.privateKey)
        secrets.put("ssh_${profile.id}_passphrase", profile.passphrase)
        write(profiles)
    }

    @Synchronized fun trust(id: String, fingerprint: String) {
        write(all().map { if (it.id == id) it.copy(fingerprint = fingerprint) else it })
    }

    @Synchronized fun delete(id: String) {
        write(all().filterNot { it.id == id })
        secrets.remove("ssh_${id}_password"); secrets.remove("ssh_${id}_key"); secrets.remove("ssh_${id}_passphrase")
    }

    private fun write(profiles: List<SshProfile>) {
        val array = JSONArray().apply { profiles.forEach { profile ->
            put(JSONObject().put("id", profile.id).put("name", profile.name).put("host", profile.host).put("port", profile.port).put("username", profile.username).put("fingerprint", profile.fingerprint))
        } }
        check(prefs.edit().putString("profiles", array.toString()).commit()) { "Не удалось сохранить SSH-профили" }
    }
}
