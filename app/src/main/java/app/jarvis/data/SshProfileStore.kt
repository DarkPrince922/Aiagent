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

/** Профиль без секретов: всё, что можно показывать в UI и отдавать модели. */
data class SshProfileSummary(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val fingerprint: String
) {
    val hostKeyTrusted: Boolean get() = fingerprint.isNotBlank()
    fun agentContext(): String = "$id: $name - $username@$host:$port; host_key_trusted=$hostKeyTrusted"
}

fun SshProfile.publicSummary() = SshProfileSummary(
    id = id,
    name = name,
    host = host,
    port = port,
    username = username,
    fingerprint = fingerprint
)

internal fun SshProfile.resetTrustIfEndpointChanged(previous: SshProfile?): SshProfile {
    if (previous == null) return this
    val sameHost = previous.host.equals(host, ignoreCase = true)
    return if (sameHost && previous.port == port) this else copy(fingerprint = "")
}

class SshProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("ssh_profiles", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)

    /**
     * Публичные поля профилей без обращения к Keystore.
     *
     * Списки, контекст модели и опрос UI не должны расшифровывать пароли и приватные ключи:
     * это и лишняя работа на каждый тик, и лишний повод держать секреты в памяти.
     */
    @Synchronized fun summaries(): List<SshProfileSummary> = stored().map { item ->
        SshProfileSummary(
            id = item.getString("id"),
            name = item.optString("name", "Server"),
            host = item.optString("host"),
            port = item.optInt("port", 22),
            username = item.optString("username", "root"),
            fingerprint = item.optString("fingerprint")
        )
    }

    @Synchronized fun all(): List<SshProfile> = stored().map(::withSecrets)

    @Synchronized fun find(idOrName: String): SshProfile? = stored().firstOrNull {
        it.getString("id") == idOrName || it.optString("name").equals(idOrName, true)
    }?.let(::withSecrets)

    @Synchronized fun save(profile: SshProfile) {
        require(profile.host.isNotBlank()) { "Укажите адрес сервера" }
        require(profile.port in 1..65535) { "Некорректный SSH-порт" }
        val entries = stored()
        val previous = entries.firstOrNull { it.getString("id") == profile.id }?.let { item ->
            SshProfile(id = profile.id, host = item.optString("host"), port = item.optInt("port", 22))
        }
        val updated = profile.resetTrustIfEndpointChanged(previous)
        secrets.put("ssh_${profile.id}_password", profile.password)
        secrets.put("ssh_${profile.id}_key", profile.privateKey)
        secrets.put("ssh_${profile.id}_passphrase", profile.passphrase)
        write(entries.filterNot { it.getString("id") == profile.id } + publicEntry(updated))
    }

    @Synchronized fun trust(id: String, fingerprint: String) {
        write(stored().map { if (it.getString("id") == id) it.put("fingerprint", fingerprint) else it })
    }

    @Synchronized fun delete(id: String) {
        write(stored().filterNot { it.getString("id") == id })
        secrets.remove("ssh_${id}_password"); secrets.remove("ssh_${id}_key"); secrets.remove("ssh_${id}_passphrase")
    }

    private fun stored(): List<JSONObject> {
        val array = runCatching { JSONArray(prefs.getString("profiles", "[]")) }.getOrDefault(JSONArray())
        return List(array.length()) { array.getJSONObject(it) }.filter { it.optString("id").isNotBlank() }
    }

    private fun withSecrets(item: JSONObject): SshProfile {
        val id = item.getString("id")
        return SshProfile(
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

    private fun publicEntry(profile: SshProfile) = JSONObject()
        .put("id", profile.id)
        .put("name", profile.name)
        .put("host", profile.host)
        .put("port", profile.port)
        .put("username", profile.username)
        .put("fingerprint", profile.fingerprint)

    private fun write(entries: List<JSONObject>) {
        val array = JSONArray().apply { entries.forEach { put(it) } }
        check(prefs.edit().putString("profiles", array.toString()).commit()) { "Не удалось сохранить SSH-профили" }
    }
}
