package app.jarvis.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.jarvis.data.SshProfile
import app.jarvis.data.SshProfileStore
import app.jarvis.data.SshProfileSummary
import app.jarvis.net.SshService
import app.jarvis.net.TerminalProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Что показываем: живые окна tmux или журналы операций агента. */
enum class TerminalSource(val label: String) { SESSIONS("Сессии tmux"), OPERATIONS("Операции агента") }

/** Строка списка: имя для команды и то, что видит человек. */
data class TerminalTarget(val id: String, val title: String, val subtitle: String)

data class TerminalState(
    val profiles: List<SshProfileSummary> = emptyList(),
    val profileId: String? = null,
    val source: TerminalSource = TerminalSource.SESSIONS,
    val targets: List<TerminalTarget> = emptyList(),
    val selected: String? = null,
    val output: String = "",
    val command: String = "",
    val follow: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val updatedAt: Long = 0
) {
    val profile: SshProfileSummary? get() = profiles.firstOrNull { it.id == profileId }
}

/**
 * Окно терминала: что прямо сейчас происходит на сервере.
 *
 * Раньше выполнение было видно только по результату команды в переписке. Долгая сборка,
 * зависший процесс, вывод, который агент не показал, — всё это существовало, но посмотреть
 * на него было нельзя. Здесь читается то же, что видит агент: живое окно tmux и файлы
 * операции в `~/.cache/jarvis-agent/ops`.
 */
