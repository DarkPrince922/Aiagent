package app.jarvis.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.jarvis.data.InstalledModel
import app.jarvis.data.ModelCatalog
import app.jarvis.data.ModelPreset
import app.jarvis.data.SettingsStore
import app.jarvis.llm.DownloadState
import app.jarvis.llm.ModelDownloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ModelsState(
    val presets: List<ModelPreset> = ModelCatalog.presets,
    val installed: List<InstalledModel> = emptyList(),
    val activePath: String = "",
    val download: DownloadState? = null,
    val customUrl: String = "",
    val freeBytes: Long = 0,
    val banner: String? = null
)

class ModelsViewModel(
    private val downloads: ModelDownloads,
    private val settings: SettingsStore
) : ViewModel() {
    private val mutable = MutableStateFlow(ModelsState())
    val state = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            refresh()
            while (isActive) {
                // Пока идёт загрузка, прогресс нужен живой; в покое опрос замедляется.
                delay(if (mutable.value.download?.running == true) 700 else 4_000)
                refresh()
            }
        }
    }

    fun updateCustomUrl(value: String) { mutable.value = mutable.value.copy(customUrl = value.trim()) }
    fun dismissBanner() { mutable.value = mutable.value.copy(banner = null) }

    fun download(preset: ModelPreset) = command {
        downloads.start(preset.url, preset.title, preset.approxBytes)
        settings.save(settings.get().copy(localContextTokens = preset.recommendedContext))
    }

    fun downloadCustom() {
        val url = mutable.value.customUrl
        if (url.isBlank()) return
        command { downloads.start(url, "Своя модель", 0) }
    }

    fun cancel() = command { downloads.cancel() }
    fun delete(model: InstalledModel) = command { downloads.delete(model) }
    fun select(model: InstalledModel) = command { downloads.select(model) }

    private fun command(action: () -> Unit) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { action() } }
                .onFailure { mutable.value = mutable.value.copy(banner = it.message ?: "Не удалось выполнить") }
            refresh(preserveBanner = true)
        }
    }

    private suspend fun refresh(preserveBanner: Boolean = true) {
        data class Snapshot(
            val installed: List<InstalledModel>,
            val download: DownloadState?,
            val activePath: String,
            val freeBytes: Long
        )
        val snapshot = withContext(Dispatchers.IO) {
            Snapshot(downloads.installed(), downloads.state(), settings.get().localModelPath, downloads.freeBytes())
        }
        val old = mutable.value
        mutable.value = old.copy(
            installed = snapshot.installed,
            download = snapshot.download,
            activePath = snapshot.activePath,
            freeBytes = snapshot.freeBytes,
            banner = if (preserveBanner) old.banner else null
        )
    }
}
