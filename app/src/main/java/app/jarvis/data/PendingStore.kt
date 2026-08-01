package app.jarvis.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class PendingRequest(val id: Long, val text: String)

class PendingStore(context: Context) : SQLiteOpenHelper(context, "pending.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) = db.execSQL("CREATE TABLE pending (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, created INTEGER NOT NULL)")
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun add(text: String): Long = writableDatabase.insert("pending", null, ContentValues().apply { put("text", text); put("created", System.currentTimeMillis()) })
    fun all(): List<PendingRequest> = readableDatabase.query("pending", arrayOf("id", "text"), null, null, null, null, "created ASC").use { c ->
        buildList { while (c.moveToNext()) add(PendingRequest(c.getLong(0), c.getString(1))) }
    }
    fun remove(id: Long) { writableDatabase.delete("pending", "id=?", arrayOf(id.toString())) }
}
