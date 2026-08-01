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
        val profiles = all().filterNot { it.id == profile.id } + profile
        write(profiles)
        secrets.put("ssh_${profile.id}_password", profile.password)
        secrets.put("ssh_${profile.id}_key", profile.privateKey)
        secrets.put("ssh_${profile.id}_passphrase", profile.passphrase)
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
        prefs.edit().putString("profiles", array.toString()).apply()
    }
}
