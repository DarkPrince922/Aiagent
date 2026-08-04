package app.jarvis.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.jarvis.data.SshProfile
import app.jarvis.data.SshProfileStore
import app.jarvis.data.SshProfileSummary
import app.jarvis.net.SshService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun ServersScreen(store: SshProfileStore, ssh: SshService) {
    // Список показывает только публичные поля: расшифровка Keystore происходит лишь при
    // редактировании и проверке конкретного профиля, а не на каждой рекомпозиции.
    var profiles by remember { mutableStateOf(emptyList<SshProfileSummary>()) }
    var editing by remember { mutableStateOf<SshProfile?>(null) }
    var deleteCandidate by remember { mutableStateOf<SshProfileSummary?>(null) }
    var testingId by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    suspend fun reload() { profiles = withContext(Dispatchers.IO) { store.summaries() } }
    LaunchedEffect(Unit) { reload() }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbar) }, floatingActionButton = { FloatingActionButton(onClick = { editing = SshProfile() }, shape = RoundedCornerShape(8.dp), containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.primary) { Icon(Icons.Default.Add, "Добавить сервер") } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding()) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column {
                    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("SSH-серверы", style = MaterialTheme.typography.titleMedium); Text("VAULT / ANDROID KEYSTORE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Icon(Icons.Default.Security, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            if (profiles.isEmpty()) Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.Dns, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(12.dp)); Text("Нет SSH-профилей", style = MaterialTheme.typography.titleMedium); Text("Добавьте сервер, чтобы Jarvis мог выполнять диагностику и команды.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(profiles, key = { it.id }) { profile ->
                    Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)), modifier = Modifier.size(40.dp)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Terminal, null, tint = MaterialTheme.colorScheme.secondary) } }
                                Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(profile.name, style = MaterialTheme.typography.titleMedium); Text("${profile.username}@${profile.host}:${profile.port}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                IconButton(onClick = { scope.launch { editing = withContext(Dispatchers.IO) { store.find(profile.id) } } }) { Icon(Icons.Default.Edit, "Изменить") }
                                IconButton(onClick = { deleteCandidate = profile }) { Icon(Icons.Default.DeleteOutline, "Удалить") }
                            }
                            Spacer(Modifier.height(10.dp)); HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant); Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (profile.fingerprint.isBlank()) Icons.Default.GppMaybe else Icons.Default.VerifiedUser, null, Modifier.size(18.dp), tint = if (profile.fingerprint.isBlank()) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(7.dp)); Text(if (profile.fingerprint.isBlank()) "Хост ещё не проверен" else profile.fingerprint, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                TextButton(enabled = testingId == null, onClick = {
                                    testingId = profile.id
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching {
                                                val full = store.find(profile.id) ?: error("Профиль не найден")
                                                ssh.execute(full, "printf JARVIS_OK")
                                            }
                                        }
                                        testingId = null; reload(); snackbar.showSnackbar(result.fold({ "SSH подключение работает" }, { "SSH: ${it.message}" }))
                                    }
                                }) { if (testingId == profile.id) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Default.NetworkCheck, null); Spacer(Modifier.width(5.dp)); Text("Проверить") }
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { profile -> ProfileDialog(profile, close = { editing = null }, save = { value ->
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { store.save(value) } }
                .onSuccess { reload(); editing = null }
                .onFailure { snackbar.showSnackbar(it.message ?: "Не удалось сохранить") }
        }
    }) }
    deleteCandidate?.let { profile -> AlertDialog(onDismissRequest = { deleteCandidate = null }, icon = { Icon(Icons.Default.DeleteOutline, null) }, title = { Text("Удалить ${profile.name}?") }, text = { Text("Профиль и сохранённые SSH-секреты будут удалены с устройства.") }, dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("Отмена") } }, confirmButton = { Button(onClick = { scope.launch { withContext(Dispatchers.IO) { store.delete(profile.id) }; reload(); deleteCandidate = null } }) { Text("Удалить") } }) }
}

@Composable private fun ProfileDialog(initial: SshProfile, close: () -> Unit, save: (SshProfile) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    Dialog(onDismissRequest = close) {
        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), modifier = Modifier.fillMaxWidth().heightIn(max = 680.dp)) {
            Column {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Terminal, null); Spacer(Modifier.width(10.dp)); Text(if (initial.host.isBlank()) "Новый SSH-профиль" else "SSH-профиль", style = MaterialTheme.typography.titleLarge); Spacer(Modifier.weight(1f)); IconButton(onClick = close) { Icon(Icons.Default.Close, "Закрыть") } }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { OutlinedTextField(value.name, { value = value.copy(name = it) }, Modifier.fillMaxWidth(), label = { Text("Название") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, null) }, singleLine = true) }
                    item { OutlinedTextField(value.host, { value = value.copy(host = it.trim()) }, Modifier.fillMaxWidth(), label = { Text("Host") }, leadingIcon = { Icon(Icons.Default.Dns, null) }, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), singleLine = true) }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedTextField(value.username, { value = value.copy(username = it) }, Modifier.weight(1f), label = { Text("Пользователь") }, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), singleLine = true); OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, Modifier.width(110.dp), label = { Text("Порт") }, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true) } }
                    item { OutlinedTextField(value.password, { value = value.copy(password = it) }, Modifier.fillMaxWidth(), label = { Text("Пароль (необязательно)") }, leadingIcon = { Icon(Icons.Default.Password, null) }, visualTransformation = PasswordVisualTransformation(), singleLine = true) }
                    item { OutlinedTextField(value.privateKey, { value = value.copy(privateKey = it) }, Modifier.fillMaxWidth(), label = { Text("Private key (необязательно)") }, leadingIcon = { Icon(Icons.Default.Key, null) }, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), minLines = 3, maxLines = 7) }
                    item { OutlinedTextField(value.passphrase, { value = value.copy(passphrase = it) }, Modifier.fillMaxWidth(), label = { Text("Passphrase ключа") }, visualTransformation = PasswordVisualTransformation(), singleLine = true) }
                }
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) { TextButton(onClick = close) { Text("Отмена") }; Spacer(Modifier.width(8.dp)); Button(onClick = { save(value.copy(port = port.toIntOrNull() ?: 22)) }, enabled = value.host.isNotBlank() && value.username.isNotBlank()) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(6.dp)); Text("Сохранить") } }
            }
        }
    }
}
