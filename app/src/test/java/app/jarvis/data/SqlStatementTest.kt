package app.jarvis.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разделение читающих и изменяющих запросов — единственное, что стоит между «посмотреть
 * таблицу» и «стереть её». Ошибка в любую сторону заметна не сразу: либо агент не может
 * прочитать базу, либо меняет её там, где обещал только читать.
 */
class SqlStatementTest {
    private fun readOnly(sql: String): Boolean {
        val statements = sql.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        if (statements.size != 1) return false
        return statements.single().substringBefore(' ').lowercase() in setOf("select", "with", "pragma", "explain")
    }

    @Test fun readingStatementsPass() {
        listOf(
            "SELECT * FROM users",
            "select id from t",
            "WITH x AS (SELECT 1) SELECT * FROM x",
            "PRAGMA table_info(users)",
            "EXPLAIN QUERY PLAN SELECT 1"
        ).forEach { assertTrue(it, readOnly(it)) }
    }

    @Test fun writingStatementsDoNotPassAsReads() {
        listOf(
            "DELETE FROM users",
            "UPDATE users SET name = 'x'",
            "INSERT INTO users VALUES (1)",
            "DROP TABLE users",
            "CREATE TABLE t (id INT)"
        ).forEach { assertFalse(it, readOnly(it)) }
    }

    /** Главное: «читающий» запрос не должен протащить за собой изменение. */
    @Test fun aSecondStatementIsNeverSmuggledIn() {
        assertFalse(readOnly("SELECT 1; DELETE FROM users"))
        assertFalse(readOnly("SELECT 1;DROP TABLE users"))
    }

    /** Имя таблицы, содержащее ключевое слово, не делает запрос изменяющим. */
    @Test fun aTableNamedLikeAKeywordIsStillJustARead() {
        assertTrue(readOnly("SELECT * FROM updates"))
        assertTrue(readOnly("SELECT * FROM deleted_items"))
        assertTrue(readOnly("SELECT insert_time FROM log"))
    }

    @Test fun aTrailingSemicolonIsFine() {
        assertTrue(readOnly("SELECT 1;"))
        assertTrue(readOnly("  SELECT 1 ;  "))
    }

    @Test fun anEmptyStatementIsNotAValidRead() {
        assertFalse(readOnly(""))
        assertFalse(readOnly("   "))
        assertFalse(readOnly(";;"))
    }

    @Test fun databasesAreRecognisedByExtension() {
        listOf("data.db", "app.sqlite", "store.SQLITE3").forEach {
            assertTrue(it, WorkspaceStore.isDatabase(it))
        }
        listOf("report.txt", "bundle.zip", "notes.md").forEach {
            assertFalse(it, WorkspaceStore.isDatabase(it))
        }
    }

    @Test fun aDatabaseNameSurvivesNormalisationButIsNotText() {
        assertEquals("data.db", WorkspaceStore.safeName("/var/lib/../data.db"))
        assertFalse(WorkspaceStore.isText("data.db"))
    }
}
