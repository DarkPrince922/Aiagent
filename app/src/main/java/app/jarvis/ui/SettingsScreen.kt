package app.jarvis.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
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
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column {
                    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("Настройки", style = MaterialTheme.typography.titleMedium); Text("CONFIG / AGENT", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Icon(Icons.Default.Tune, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        item { SectionTitle("AI-провайдер", Icons.Default.Cloud) }
        item { SettingsField { OutlinedTextField(value.endpoint, { value = value.copy(endpoint = it) }, Modifier.fillMaxWidth(), label = { Text("API endpoint") }, leadingIcon = { Icon(Icons.Default.Link, null) }, supportingText = { Text("OpenAI-совместимый адрес, заканчивающийся на /v1") }, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), singleLine = true) } }
        item { SettingsField { OutlinedTextField(value.model, { value = value.copy(model = it) }, Modifier.fillMaxWidth(), label = { Text("Модель") }, leadingIcon = { Icon(Icons.Default.Memory, null) }, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), singleLine = true) } }
        item { LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(models) { model -> SuggestionChip(onClick = { value = value.copy(model = model) }, label = { Text(model) }, icon = if (value.model == model) ({ Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }) else null) } } }
        item { SettingsField { OutlinedTextField(value.apiKey, { value = value.copy(apiKey = it) }, Modifier.fillMaxWidth(), label = { Text("API-ключ") }, leadingIcon = { Icon(Icons.Default.Key, null) }, trailingIcon = { IconButton(onClick = { keyVisible = !keyVisible }) { Icon(if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (keyVisible) "Скрыть" else "Показать") } }, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(), singleLine = true, supportingText = { Text("Шифруется ключом Android Keystore") }) } }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.checkConnection(value) }, enabled = state.apiStatus != ApiStatus.CHECKING, modifier = Modifier.weight(1f)) { if (state.apiStatus == ApiStatus.CHECKING) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.NetworkCheck, null); Spacer(Modifier.width(7.dp)); Text("Проверить") }
                Button(onClick = { vm.saveSettings(value) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(7.dp)); Text("Сохранить") }
            }
        }
        if (state.apiStatus != ApiStatus.NOT_CONFIGURED) item {
            val accent = when (state.apiStatus) { ApiStatus.ONLINE -> MaterialTheme.colorScheme.primary; ApiStatus.ERROR, ApiStatus.OFFLINE -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.secondary }
            Surface(color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) { Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Icon(when (state.apiStatus) { ApiStatus.ONLINE -> Icons.Default.CheckCircle; ApiStatus.ERROR -> Icons.Default.Error; ApiStatus.OFFLINE -> Icons.Default.CloudOff; else -> Icons.Default.Sync }, null, tint = accent); Spacer(Modifier.width(9.dp)); Text(state.statusText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        }
        item { SectionTitle("Агент", Icons.Default.AutoAwesome) }
        item {
            ListItem(
                headlineContent = { Text("Использовать инструменты") },
                supportingContent = { Text("Поиск, SSH, заметки и действия Android") },
                leadingContent = { Icon(Icons.Default.Extension, null) },
                trailingContent = { Switch(value.toolsEnabled, { value = value.copy(toolsEnabled = it) }) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
        }
        item {
            ListItem(
                headlineContent = { Text("Без лимита шагов") },
                supportingContent = { Text("Агент работает до результата или нажатия «Стоп»; повторяющиеся циклы блокируются") },
                leadingContent = { Icon(Icons.Default.AllInclusive, null) },
                trailingContent = { Switch(value.unlimitedAgent, { value = value.copy(unlimitedAgent = it) }) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
        }
        if (!value.unlimitedAgent) item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.AccountTree, null); Spacer(Modifier.width(12.dp)); Text("Максимум шагов: ${value.agentSteps}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall) }
                Slider(value.agentSteps.toFloat(), { value = value.copy(agentSteps = it.toInt()) }, valueRange = 1f..20f, steps = 18)
            }
        }
        item { SettingsField { OutlinedTextField(value.systemPrompt, { value = value.copy(systemPrompt = it) }, Modifier.fillMaxWidth(), label = { Text("Системная инструкция") }, leadingIcon = { Icon(Icons.Default.Psychology, null) }, minLines = 5, maxLines = 12) } }
        item { SectionTitle("Безопасность", Icons.Default.Security) }
        item { ListItem(headlineContent = { Text("Будильники и таймеры") }, supportingContent = { Text("Разрешение SET_ALARM выдаётся Android при установке") }, leadingContent = { Icon(Icons.Default.AlarmOn, null, tint = MaterialTheme.colorScheme.primary) }, trailingContent = { Icon(Icons.Default.CheckCircle, "Разрешено", tint = MaterialTheme.colorScheme.primary) }) }
        item { ListItem(headlineContent = { Text("Календарь, карты, звонки и почта") }, supportingContent = { Text("Открываются в системных приложениях с вашим подтверждением") }, leadingContent = { Icon(Icons.Default.Apps, null, tint = MaterialTheme.colorScheme.primary) }) }
        item { ListItem(headlineContent = { Text("Контроль SSH-действий") }, supportingContent = { Text("В обычном чате SSH-команды требуют подтверждения. Автономная задача получает доступ только к выбранному для неё профилю.") }, leadingContent = { Icon(Icons.Default.AdminPanelSettings, null, tint = MaterialTheme.colorScheme.primary) }) }
        item { ListItem(headlineContent = { Text("Закрепление SSH-хоста") }, supportingContent = { Text("После первой успешной проверки Jarvis запоминает fingerprint и отклоняет подмену сервера") }, leadingContent = { Icon(Icons.Default.VerifiedUser, null, tint = MaterialTheme.colorScheme.primary) }) }
        item { ListItem(headlineContent = { Text("Фоновая работа агента") }, supportingContent = { Text("Активная автономная задача продолжается при закрытом приложении и показывает уведомление с кнопками «Стоп» и «Пауза»") }, leadingContent = { Icon(Icons.Default.Notifications, null, tint = MaterialTheme.colorScheme.primary) }) }
        item { SectionTitle("О приложении", Icons.Default.Info) }
        item { ListItem(headlineContent = { Text("Jarvis 0.5.0") }, supportingContent = { Text("Личный Android-агент • автономные задачи") }, leadingContent = { Icon(Icons.Default.AutoAwesome, null) }) }
    }
}

@Composable private fun SectionTitle(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)); Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp)); HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant) }
}

@Composable private fun SettingsField(content: @Composable () -> Unit) { Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) { content() } }
