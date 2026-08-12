package app.jarvis.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddTask
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.jarvis.data.AgentEventKind
import app.jarvis.data.AgentTask
import app.jarvis.data.AgentTaskEvent
import app.jarvis.data.AgentTaskStatus
import java.text.DateFormat
import java.util.Date

@Composable
fun AutonomyScreen(vm: AutonomyViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var profileMenu by remember { mutableStateOf(false) }
    val selectedProfile = state.sshProfiles.firstOrNull { it.id == state.selectedProfileId }

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("AUTONOMOUS OPS", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${state.tasks.count { it.status.active }} ACTIVE  /  ${state.tasks.count { it.status == AgentTaskStatus.COMPLETED }} DONE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                ActiveGlyph(state.tasks.any { it.status.active })
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AddTask, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text("Новая задача", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.objective,
                    onValueChange = vm::updateObjective,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Цель и критерии готовности") },
                    placeholder = { Text("Обнови nginx, проверь конфиг и health endpoint") },
                    leadingIcon = { Icon(Icons.Default.Terminal, null) },
                    minLines = 3,
                    maxLines = 7,
                    shape = RoundedCornerShape(6.dp)
                )
                Spacer(Modifier.height(10.dp))
                Box {
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { profileMenu = true },
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Key, null, tint = if (selectedProfile?.trusted == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(selectedProfile?.name ?: "Без SSH", style = MaterialTheme.typography.titleSmall)
                                Text(selectedProfile?.target ?: "WEB / LOCAL TOOLS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.Default.MoreVert, "Выбрать SSH-профиль")
                        }
                    }
                    DropdownMenu(expanded = profileMenu, onDismissRequest = { profileMenu = false }) {
                        DropdownMenuItem(text = { Text("Без SSH") }, leadingIcon = { Icon(Icons.Default.CloudOff, null) }, onClick = { vm.selectProfile(null); profileMenu = false })
                        state.sshProfiles.forEach { profile ->
                            DropdownMenuItem(
                                text = { Column { Text(profile.name); Text(profile.target, style = MaterialTheme.typography.labelSmall) } },
                                leadingIcon = { Icon(if (profile.trusted) Icons.Default.Security else Icons.Default.WarningAmber, null, tint = if (profile.trusted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary) },
                                onClick = { vm.selectProfile(profile.id); profileMenu = false }
                            )
                        }
                    }
                }
                AnimatedVisibility(selectedProfile != null) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoMode, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text("SSH AUTOGRANT", style = MaterialTheme.typography.labelMedium)
                            Text(selectedProfile?.target.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = state.autoApproveSsh, onCheckedChange = vm::setAutoApprove)
                    }
                }
                Spacer(Modifier.height(12.dp))
                // Порог совпадает с проверкой в AutonomousAgentManager.start: иначе кнопка
                // включалась бы на двух буквах, а запуск тут же падал с ошибкой.
                val blocker = when {
                    state.objective.trim().length < MIN_OBJECTIVE -> "Опишите цель задачи — не короче $MIN_OBJECTIVE символов"
                    selectedProfile != null && state.autoApproveSsh && !selectedProfile.trusted ->
                        "Профиль «${selectedProfile.name}» не проверен: откройте «Серверы» и нажмите «Проверить», либо выключите SSH AUTOGRANT"
                    else -> null
                }
                Button(
                    onClick = vm::start,
                    enabled = blocker == null && !state.creating,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    if (state.creating) CircularProgressIndicator(Modifier.size(19.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    else Icon(Icons.Default.RocketLaunch, null)
                    Spacer(Modifier.width(8.dp))
                    Text("ЗАПУСТИТЬ")
                }
                // Неактивная кнопка без причины — самая частая причина считать приложение сломанным.
                blocker?.let {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(6.dp))
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        item {
            AnimatedVisibility(state.banner != null, enter = fadeIn(), exit = fadeOut()) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(9.dp))
                        Text(state.banner.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = vm::dismissBanner, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.Close, "Закрыть", Modifier.size(18.dp)) }
                    }
                }
            }
        }

        if (state.tasks.isNotEmpty()) {
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.tasks, key = { it.id }) { task ->
                        TaskSelector(task, selected = task.id == state.selectedTaskId, onClick = { vm.selectTask(task.id) })
                    }
                }
            }
        }

        state.selectedTask?.let { task ->
            item { TaskConsole(task, pause = { vm.pause(task.id) }, resume = { vm.resume(task.id) }, stop = { vm.stop(task.id) }, delete = { vm.delete(task.id) }) }
            item {
                InstructionComposer(
                    value = state.instruction,
                    status = task.status,
                    sending = state.sendingInstruction,
                    onValueChange = vm::updateInstruction,
                    onSend = vm::sendInstruction
                )
            }
            item {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Terminal, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("КЛЮЧЕВЫЕ СОБЫТИЯ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.weight(1f))
                    Text("${state.events.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.events.isEmpty()) item {
                Text("Ожидаю первый checkpoint", Modifier.padding(horizontal = 16.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            items(state.events, key = { it.id }) { event -> TaskEventRow(event) }
        }
    }
}

/**
 * Ввод указаний уже запущенной задаче: агент подхватит текст перед следующим шагом,
 * не теряя контекст. Для завершённой или остановленной задачи указание её возобновляет.
 */
@Composable
private fun InstructionComposer(
    value: String,
    status: AgentTaskStatus,
    sending: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Bolt, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(8.dp))
            Text("ВМЕШАТЬСЯ ПО ХОДУ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Уточнение или новое требование") },
            placeholder = { Text("Не трогай продовую базу, сначала сделай дамп") },
            minLines = 2,
            maxLines = 5,
            shape = RoundedCornerShape(6.dp),
            trailingIcon = {
                IconButton(onClick = onSend, enabled = value.isNotBlank() && !sending) {
                    if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.AutoMirrored.Filled.Send, "Передать агенту")
                }
            }
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (status.active) {
                "Агент учтёт указание перед следующим шагом и продолжит без остановки."
            } else {
                "Задача не активна: указание вернёт её в работу с сохранённого checkpoint."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun ActiveGlyph(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "agent-live")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
        label = "agent-live-alpha"
    )
    Box(
        Modifier.size(42.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.AutoMode,
            contentDescription = null,
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alpha(if (active) pulse else 1f)
        )
    }
}

