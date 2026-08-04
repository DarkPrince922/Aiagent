package app.jarvis.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

enum class AgentTaskStatus {
    QUEUED,
    RUNNING,
    WAITING_NETWORK,
    PAUSED,
    COMPLETED,
    FAILED,
    STOPPED;

    val active: Boolean get() = this == QUEUED || this == RUNNING || this == WAITING_NETWORK
}

enum class AgentEventKind { START, PROGRESS, TOOL, SUCCESS, WARNING, ERROR, SYSTEM }

enum class AgentOperationStatus { PLANNED, DISPATCHING, SUCCEEDED, FAILED, UNKNOWN }

data class AgentTask(
    val id: String,
    val title: String,
    val objective: String,
    val conversationId: String?,
    val sshProfileId: String?,
    val autoApproveSsh: Boolean,
    val status: AgentTaskStatus,
    val checkpoint: String,
    val step: Int,
    val currentAction: String,
    val summary: String?,
    val lastError: String?,
    val createdAt: Long,
    val updatedAt: Long
)

data class AgentTaskEvent(
    val id: Long,
    val taskId: String,
    val kind: AgentEventKind,
    val title: String,
    val detail: String,
    val createdAt: Long
)

/** Указание, добавленное пользователем уже после старта задачи. */
data class AgentInstruction(val id: Long, val taskId: String, val text: String, val createdAt: Long)

data class AgentOperation(
    val taskId: String,
    val callId: String,
    val toolName: String,
    val arguments: String,
    val status: AgentOperationStatus,
    val result: String?,
    val createdAt: Long,
    val updatedAt: Long
)

