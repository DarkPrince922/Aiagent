package app.jarvis.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.jarvis.agent.AutonomousAgentManager
import app.jarvis.data.AgentTask
import app.jarvis.data.AgentTaskEvent
import app.jarvis.data.AgentTaskStatus
import android.content.ContentResolver
import android.net.Uri
import app.jarvis.data.ShareIntake
import app.jarvis.data.SshProfileStore
import app.jarvis.data.WorkspaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SshProfileChoice(val id: String, val name: String, val target: String, val trusted: Boolean)

data class AutonomyState(
    val tasks: List<AgentTask> = emptyList(),
    val selectedTaskId: String? = null,
    val events: List<AgentTaskEvent> = emptyList(),
    val sshProfiles: List<SshProfileChoice> = emptyList(),
    val objective: String = "",
    val instruction: String = "",
    /** Файлы, прикреплённые к новой задаче: их имена уйдут в цель. */
    val objectiveAttachments: List<String> = emptyList(),
    /** Файлы, прикреплённые к указанию для работающей задачи. */
    val instructionAttachments: List<String> = emptyList(),
    val selectedProfileId: String? = null,
    val autoApproveSsh: Boolean = true,
    val creating: Boolean = false,
    val sendingInstruction: Boolean = false,
    val banner: String? = null
) {
    val selectedTask: AgentTask? get() = tasks.firstOrNull { it.id == selectedTaskId }
}

