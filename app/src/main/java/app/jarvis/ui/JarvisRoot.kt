package app.jarvis.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import app.jarvis.AppContainer

private enum class Destination(val label: String) { CHAT("Чат"), TOOLS("Инструменты"), SERVERS("Серверы"), SETTINGS("Настройки") }

@Composable fun JarvisRoot(container: AppContainer) {
    var destination by remember { mutableStateOf(Destination.CHAT) }
    val vm: ChatViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = ChatViewModel(container.repository) as T
    })
    Scaffold(bottomBar = {
        NavigationBar {
            Destination.entries.forEach { item ->
                NavigationBarItem(
                    selected = destination == item,
                    onClick = { destination = item },
                    icon = { Icon(when (item) { Destination.CHAT -> Icons.Default.ChatBubble; Destination.TOOLS -> Icons.Default.Build; Destination.SERVERS -> Icons.Default.Dns; Destination.SETTINGS -> Icons.Default.Settings }, item.label) },
                    label = { Text(item.label) }
                )
            }
        }
    }) { padding ->
        Box(Modifier.padding(padding)) {
            when (destination) {
                Destination.CHAT -> ChatScreen(vm, openSettings = { destination = Destination.SETTINGS }, openTools = { destination = Destination.TOOLS })
                Destination.TOOLS -> ToolsScreen(vm.tools) { prompt -> vm.prefill(prompt); destination = Destination.CHAT }
                Destination.SERVERS -> ServersScreen(container.sshProfiles, container.ssh)
                Destination.SETTINGS -> SettingsScreen(vm, container.settings.get())
            }
        }
    }
}
