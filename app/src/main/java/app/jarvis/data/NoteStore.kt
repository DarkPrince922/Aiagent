package app.jarvis.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Note(val id: Long, val title: String, val body: String, val updatedAt: Long)

class NoteStore(context: Context) : SQLiteOpenHelper(context, "notes.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, body TEXT NOT NULL, updated INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun create(title: String, body: String): Long = writableDatabase.insert("notes", null, ContentValues().apply {
        put("title", title.take(120)); put("body", body.take(20_000)); put("updated", System.currentTimeMillis())
    })

    fun list(query: String = ""): List<Note> {
        val selection = if (query.isBlank()) null else "title LIKE ? OR body LIKE ?"
        val args = if (query.isBlank()) null else arrayOf("%$query%", "%$query%")
        return readableDatabase.query("notes", arrayOf("id", "title", "body", "updated"), selection, args, null, null, "updated DESC", "30").use { cursor ->
            buildList { while (cursor.moveToNext()) add(Note(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3))) }
        }
    }

    fun delete(id: Long): Boolean = writableDatabase.delete("notes", "id=?", arrayOf(id.toString())) > 0
}
