package app.jarvis.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.jarvis.data.DeliveryState
import app.jarvis.data.Message
import app.jarvis.data.Conversation
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ChatScreen(vm: ChatViewModel, openSettings: () -> Unit, openTools: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var historyOpen by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<Conversation?>(null) }
    val listState = rememberLazyListState()
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) vm.updateDraft(result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty())
    }
    LaunchedEffect(state.messages.size, state.pending) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex + if (state.pending != null) 1 else 0)
    }
    Column(Modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(42.dp)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary) } }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.activeTitle, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(statusIcon(state.apiStatus), null, Modifier.size(14.dp), tint = statusColor(state.apiStatus))
                        Spacer(Modifier.width(5.dp))
                        Text(state.statusText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = { historyOpen = true }) { Icon(Icons.Default.History, "История чатов") }
                IconButton(onClick = vm::newConversation, enabled = !state.sending && state.pending == null) { Icon(Icons.Default.EditNote, "Новый чат") }
                IconButton(onClick = openSettings) { Icon(Icons.Default.Settings, "Настройки") }
            }
        }
        AnimatedVisibility(state.banner != null) {
            Surface(color = if (state.apiStatus == ApiStatus.ERROR || state.apiStatus == ApiStatus.OFFLINE) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (state.apiStatus == ApiStatus.OFFLINE) Icons.Default.CloudOff else Icons.Default.Info, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp)); Text(state.banner.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    if (state.apiStatus == ApiStatus.ERROR) IconButton(onClick = openSettings, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Tune, "Исправить настройки") }
                }
            }
        }
        if (state.loading) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else if (state.messages.isEmpty()) EmptyChat(Modifier.weight(1f), onPrompt = vm::prefill, openTools = openTools)
        else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.messages, key = { it.id }) { message -> MessageBubble(message, retry = { vm.retry(message) }) }
            state.pending?.let { action -> item(key = "confirmation") { ConfirmationPanel(action.label, approve = { vm.resolvePending(true) }, reject = { vm.resolvePending(false) }) } }
            if (state.sending) item(key = "thinking") { ThinkingRow() }
        }
        ChatComposer(
            draft = state.draft,
            enabled = !state.sending && state.pending == null,
            sending = state.sending,
            onDraft = vm::updateDraft,
            onSend = { vm.send() },
            onStop = vm::stop,
            onVoice = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag()).putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите")
                runCatching { speech.launch(intent) }.onFailure { vm.showBanner("На устройстве не найден сервис голосового ввода") }
            }
        )
    }
    if (historyOpen) ChatHistorySheet(
        conversations = state.conversations,
        activeId = state.activeConversationId,
        enabled = !state.sending && state.pending == null,
        select = { vm.selectConversation(it); historyOpen = false },
        create = { vm.newConversation(); historyOpen = false },
        delete = { deleteCandidate = it },
        close = { historyOpen = false }
    )
    deleteCandidate?.let { conversation ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            icon = { Icon(Icons.Default.DeleteOutline, null) },
            title = { Text("Удалить чат?") },
            text = { Text("«${conversation.title}» и вся его история будут удалены.") },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("Отмена") } },
            confirmButton = { Button(onClick = { vm.deleteConversation(conversation.id); deleteCandidate = null }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Удалить") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ChatHistorySheet(conversations: List<Conversation>, activeId: String?, enabled: Boolean, select: (String) -> Unit, create: () -> Unit, delete: (Conversation) -> Unit, close: () -> Unit) {
    ModalBottomSheet(onDismissRequest = close) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("История чатов", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            FilledIconButton(onClick = create, enabled = enabled, shape = RoundedCornerShape(8.dp)) { Icon(Icons.Default.Add, "Новый чат") }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(conversations, key = { it.id }) { conversation ->
                Surface(onClick = { if (enabled) select(conversation.id) }, shape = RoundedCornerShape(8.dp), color = if (conversation.id == activeId) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (conversation.id == activeId) Icons.Default.ChatBubble else Icons.Default.ChatBubbleOutline, null)
                        Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(conversation.title, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(conversation.updatedAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        IconButton(onClick = { if (enabled) delete(conversation) }, enabled = enabled) { Icon(Icons.Default.DeleteOutline, "Удалить чат") }
                    }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable private fun EmptyChat(modifier: Modifier, onPrompt: (String) -> Unit, openTools: () -> Unit) {
    val prompts = listOf(
        Triple(Icons.Default.TravelExplore, "Найти в сети", "Найди свежую информацию о "),
        Triple(Icons.Default.Terminal, "Проверить сервер", "Проверь состояние моего SSH-сервера: uptime, disk, memory"),
        Triple(Icons.Default.Today, "Спланировать день", "Помоги составить реалистичный план на сегодня"),
        Triple(Icons.Default.Code, "Помочь с кодом", "Помоги разобраться с задачей разработки: ")
    )
    Column(modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.Center) {
        Text("Чем займёмся?", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp)); Text("Поручите задачу целиком. Jarvis сам выберет нужные инструменты.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(22.dp))
        prompts.forEach { (icon, title, prompt) ->
            Surface(onClick = { onPrompt(prompt) }, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp)); Text(title, Modifier.weight(1f)); Icon(Icons.Default.ChevronRight, null) }
            }
        }
        TextButton(onClick = openTools, modifier = Modifier.align(Alignment.End)) { Icon(Icons.Default.GridView, null); Spacer(Modifier.width(6.dp)); Text("Все инструменты") }
    }
}

@Composable private fun MessageBubble(message: Message, retry: () -> Unit) {
    val user = message.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Top) {
        if (!user) { Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(30.dp)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)) } }; Spacer(Modifier.width(8.dp)) }
        Surface(shape = RoundedCornerShape(8.dp), color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth(.86f)) {
            Column(Modifier.padding(12.dp)) {
                SelectionContainer { Text(message.text, style = if (message.text.contains("```")) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge) }
                when (message.state) {
                    DeliveryState.SENDING -> MessageStatus(Icons.Default.Sync, "Отправка")
                    DeliveryState.QUEUED -> MessageStatus(Icons.Default.CloudUpload, "В очереди")
                    DeliveryState.FAILED -> Row(verticalAlignment = Alignment.CenterVertically) { MessageStatus(Icons.Default.ErrorOutline, message.detail ?: "Ошибка"); IconButton(onClick = retry, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.Refresh, "Повторить", Modifier.size(18.dp)) } }
                    DeliveryState.SENT -> Unit
                }
            }
        }
    }
}

