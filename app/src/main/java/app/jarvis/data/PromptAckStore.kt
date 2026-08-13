package app.jarvis.data

import android.content.Context

/**
 * Ответы модели на знакомство: отдельно на инструкцию, отдельно на служебные правила.
 *
 * Берутся один раз на диалог, а не на каждое сообщение: на своём сервере лишний запрос стоит
 * времени. Каждый привязан к своему отпечатку, поэтому правка инструкции переспрашивает оба,
 * а изменение набора инструментов — только второй.
 */
class PromptAckStore(context: Context) {
    private val prefs = context.getSharedPreferences("prompt_ack", Context.MODE_PRIVATE)

    fun get(conversationId: String, slot: Slot, fingerprint: String): String? {
        if (prefs.getString(key(conversationId, slot, "fp"), null) != fingerprint) return null
        return prefs.getString(key(conversationId, slot, "text"), null)?.takeIf { it.isNotBlank() }
    }

    fun save(conversationId: String, slot: Slot, fingerprint: String, ack: String) {
        prefs.edit()
            .putString(key(conversationId, slot, "fp"), fingerprint)
            .putString(key(conversationId, slot, "text"), ack.trim())
            .apply()
    }

    fun clear(conversationId: String) {
        val editor = prefs.edit()
        Slot.entries.forEach { slot ->
            editor.remove(key(conversationId, slot, "fp")).remove(key(conversationId, slot, "text"))
        }
        editor.apply()
    }

    private fun key(conversationId: String, slot: Slot, suffix: String) =
        "${slot.name.lowercase()}:$suffix:$conversationId"

    enum class Slot { INSTRUCTION, SERVICE }
}
