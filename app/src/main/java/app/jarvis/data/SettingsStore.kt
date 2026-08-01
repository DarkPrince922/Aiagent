package app.jarvis.data

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("provider", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)
    fun get() = ProviderSettings(
        endpoint = prefs.getString("endpoint", null) ?: ProviderSettings().endpoint,
        model = prefs.getString("model", null) ?: ProviderSettings().model,
        apiKey = secrets.get("provider_api_key").ifBlank { prefs.getString("api_key", "") ?: "" },
        systemPrompt = prefs.getString("system", null) ?: ProviderSettings().systemPrompt,
        agentSteps = prefs.getInt("agent_steps", ProviderSettings().agentSteps),
        toolsEnabled = prefs.getBoolean("tools_enabled", true)
    )
    fun save(value: ProviderSettings) {
        secrets.put("provider_api_key", value.apiKey.trim())
        prefs.edit()
        .putString("endpoint", value.endpoint.trimEnd('/'))
        .putString("model", value.model.trim())
        .remove("api_key")
        .putString("system", value.systemPrompt)
        .putInt("agent_steps", value.agentSteps.coerceIn(1, 10))
        .putBoolean("tools_enabled", value.toolsEnabled)
        .apply()
    }
}