class AutonomyViewModel(
    private val manager: AutonomousAgentManager,
    private val profiles: SshProfileStore,
    private val workspace: WorkspaceStore
) : ViewModel() {
    private val mutable = MutableStateFlow(AutonomyState())
    val state = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            refresh()
            while (isActive) {
                // Пока агент работает, экран должен обновляться живо; в покое опрос замедляется,
                // чтобы не будить БД каждые полторы секунды впустую.
                delay(if (mutable.value.tasks.any { it.status.active }) ACTIVE_POLL_MS else IDLE_POLL_MS)
                refresh(preserveBanner = true)
            }
        }
    }

    fun updateObjective(value: String) { mutable.value = mutable.value.copy(objective = value, banner = null) }
    fun updateInstruction(value: String) { mutable.value = mutable.value.copy(instruction = value) }
    fun selectProfile(id: String?) { mutable.value = mutable.value.copy(selectedProfileId = id ?: NO_SSH, banner = null) }
    fun setAutoApprove(value: Boolean) { mutable.value = mutable.value.copy(autoApproveSsh = value) }
    fun dismissBanner() { mutable.value = mutable.value.copy(banner = null) }

    /**
     * Прикрепляет файл к задаче или к указанию.
     *
     * Рабочая папка общая, поэтому «прикрепить» — это положить файл туда и назвать его в
     * тексте. Без имени в тексте агент файл не найдёт: свежие файлы он видит списком, но
     * какой из них относится к делу, решает не он.
     */
    fun attach(resolver: ContentResolver, uri: Uri, toInstruction: Boolean) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { ShareIntake(resolver, workspace).importUri(uri) } }
                .onSuccess { saved ->
                    val current = mutable.value
                    mutable.value = if (toInstruction) {
                        current.copy(
                            instructionAttachments = (current.instructionAttachments + saved.name).distinct(),
                            banner = null
                        )
                    } else {
                        current.copy(
                            objectiveAttachments = (current.objectiveAttachments + saved.name).distinct(),
                            banner = null
                        )
                    }
                }
                .onFailure { mutable.value = mutable.value.copy(banner = it.message ?: "Не удалось прикрепить файл") }
        }
    }

    fun removeAttachment(name: String, fromInstruction: Boolean) {
        val current = mutable.value
        mutable.value = if (fromInstruction) {
            current.copy(instructionAttachments = current.instructionAttachments - name)
        } else {
            current.copy(objectiveAttachments = current.objectiveAttachments - name)
        }
    }

    fun selectTask(id: String) {
        mutable.value = mutable.value.copy(selectedTaskId = id, instruction = "")
        viewModelScope.launch { refresh(preserveBanner = true) }
    }

    fun start() {
        val snapshot = mutable.value
        if (snapshot.creating) return
        mutable.value = snapshot.copy(creating = true, banner = null)
        viewModelScope.launch {
            runCatching {
                val goal = withFiles(snapshot.objective, snapshot.objectiveAttachments)
                withContext(Dispatchers.IO) { manager.start(goal, snapshot.selectedProfileId?.takeIf { it.isNotBlank() }, snapshot.autoApproveSsh) }
            }.onSuccess { task ->
                mutable.value = mutable.value.copy(
                    selectedTaskId = task.id,
                    objective = "",
                    objectiveAttachments = emptyList(),
                    creating = false,
                    banner = "Автономная задача запущена"
                )
                refresh(preserveBanner = true)
            }.onFailure { error ->
                mutable.value = mutable.value.copy(creating = false, banner = error.message ?: "Не удалось запустить задачу")
            }
        }
    }

    /**
     * Дописывает имена файлов в текст: рабочая папка общая, и без явного имени агент не
     * поймёт, какой из лежащих там файлов относится к этой задаче.
     */
    private fun withFiles(text: String, attachments: List<String>): String {
        if (attachments.isEmpty()) return text
        val list = attachments.joinToString("\n") { "- $it" }
        return buildString {
            append(text.trim())
            if (isNotEmpty()) append("\n\n")
            append("Файлы для этой работы (лежат в рабочей папке):\n").append(list)
        }
    }

    /** Передаёт указание работающему агенту — он учтёт его перед следующим шагом. */
    fun sendInstruction() {
        val snapshot = mutable.value
        val taskId = snapshot.selectedTaskId ?: return
        val text = withFiles(snapshot.instruction.trim(), snapshot.instructionAttachments)
        if (text.isBlank() || snapshot.sendingInstruction) return
        mutable.value = snapshot.copy(sendingInstruction = true, banner = null)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { manager.addInstruction(taskId, text) } }
                .onSuccess { status ->
                    mutable.value = mutable.value.copy(
                        instruction = "",
                        instructionAttachments = emptyList(),
                        sendingInstruction = false,
                        banner = if (status == AgentTaskStatus.QUEUED) {
                            "Указание принято; задача возобновлена"
                        } else {
                            "Указание принято; агент учтёт его на следующем шаге"
                        }
                    )
                }
                .onFailure { error ->
                    mutable.value = mutable.value.copy(sendingInstruction = false, banner = error.message ?: "Не удалось передать указание")
                }
            refresh(preserveBanner = true)
        }
    }

    fun pause(id: String) = command { manager.pause(id) }
    fun resume(id: String) = command { manager.resume(id) }
    fun stop(id: String) = command { manager.stop(id) }

    fun delete(id: String) = command {
        manager.delete(id)
        if (mutable.value.selectedTaskId == id) mutable.value = mutable.value.copy(selectedTaskId = null)
    }

    private fun command(action: () -> Unit) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { action() } }
                .onFailure { mutable.value = mutable.value.copy(banner = it.message ?: "Операция не выполнена") }
            refresh(preserveBanner = true)
        }
    }

    private suspend fun refresh(preserveBanner: Boolean = false) {
        val snapshot = withContext(Dispatchers.IO) {
            val tasks = manager.tasks()
            val current = mutable.value.selectedTaskId?.takeIf { id -> tasks.any { it.id == id } }
                ?: tasks.firstOrNull { it.status.active }?.id
                ?: tasks.firstOrNull()?.id
            val events = current?.let(manager::events).orEmpty()
            // summaries() не трогает Keystore: опрос экрана не должен расшифровывать SSH-секреты.
            val choices = profiles.summaries().map { SshProfileChoice(it.id, it.name, "${it.username}@${it.host}:${it.port}", it.hostKeyTrusted) }
            Triple(tasks, current, Pair(events, choices))
        }
        val old = mutable.value
        mutable.value = old.copy(
            tasks = snapshot.first,
            selectedTaskId = snapshot.second,
            events = snapshot.third.first,
            sshProfiles = snapshot.third.second,
            selectedProfileId = old.selectedProfileId?.takeIf { id -> id == NO_SSH || snapshot.third.second.any { it.id == id } }
                ?: snapshot.third.second.firstOrNull { it.trusted }?.id,
            banner = if (preserveBanner) old.banner else null
        )
    }

    private companion object {
        const val NO_SSH = ""
        const val ACTIVE_POLL_MS = 1_500L
        const val IDLE_POLL_MS = 6_000L
    }
}
