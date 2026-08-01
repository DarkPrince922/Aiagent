package app.jarvis.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.jarvis.AppContainer

private enum class Destination(val label: String) {
    CHAT("Чат"),
    AUTONOMY("Агент"),
    TOOLS("Инструменты"),
    SERVERS("Серверы"),
    SETTINGS("Настройки")
}

@Composable
fun JarvisRoot(container: AppContainer) {
    var destination by rememberSaveable { mutableStateOf(Destination.CHAT) }
    val stateHolder = rememberSaveableStateHolder()
    val slideDistance = with(LocalDensity.current) { 24.dp.roundToPx() }
    val vm: ChatViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
            ChatViewModel(container.repository) as T
    })
    val autonomyVm: AutonomyViewModel = viewModel(key = "autonomy", factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
            AutonomyViewModel(container.autonomous, container.sshProfiles) as T
    })

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp
                ) {
                    Destination.entries.forEach { item ->
                        NavigationBarItem(
                            selected = destination == item,
                            onClick = { destination = item },
                            icon = {
                                Icon(
                                    imageVector = when (item) {
                                        Destination.CHAT -> Icons.Default.ChatBubble
                                        Destination.AUTONOMY -> Icons.Default.AutoMode
                                        Destination.TOOLS -> Icons.Default.Build
                                        Destination.SERVERS -> Icons.Default.Dns
                                        Destination.SETTINGS -> Icons.Default.Settings
                                    },
                                    contentDescription = item.label
                                )
                            },
                            label = { Text(item.label) },
                            alwaysShowLabel = false,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        AnimatedContent(
            targetState = destination,
            modifier = Modifier.fillMaxSize().padding(padding),
            transitionSpec = {
                val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                (slideInHorizontally(
                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                    initialOffsetX = { direction * slideDistance }
                ) + fadeIn(animationSpec = tween(180, delayMillis = 30))) togetherWith
                    (slideOutHorizontally(
                        animationSpec = tween(150, easing = LinearOutSlowInEasing),
                        targetOffsetX = { -direction * slideDistance }
                    ) + fadeOut(animationSpec = tween(120)))
            },
            label = "primary-navigation"
        ) { target ->
            stateHolder.SaveableStateProvider(target.name) {
                when (target) {
                    Destination.CHAT -> ChatScreen(
                        vm,
                        openSettings = { destination = Destination.SETTINGS },
                        openTools = { destination = Destination.TOOLS }
                    )
                    Destination.AUTONOMY -> AutonomyScreen(autonomyVm)
                    Destination.TOOLS -> ToolsScreen(vm.tools) { prompt ->
                        vm.prefill(prompt)
                        destination = Destination.CHAT
                    }
                    Destination.SERVERS -> ServersScreen(container.sshProfiles, container.ssh)
                    Destination.SETTINGS -> SettingsScreen(vm, container.settings.get())
                }
            }
        }
    }
}
