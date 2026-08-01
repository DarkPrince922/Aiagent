package app.jarvis.data

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("provider", Context.MODE_PRIVATE)
    fun get() = ProviderSettings(
        endpoint = prefs.getString("endpoint", null) ?: ProviderSettings().endpoint,
        model = prefs.getString("model", null) ?: ProviderSettings().model,
        apiKey = prefs.getString("api_key", "") ?: "",
        systemPrompt = prefs.getString("system", null) ?: ProviderSettings().systemPrompt
    )
    fun save(value: ProviderSettings) = prefs.edit()
        .putString("endpoint", value.endpoint.trimEnd('/'))
        .putString("model", value.model.trim())
        .putString("api_key", value.apiKey.trim())
        .putString("system", value.systemPrompt)
        .apply()
}

