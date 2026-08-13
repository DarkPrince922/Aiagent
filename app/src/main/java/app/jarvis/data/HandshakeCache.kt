package app.jarvis.data

/**
 * Хранилище исходов знакомства. Отдельный интерфейс — чтобы решение о повторе можно было
 * проверить тестом: именно оно и сломалось, а SharedPreferences в unit-тест не занести.
 */
interface AckStorage {
    fun get(conversationId: String, slot: PromptAckStore.Slot, fingerprint: String): String?
    fun attempted(conversationId: String, slot: PromptAckStore.Slot, fingerprint: String): Boolean
    fun save(conversationId: String, slot: PromptAckStore.Slot, fingerprint: String, ack: String)
    fun markUnavailable(conversationId: String, slot: PromptAckStore.Slot, fingerprint: String)
}

/**
 * Один ход знакомства с запоминанием исхода — включая неудачный.
 *
 * Провал не запоминался, и ход повторялся на каждом сообщении: лишний запрос за каждую
 * реплику, причём ровно тот, который и падает. Со стороны это выглядело как пачка обращений
 * к серверу на один вопрос и ошибки 500 на ровном месте.
 *
 * Поэтому исход записывается всегда: удачный — ответом, неудачный — пометкой. Повторить
 * попытку заставляет только смена отпечатка, то есть правка инструкции или набора
 * инструментов.
 */
fun AckStorage.handshake(
    conversationId: String,
    slot: PromptAckStore.Slot,
    fingerprint: String,
    ask: () -> String?
): String? {
    get(conversationId, slot, fingerprint)?.let { return it }
    if (attempted(conversationId, slot, fingerprint)) return null
    val answer = ask()
    if (answer == null) markUnavailable(conversationId, slot, fingerprint)
    else save(conversationId, slot, fingerprint, answer)
    return answer
}