class TerminalViewModel(
    private val profiles: SshProfileStore,
    private val ssh: SshService
) : ViewModel() {
    private val mutable = MutableStateFlow(TerminalState())
    val state = mutable.asStateFlow()
    private var follower: Job? = null

    init {
        // Список берём сводками: расшифровывать ключи ради имён профилей незачем.
        val list = profiles.summaries()
        mutable.value = mutable.value.copy(profiles = list, profileId = list.firstOrNull()?.id)
        if (list.isNotEmpty()) refresh()
    }

    fun selectProfile(id: String) {
        mutable.value = mutable.value.copy(profileId = id, targets = emptyList(), selected = null, output = "")
        refresh()
    }

    fun selectSource(source: TerminalSource) {
        mutable.value = mutable.value.copy(source = source, targets = emptyList(), selected = null, output = "")
        refresh()
    }

    fun select(id: String) {
        mutable.value = mutable.value.copy(selected = id, output = "")
        load()
    }

    fun updateCommand(value: String) { mutable.value = mutable.value.copy(command = value) }

    fun toggleFollow() {
        val next = !mutable.value.follow
        mutable.value = mutable.value.copy(follow = next)
        if (next) startFollowing() else follower?.cancel()
    }

    /** Обновляет список окон и операций. */
    fun refresh() {
        val profile = mutable.value.profile ?: return
        run(profile) { target ->
            val source = mutable.value.source
            val listing = ssh.execute(
                target,
                if (source == TerminalSource.SESSIONS) TerminalProbe.SESSIONS else TerminalProbe.OPERATIONS
            ).output
            val parsed = TerminalProbe.parse(listing).map { entry ->
                TerminalTarget(
                    id = entry.id,
                    // Хэш операции целиком не нужен: в списке важнее видеть состояние.
                    title = if (source == TerminalSource.OPERATIONS) entry.id.take(12) else entry.id,
                    subtitle = entry.detail
                )
            }
            val keep = mutable.value.selected?.takeIf { id -> parsed.any { it.id == id } } ?: parsed.firstOrNull()?.id
            mutable.value = mutable.value.copy(targets = parsed, selected = keep)
            if (keep != null) loadBlocking(target, keep)
        }
    }

    /**
     * Открывает своё окно с оболочкой и переключается в него.
     *
     * В окне задачи приглашения нет — там работает сама команда, и набранный текст ушёл бы ей
     * в stdin. Своё окно — обычный шелл, в котором можно смотреть и делать что угодно, не
     * трогая работу агента.
     */
    fun openOwnSession() {
        val profile = mutable.value.profile ?: return
        mutable.value = mutable.value.copy(source = TerminalSource.SESSIONS)
        run(profile) { target ->
            ssh.execute(target, TerminalProbe.createSession(TerminalProbe.USER_SESSION))
            val listing = ssh.execute(target, TerminalProbe.SESSIONS).output
            mutable.value = mutable.value.copy(
                targets = TerminalProbe.parse(listing).map { TerminalTarget(it.id, it.id, it.detail) },
                selected = TerminalProbe.USER_SESSION
            )
            loadBlocking(target, TerminalProbe.USER_SESSION)
        }
    }

    /** Перечитывает вывод выбранного окна. */
    fun load() {
        val profile = mutable.value.profile ?: return
        val selected = mutable.value.selected ?: return
        run(profile) { target -> loadBlocking(target, selected) }
    }

    /**
     * Отправляет команду.
     *
     * В окно tmux — теми же клавишами, что набрал бы человек: команда попадает в ту самую
     * сессию, где работает агент, и её вывод виден там же. Без выбранного окна команда
     * выполняется обычным сеансом, и её вывод показывается как есть.
     */
    fun send() {
        val profile = mutable.value.profile ?: return
        val command = mutable.value.command.trim()
        if (command.isBlank()) return
        val session = mutable.value.selected?.takeIf { mutable.value.source == TerminalSource.SESSIONS }
        mutable.value = mutable.value.copy(command = "")
        run(profile) { target ->
            if (session == null) {
                val result = ssh.execute(target, command)
                mutable.value = mutable.value.copy(
                    output = buildString {
                        append(mutable.value.output)
                        if (isNotEmpty()) append("\n")
                        append("$ ").append(command).append("\n").append(result.output)
                    }.takeLast(MAX_OUTPUT_CHARS),
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                ssh.execute(target, TerminalProbe.sendKeys(session, command))
                // Команде нужно мгновение, чтобы напечататься в окне и что-то вывести.
                Thread.sleep(400)
                loadBlocking(target, session)
            }
        }
    }

    private fun loadBlocking(profile: SshProfile, selected: String) {
        val command = when (mutable.value.source) {
            TerminalSource.SESSIONS -> TerminalProbe.capture(selected, CAPTURE_LINES)
            TerminalSource.OPERATIONS -> TerminalProbe.operation(selected)
        }
        val output = ssh.execute(profile, command).output
        mutable.value = mutable.value.copy(
            output = output.takeLast(MAX_OUTPUT_CHARS),
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun run(summary: SshProfileSummary, action: (SshProfile) -> Unit) {
        follower?.cancel()
        mutable.value = mutable.value.copy(busy = true, error = null)
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { action(resolve(summary)) }.exceptionOrNull()
            }
            mutable.value = mutable.value.copy(
                busy = false,
                error = error?.let { it.message ?: "Ошибка связи с сервером" }
            )
            if (error == null && mutable.value.follow && mutable.value.selected != null) startFollowing()
        }
    }

    /**
     * Живое обновление.
     *
     * Пауза между запросами не косметическая: каждый — это новое SSH-соединение, и слишком
     * частый опрос сервер видит как поток подключений.
     */
    private fun startFollowing() {
        follower?.cancel()
        follower = viewModelScope.launch {
            while (isActive) {
                delay(FOLLOW_INTERVAL_MS)
                val profile = mutable.value.profile ?: return@launch
                val selected = mutable.value.selected ?: return@launch
                if (!mutable.value.follow || mutable.value.busy) continue
                val error = withContext(Dispatchers.IO) {
                    runCatching { loadBlocking(resolve(profile), selected) }.exceptionOrNull()
                }
                // Разрыв связи не повод гасить экран: показываем причину и продолжаем пробовать.
                if (error != null) mutable.value = mutable.value.copy(error = error.message ?: "Нет связи")
            }
        }
    }

    /** Секреты профиля берём только здесь, уже на IO и только ради самого соединения. */
    private fun resolve(summary: SshProfileSummary): SshProfile =
        profiles.find(summary.id) ?: error("SSH-профиль ${summary.name} не найден")

    override fun onCleared() {
        follower?.cancel()
    }

    companion object {
        private const val CAPTURE_LINES = 400
        private const val MAX_OUTPUT_CHARS = 60_000
        private const val FOLLOW_INTERVAL_MS = 4_000L

    }
}
