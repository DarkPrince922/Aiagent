package app.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Окно терминала: то же, что видит агент на сервере.
 *
 * Пока выполнение было видно только по итоговому результату команды, «что там сейчас
 * происходит» приходилось угадывать: долгая сборка, зависший процесс и подробный вывод,
 * который агент не стал показывать, выглядели одинаково — тишиной.
 */
@Composable fun TerminalScreen(vm: TerminalViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val outputScroll = rememberScrollState()

    // Свежие строки внизу: следом за выводом должен ехать и экран.
    LaunchedEffect(state.output) { if (state.follow) outputScroll.animateScrollTo(outputScroll.maxValue) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Терминал", style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                state.profile == null -> "Нет SSH-профилей — добавьте сервер"
                                state.updatedAt == 0L -> state.profile!!.name
                                else -> "${state.profile!!.name} · обновлено ${TIME.format(Instant.ofEpochMilli(state.updatedAt).atZone(ZoneId.systemDefault()))}"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    IconButton(onClick = vm::toggleFollow, modifier = Modifier.size(40.dp)) {
                        Icon(
                            if (state.follow) Icons.Default.PlayArrow else Icons.Default.Pause,
                            if (state.follow) "Слежение включено" else "Слежение выключено",
                            tint = if (state.follow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (state.source == TerminalSource.SESSIONS) {
                        IconButton(onClick = vm::openOwnSession, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.AddBox, "Своё окно с оболочкой")
                        }
                    }
                    IconButton(onClick = vm::refresh, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Refresh, "Обновить") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        if (state.profile == null) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Терминал показывает окна tmux и журналы операций на вашем сервере. " +
                        "Добавьте SSH-профиль на вкладке «Серверы».",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Column
        }

        if (state.profiles.size > 1) {
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.profiles, key = { it.id }) { profile ->
                    FilterChip(
                        selected = profile.id == state.profileId,
                        onClick = { vm.selectProfile(profile.id) },
                        label = { Text(profile.name) }
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TerminalSource.entries.forEach { source ->
                FilterChip(
                    selected = state.source == source,
                    onClick = { vm.selectSource(source) },
                    label = { Text(source.label) }
                )
            }
        }

        if (state.targets.isEmpty()) {
            Text(
                if (state.source == TerminalSource.SESSIONS)
                    "Открытых окон tmux нет. Задача создаёт своё окно, когда выполняет команду на сервере, " +
                        "а кнопка «+» открывает ваше — с обычной оболочкой."
                else "Операций пока нет — их каталоги появляются при первой команде задачи.",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.targets, key = { it.id }) { target ->
                    Surface(
                        color = if (target.id == state.selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.clickable { vm.select(target.id) }
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
                            Text(target.title, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                            Text(
                                target.subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        state.error?.let { message ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ErrorOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.width(8.dp))
                    Text(message, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }

        // Вывод не переносится по словам: колонки `top` и таблицы иначе перестают читаться.
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Box(Modifier.fillMaxSize().verticalScroll(outputScroll)) {
                Text(
                    state.output.ifBlank { "Пусто" },
                    Modifier.horizontalScroll(rememberScrollState()).padding(10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    softWrap = false
                )
            }
        }

        Surface(color = MaterialTheme.colorScheme.surface) {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).navigationBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = state.command,
                        onValueChange = vm::updateCommand,
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            // Куда уйдёт текст, видно до нажатия: в окне задачи это stdin её команды.
                            Text(
                                when {
                                    state.source != TerminalSource.SESSIONS || state.selected == null -> "Команда на сервере"
                                    state.selected == app.jarvis.net.TerminalProbe.USER_SESSION -> "Команда в вашем окне"
                                    else -> "Ввод в окно задачи ${state.selected}"
                                }
                            )
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = vm::send,
                        enabled = state.command.isNotBlank() && !state.busy,
                        shape = RoundedCornerShape(8.dp)
                    ) { Icon(Icons.Default.KeyboardReturn, "Выполнить") }
                }
            }
        }
    }
}

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
