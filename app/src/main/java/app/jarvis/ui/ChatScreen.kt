package app.jarvis.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
@Composable fun ChatScreen(vm: ChatViewModel, openSettings: () -> Unit, openTools: () -> Unit, openAgent: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var historyOpen by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<Conversation?>(null) }
    var displayedBanner by remember { mutableStateOf(state.banner.orEmpty()) }
    val listState = rememberLazyListState()
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) vm.updateDraft(result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty())
    }
    LaunchedEffect(state.messages.size, state.pending) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex + if (state.pending != null) 1 else 0)
    }
    LaunchedEffect(state.banner) {
        state.banner?.let { displayedBanner = it }
    }
    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column {
                Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 58.dp).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(state.activeTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).background(statusColor(state.apiStatus), CircleShape))
                            Spacer(Modifier.width(7.dp))
                            Text(state.statusText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            // Без инструментов агент не читает файлы и не ходит в сеть, а внешне
                            // выглядит полностью рабочим — состояние должно быть на виду.
                            if (state.toolsDisabled) {
                                Spacer(Modifier.width(8.dp))
                                Icon(Icons.Default.Extension, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.tertiary)
                                Spacer(Modifier.width(3.dp))
                                Text("инструменты выкл", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary, maxLines = 1)
                            }
                        }
                    }
                    IconButton(onClick = { historyOpen = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.History, "История чатов") }
                    IconButton(onClick = vm::newConversation, enabled = !state.sending && state.pending == null, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.EditNote, "Новый чат") }
                    IconButton(onClick = openSettings, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Settings, "Настройки") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        AnimatedVisibility(
            visible = state.banner != null,
            enter = expandVertically(animationSpec = tween(180), expandFrom = Alignment.Top) + fadeIn(tween(150)),
            exit = shrinkVertically(animationSpec = tween(140), shrinkTowards = Alignment.Top) + fadeOut(tween(100))
        ) {
            val alertColor = if (state.apiStatus == ApiStatus.ERROR || state.apiStatus == ApiStatus.OFFLINE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
            Surface(color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, alertColor.copy(alpha = 0.45f))) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (state.apiStatus == ApiStatus.OFFLINE) Icons.Default.CloudOff else Icons.Default.Info, null, Modifier.size(20.dp), tint = alertColor)
                    Spacer(Modifier.width(10.dp)); Text(displayedBanner, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
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
            attachments = state.attachments,
            onAttach = vm::attach,
            onDelegate = { vm.startAutonomous(); openAgent() },
            onRemoveAttachment = vm::removeAttachment,
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
                val active = conversation.id == activeId
                Surface(onClick = { if (enabled) select(conversation.id) }, shape = RoundedCornerShape(8.dp), color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (active) Icons.Default.ChatBubble else Icons.Default.ChatBubbleOutline, null)
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
            Surface(onClick = { onPrompt(prompt) }, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp)); Text(title, Modifier.weight(1f)); Icon(Icons.Default.ChevronRight, null) }
            }
        }
        TextButton(onClick = openTools, modifier = Modifier.align(Alignment.End)) { Icon(Icons.Default.GridView, null); Spacer(Modifier.width(6.dp)); Text("Все инструменты") }
    }
}

@Composable private fun MessageBubble(message: Message, retry: () -> Unit) {
    val user = message.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Top) {
        if (!user) { Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)), modifier = Modifier.size(30.dp)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Terminal, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.secondary) } }; Spacer(Modifier.width(8.dp)) }
        Surface(shape = RoundedCornerShape(8.dp), color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, if (user) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth(.86f)) {
            Column(Modifier.padding(12.dp)) {
                // Итоговые сообщения помечаются, чтобы не теряться среди промежуточных ответов.
                if (message.detail == "summary" || message.detail?.startsWith("agent-") == true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Summarize, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (message.detail == "summary") "ИТОГ" else "АВТОНОМНАЯ ЗАДАЧА",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
                SelectionContainer { Text(message.text, style = if (message.text.contains("```")) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge) }
                when (message.state) {
                    DeliveryState.SENDING -> MessageStatus(Icons.Default.Sync, "Отправка")
                    DeliveryState.QUEUED -> MessageStatus(Icons.Default.CloudUpload, "В очереди")
                    DeliveryState.FAILED -> Row(verticalAlignment = Alignment.CenterVertically) { MessageStatus(Icons.Default.ErrorOutline, message.detail ?: "Ошибка"); IconButton(onClick = retry, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.Refresh, "Повторить", Modifier.size(18.dp)) } }
                    DeliveryState.CANCELLED -> Row(verticalAlignment = Alignment.CenterVertically) { MessageStatus(Icons.Default.StopCircle, message.detail ?: "Остановлено"); IconButton(onClick = retry, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.Refresh, "Запустить снова", Modifier.size(18.dp)) } }
                    DeliveryState.SENT -> Unit
                }
            }
        }
    }
}

