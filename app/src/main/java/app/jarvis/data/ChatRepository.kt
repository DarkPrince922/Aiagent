package app.jarvis.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.jarvis.net.ApiAnswer
import app.jarvis.net.ApiMessage
import app.jarvis.net.ApiToolCall
import app.jarvis.net.ChatApi
import app.jarvis.net.ChatFailure
import app.jarvis.net.ConnectionCheck
import app.jarvis.llm.LanguageModelRouter
import app.jarvis.net.LanguageModel
import app.jarvis.tools.ToolRegistry
import app.jarvis.worker.RetryWorker
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.time.ZonedDateTime

data class PendingAgentAction(
    val label: String,
    val messages: List<ApiMessage>,
    val calls: List<ApiToolCall>,
    val settings: ProviderSettings
)

data class AgentReply(val text: String, val pending: PendingAgentAction? = null, val notice: String? = null)

class ChatRepository(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val pendingStore: PendingStore,
    private val conversationStore: ConversationStore,
    private val api: LanguageModel,
    private val tools: ToolRegistry,
    private val ackStore: PromptAckStore = PromptAckStore(context)
) {
    fun settings() = settingsStore.get()
    /** Прерывает счёт локальной модели: без этого следующий запрос ждёт освобождения движка. */
    fun cancelLocalGeneration() = (api as? LanguageModelRouter)?.cancelLocal()
    fun saveSettings(value: ProviderSettings) = settingsStore.save(value)
    fun catalog() = tools.catalog
    fun checkConnection(value: ProviderSettings = settingsStore.get()): ConnectionCheck = api.check(value)
    fun conversations() = conversationStore.list()
    fun ensureConversation() = conversationStore.ensureConversation()
    fun createConversation() = conversationStore.create()
    fun deleteConversation(id: String) { pendingStore.removeConversation(id); conversationStore.deleteConversation(id) }
    fun messages(id: String) = conversationStore.messages(id)
    fun saveMessage(conversationId: String, message: Message) = conversationStore.saveMessage(conversationId, message)
    fun deleteMessage(id: Long) = conversationStore.deleteMessage(id)
    fun titleFromFirstMessage(conversationId: String, text: String) = conversationStore.titleFromFirstMessage(conversationId, text)

    fun send(
        history: List<Message>,
        conversationId: String? = null,
        shouldContinue: () -> Boolean = { true },
        allowUnlimited: Boolean = true,
        onProgress: (String) -> Unit = {},
        onInterim: (String) -> Unit = {},
        onSummary: (String) -> Unit = {}
    ): Result<AgentReply> = runCatching {
        val saved = settingsStore.get()
        val limited = if (allowUnlimited) saved else saved.copy(unlimitedAgent = false, agentSteps = 10)
        // Каждый шаг локальной модели — это полный prefill и сотни токенов декода, то есть
        // минуты. Восемь шагов подряд превращали простой вопрос в получасовое ожидание.
        val settings = if (limited.engine == LlmEngine.LOCAL) {
            limited.copy(unlimitedAgent = false, agentSteps = limited.agentSteps.coerceAtMost(MAX_LOCAL_STEPS))
        } else {
            limited
        }
        settings.readinessError?.let { throw IllegalArgumentException(it) }
        val messages = buildList {
            // Голая инструкция, затем собственное подтверждение модели, и только потом
            // служебная обвязка приложения — отдельным и явно подчинённым блоком.
            addAll(PromptComposer.opening(settings.systemPrompt, primingAck(settings, conversationId, onProgress)))
            PromptComposer.service(
                if (settings.toolsEnabled && settings.answerBeforeTools) ANSWER_FIRST_PROTOCOL else null,
                "Текущие локальные дата и время: ${ZonedDateTime.now()}",
                "Сохранённые SSH-профили (секреты не передаются):\n${tools.sshContext()}",
                "Рабочая папка обмена файлами: ${workspaceHint()}"
            )?.let { add(it) }
            history.filter { (it.role == "user" || it.role == "assistant") && it.state in setOf(DeliveryState.SENT, DeliveryState.SENDING) }.forEach { add(ApiMessage(it.role, it.text)) }
        }
        runAgent(settings, messages, shouldContinue, onProgress, onInterim, onSummary)
    }

    fun confirm(action: PendingAgentAction, approved: Boolean, shouldContinue: () -> Boolean = { true }): Result<AgentReply> = runCatching {
        val call = action.calls.firstOrNull() ?: return@runCatching runAgent(action.settings, action.messages, shouldContinue)
        val result = if (approved) tools.execute(call.name, call.arguments, true) else app.jarvis.tools.ToolResult("Пользователь отклонил действие")
        var continued = action.messages + ApiMessage("tool", result.content, toolCallId = call.id)
        action.calls.drop(1).forEachIndexed { index, nextCall ->
            val nextResult = tools.execute(nextCall.name, nextCall.arguments)
            if (nextResult.needsConfirmation) {
                return@runCatching AgentReply("Нужно ваше подтверждение", PendingAgentAction(nextResult.prompt, continued, action.calls.drop(index + 1), action.settings))
            }
            continued = continued + ApiMessage("tool", nextResult.content, toolCallId = nextCall.id)
        }
        runAgent(action.settings, continued, shouldContinue)
    }

    fun queue(conversationId: String, text: String): Long {
        val id = pendingStore.add(conversationId, text)
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<RetryWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("jarvis-chat-outbox", ExistingWorkPolicy.KEEP, request)
        return id
    }

    fun consumeCompleted(): Set<String> {
        val affected = mutableSetOf<String>()
        pendingStore.completed().forEach { item ->
            val conversationId = item.conversationId.takeIf { it.isNotBlank() } ?: conversationStore.ensureConversation().id
            val old = conversationStore.messages(conversationId).firstOrNull { it.detail == "queue:${item.id}" }
            if (old != null) conversationStore.saveMessage(conversationId, old.copy(state = if (item.error == null) DeliveryState.SENT else DeliveryState.FAILED, detail = item.error))
            else conversationStore.saveMessage(conversationId, Message(role = "user", text = item.text, state = if (item.error == null) DeliveryState.SENT else DeliveryState.FAILED, detail = item.error))
            conversationStore.saveMessage(conversationId, Message(role = "assistant", text = item.result ?: item.error ?: "Фоновая отправка завершена"))
            affected += conversationId
            pendingStore.remove(item.id)
        }
        return affected
    }

    fun retryPending(): Boolean {
        for (item in pendingStore.waiting()) {
            try {
                val restoredHistory = conversationStore.messages(item.conversationId).map { message ->
                    if (message.detail == "queue:${item.id}") message.copy(state = DeliveryState.SENT, detail = null) else message
                }.ifEmpty { listOf(Message(role = "user", text = item.text)) }
                val reply = send(restoredHistory, conversationId = item.conversationId, allowUnlimited = false).getOrThrow()
                pendingStore.complete(item.id, reply.text + if (reply.pending != null) "\nОткройте Jarvis и повторите команду для подтверждения действия." else "")
            } catch (error: ChatFailure.Transport) {
                return true
            } catch (error: ChatFailure.Http) {
                if (error.retryable) return true else pendingStore.fail(item.id, error.message ?: "Ошибка API")
            } catch (error: Exception) {
                pendingStore.fail(item.id, error.message ?: "Ошибка фоновой отправки")
            }
        }
        return false
    }

    private fun runAgent(
        settings: ProviderSettings,
        initial: List<ApiMessage>,
        shouldContinue: () -> Boolean,
        onProgress: (String) -> Unit = {},
        onInterim: (String) -> Unit = {},
        onSummary: (String) -> Unit = {}
    ): AgentReply {
        var messages = initial
        var fallbackNotice: String? = null
        var step = 0
        var lastCallSignature = ""
        var repeatedCalls = 0
        var toolsUsed = 0
        var assistantOutputs = 0
        while (settings.unlimitedAgent || step < settings.agentSteps.coerceIn(1, 20)) {
            if (!shouldContinue()) throw CancellationException("Остановлено пользователем")
            step++
            // Локальная модель думает минутами: без этого экран выглядит зависшим.
            onProgress(if (step == 1) "Модель думает" else "Шаг $step: модель думает")
            messages = compactContext(messages, settings)
            val schemas = if (settings.toolsEnabled) tools.schemas(compact = settings.engine == LlmEngine.LOCAL) else org.json.JSONArray()
            fun ask(extra: String? = null): ApiAnswer {
                val request = PromptComposer.withReminder(messages, settings.systemPrompt)
                    .let { if (extra == null) it else it + ApiMessage("system", extra) }
                return try {
                    api.complete(settings, request, schemas)
                } catch (error: ChatFailure.Http) {
                    val toolRejected = error.status == 400 &&
                        (error.serverMessage.contains("tool", true) || error.serverMessage.contains("function", true))
                    if (!toolRejected || !settings.toolsEnabled) throw error
                    fallbackNotice = "Эта модель не приняла tools; ответ получен в обычном режиме."
                    api.complete(settings.copy(toolsEnabled = false), request, org.json.JSONArray())
                }
            }
            var answer = ask()
            // Инструкцию «сначала ответь текстом» модель может проигнорировать, поэтому она
            // проверяется, а не только объявляется: на первом шаге молчаливый вызов инструмента
            // переспрашиваем. Дальше молчание нормально — там уже идёт продолжение начатого.
            // Локальный движок исключён: лишний запрос стоит там минут ожидания.
            if (step == 1 && enforcesAnswerFirst(settings) && answer.text.isBlank() && answer.toolCalls.isNotEmpty()) {
                onProgress("Прошу сначала ответить текстом")
                answer = ask(ANSWER_FIRST_NUDGE)
            }
            messages = messages + answer.rawMessage
            if (answer.text.isNotBlank()) assistantOutputs++
            if (answer.toolCalls.isEmpty()) {
                val finalText = answer.text.ifBlank { "ИИ вернул пустой ответ" }
                summarize(settings, messages, toolsUsed, assistantOutputs, onProgress, onSummary)
                return AgentReply(finalText, notice = fallbackNotice)
            }
            val signature = answer.toolCalls.joinToString("|") { "${it.name}:${it.arguments}" }
            repeatedCalls = if (signature == lastCallSignature) repeatedCalls + 1 else 0
            lastCallSignature = signature
            if (repeatedCalls >= 3) {
                answer.toolCalls.forEach { call -> messages = messages + ApiMessage("tool", "REPEATED_CALL: вызов остановлен как повторяющийся", toolCallId = call.id) }
                return synthesize(settings, messages, fallbackNotice, "Модель повторяла один и тот же инструмент")
            }
            // Ответ модели, предшествующий вызовам, показываем сразу: пользователь видит,
            // что понято и что сейчас будет сделано, ещё до выполнения команд.
            if (answer.text.isNotBlank()) onInterim(answer.text)
            answer.toolCalls.forEachIndexed { index, call ->
                onProgress("Инструмент: ${call.name}")
                toolsUsed++
                val result = tools.execute(call.name, call.arguments)
                if (result.needsConfirmation) {
                    return AgentReply("Нужно ваше подтверждение", PendingAgentAction(result.prompt, messages, answer.toolCalls.drop(index), settings), fallbackNotice)
                }
                messages = messages + ApiMessage("tool", result.content, toolCallId = call.id)
            }
        }
        onProgress("Формирую итоговый ответ")
        return synthesize(settings, messages, fallbackNotice, "Достигнут настроенный лимит шагов")
    }

    private fun synthesize(settings: ProviderSettings, messages: List<ApiMessage>, notice: String?, reason: String): AgentReply = runCatching {
        // Итоговый текст пользователь и читает, поэтому инструкция повторяется здесь всегда.
        val finalMessages = PromptComposer.withReminder(compactContext(messages, settings), settings.systemPrompt, force = true) +
            ApiMessage("user", "Сформируй лучший итоговый ответ по уже полученным результатам. Не вызывай инструменты. Честно укажи, что осталось незавершённым. Причина завершения: $reason")
        val answer = api.complete(settings.copy(toolsEnabled = false), finalMessages, org.json.JSONArray())
        AgentReply(answer.text.ifBlank { reason }, notice = notice)
    }.getOrElse { AgentReply("$reason. Не удалось сформировать итог: ${it.message}", notice = notice) }

    /**
     * @param settings нужен из-за движка: у облака контекст на порядок больше телефонного.
     *   Общий потолок в 6000 символов на результат инструмента резал прочитанный кусок файла
     *   в тридцать раз — модель видела проценты от отчёта и не понимала, почему.
     */
    private fun compactContext(input: List<ApiMessage>, settings: ProviderSettings): List<ApiMessage> {
        val local = settings.engine == LlmEngine.LOCAL
        val toolLimit = if (local) 6_000 else 110_000
        val textLimit = if (local) 12_000 else 24_000
        val systemLimit = if (local) 8_000 else 24_000
        val budget = if (local) 120_000 else 320_000
        var messages = input.map { message ->
            val content = message.content ?: return@map message
            // Инструкция пользователя не режется никогда; урезать можно только то,
            // что дописало само приложение.
            if (message.role == "system" && !PromptComposer.isService(message)) return@map message
            if (PromptComposer.isService(message)) {
                val clamped = PromptComposer.clampService(content, systemLimit)
                return@map if (clamped == content) message else message.copy(content = clamped)
            }
            val limit = if (message.role == "tool") toolLimit else textLimit
            if (content.length > limit) message.copy(content = content.take(limit) + "\n[сокращено]") else message
        }
        if (messages.sumOf { it.content?.length ?: 0 } <= budget && messages.size <= 40) return messages
        // Вступление сохраняется целиком: без него модель теряет и инструкцию, и своё
        // подтверждение — то есть ровно то, что удерживает её в нужном режиме.
        val prelude = messages.take(PromptComposer.preludeSize(messages))
        val tail = messages.drop(prelude.size).takeLast(34).dropWhile { it.role == "tool" }
        return prelude + tail
    }

    /**
     * Отдельное резюме поверх ответа.
     *
     * Нужно там, где ответов за ход было несколько или выполнялись действия: итог
     * собирает их в одно сообщение, которое дублируется в чат и не теряется в переписке.
     */
    private fun summarize(
        settings: ProviderSettings,
        messages: List<ApiMessage>,
        toolsUsed: Int,
        assistantOutputs: Int,
        onProgress: (String) -> Unit,
        onSummary: (String) -> Unit
    ) {
        if (!settings.summarizeAnswers) return
        // Для короткой реплики без действий отдельный итог только дублировал бы ответ.
        if (toolsUsed == 0 && assistantOutputs < 2) return
        onProgress("Готовлю итог")
        runCatching {
            val request = PromptComposer.withReminder(compactContext(messages, settings), settings.systemPrompt, force = true) +
                ApiMessage("user", SUMMARY_PROMPT)
            api.complete(settings.copy(toolsEnabled = false), request, org.json.JSONArray()).text
        }.onSuccess { text ->
            if (text.isNotBlank()) onSummary(text.trim())
        }.onFailure {
            // Итог — надстройка: его потеря не должна ронять уже полученный ответ.
            onSummary("Итог сформировать не удалось: ${it.message ?: "ошибка запроса"}")
        }
    }

    private fun workspaceHint(): String =
        "файлы, которыми обменялись с пользователем; список — list_files, чтение — read_file, поиск по большому файлу — search_file, " +
            "создание — write_file, отправка пользователю — send_file. Большой файл read_file отдаёт окнами: в ответе есть общий размер " +
            "и offset следующего куска — дочитывай повторными вызовами, а не делай вывод по началу файла"

    /**
     * Подтверждение инструкции от самой модели.
     *
     * Небольшая модель на своём сервере читает служебные указания приложения наравне с
     * основной инструкцией. Собственный ответ «принял, работаю так-то» держит её заметно
     * сильнее любого текста от приложения, поэтому он берётся один раз на диалог и дальше
     * лежит в контексте. Запрос идёт без инструментов: подтверждать нечего исполнять.
     *
     * Неудача не блокирует сообщение — работаем без подтверждения.
     */
    private fun primingAck(settings: ProviderSettings, conversationId: String?, onProgress: (String) -> Unit): String? {
        if (!settings.primePrompt || conversationId == null || settings.systemPrompt.isBlank()) return null
        ackStore.get(conversationId, settings.systemPrompt)?.let { return it }
        onProgress("Согласую инструкцию")
        return runCatching {
            api.complete(
                settings.copy(toolsEnabled = false),
                PromptComposer.priming(settings.systemPrompt),
                org.json.JSONArray()
            ).text.trim().takeIf { it.isNotBlank() }
        }.getOrNull()?.also { ackStore.save(conversationId, settings.systemPrompt, it) }
    }

    /** У локального движка лишний запрос стоит минут, поэтому там правило только объявляется. */
    private fun enforcesAnswerFirst(settings: ProviderSettings): Boolean =
        settings.toolsEnabled && settings.answerBeforeTools && settings.engine != LlmEngine.LOCAL

    private companion object {
        const val MAX_LOCAL_STEPS = 2
        const val ANSWER_FIRST_NUDGE =
            "Ты вызвал инструмент, не написав ни слова. Сначала ответь пользователю обычным текстом " +
                "по основной инструкции: что понято и что собираешься сделать. Вызовы инструментов " +
                "помести в тот же ответ после текста."
        const val SUMMARY_PROMPT =
            "Подведи итог этого хода отдельным сообщением: что было сделано, что получилось и что осталось. " +
                "Не вызывай инструменты, не повторяй длинные выдержки, уложись в 5 пунктов."
        const val ANSWER_FIRST_PROTOCOL =
            "ПОРЯДОК РАБОТЫ: сначала прочитай запрос и ответь на него обычным текстом — что ты понял и что " +
                "намерен сделать. Вызовы инструментов помещай в тот же ответ, но только после этого текста. " +
                "Никогда не вызывай инструмент, не написав перед этим ни слова."
    }
}
