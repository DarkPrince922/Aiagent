package app.jarvis.data

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("provider", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)
    fun get() = ProviderSettings(
        endpoint = prefs.getString("endpoint", null) ?: ProviderSettings().endpoint,
        model = prefs.getString("model", null) ?: ProviderSettings().model,
        apiKey = secrets.get("provider_api_key").ifBlank { prefs.getString("api_key", "") ?: "" },
        engine = runCatching { LlmEngine.valueOf(prefs.getString("engine", "") ?: "") }.getOrDefault(LlmEngine.CLOUD),
        localModelPath = prefs.getString("local_model_path", null) ?: "",
        localContextTokens = prefs.getInt("local_context", ProviderSettings().localContextTokens),
        localMaxTokens = prefs.getInt("local_max_tokens", ProviderSettings().localMaxTokens),
        localThreads = prefs.getInt("local_threads", 0),
        systemPrompt = prefs.getString("system", null) ?: ProviderSettings().systemPrompt,
        agentSteps = prefs.getInt("agent_steps", ProviderSettings().agentSteps),
        unlimitedAgent = prefs.getBoolean("unlimited_agent", false),
        toolsEnabled = prefs.getBoolean("tools_enabled", true),
        answerBeforeTools = prefs.getBoolean("answer_before_tools", true),
        primePrompt = prefs.getBoolean("prime_prompt", true),
        instructionAsUserTurn = prefs.getBoolean("instruction_as_user", false),
        answerFirstPrompt = prefs.getString("prompt_answer_first", null) ?: PromptDefaults.ANSWER_FIRST,
        toolsPrompt = prefs.getString("prompt_tools", null) ?: PromptDefaults.TOOLS,
        autonomyPrompt = prefs.getString("prompt_autonomy", null) ?: PromptDefaults.AUTONOMY,
        summaryPrompt = prefs.getString("prompt_summary", null) ?: PromptDefaults.SUMMARY,
        maxParallelTasks = prefs.getInt("max_parallel_tasks", 3).coerceIn(1, 4),
        summarizeAnswers = prefs.getBoolean("summarize_answers", true)
    )
    fun save(value: ProviderSettings) {
        secrets.put("provider_api_key", value.apiKey.trim())
        prefs.edit()
        .putString("endpoint", value.endpoint.trimEnd('/'))
        .putString("model", value.model.trim())
        .remove("api_key")
        .putString("engine", value.engine.name)
        .putString("local_model_path", value.localModelPath.trim())
        .putInt("local_context", value.localContextTokens.coerceIn(512, 32_768))
        .putInt("local_max_tokens", value.localMaxTokens.coerceIn(64, 4_096))
        .putInt("local_threads", value.localThreads.coerceIn(0, 16))
        .putString("system", value.systemPrompt)
        .putInt("agent_steps", value.agentSteps.coerceIn(1, 20))
        .putBoolean("unlimited_agent", value.unlimitedAgent)
        .putBoolean("tools_enabled", value.toolsEnabled)
        .putBoolean("answer_before_tools", value.answerBeforeTools)
        .putBoolean("prime_prompt", value.primePrompt)
        .putBoolean("instruction_as_user", value.instructionAsUserTurn)
        .putString("prompt_answer_first", PromptDefaults.orDefault(value.answerFirstPrompt, PromptDefaults.ANSWER_FIRST))
        .putString("prompt_tools", PromptDefaults.orDefault(value.toolsPrompt, PromptDefaults.TOOLS))
        .putString("prompt_autonomy", PromptDefaults.orDefault(value.autonomyPrompt, PromptDefaults.AUTONOMY))
        .putString("prompt_summary", PromptDefaults.orDefault(value.summaryPrompt, PromptDefaults.SUMMARY))
        .putInt("max_parallel_tasks", value.maxParallelTasks.coerceIn(1, 4))
        .putBoolean("summarize_answers", value.summarizeAnswers)
        .apply()
    }
}
