package app.jarvis.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.jarvis.data.WorkspaceFile
import app.jarvis.data.WorkspaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FilesState(
    val files: List<WorkspaceFile> = emptyList(),
    val loading: Boolean = true,
    /** Имя открытого файла и его начало; null — ничего не открыто. */
    val preview: FilePreview? = null,
    val error: String? = null
)

data class FilePreview(
    val name: String,
    val text: String,
    val totalChars: Int,
    val shown: Int,
    val readable: Boolean
) {
    val truncated: Boolean get() = readable && shown < totalChars
}

/**
 * Файлы, которыми обменялись с агентом.
 *
 * Агент складывает отчёты и архивы в рабочую папку, но добраться до них можно было только
 * через само́й же агента — попросив его вызвать send_file. Экран показывает их напрямую.
 */
class FilesViewModel(private val workspace: WorkspaceStore) : ViewModel() {
    private val mutable = MutableStateFlow(FilesState())
    val state = mutable.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val files = withContext(Dispatchers.IO) { runCatching { workspace.list() } }
            mutable.value = mutable.value.copy(
                files = files.getOrDefault(emptyList()),
                loading = false,
                error = files.exceptionOrNull()?.message
            )
        }
    }

    fun open(name: String) {
        viewModelScope.launch {
            val preview = withContext(Dispatchers.IO) { load(name) }
            mutable.value = mutable.value.copy(preview = preview)
        }
    }

    fun closePreview() { mutable.value = mutable.value.copy(preview = null) }

    fun delete(name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { workspace.delete(name) } }
            mutable.value = mutable.value.copy(preview = null)
            refresh()
        }
    }

    /**
     * Очистка папки целиком.
     *
     * Папка общая для всех задач, и накопившиеся отчёты прежних запусков сбивают агента:
     * он их читает и путается. Разбирать их по одному вручную — работа, которую никто
     * делать не станет.
     */
    fun deleteAll() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                workspace.list().forEach { runCatching { workspace.delete(it.name) } }
            }
            mutable.value = mutable.value.copy(preview = null)
            refresh()
        }
    }

    /** Архив открывать нечем, и это не ошибка: его отправляют, а не читают. */
    private fun load(name: String): FilePreview {
        if (!WorkspaceStore.isText(name)) {
            return FilePreview(name, "Двоичный файл — его можно отправить, но не показать текстом.", 0, 0, readable = false)
        }
        return runCatching {
            val chunk = workspace.readChunk(name, 0, PREVIEW_CHARS)
            FilePreview(chunk.name, chunk.text, chunk.totalChars, chunk.text.length, readable = true)
        }.getOrElse {
            FilePreview(name, "Не удалось прочитать: ${it.message ?: "ошибка"}", 0, 0, readable = false)
        }
    }

    private companion object {
        /** Хватает, чтобы понять, что за файл; полный текст открывается внешним приложением. */
        const val PREVIEW_CHARS = 20_000
    }
}
