package app.jarvis

import android.content.Context
import app.jarvis.data.ChatRepository
import app.jarvis.data.PendingStore
import app.jarvis.data.SettingsStore
import app.jarvis.net.ChatApi
import app.jarvis.tools.ToolRegistry

class AppContainer(context: Context) {
    val settings = SettingsStore(context)
    val pending = PendingStore(context)
    val tools = ToolRegistry(context)
    val repository = ChatRepository(context, settings, pending, ChatApi(), tools)
}

