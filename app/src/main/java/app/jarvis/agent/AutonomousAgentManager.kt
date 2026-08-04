package app.jarvis.agent

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.jarvis.data.AgentEventKind
import app.jarvis.data.AgentOperationStatus
import app.jarvis.data.AgentTask
import app.jarvis.data.AgentTaskEvent
import app.jarvis.data.AgentTaskStatus
import app.jarvis.data.AgentTaskStore
import app.jarvis.data.ConversationStore
import app.jarvis.data.Message
import app.jarvis.data.SettingsStore
import app.jarvis.data.SshProfileStore
import app.jarvis.data.readinessError
import app.jarvis.net.ApiMessage
import app.jarvis.net.ApiToolCall
import app.jarvis.net.ChatApi
import app.jarvis.net.ChatFailure
import app.jarvis.net.LanguageModel
import app.jarvis.tools.ToolExecutionContext
import app.jarvis.tools.ToolRegistry
import app.jarvis.tools.ToolResult
import app.jarvis.worker.AgentNotifications
import app.jarvis.worker.AutonomousAgentWorker
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

enum class AutonomousRunResult { DONE, CONTINUE, RETRY }

class AutonomousAgentManager(
    context: Context,
    private val settings: SettingsStore,
    private val store: AgentTaskStore,
    private val conversations: ConversationStore,
    private val profiles: SshProfileStore,
    private val api: LanguageModel,
    private val tools: ToolRegistry,
    private val notifications: AgentNotifications
) {
    private val workManager = WorkManager.getInstance(context)

    fun tasks(): List<AgentTask> = store.all()
    fun events(taskId: String): List<AgentTaskEvent> = store.events(taskId)
    fun task(id: String): AgentTask? = store.get(id)

    fun start(objective: String, sshProfileId: String?, autoApproveSsh: Boolean = true): AgentTask {
        val goal = objective.trim()
        require(goal.length >= 8) { "Опишите задачу чуть подробнее" }
        require(goal.length <= 8_000) { "Цель слишком длинная; сократите её до 8000 символов" }
        settings.get().readinessError?.let { throw IllegalStateException(it) }
        val profile = sshProfileId?.let { id -> profiles.find(id) ?: error("SSH-профиль не найден") }
        if (profile != null && autoApproveSsh) {
            require(profile.fingerprint.isNotBlank()) { "Сначала проверьте SSH-профиль и закрепите fingerprint хоста" }
        }
        val conversation = conversations.create()
        conversations.saveMessage(conversation.id, Message(role = "user", text = "[Автономная задача]\n$goal"))
        conversations.titleFromFirstMessage(conversation.id, goal)
        val checkpoint = encodeMessages(initialMessages(goal, profile?.id, profile?.name, autoApproveSsh))
        val task = store.create(goal, conversation.id, profile?.id, autoApproveSsh && profile != null, checkpoint)
        enqueue(task.id, ExistingWorkPolicy.REPLACE)
        return task
    }

    fun pause(id: String) {
        val task = store.get(id) ?: return
        if (!task.status.active) return
        store.setStatus(id, AgentTaskStatus.PAUSED, "Приостановлено пользователем")
        store.addEvent(id, AgentEventKind.SYSTEM, "Задача на паузе")
        workManager.cancelUniqueWork(workName(id))
        notifications.cancelProgress(id)
    }

    fun resume(id: String) {
        store.get(id) ?: return
        store.setStatus(id, AgentTaskStatus.QUEUED, "Возобновление")
        store.addEvent(id, AgentEventKind.SYSTEM, "Задача возобновлена")
        enqueue(id, ExistingWorkPolicy.REPLACE)
    }

    fun stop(id: String) {
        val task = store.get(id) ?: return
        if (task.status == AgentTaskStatus.STOPPED) return
        store.setStatus(id, AgentTaskStatus.STOPPED, "Остановлено пользователем")
        store.addEvent(id, AgentEventKind.WARNING, "Задача остановлена", "Новые шаги не будут запущены. Уже отправленная на сервер команда может завершиться удалённо.")
        workManager.cancelUniqueWork(workName(id))
        announce(task, AgentTaskStatus.STOPPED, "Агент остановлен на шаге ${task.step}.")
    }

    fun delete(id: String) {
        workManager.cancelUniqueWork(workName(id))
        notifications.cancelProgress(id)
        store.delete(id)
    }

    /**
     * Добавляет указание работающей задаче.
     *
     * Агент подхватит его перед следующим обращением к модели — вмешаться можно, не останавливая
     * работу и не теряя контекст. Если задача уже не активна, она возвращается в очередь:
     * новое указание — это явное желание продолжить.
     */
    fun addInstruction(id: String, text: String): AgentTaskStatus {
        val task = store.get(id) ?: error("Задача не найдена")
        val instruction = text.trim()
        require(instruction.isNotBlank()) { "Опишите, что изменить" }
        require(instruction.length <= 8_000) { "Указание слишком длинное; сократите до 8000 символов" }
        store.addInstruction(id, instruction)
        store.addEvent(id, AgentEventKind.SYSTEM, "Указание от пользователя", instruction.take(2_000))
        if (task.status.active) return task.status
        store.setStatus(id, AgentTaskStatus.QUEUED, "Возобновление с новым указанием")
        enqueue(id, ExistingWorkPolicy.REPLACE)
        return AgentTaskStatus.QUEUED
    }

    fun resumeActive() {
        store.active().forEach { enqueue(it.id, ExistingWorkPolicy.KEEP) }
    }

    fun enqueueContinuation(id: String) = enqueue(id, ExistingWorkPolicy.APPEND_OR_REPLACE)

    fun runBatch(taskId: String, shouldContinue: () -> Boolean): AutonomousRunResult {
        var task = store.get(taskId) ?: return AutonomousRunResult.DONE
        if (!task.status.active) return AutonomousRunResult.DONE
        store.setStatus(taskId, AgentTaskStatus.RUNNING, task.currentAction)
        var messages = decodeMessages(task.checkpoint)
        var step = task.step
        val started = System.currentTimeMillis()
        return try {
            repeat(MAX_MODEL_STEPS_PER_RUN) {
                ensureRunning(taskId, shouldContinue)
                val unresolved = unresolvedCalls(messages)
                if (unresolved.isNotEmpty()) {
                    for (call in unresolved) {
                        ensureRunning(taskId, shouldContinue)
                        when (val outcome = executeCall(task, call, messages, step, shouldContinue)) {
                            is CallOutcome.Completed -> messages = outcome.messages
                            CallOutcome.TaskFinished -> return AutonomousRunResult.DONE
                            CallOutcome.StillRunning -> return AutonomousRunResult.CONTINUE
                            CallOutcome.RetryLater -> return AutonomousRunResult.RETRY
                            CallOutcome.StopWorker -> return AutonomousRunResult.DONE
                        }
                    }
                } else {
                    val currentSettings = settings.get().copy(toolsEnabled = true, unlimitedAgent = true)
                    messages = drainInstructions(taskId, messages, step)
                    val compacted = compactContext(messages)
                    if (compacted != messages) {
                        messages = compacted
                        store.updateCheckpoint(taskId, encodeMessages(messages), step, "Контекст сжат; ключевые события сохранены")
                    }
                    val answer = api.complete(currentSettings, messages, tools.schemas(autonomous = true))
                    ensureRunning(taskId, shouldContinue)
                    step++
                    messages = messages + answer.rawMessage
                    store.updateCheckpoint(taskId, encodeMessages(messages), step, answer.text.ifBlank { "Планирую следующий шаг" })
                    if (answer.toolCalls.isEmpty()) {
                        if (answer.text.isNotBlank()) store.addEvent(taskId, AgentEventKind.PROGRESS, "Промежуточный вывод", answer.text)
                        messages = messages + ApiMessage("user", CONTINUE_PROMPT)
                        store.updateCheckpoint(taskId, encodeMessages(messages), step, "Продолжаю до проверенного результата")
                    } else {
                        if (isRepeatedCallLoop(messages)) {
                            store.addEvent(taskId, AgentEventKind.WARNING, "Повтор команды заблокирован", "Агенту предложено проверить состояние и сменить план.")
                            answer.toolCalls.forEach { call ->
                                store.planOperation(taskId, call.id, call.name, call.arguments.toString())
                                val result = "REPEATED_CALL_BLOCKED: не повторяй тот же вызов; проверь состояние и измени план"
                                store.updateOperation(taskId, call.id, AgentOperationStatus.FAILED, result)
                                messages = messages + ApiMessage("tool", result, toolCallId = call.id)
                            }
                            store.updateCheckpoint(taskId, encodeMessages(messages), step, "Меняю план после повтора")
                        } else {
                            answer.toolCalls.forEach { store.planOperation(taskId, it.id, it.name, it.arguments.toString()) }
                        }
                    }
                }
                task = store.get(taskId) ?: return AutonomousRunResult.DONE
                notifications.updateProgress(task)
                if (System.currentTimeMillis() - started >= MAX_RUN_MILLIS) return AutonomousRunResult.CONTINUE
            }
            AutonomousRunResult.CONTINUE
        } catch (error: CancellationException) {
            if (store.get(taskId)?.status?.active == true) store.setStatus(taskId, AgentTaskStatus.QUEUED, "Выполнение было прервано; продолжу с checkpoint")
            throw error
        } catch (error: ChatFailure.Transport) {
            if (store.get(taskId)?.status?.active != true) return AutonomousRunResult.DONE
            waitForNetwork(taskId, error.message ?: "Нет связи")
            AutonomousRunResult.RETRY
        } catch (error: ChatFailure.Http) {
            if (store.get(taskId)?.status?.active != true) return AutonomousRunResult.DONE
            if (error.retryable) {
                waitForNetwork(taskId, "API ${error.status}: ${error.serverMessage}")
                AutonomousRunResult.RETRY
            } else {
                val status = if (error.status == 401 || error.status == 403) AgentTaskStatus.PAUSED else AgentTaskStatus.FAILED
                store.setStatus(taskId, status, "Требуется проверка API", error.message)
                store.addEvent(taskId, AgentEventKind.ERROR, "Ошибка API ${error.status}", error.serverMessage)
                announce(store.get(taskId), status, "Ошибка API ${error.status}: ${error.serverMessage}")
                AutonomousRunResult.DONE
            }
        } catch (error: Exception) {
            if (store.get(taskId)?.status?.active != true) return AutonomousRunResult.DONE
            val message = error.message ?: error.javaClass.simpleName
            store.setStatus(taskId, AgentTaskStatus.FAILED, "Агент остановлен ошибкой", message)
            store.addEvent(taskId, AgentEventKind.ERROR, "Невосстановимая ошибка", message)
            announce(store.get(taskId), AgentTaskStatus.FAILED, message)
            AutonomousRunResult.DONE
        }
    }

    /**
     * Переносит накопленные указания в диалог перед следующим обращением к модели.
     *
     * Вызывается только когда все tool_calls уже закрыты: вставлять сообщение пользователя
     * между вызовом инструмента и его результатом API не разрешает.
     */
    private fun drainInstructions(taskId: String, messages: List<ApiMessage>, step: Int): List<ApiMessage> {
        val pending = store.pendingInstructions(taskId)
        if (pending.isEmpty()) return messages
        val text = pending.joinToString("\n\n") { it.text }
        val updated = messages + ApiMessage(
            "user",
            "НОВОЕ УКАЗАНИЕ ПОЛЬЗОВАТЕЛЯ (имеет приоритет над прежними инструкциями, цель задачи скорректирована):\n$text"
        )
        store.consumeInstructions(pending.map { it.id })
        store.addEvent(taskId, AgentEventKind.SYSTEM, "Указание учтено", text.take(2_000))
        store.updateCheckpoint(taskId, encodeMessages(updated), step, "Учитываю новое указание")
        return updated
    }

    private fun announce(task: AgentTask?, status: AgentTaskStatus, text: String) {
        val id = task?.id ?: return
        notifications.cancelProgress(id)
        notifications.notifyFinished(id, task.title, text, status)
    }

    private fun executeCall(
        task: AgentTask,
        call: ApiToolCall,
        messages: List<ApiMessage>,
        step: Int,
        shouldContinue: () -> Boolean
    ): CallOutcome {
        var existing = store.operation(task.id, call.id)
        if (existing == null) {
            store.planOperation(task.id, call.id, call.name, call.arguments.toString())
            existing = store.operation(task.id, call.id)
        }
        if (existing != null && (existing.toolName != call.name || existing.arguments != call.arguments.toString())) {
            val conflict = "TOOL_CALL_ID_CONFLICT: API повторил id для другого вызова; операция не запущена"
            store.addEventOnce(task.id, AgentEventKind.ERROR, "Конфликт ID инструмента", call.id)
            val updated = appendToolResult(messages, call.id, conflict)
            store.updateCheckpoint(task.id, encodeMessages(updated), step, "Меняю план после конфликта API")
            return CallOutcome.Completed(updated)
        }
        if (call.name == "record_progress") {
            val title = call.arguments.optString("title").ifBlank { "Прогресс" }
            val detail = call.arguments.optString("detail")
            if (existing?.status != AgentOperationStatus.SUCCEEDED) {
                store.addEventOnce(task.id, AgentEventKind.PROGRESS, title, detail)
                store.updateOperation(task.id, call.id, AgentOperationStatus.SUCCEEDED, "PROGRESS_RECORDED")
            }
            val updated = appendToolResult(messages, call.id, existing?.result ?: "PROGRESS_RECORDED")
            store.updateCheckpoint(task.id, encodeMessages(updated), step, title)
            return CallOutcome.Completed(updated)
        }
        if (call.name == "finish_task") {
            val summary = call.arguments.optString("summary").trim().ifBlank { "Задача выполнена" }
            val evidence = call.arguments.optString("evidence").trim()
            val finalText = if (evidence.isBlank()) summary else "$summary\n\nПроверка:\n$evidence"
            store.updateOperation(task.id, call.id, AgentOperationStatus.SUCCEEDED, "TASK_FINISHED")
            val updated = appendToolResult(messages, call.id, "TASK_FINISHED")
            store.complete(task.id, finalText, encodeMessages(updated), step)
            store.addEvent(task.id, AgentEventKind.SUCCESS, "Цель достигнута", finalText)
            announce(task, AgentTaskStatus.COMPLETED, finalText)
            task.conversationId?.let { conversationId ->
                if (conversations.messages(conversationId).none { it.detail == "agent-task:${task.id}" }) {
                    conversations.saveMessage(conversationId, Message(role = "assistant", text = finalText, detail = "agent-task:${task.id}"))
                }
            }
            return CallOutcome.TaskFinished
        }

        val storedResult = existing?.takeIf {
            it.status in setOf(AgentOperationStatus.SUCCEEDED, AgentOperationStatus.FAILED, AgentOperationStatus.UNKNOWN)
        }?.result
        if (storedResult != null) {
            val updated = appendToolResult(messages, call.id, storedResult)
            store.updateCheckpoint(task.id, encodeMessages(updated), step, "Восстановлен результат ${call.name}")
            return CallOutcome.Completed(updated)
        }

        store.updateOperation(task.id, call.id, AgentOperationStatus.DISPATCHING)
        store.updateCheckpoint(task.id, encodeMessages(messages), step, toolTitle(call))
        val result = tools.execute(
            call.name,
            call.arguments,
            confirmed = false,
            execution = ToolExecutionContext(
                taskId = task.id,
                autonomous = true,
                allowedSshProfileId = task.sshProfileId.takeIf { task.autoApproveSsh },
                operationId = "${task.id}-${call.id}",
                shouldContinue = shouldContinue
            )
        ).withoutConfirmation()
        if (result.pending) {
            store.addEventOnce(task.id, AgentEventKind.TOOL, "${toolTitle(call)} выполняется", call.argumentsSummary())
            return CallOutcome.StillRunning
        }
        if (result.retryable) {
            waitForNetwork(task.id, result.content)
            return CallOutcome.RetryLater
        }
        if (call.name == "ssh_exec" && result.isError && isFatalSshFailure(result.content)) {
            store.updateOperation(task.id, call.id, AgentOperationStatus.FAILED, result.content)
            store.setStatus(task.id, AgentTaskStatus.PAUSED, "Нужно исправить SSH-профиль", result.content)
            store.addEvent(task.id, AgentEventKind.ERROR, "SSH-доступ приостановлен", summarizeResult(result.content))
            announce(task, AgentTaskStatus.PAUSED, summarizeResult(result.content))
            return CallOutcome.StopWorker
        }
        val operationStatus = when {
            result.uncertain -> AgentOperationStatus.UNKNOWN
            result.isError -> AgentOperationStatus.FAILED
            else -> AgentOperationStatus.SUCCEEDED
        }
        store.updateOperation(task.id, call.id, operationStatus, result.content)
        val eventKind = when {
            result.uncertain || result.isError || result.content.startsWith("HTTP 409") -> AgentEventKind.WARNING
            else -> AgentEventKind.TOOL
        }
        store.addEvent(task.id, eventKind, toolTitle(call), summarizeResult(result.content))
        val updated = appendToolResult(messages, call.id, result.content)
        store.updateCheckpoint(task.id, encodeMessages(updated), step, toolTitle(call))
        return CallOutcome.Completed(updated)
    }

    private fun ToolResult.withoutConfirmation(): ToolResult = if (!needsConfirmation) this else ToolResult(
        content = "AUTONOMOUS_POLICY_DENIED: инструмент не разрешён грантом этой задачи. Не жди подтверждения; выбери другой путь.",
        isError = true
    )

    private fun initialMessages(goal: String, profileId: String?, profileName: String?, autoApproveSsh: Boolean): List<ApiMessage> {
        val selected = if (profileId == null) {
            "SSH-профиль для этой задачи не выбран. Не используй ssh_exec."
        } else {
            "За задачей закреплён SSH profile_id=$profileId, name=$profileName. " +
                if (autoApproveSsh) "SSH-команды на этом профиле заранее разрешены; не проси подтверждения." else "SSH-команды не разрешены."
        }
        val system = """${settings.get().systemPrompt}

Ты выполняешь долговременную автономную задачу. Работай до фактического и проверенного результата.
ЦЕЛЬ ЗАДАЧИ: $goal
- Не проси промежуточных подтверждений. Если действие запрещено политикой задачи, выбери другой путь.
- Перед изменениями сначала изучи состояние; делай резервные копии, когда это разумно; после изменения проверь результат.
- HTTP 409, ненулевой exit code и временные сетевые сбои не считай концом задачи: проверь текущее состояние, смени подход и продолжай.
- Записывай только ключевые проверяемые моменты через record_progress. Не записывай скрытые рассуждения.
- Вызови finish_task только после проверки цели. Обычный текст не завершает задачу.

$selected
Доступные SSH-профили (без секретов):
${tools.sshContext()}
Текущие дата и время: ${ZonedDateTime.now()}"""
        return listOf(ApiMessage("system", system), ApiMessage("user", goal))
    }

    private fun waitForNetwork(taskId: String, error: String) {
        if (store.get(taskId)?.status?.active != true) return
        store.setStatus(taskId, AgentTaskStatus.WAITING_NETWORK, "Жду стабильное соединение", error)
        store.addEventOnce(taskId, AgentEventKind.WARNING, "Связь нестабильна", "$error. Checkpoint сохранён, работа продолжится автоматически.")
    }

    private fun ensureRunning(taskId: String, shouldContinue: () -> Boolean) {
        if (!shouldContinue()) throw CancellationException("Остановлено WorkManager")
        val status = store.get(taskId)?.status ?: throw CancellationException("Задача удалена")
        if (!status.active) throw CancellationException("Задача больше не активна")
    }

    private fun unresolvedCalls(messages: List<ApiMessage>): List<ApiToolCall> {
        val resolved = messages.asSequence().filter { it.role == "tool" }.mapNotNull { it.toolCallId }.toSet()
        return messages.asSequence().flatMap { it.toolCalls.asSequence() }.filterNot { it.id in resolved }.toList()
    }

    private fun appendToolResult(messages: List<ApiMessage>, callId: String, result: String): List<ApiMessage> {
        if (messages.any { it.role == "tool" && it.toolCallId == callId }) return messages
        return messages + ApiMessage("tool", result, toolCallId = callId)
    }

    private fun isRepeatedCallLoop(messages: List<ApiMessage>): Boolean {
        val signatures = messages.asReversed().asSequence().filter { it.role == "assistant" && it.toolCalls.isNotEmpty() }
            .take(3).map { message -> message.toolCalls.joinToString("|") { "${it.name}:${it.arguments}" } }.toList()
        return signatures.size == 3 && signatures.distinct().size == 1
    }

    private fun toolTitle(call: ApiToolCall): String = when (call.name) {
        "ssh_exec" -> "SSH • ${call.arguments.optString("command").take(90)}"
        "web_search" -> "Поиск • ${call.arguments.optString("query").take(90)}"
        "web_fetch" -> "Чтение источника"
        "http_request" -> "HTTP • ${call.arguments.optString("method", "GET").uppercase()}"
        else -> call.name
    }

    private fun ApiToolCall.argumentsSummary(): String = when (name) {
        "ssh_exec" -> arguments.optString("command").take(500)
        else -> arguments.toString().take(500)
    }

    private fun summarizeResult(result: String): String = result.lineSequence().take(12).joinToString("\n").take(1_500)

    private fun isFatalSshFailure(content: String): Boolean {
        val normalized = content.lowercase()
        return listOf(
            "auth fail",
            "auth cancel",
            "invalid privatekey",
            "reject hostkey",
            "hostkey has been changed",
            "доверить fingerprint",
            "fingerprint хоста",
            "ssh-профиль не найден"
        ).any(normalized::contains)
    }

    private fun compactContext(input: List<ApiMessage>): List<ApiMessage> {
        val clipped = input.mapIndexed { index, message ->
            val limit = if (index == 0) 24_000 else if (message.role == "tool") 8_000 else 14_000
            if (message.content != null && message.content.length > limit) message.copy(content = message.content.take(limit) + "\n[сокращено]") else message
        }
        if (clipped.sumOf { it.content?.length ?: 0 } <= 140_000 && clipped.size <= 60) return clipped
        val first = clipped.firstOrNull()
        val tail = clipped.takeLast(48).dropWhile { it.role == "tool" }
        return if (first == null || first in tail) tail else listOf(first) + tail
    }

    private fun encodeMessages(messages: List<ApiMessage>): String = JSONArray().apply {
        messages.forEach { put(it.toJson()) }
    }.toString()

    private fun decodeMessages(raw: String): List<ApiMessage> {
        val array = JSONArray(raw)
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            val callsJson = item.optJSONArray("tool_calls") ?: JSONArray()
            val calls = List(callsJson.length()) { callIndex ->
                val call = callsJson.getJSONObject(callIndex)
                val function = call.getJSONObject("function")
                val arguments = function.opt("arguments")
                ApiToolCall(
                    id = call.optString("id").ifBlank { "checkpoint_${index}_$callIndex" },
                    name = function.getString("name"),
                    arguments = when (arguments) {
                        is JSONObject -> arguments
                        is String -> if (arguments.isBlank()) JSONObject() else JSONObject(arguments)
                        else -> JSONObject()
                    }
                )
            }
            ApiMessage(
                role = item.getString("role"),
                content = item.opt("content").takeUnless { it == null || it == JSONObject.NULL }?.toString(),
                toolCallId = item.optString("tool_call_id").takeIf { it.isNotBlank() },
                toolCalls = calls
            )
        }
    }

    private fun enqueue(id: String, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<AutonomousAgentWorker>()
            .setInputData(workDataOf(AutonomousAgentWorker.TASK_ID to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag(workName(id))
            .build()
        workManager.enqueueUniqueWork(workName(id), policy, request)
    }

    private fun workName(id: String) = "jarvis-autonomous-$id"

    private sealed interface CallOutcome {
        data class Completed(val messages: List<ApiMessage>) : CallOutcome
        data object StillRunning : CallOutcome
        data object RetryLater : CallOutcome
        data object StopWorker : CallOutcome
        data object TaskFinished : CallOutcome
    }

    private fun AgentTaskStore.addEventOnce(taskId: String, kind: AgentEventKind, title: String, detail: String) {
        if (events(taskId, 1).lastOrNull()?.let { it.title == title && it.detail == detail } != true) addEvent(taskId, kind, title, detail)
    }

    companion object {
        private const val MAX_MODEL_STEPS_PER_RUN = 6
        private const val MAX_RUN_MILLIS = 6 * 60_000L
        private const val CONTINUE_PROMPT = "Задача ещё не завершена через finish_task. Продолжай самостоятельно: выбери следующий проверяемый шаг, используй инструменты или заверши задачу только после проверки."
    }
}
