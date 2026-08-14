package app.jarvis.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.jarvis.data.WorkspaceFile
import app.jarvis.data.WorkspaceStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Файлы, которые агент отдал: отчёты, архивы, всё из рабочей папки.
 *
 * Раньше добраться до них можно было только через самого агента — попросив его вызвать
 * send_file. Здесь они открываются и сохраняются напрямую.
 */
@Composable fun FilesScreen(vm: FilesViewModel, workspace: WorkspaceStore) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }

    fun share(name: String) {
        val file = workspace.resolve(name)
        if (!file.isFile) return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mimeOf(name))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching {
            context.startActivity(Intent.createChooser(intent, "Сохранить или отправить").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Файлы", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "WORKSPACE / ${state.files.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = vm::refresh) { Icon(Icons.Default.Refresh, "Обновить") }
                    if (state.files.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) { Icon(Icons.Default.DeleteSweep, "Очистить папку") }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        state.error?.let {
            Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        when {
            state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            state.files.isEmpty() -> EmptyWorkspace()
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(state.files, key = { it.name }) { file ->
                    FileRow(file, onOpen = { vm.open(file.name) }, onShare = { share(file.name) })
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить рабочую папку?") },
            text = {
                Text(
                    "Удалятся все ${state.files.size} файлов. Автономные задачи перестанут натыкаться " +
                        "на отчёты прежних запусков. Отменить удаление нельзя."
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.deleteAll(); confirmClear = false }) { Text("Удалить всё") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } }
        )
    }

    state.preview?.let { preview ->
        AlertDialog(
            onDismissRequest = vm::closePreview,
            title = { Text(preview.name, style = MaterialTheme.typography.titleSmall) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (preview.truncated) {
                        Text(
                            "Показано ${preview.shown} из ${preview.totalChars} символов. " +
                                "Целиком — «Сохранить» и открыть в другом приложении.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        preview.text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { share(preview.name); vm.closePreview() }) {
                    Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Сохранить")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.delete(preview.name) }) { Text("Удалить") }
                    TextButton(onClick = vm::closePreview) { Text("Закрыть") }
                }
            }
        )
    }
}

@Composable private fun FileRow(file: WorkspaceFile, onOpen: () -> Unit, onShare: () -> Unit) {
    ListItem(
        headlineContent = { Text(file.name, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = {
            Text(
                "${humanSize(file.bytes)} · ${humanTime(file.modifiedAt)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            Icon(
                if (WorkspaceStore.isText(file.name)) Icons.Default.Description else Icons.Default.FolderZip,
                null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        trailingContent = { IconButton(onClick = onShare) { Icon(Icons.Default.Save, "Сохранить") } },
        modifier = Modifier.clickable(onClick = onOpen),
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
    )
}

@Composable private fun EmptyWorkspace() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.FolderOpen, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text("Пока пусто", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "Сюда попадают файлы, которые вы прикрепили в чате, и всё, что агент собрал через " +
                "write_file, create_zip или download_file.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "json" -> "application/json"
    "csv" -> "text/csv"
    "zip" -> "application/zip"
    else -> "text/plain"
}

private fun humanSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f МБ".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "${bytes / 1024} КБ"
    else -> "$bytes Б"
}

private val TIME_FORMAT = DateTimeFormatter.ofPattern("dd.MM HH:mm")

private fun humanTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(TIME_FORMAT)
