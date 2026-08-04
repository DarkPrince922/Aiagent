package app.jarvis.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.jarvis.data.InstalledModel
import app.jarvis.data.ModelPreset

/**
 * Загрузка моделей прямо из приложения.
 *
 * Файл кладётся в собственный каталог приложения, поэтому не нужны ни компьютер,
 * ни разрешения на доступ к общей памяти, недоступной с Android 11.
 */
@Composable
fun ModelsSection(vm: ModelsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            "Свободно ${state.freeBytes / (1024 * 1024 * 1024)} ГБ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        state.banner?.let { banner ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(banner, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = vm::dismissBanner, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Close, "Закрыть", Modifier.size(16.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        state.download?.takeIf { it.running }?.let { download ->
            Card(shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Загрузка модели", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    if (download.totalBytes > 0) {
                        LinearProgressIndicator(progress = { download.percent / 100f }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${download.downloadedBytes / (1024 * 1024)} из ${download.totalBytes / (1024 * 1024)} МБ · ${download.percent}%",
                            style = MaterialTheme.typography.labelSmall
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Text("${download.downloadedBytes / (1024 * 1024)} МБ", style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Можно свернуть приложение — загрузка продолжится в фоне и возобновится после обрыва связи",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = vm::cancel) { Text("Отменить") }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        state.installed.forEach { model ->
            InstalledRow(model, active = model.path == state.activePath, select = { vm.select(model) }, delete = { vm.delete(model) })
            Spacer(Modifier.height(8.dp))
        }

        val busy = state.download?.running == true
        state.presets.filterNot { preset -> state.installed.any { it.name == java.io.File(preset.url).name } }.forEach { preset ->
            PresetRow(preset, enabled = !busy, download = { vm.download(preset) })
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = state.customUrl,
            onValueChange = vm::updateCustomUrl,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Своя ссылка на .gguf") },
            placeholder = { Text("https://huggingface.co/.../model-Q4_K_M.gguf") },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            singleLine = true,
            shape = RoundedCornerShape(6.dp)
        )
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = vm::downloadCustom,
            enabled = state.customUrl.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Download, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Скачать по ссылке")
        }
    }
}

@Composable
private fun InstalledRow(model: InstalledModel, active: Boolean, select: () -> Unit, delete: () -> Unit) {
    Card(
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (active) Icons.Default.CheckCircle else Icons.Default.Memory,
                null,
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(model.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${model.bytes / (1024 * 1024)} МБ${if (active) " · используется" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!active) TextButton(onClick = select) { Text("Выбрать") }
            IconButton(onClick = delete) { Icon(Icons.Default.DeleteOutline, "Удалить модель") }
        }
    }
}

@Composable
private fun PresetRow(preset: ModelPreset, enabled: Boolean, download: () -> Unit) {
    Card(
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(preset.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "~${preset.approxBytes / (1024 * 1024)} МБ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(onClick = download, enabled = enabled, shape = RoundedCornerShape(6.dp)) {
                    Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Скачать")
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(preset.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
