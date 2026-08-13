package app.jarvis

import android.content.Context
import app.jarvis.agent.AutonomousAgentManager
import app.jarvis.data.AgentTaskStore
import app.jarvis.data.ChatRepository
import app.jarvis.data.ConversationStore
import app.jarvis.data.PendingStore
import app.jarvis.data.NoteStore
import app.jarvis.data.SettingsStore
import app.jarvis.data.SshProfileStore
import app.jarvis.data.WorkspaceStore
import app.jarvis.net.ChatApi
import app.jarvis.net.SshService
import app.jarvis.net.WebService
import app.jarvis.data.ModelStore
import app.jarvis.llm.LanguageModelRouter
import app.jarvis.llm.ModelDownloads
import app.jarvis.llm.LocalLanguageModel
import app.jarvis.tools.ToolRegistry
import app.jarvis.worker.AgentNotifications

class AppContainer(context: Context) {
    val settings = SettingsStore(context)
    val pending = PendingStore(context)
    val agentTasks = AgentTaskStore(context)
    val conversations = ConversationStore(context)
    val notes = NoteStore(context)
    val sshProfiles = SshProfileStore(context)
    val workspace = WorkspaceStore(context)
    val web = WebService()
    val api = ChatApi()
    val ssh = SshService(sshProfiles)
    val tools = ToolRegistry(context, notes, sshProfiles, web, ssh, workspace)
    val notifications = AgentNotifications(context)
    val modelStore = ModelStore(context)
    val modelDownloads = ModelDownloads(context, modelStore, settings)
    val localModel = LocalLanguageModel()
    /** Агент-цикл работает через роутер и не знает, считает ответ облако или llama.cpp. */
    val models = LanguageModelRouter(api, localModel)
    val repository = ChatRepository(context, settings, pending, conversations, models, tools)
    val autonomous = AutonomousAgentManager(context, settings, agentTasks, conversations, sshProfiles, models, tools, notifications)

    init {
        // Позднее связывание: менеджер сам зависит от реестра инструментов.
        tools.autonomousLauncher = { objective, sshProfileId ->
            val task = autonomous.start(objective, sshProfileId, autoApproveSsh = sshProfileId != null)
            "Автономная задача запущена: ${task.title}. Следите на вкладке «Агент»; результат придёт уведомлением."
        }
        tools.fileDelivery = { name, uri, mime -> notifications.notifyFileReady(name, uri, mime) }
    }
}
