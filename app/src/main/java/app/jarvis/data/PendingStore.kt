package app.jarvis.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class PendingRequest(val id: Long, val text: String, val status: String, val result: String?, val error: String?)

class PendingStore(context: Context) : SQLiteOpenHelper(context, "pending.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE pending (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, created INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'pending', result TEXT, error TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE pending ADD COLUMN status TEXT NOT NULL DEFAULT 'pending'")
            db.execSQL("ALTER TABLE pending ADD COLUMN result TEXT")
            db.execSQL("ALTER TABLE pending ADD COLUMN error TEXT")
        }
    }
    fun add(text: String): Long = writableDatabase.insert("pending", null, ContentValues().apply {
        put("text", text); put("created", System.currentTimeMillis()); put("status", "pending")
    })
    fun waiting(): List<PendingRequest> = query("status='pending'")
    fun completed(): List<PendingRequest> = query("status='complete'")
    fun complete(id: Long, result: String) { writableDatabase.update("pending", ContentValues().apply { put("status", "complete"); put("result", result); putNull("error") }, "id=?", arrayOf(id.toString())) }
    fun fail(id: Long, error: String) { writableDatabase.update("pending", ContentValues().apply { put("status", "complete"); put("error", error) }, "id=?", arrayOf(id.toString())) }
    fun remove(id: Long) { writableDatabase.delete("pending", "id=?", arrayOf(id.toString())) }
    private fun query(selection: String): List<PendingRequest> = readableDatabase.query("pending", arrayOf("id", "text", "status", "result", "error"), selection, null, null, null, "created ASC").use { cursor ->
        buildList { while (cursor.moveToNext()) add(PendingRequest(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4))) }
    }
}
