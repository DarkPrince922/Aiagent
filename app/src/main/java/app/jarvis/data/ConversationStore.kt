package app.jarvis.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

class ConversationStore(context: Context) : SQLiteOpenHelper(context, "conversations.db", null, 1) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE conversations (id TEXT PRIMARY KEY, title TEXT NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY, conversation_id TEXT NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL, state TEXT NOT NULL, detail TEXT, created INTEGER NOT NULL, FOREIGN KEY(conversation_id) REFERENCES conversations(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX messages_conversation_created ON messages(conversation_id, created)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized fun ensureConversation(): Conversation = list().firstOrNull() ?: create()

    @Synchronized fun create(title: String = "Новый чат"): Conversation {
        val now = System.currentTimeMillis()
        val conversation = Conversation(UUID.randomUUID().toString(), title, now, now)
        writableDatabase.insertOrThrow("conversations", null, ContentValues().apply {
            put("id", conversation.id); put("title", conversation.title); put("created", now); put("updated", now)
        })
        return conversation
    }

    @Synchronized fun list(): List<Conversation> = readableDatabase.query("conversations", arrayOf("id", "title", "created", "updated"), null, null, null, null, "updated DESC").use { cursor ->
        buildList { while (cursor.moveToNext()) add(Conversation(cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getLong(3))) }
    }

    @Synchronized fun messages(conversationId: String): List<Message> = readableDatabase.query("messages", arrayOf("id", "role", "text", "state", "detail"), "conversation_id=?", arrayOf(conversationId), null, null, "created ASC").use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(
                Message(
                    id = cursor.getLong(0), role = cursor.getString(1), text = cursor.getString(2),
                    state = runCatching { DeliveryState.valueOf(cursor.getString(3)) }.getOrDefault(DeliveryState.SENT),
                    detail = cursor.getString(4)
                )
            )
        }
    }

    @Synchronized fun saveMessage(conversationId: String, message: Message) {
        val now = System.currentTimeMillis()
        writableDatabase.insertWithOnConflict("messages", null, ContentValues().apply {
            put("id", message.id); put("conversation_id", conversationId); put("role", message.role); put("text", message.text)
            put("state", message.state.name); put("detail", message.detail); put("created", now)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        touch(conversationId, now)
    }

    @Synchronized fun deleteMessage(id: Long) { writableDatabase.delete("messages", "id=?", arrayOf(id.toString())) }

    @Synchronized fun titleFromFirstMessage(conversationId: String, text: String) {
        val current = list().firstOrNull { it.id == conversationId } ?: return
        if (current.title != "Новый чат") return
        val title = text.replace(Regex("\\s+"), " ").trim().take(48).ifBlank { "Новый чат" }
        writableDatabase.update("conversations", ContentValues().apply { put("title", title); put("updated", System.currentTimeMillis()) }, "id=?", arrayOf(conversationId))
    }

    @Synchronized fun deleteConversation(id: String) { writableDatabase.delete("conversations", "id=?", arrayOf(id)) }

    private fun touch(id: String, time: Long) {
        writableDatabase.update("conversations", ContentValues().apply { put("updated", time) }, "id=?", arrayOf(id))
    }
}