@Composable
private fun TaskSelector(task: AgentTask, selected: Boolean, onClick: () -> Unit) {
    val color = statusColor(task.status)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(4.dp),
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) color else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.width(190.dp).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(color, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(task.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text("${statusLabel(task.status)} / ${task.step}", style = MaterialTheme.typography.labelSmall, color = color)
            }
        }
    }
}

@Composable
private fun TaskConsole(task: AgentTask, pause: () -> Unit, resume: () -> Unit, stop: () -> Unit, delete: () -> Unit) {
    val color = statusColor(task.status)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusIcon(task.status, color)
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(task.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${statusLabel(task.status)}  •  STEP ${task.step}", style = MaterialTheme.typography.labelSmall, color = color)
                    }
                    AnimatedContent(
                        targetState = task.status,
                        transitionSpec = { (fadeIn() + scaleIn(initialScale = .85f)) togetherWith (fadeOut() + scaleOut(targetScale = .85f)) },
                        label = "task-controls"
                    ) { status ->
                        Row {
                            when {
                                status.active -> {
                                    IconButton(onClick = pause) { Icon(Icons.Default.Pause, "Пауза") }
                                    FilledIconButton(onClick = stop, colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)) { Icon(Icons.Default.PowerSettingsNew, "Аварийная остановка") }
                                }
                                else -> {
                                    FilledIconButton(onClick = resume) { Icon(Icons.Default.PlayArrow, "Продолжить") }
                                    IconButton(onClick = delete) { Icon(Icons.Default.DeleteOutline, "Удалить задачу") }
                                }
                            }
                        }
                    }
                }
                if (task.status.active) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp), color = color, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.padding(14.dp)) {
                    Text("CURRENT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(5.dp))
                    Text(task.currentAction, style = MaterialTheme.typography.bodyMedium)
                    task.lastError?.let {
                        Spacer(Modifier.height(9.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                    task.summary?.let {
                        Spacer(Modifier.height(12.dp))
                        Text("RESULT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(5.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusIcon(status: AgentTaskStatus, color: Color) {
    val icon = when (status) {
        AgentTaskStatus.QUEUED -> Icons.Default.HourglassTop
        AgentTaskStatus.RUNNING -> Icons.Default.AutoMode
        AgentTaskStatus.WAITING_NETWORK -> Icons.Default.CloudOff
        AgentTaskStatus.PAUSED -> Icons.Default.Pause
        AgentTaskStatus.COMPLETED -> Icons.Default.CheckCircle
        AgentTaskStatus.FAILED -> Icons.Default.ErrorOutline
        AgentTaskStatus.STOPPED -> Icons.Default.PowerSettingsNew
    }
    Icon(icon, null, tint = color)
}

@Composable
private fun TaskEventRow(event: AgentTaskEvent) {
    val color = eventColor(event.kind)
    val icon: ImageVector = when (event.kind) {
        AgentEventKind.START -> Icons.Default.RocketLaunch
        AgentEventKind.PROGRESS -> Icons.Default.Refresh
        AgentEventKind.TOOL -> Icons.Default.Terminal
        AgentEventKind.SUCCESS -> Icons.Default.CheckCircle
        AgentEventKind.WARNING -> Icons.Default.WarningAmber
        AgentEventKind.ERROR -> Icons.Default.ErrorOutline
        AgentEventKind.SYSTEM -> Icons.Default.AutoMode
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(18.dp), tint = color)
            Box(Modifier.width(1.dp).height(34.dp).background(MaterialTheme.colorScheme.outlineVariant))
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(event.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(event.createdAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (event.detail.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(event.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun statusColor(status: AgentTaskStatus): Color = when (status) {
    AgentTaskStatus.RUNNING, AgentTaskStatus.COMPLETED -> MaterialTheme.colorScheme.primary
    AgentTaskStatus.QUEUED -> MaterialTheme.colorScheme.secondary
    AgentTaskStatus.WAITING_NETWORK, AgentTaskStatus.PAUSED -> MaterialTheme.colorScheme.tertiary
    AgentTaskStatus.FAILED, AgentTaskStatus.STOPPED -> MaterialTheme.colorScheme.error
}

private fun statusLabel(status: AgentTaskStatus): String = when (status) {
    AgentTaskStatus.QUEUED -> "QUEUED"
    AgentTaskStatus.RUNNING -> "RUNNING"
    AgentTaskStatus.WAITING_NETWORK -> "WAITING NET"
    AgentTaskStatus.PAUSED -> "PAUSED"
    AgentTaskStatus.COMPLETED -> "COMPLETED"
    AgentTaskStatus.FAILED -> "FAILED"
    AgentTaskStatus.STOPPED -> "STOPPED"
}

@Composable
private fun eventColor(kind: AgentEventKind): Color = when (kind) {
    AgentEventKind.START, AgentEventKind.PROGRESS, AgentEventKind.TOOL -> MaterialTheme.colorScheme.secondary
    AgentEventKind.SUCCESS -> MaterialTheme.colorScheme.primary
    AgentEventKind.WARNING -> MaterialTheme.colorScheme.tertiary
    AgentEventKind.ERROR -> MaterialTheme.colorScheme.error
    AgentEventKind.SYSTEM -> MaterialTheme.colorScheme.onSurfaceVariant
}

private const val MIN_OBJECTIVE = 8
