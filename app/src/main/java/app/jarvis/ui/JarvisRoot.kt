package app.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.jarvis.AppContainer
import app.jarvis.data.DeliveryState
import app.jarvis.data.Message
import app.jarvis.data.ProviderSettings

@Composable fun JarvisRoot(container: AppContainer) {
    var settingsOpen by remember { mutableStateOf(false) }
    val vm: ChatViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>) = ChatViewModel(container.repository) as T
    })
    if (settingsOpen) SettingsScreen(container.settings.get(), { container.settings.save(it); settingsOpen = false }, { settingsOpen = false })
    else ChatScreen(vm, { settingsOpen = true })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ChatScreen(vm: ChatViewModel, openSettings: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    Scaffold(
        topBar = { TopAppBar(title = { Column { Text("JARVIS"); Text("AI assistant", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) } }, actions = { IconButton(onClick = openSettings) { Icon(Icons.Default.Settings, "Настройки") } }) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) { Row(Modifier.navigationBarsPadding().padding(12.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), placeholder = { Text("Сообщение") }, maxLines = 5, keyboardActions = KeyboardActions(onSend = { vm.send(draft); draft = "" }))
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = { vm.send(draft); draft = "" }, enabled = draft.isNotBlank() && !state.sending, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.Send, "Отправить") }
            } }
        }
    ) { padding ->
        if (state.messages.isEmpty()) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("Готов к работе", style = MaterialTheme.typography.headlineSmall); Spacer(Modifier.height(6.dp)); Text("Что нужно сделать?", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.messages, key = { it.id }) { MessageBubble(it) }
            items(state.confirmations) { item ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = { vm.confirm(item) }) { Text("Подтвердить") }
                }
            }
        }
    }
}

@Composable private fun MessageBubble(message: Message) {
    val user = message.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Column(Modifier.fillMaxWidth(.88f).background(if (user) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(12.dp)) {
            Text(message.text, color = if (user) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            if (message.state != DeliveryState.SENT) Text(when (message.state) { DeliveryState.SENDING -> "Отправка"; DeliveryState.QUEUED -> "В очереди"; DeliveryState.FAILED -> "Ошибка"; else -> "" }, style = MaterialTheme.typography.labelSmall, color = if (user) MaterialTheme.colorScheme.onPrimary.copy(alpha = .7f) else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SettingsScreen(initial: ProviderSettings, save: (ProviderSettings) -> Unit, close: () -> Unit) {
    var value by remember { mutableStateOf(initial) }
    Scaffold(topBar = { TopAppBar(title = { Text("Настройки") }, navigationIcon = { IconButton(onClick = close) { Icon(Icons.Default.ArrowBack, "Назад") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(value.endpoint, { value = value.copy(endpoint = it) }, Modifier.fillMaxWidth(), label = { Text("API endpoint") }, singleLine = true)
            OutlinedTextField(value.model, { value = value.copy(model = it) }, Modifier.fillMaxWidth(), label = { Text("Модель") }, singleLine = true)
            OutlinedTextField(value.apiKey, { value = value.copy(apiKey = it) }, Modifier.fillMaxWidth(), label = { Text("API-ключ") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(value.systemPrompt, { value = value.copy(systemPrompt = it) }, Modifier.fillMaxWidth(), label = { Text("Системная инструкция") }, minLines = 3)
            Button(onClick = { save(value) }, Modifier.fillMaxWidth()) { Text("Сохранить") }
        }
    }
}