class AgentTaskStore(context: Context) : SQLiteOpenHelper(context, "agent_tasks.db", null, VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE tasks (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                objective TEXT NOT NULL,
                conversation_id TEXT,
                ssh_profile_id TEXT,
                auto_approve_ssh INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL,
                checkpoint TEXT NOT NULL,
                step INTEGER NOT NULL DEFAULT 0,
                current_action TEXT NOT NULL DEFAULT '',
                summary TEXT,
                last_error TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE task_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                task_id TEXT NOT NULL,
                kind TEXT NOT NULL,
                title TEXT NOT NULL,
                detail TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(task_id) REFERENCES tasks(id) ON DELETE CASCADE
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE tool_operations (
                task_id TEXT NOT NULL,
                call_id TEXT NOT NULL,
                tool_name TEXT NOT NULL,
                arguments TEXT NOT NULL,
                status TEXT NOT NULL,
                result TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(task_id, call_id),
                FOREIGN KEY(task_id) REFERENCES tasks(id) ON DELETE CASCADE
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX task_events_task_time ON task_events(task_id, created_at)")
        db.execSQL("CREATE INDEX tasks_status_updated ON tasks(status, updated_at)")
        createInstructions(db)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createInstructions(db)
    }

    // По умолчанию SQLiteOpenHelper бросает исключение при откате версии и роняет приложение.
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private fun createInstructions(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS task_instructions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                task_id TEXT NOT NULL,
                text TEXT NOT NULL,
                consumed INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(task_id) REFERENCES tasks(id) ON DELETE CASCADE
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS task_instructions_pending ON task_instructions(task_id, consumed, id)")
    }

    @Synchronized
    fun create(
        objective: String,
        conversationId: String?,
        sshProfileId: String?,
        autoApproveSsh: Boolean,
        checkpoint: String
    ): AgentTask {
        val now = System.currentTimeMillis()
        val task = AgentTask(
            id = UUID.randomUUID().toString(),
            title = objective.lineSequence().firstOrNull().orEmpty().trim().take(72).ifBlank { "Автономная задача" },
            objective = objective,
            conversationId = conversationId,
            sshProfileId = sshProfileId,
            autoApproveSsh = autoApproveSsh,
            status = AgentTaskStatus.QUEUED,
            checkpoint = checkpoint,
            step = 0,
            currentAction = "Постановка задачи",
            summary = null,
            lastError = null,
            createdAt = now,
            updatedAt = now
        )
        writableDatabase.insertOrThrow("tasks", null, task.values())
        addEvent(task.id, AgentEventKind.START, "Задача запущена", objective.take(2_000))
        return task
    }

    @Synchronized
    fun all(): List<AgentTask> = queryTasks(null, null)

    @Synchronized
    fun active(): List<AgentTask> = queryTasks(
        "status IN (?,?,?)",
        arrayOf(AgentTaskStatus.QUEUED.name, AgentTaskStatus.RUNNING.name, AgentTaskStatus.WAITING_NETWORK.name)
    )

    @Synchronized
    fun get(id: String): AgentTask? = queryTasks("id=?", arrayOf(id)).firstOrNull()

    @Synchronized
    fun updateCheckpoint(id: String, checkpoint: String, step: Int, currentAction: String, status: AgentTaskStatus = AgentTaskStatus.RUNNING) {
        writableDatabase.update("tasks", ContentValues().apply {
            put("checkpoint", checkpoint)
            put("step", step)
            put("current_action", currentAction.take(240))
            put("status", status.name)
            putNull("last_error")
            put("updated_at", System.currentTimeMillis())
        }, "id=?", arrayOf(id))
    }

    @Synchronized
    fun setStatus(id: String, status: AgentTaskStatus, action: String, error: String? = null) {
        writableDatabase.update("tasks", ContentValues().apply {
            put("status", status.name)
            put("current_action", action.take(240))
            if (error == null) putNull("last_error") else put("last_error", error.take(2_000))
            put("updated_at", System.currentTimeMillis())
        }, "id=?", arrayOf(id))
    }

    @Synchronized
    fun complete(id: String, summary: String, checkpoint: String, step: Int) {
        writableDatabase.update("tasks", ContentValues().apply {
            put("status", AgentTaskStatus.COMPLETED.name)
            put("summary", summary.take(12_000))
            put("checkpoint", checkpoint)
            put("step", step)
            put("current_action", "Задача выполнена")
            putNull("last_error")
            put("updated_at", System.currentTimeMillis())
        }, "id=?", arrayOf(id))
    }

    @Synchronized
    fun delete(id: String) {
        writableDatabase.delete("tasks", "id=?", arrayOf(id))
    }

    @Synchronized
    fun addEvent(taskId: String, kind: AgentEventKind, title: String, detail: String = "") {
        val db = writableDatabase
        db.insertOrThrow("task_events", null, ContentValues().apply {
            put("task_id", taskId)
            put("kind", kind.name)
            put("title", title.take(240))
            put("detail", detail.take(8_000))
            put("created_at", System.currentTimeMillis())
        })
        db.execSQL(
            "DELETE FROM task_events WHERE task_id=? AND id NOT IN (SELECT id FROM task_events WHERE task_id=? ORDER BY id DESC LIMIT 500)",
            arrayOf(taskId, taskId)
        )
    }

    @Synchronized
    fun events(taskId: String, limit: Int = 200): List<AgentTaskEvent> = readableDatabase.query(
        "task_events",
        arrayOf("id", "task_id", "kind", "title", "detail", "created_at"),
        "task_id=?",
        arrayOf(taskId),
        null,
        null,
        "id DESC",
        limit.coerceIn(1, 500).toString()
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(
                AgentTaskEvent(
                    id = cursor.getLong(0),
                    taskId = cursor.getString(1),
                    kind = enumOrDefault(cursor.getString(2), AgentEventKind.SYSTEM),
                    title = cursor.getString(3),
                    detail = cursor.getString(4),
                    createdAt = cursor.getLong(5)
                )
            )
        }.reversed()
    }

    @Synchronized
    fun addInstruction(taskId: String, text: String): Long = writableDatabase.insertOrThrow(
        "task_instructions",
        null,
        ContentValues().apply {
            put("task_id", taskId)
            put("text", text.take(8_000))
            put("consumed", 0)
            put("created_at", System.currentTimeMillis())
        }
    )

    @Synchronized
    fun pendingInstructions(taskId: String): List<AgentInstruction> = readableDatabase.query(
        "task_instructions",
        arrayOf("id", "task_id", "text", "created_at"),
        "task_id=? AND consumed=0",
        arrayOf(taskId),
        null,
        null,
        "id ASC",
        "50"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(
                AgentInstruction(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3))
            )
        }
    }

    @Synchronized
    fun consumeInstructions(ids: List<Long>) {
        if (ids.isEmpty()) return
        val placeholders = ids.joinToString(",") { "?" }
        writableDatabase.execSQL(
            "UPDATE task_instructions SET consumed=1 WHERE id IN ($placeholders)",
            ids.map { it.toString() }.toTypedArray()
        )
    }

    @Synchronized
    fun planOperation(taskId: String, callId: String, toolName: String, arguments: String) {
        val now = System.currentTimeMillis()
        writableDatabase.insertWithOnConflict("tool_operations", null, ContentValues().apply {
            put("task_id", taskId)
            put("call_id", callId)
            put("tool_name", toolName)
            put("arguments", arguments)
            put("status", AgentOperationStatus.PLANNED.name)
            put("created_at", now)
            put("updated_at", now)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    @Synchronized
    fun operation(taskId: String, callId: String): AgentOperation? = readableDatabase.query(
        "tool_operations",
        arrayOf("task_id", "call_id", "tool_name", "arguments", "status", "result", "created_at", "updated_at"),
        "task_id=? AND call_id=?",
        arrayOf(taskId, callId),
        null,
        null,
        null
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else AgentOperation(
            taskId = cursor.getString(0),
            callId = cursor.getString(1),
            toolName = cursor.getString(2),
            arguments = cursor.getString(3),
            status = enumOrDefault(cursor.getString(4), AgentOperationStatus.UNKNOWN),
            result = if (cursor.isNull(5)) null else cursor.getString(5),
            createdAt = cursor.getLong(6),
            updatedAt = cursor.getLong(7)
        )
    }

    @Synchronized
    fun updateOperation(taskId: String, callId: String, status: AgentOperationStatus, result: String? = null) {
        writableDatabase.update("tool_operations", ContentValues().apply {
            put("status", status.name)
            if (result == null) putNull("result") else put("result", result.take(64_000))
            put("updated_at", System.currentTimeMillis())
        }, "task_id=? AND call_id=?", arrayOf(taskId, callId))
    }

    private fun queryTasks(selection: String?, args: Array<String>?): List<AgentTask> = readableDatabase.query(
        "tasks",
        arrayOf("id", "title", "objective", "conversation_id", "ssh_profile_id", "auto_approve_ssh", "status", "checkpoint", "step", "current_action", "summary", "last_error", "created_at", "updated_at"),
        selection,
        args,
        null,
        null,
        "updated_at DESC"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(
                AgentTask(
                    id = cursor.getString(0),
                    title = cursor.getString(1),
                    objective = cursor.getString(2),
                    conversationId = if (cursor.isNull(3)) null else cursor.getString(3),
                    sshProfileId = if (cursor.isNull(4)) null else cursor.getString(4),
                    autoApproveSsh = cursor.getInt(5) != 0,
                    status = enumOrDefault(cursor.getString(6), AgentTaskStatus.FAILED),
                    checkpoint = cursor.getString(7),
                    step = cursor.getInt(8),
                    currentAction = cursor.getString(9),
                    summary = if (cursor.isNull(10)) null else cursor.getString(10),
                    lastError = if (cursor.isNull(11)) null else cursor.getString(11),
                    createdAt = cursor.getLong(12),
                    updatedAt = cursor.getLong(13)
                )
            )
        }
    }

    private fun AgentTask.values() = ContentValues().apply {
        put("id", id)
        put("title", title)
        put("objective", objective)
        if (conversationId == null) putNull("conversation_id") else put("conversation_id", conversationId)
        if (sshProfileId == null) putNull("ssh_profile_id") else put("ssh_profile_id", sshProfileId)
        put("auto_approve_ssh", if (autoApproveSsh) 1 else 0)
        put("status", status.name)
        put("checkpoint", checkpoint)
        put("step", step)
        put("current_action", currentAction)
        putNull("summary")
        putNull("last_error")
        put("created_at", createdAt)
        put("updated_at", updatedAt)
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback

    private companion object { const val VERSION = 2 }
}