@Composable private fun MessageStatus(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) { Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(4.dp)); Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) } }

@Composable private fun ConfirmationPanel(label: String, approve: () -> Unit, reject: () -> Unit) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.AdminPanelSettings, null); Spacer(Modifier.width(10.dp)); Text("Требуется подтверждение", style = MaterialTheme.typography.titleMedium) }
            Spacer(Modifier.height(8.dp)); SelectionContainer { Text(label, fontFamily = if (label.startsWith("SSH")) FontFamily.Monospace else FontFamily.Default) }
            Spacer(Modifier.height(12.dp)); Row(Modifier.align(Alignment.End)) { TextButton(onClick = reject) { Icon(Icons.Default.Close, null); Spacer(Modifier.width(5.dp)); Text("Отклонить") }; Spacer(Modifier.width(8.dp)); Button(onClick = approve) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(5.dp)); Text("Выполнить") } }
        }
    }
}

@Composable private fun ThinkingRow() {
    val transition = rememberInfiniteTransition(label = "agent-running")
    val pulse by transition.animateFloat(
        initialValue = 0.32f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(720, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "running-pulse"
    )
    Row(Modifier.padding(start = 42.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = pulse), CircleShape))
        Spacer(Modifier.width(9.dp))
        Text("JARVIS / ВЫПОЛНЕНИЕ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun ChatComposer(
    draft: String,
    attachments: List<String>,
    onAttach: (android.content.ContentResolver, android.net.Uri) -> Unit,
    onDelegate: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    enabled: Boolean,
    sending: Boolean,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onVoice: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Системный выбор файла: не требует разрешений, право на URI выдаётся под конкретный файл.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onAttach(context.contentResolver, uri)
    }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            // Длинный запрос обычно и есть задача для фонового агента — предлагаем передать её,
            // не заставляя переходить на другую вкладку и набирать текст заново.
            if (draft.trim().length >= DELEGATE_THRESHOLD && !sending) {
                Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp)) {
                    AssistChip(
                        onClick = onDelegate,
                        label = { Text("Запустить как автономную задачу") },
                        leadingIcon = { Icon(Icons.Default.RocketLaunch, null, Modifier.size(18.dp)) }
                    )
                }
            }
            if (attachments.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    attachments.forEach { name ->
                        InputChip(
                            selected = true,
                            onClick = { onRemoveAttachment(name) },
                            label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(Icons.Default.Description, null, Modifier.size(16.dp)) },
                            trailingIcon = { Icon(Icons.Default.Close, "Убрать", Modifier.size(16.dp)) }
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().imePadding().padding(10.dp), verticalAlignment = Alignment.Bottom) {
                IconButton(
                    onClick = { picker.launch(arrayOf("text/*", "application/json")) },
                    enabled = enabled
                ) { Icon(Icons.Default.AttachFile, "Прикрепить файл") }
                IconButton(onClick = onVoice, enabled = enabled) { Icon(Icons.Default.Mic, "Голосовой ввод") }
                OutlinedTextField(draft, onDraft, Modifier.weight(1f), placeholder = { Text("Поручить задачу") }, maxLines = 5, enabled = enabled, shape = RoundedCornerShape(8.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { onSend() }))
                Spacer(Modifier.width(8.dp))
                AnimatedContent(
                    targetState = sending,
                    modifier = Modifier.size(52.dp),
                    transitionSpec = {
                        (scaleIn(tween(180), initialScale = 0.86f) + fadeIn(tween(160))) togetherWith
                            (scaleOut(tween(120), targetScale = 0.9f) + fadeOut(tween(100)))
                    },
                    label = "send-stop"
                ) { isSending ->
                    if (isSending) FilledIconButton(onClick = onStop, modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(8.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error)) { Icon(Icons.Default.Stop, "Остановить") }
                    else FilledIconButton(onClick = onSend, enabled = enabled && (draft.isNotBlank() || attachments.isNotEmpty()), modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(8.dp)) { Icon(Icons.AutoMirrored.Filled.Send, "Отправить") }
                }
            }
        }
    }
}

@Composable private fun statusColor(status: ApiStatus): Color = when (status) { ApiStatus.ONLINE -> MaterialTheme.colorScheme.primary; ApiStatus.ERROR, ApiStatus.OFFLINE -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurfaceVariant }

private const val DELEGATE_THRESHOLD = 8
