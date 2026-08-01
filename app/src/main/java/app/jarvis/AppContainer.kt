package app.jarvis

import android.content.Context
import app.jarvis.data.ChatRepository
import app.jarvis.data.PendingStore
import app.jarvis.data.NoteStore
import app.jarvis.data.SettingsStore
import app.jarvis.data.SshProfileStore
import app.jarvis.net.ChatApi
import app.jarvis.net.SshService
import app.jarvis.net.WebService
import app.jarvis.tools.ToolRegistry

class AppContainer(context: Context) {
    val settings = SettingsStore(context)
    val pending = PendingStore(context)
    val notes = NoteStore(context)
    val sshProfiles = SshProfileStore(context)
    val web = WebService()
    val ssh = SshService(sshProfiles)
    val tools = ToolRegistry(context, notes, sshProfiles, web, ssh)
    val repository = ChatRepository(context, settings, pending, ChatApi(), tools)
}
