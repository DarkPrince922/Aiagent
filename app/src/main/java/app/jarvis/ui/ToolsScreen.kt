package app.jarvis.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.jarvis.tools.ToolInfo
import app.jarvis.tools.ToolRisk

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ToolsScreen(tools: List<ToolInfo>, runPrompt: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Все") }
    val categories = remember(tools) { listOf("Все") + tools.map { it.category }.distinct() }
    val filtered = tools.filter { (category == "Все" || it.category == category) && (query.isBlank() || it.title.contains(query, true) || it.description.contains(query, true)) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Инструменты", style = MaterialTheme.typography.titleLarge); Text("${tools.size} возможностей агента", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Icon(Icons.Default.Extension, null, tint = MaterialTheme.colorScheme.primary)
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Очистить") } }, placeholder = { Text("Поиск инструмента") }, singleLine = true, shape = RoundedCornerShape(8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(categories) { item -> FilterChip(selected = category == item, onClick = { category = item }, label = { Text(item) }) }
        }
        LazyVerticalGrid(columns = GridCells.Adaptive(168.dp), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(filtered, key = { it.name }) { tool -> ToolCard(tool) { runPrompt(toolPrompt(tool)) } }
        }
    }
}

@Composable private fun ToolCard(tool: ToolInfo, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 154.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(38.dp)) { Box(contentAlignment = Alignment.Center) { Icon(toolIcon(tool.icon), null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer) } }
                Spacer(Modifier.weight(1f))
                Icon(when (tool.risk) { ToolRisk.READ_ONLY -> Icons.Default.CheckCircle; ToolRisk.CHANGES_DEVICE -> Icons.Default.TouchApp; ToolRisk.REMOTE_COMMAND -> Icons.Default.AdminPanelSettings }, null, Modifier.size(18.dp), tint = when (tool.risk) { ToolRisk.READ_ONLY -> MaterialTheme.colorScheme.primary; ToolRisk.CHANGES_DEVICE -> MaterialTheme.colorScheme.tertiary; ToolRisk.REMOTE_COMMAND -> MaterialTheme.colorScheme.error })
            }
            Spacer(Modifier.height(12.dp)); Text(tool.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp)); Text(tool.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun toolPrompt(tool: ToolInfo): String = when (tool.name) {
    "web_search" -> "Найди в интернете актуальную информацию о "
    "web_fetch" -> "Прочитай и кратко проанализируй страницу: https://"
    "ssh_exec" -> "На SSH-сервере выполни диагностику: uptime, свободное место и память"
    "http_request" -> "Проверь HTTP API: https://"
    "dns_lookup" -> "Проверь DNS для домена "
    "json_format" -> "Проверь и отформатируй этот JSON: "
    "calculate" -> "Посчитай: "
    "create_note" -> "Создай заметку: "
    "list_notes" -> "Найди в моих заметках: "
    "set_alarm" -> "Поставь будильник на "
    "set_timer" -> "Поставь таймер на "
    "add_calendar_event" -> "Добавь в календарь: "
    "open_map" -> "Покажи на карте: "
    "dial_phone" -> "Открой набор номера: "
    "compose_email" -> "Подготовь письмо: "
    "share_text" -> "Поделись текстом: "
    "clipboard_read" -> "Прочитай и проанализируй мой буфер обмена"
    "clipboard_write" -> "Скопируй в буфер обмена: "
    else -> "Используй инструмент ${tool.name} и помоги мне"
}

private fun toolIcon(name: String): ImageVector = when (name) {
    "travel_explore" -> Icons.Default.TravelExplore; "language" -> Icons.Default.Language; "terminal" -> Icons.Default.Terminal
    "http" -> Icons.Default.Http; "dns" -> Icons.Default.Dns; "data_object" -> Icons.Default.DataObject; "calculate" -> Icons.Default.Calculate
    "edit_note" -> Icons.Default.EditNote; "notes" -> Icons.Default.Notes; "alarm" -> Icons.Default.Alarm; "timer" -> Icons.Default.Timer
    "calendar_month" -> Icons.Default.CalendarMonth; "map" -> Icons.Default.Map; "phone" -> Icons.Default.Phone; "mail" -> Icons.Default.Mail
    "share" -> Icons.Default.Share; "content_paste" -> Icons.Default.ContentPaste; "content_copy" -> Icons.Default.ContentCopy
    "phone_android" -> Icons.Default.PhoneAndroid; else -> Icons.Default.Extension
}