@Composable private fun MessageStatus(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) { Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(4.dp)); Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) } }

@Composable private fun ConfirmationPanel(label: String, approve: () -> Unit, reject: () -> Unit) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.AdminPanelSettings, null); Spacer(Modifier.width(10.dp)); Text("Требуется подтверждение", style = MaterialTheme.typography.titleMedium) }
            Spacer(Modifier.height(8.dp)); SelectionContainer { Text(label, fontFamily = if (label.startsWith("SSH")) FontFamily.Monospace else FontFamily.Default) }
            Spacer(Modifier.height(12.dp)); Row(Modifier.align(Alignment.End)) { TextButton(onClick = reject) { Icon(Icons.Default.Close, null); Spacer(Modifier.width(5.dp)); Text("Отклонить") }; Spacer(Modifier.width(8.dp)); Button(onClick = approve) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(5.dp)); Text("Выполнить") } }
        }
    }
}

@Composable private fun ThinkingRow() { Row(Modifier.padding(start = 42.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(9.dp)); Text("Jarvis работает", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }

@Composable private fun ChatComposer(draft: String, enabled: Boolean, sending: Boolean, onDraft: (String) -> Unit, onSend: () -> Unit, onStop: () -> Unit, onVoice: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().imePadding().padding(10.dp), verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = onVoice, enabled = enabled) { Icon(Icons.Default.Mic, "Голосовой ввод") }
            OutlinedTextField(draft, onDraft, Modifier.weight(1f), placeholder = { Text("Поручить задачу") }, maxLines = 5, enabled = enabled, shape = RoundedCornerShape(8.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { onSend() }))
            Spacer(Modifier.width(8.dp)); if (sending) FilledIconButton(onClick = onStop, modifier = Modifier.size(52.dp), shape = RoundedCornerShape(8.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error)) { Icon(Icons.Default.Stop, "Остановить") }
            else FilledIconButton(onClick = onSend, enabled = enabled && draft.isNotBlank(), modifier = Modifier.size(52.dp), shape = RoundedCornerShape(8.dp)) { Icon(Icons.AutoMirrored.Filled.Send, "Отправить") }
        }
    }
}

private fun statusIcon(status: ApiStatus) = when (status) { ApiStatus.ONLINE -> Icons.Default.CloudDone; ApiStatus.CHECKING -> Icons.Default.Sync; ApiStatus.OFFLINE -> Icons.Default.CloudOff; ApiStatus.ERROR -> Icons.Default.ErrorOutline; ApiStatus.NOT_CONFIGURED -> Icons.Default.CloudQueue }
@Composable private fun statusColor(status: ApiStatus): Color = when (status) { ApiStatus.ONLINE -> MaterialTheme.colorScheme.primary; ApiStatus.ERROR, ApiStatus.OFFLINE -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurfaceVariant }
