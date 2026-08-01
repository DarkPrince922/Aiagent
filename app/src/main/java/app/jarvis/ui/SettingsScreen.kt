package app.jarvis.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.jarvis.data.ProviderSettings

@Composable fun SettingsScreen(vm: ChatViewModel, initial: ProviderSettings) {
    var value by remember(initial) { mutableStateOf(initial) }
    var keyVisible by remember { mutableStateOf(false) }
    val state by vm.state.collectAsStateWithLifecycle()
    val models = listOf("claude-opus-4-8", "claude-sonnet-4-5", "gpt-5.2", "gpt-4.1")
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Настройки", style = MaterialTheme.typography.titleLarge); Text("Провайдер и поведение агента", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        item { SectionTitle("AI-провайдер", Icons.Default.Cloud) }
        item { SettingsField { OutlinedTextField(value.endpoint, { value = value.copy(endpoint = it) }, Modifier.fillMaxWidth(), label = { Text("API endpoint") }, leadingIcon = { Icon(Icons.Default.Link, null) }, supportingText = { Text("OpenAI-совместимый адрес, заканчивающийся на /v1") }, singleLine = true) } }
        item { SettingsField { OutlinedTextField(value.model, { value = value.copy(model = it) }, Modifier.fillMaxWidth(), label = { Text("Модель") }, leadingIcon = { Icon(Icons.Default.Memory, null) }, singleLine = true) } }
        item { LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(models) { model -> SuggestionChip(onClick = { value = value.copy(model = model) }, label = { Text(model) }, icon = if (value.model == model) ({ Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }) else null) } } }
        item { SettingsField { OutlinedTextField(value.apiKey, { value = value.copy(apiKey = it) }, Modifier.fillMaxWidth(), label = { Text("API-ключ") }, leadingIcon = { Icon(Icons.Default.Key, null) }, trailingIcon = { IconButton(onClick = { keyVisible = !keyVisible }) { Icon(if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (keyVisible) "Скрыть" else "Показать") } }, visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(), singleLine = true, supportingText = { Text("Шифруется ключом Android Keystore") }) } }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.checkConnection(value) }, enabled = state.apiStatus != ApiStatus.CHECKING, modifier = Modifier.weight(1f)) { if (state.apiStatus == ApiStatus.CHECKING) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.NetworkCheck, null); Spacer(Modifier.width(7.dp)); Text("Проверить") }
                Button(onClick = { vm.saveSettings(value) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(7.dp)); Text("Сохранить") }
            }
        }
        if (state.apiStatus != ApiStatus.NOT_CONFIGURED) item { Surface(color = when (state.apiStatus) { ApiStatus.ONLINE -> MaterialTheme.colorScheme.primaryContainer; ApiStatus.ERROR, ApiStatus.OFFLINE -> MaterialTheme.colorScheme.errorContainer; else -> MaterialTheme.colorScheme.surfaceVariant }, modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Icon(when (state.apiStatus) { ApiStatus.ONLINE -> Icons.Default.CheckCircle; ApiStatus.ERROR -> Icons.Default.Error; ApiStatus.OFFLINE -> Icons.Default.CloudOff; else -> Icons.Default.Sync }, null); Spacer(Modifier.width(9.dp)); Text(state.statusText, style = MaterialTheme.typography.bodySmall) } } }
        item { SectionTitle("Агент", Icons.Default.AutoAwesome) }
        item {
            ListItem(
                headlineContent = { Text("Использовать инструменты") },
                supportingContent = { Text("Поиск, SSH, заметки и действия Android") },
                leadingContent = { Icon(Icons.Default.Extension, null) },
                trailingContent = { Switch(value.toolsEnabled, { value = value.copy(toolsEnabled = it) }) }
            )
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.AccountTree, null); Spacer(Modifier.width(12.dp)); Text("Максимум шагов: ${value.agentSteps}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall) }
                Slider(value.agentSteps.toFloat(), { value = value.copy(agentSteps = it.toInt()) }, valueRange = 1f..10f, steps = 8)
            }
        }
        item { SettingsField { OutlinedTextField(value.systemPrompt, { value = value.copy(systemPrompt = it) }, Modifier.fillMaxWidth(), label = { Text("Системная инструкция") }, leadingIcon = { Icon(Icons.Default.Psychology, null) }, minLines = 5, maxLines = 12) } }
        item { SectionTitle("Безопасность", Icons.Default.Security) }
        item { ListItem(headlineContent = { Text("Подтверждение действий") }, supportingContent = { Text("SSH, HTTP-запросы, буфер, звонки, письма и изменения данных всегда требуют вашего нажатия") }, leadingContent = { Icon(Icons.Default.AdminPanelSettings, null, tint = MaterialTheme.colorScheme.primary) }) }
        item { ListItem(headlineContent = { Text("Закрепление SSH-хоста") }, supportingContent = { Text("После первой успешной проверки Jarvis запоминает fingerprint и отклоняет подмену сервера") }, leadingContent = { Icon(Icons.Default.VerifiedUser, null, tint = MaterialTheme.colorScheme.primary) }) }
        item { SectionTitle("О приложении", Icons.Default.Info) }
        item { ListItem(headlineContent = { Text("Jarvis 0.2") }, supportingContent = { Text("Личный Android-агент • 19 инструментов") }, leadingContent = { Icon(Icons.Default.AutoAwesome, null) }) }
    }
}

@Composable private fun SectionTitle(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)); Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
}

@Composable private fun SettingsField(content: @Composable () -> Unit) { Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) { content() } }
