package app.jarvis.data

import android.content.Context

/**
 * Ответы модели на знакомство: отдельно на инструкцию, отдельно на служебные правила.
 *
 * Берутся один раз на диалог, а не на каждое сообщение: на своём сервере лишний запрос стоит
 * времени. Каждый привязан к своему отпечатку, поэтому правка инструкции переспрашивает оба,
 * а изменение набора инструментов — только второй.
 */
class PromptAckStore(context: Context) : AckStorage {
    private val prefs = context.getSharedPreferences("prompt_ack", Context.MODE_PRIVATE)

    /** Ответ модели, или null — если его ещё не брали либо взять не удалось. */
    override fun get(conversationId: String, slot: Slot, fingerprint: String): String? {
        if (prefs.getString(key(conversationId, slot, "fp"), null) != fingerprint) return null
        return prefs.getString(key(conversationId, slot, "text"), null)
            ?.takeIf { it.isNotBlank() && it != UNAVAILABLE }
    }

    /**
     * Знакомство уже пробовали и не вышло.
     *
     * Отличать «не пробовали» от «не получилось» обязательно: без этого неудачный ход
     * повторялся на каждом сообщении — лишний запрос к серверу за каждую реплику, да ещё
     * тот самый, который и падает.
     */
    override fun attempted(conversationId: String, slot: Slot, fingerprint: String): Boolean =
        prefs.getString(key(conversationId, slot, "fp"), null) == fingerprint

    override fun save(conversationId: String, slot: Slot, fingerprint: String, ack: String) {
        prefs.edit()
            .putString(key(conversationId, slot, "fp"), fingerprint)
            .putString(key(conversationId, slot, "text"), ack.trim())
            .apply()
    }

    /** Помечает попытку неудачной, чтобы она не повторялась до правки инструкции. */
    override fun markUnavailable(conversationId: String, slot: Slot, fingerprint: String) =
        save(conversationId, slot, fingerprint, UNAVAILABLE)

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

    private companion object {
        /** Метка переживает trim() в save(): иначе она перестала бы совпадать сама с собой. */
        const val UNAVAILABLE = "__jarvis_handshake_unavailable__"
    }
}
