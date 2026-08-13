package app.jarvis.data

import android.content.Context

/**
 * Подтверждение инструкции, полученное от модели.
 *
 * Берётся один раз на диалог, а не на каждое сообщение: это лишний запрос, и на своём
 * сервере он стоит времени. Привязано к содержимому инструкции — поправил промт, и старое
 * подтверждение перестаёт годиться само, без ручного сброса.
 */
class PromptAckStore(context: Context) {
    private val prefs = context.getSharedPreferences("prompt_ack", Context.MODE_PRIVATE)

    fun get(conversationId: String, instruction: String): String? {
        val expected = PromptComposer.fingerprint(instruction)
        if (prefs.getString(fingerprintKey(conversationId), null) != expected) return null
        return prefs.getString(textKey(conversationId), null)?.takeIf { it.isNotBlank() }
    }

    fun save(conversationId: String, instruction: String, ack: String) {
        prefs.edit()
            .putString(fingerprintKey(conversationId), PromptComposer.fingerprint(instruction))
            .putString(textKey(conversationId), ack.trim())
            .apply()
    }

    fun clear(conversationId: String) {
        prefs.edit().remove(fingerprintKey(conversationId)).remove(textKey(conversationId)).apply()
    }

    private fun fingerprintKey(conversationId: String) = "fp:$conversationId"
    private fun textKey(conversationId: String) = "ack:$conversationId"
}
