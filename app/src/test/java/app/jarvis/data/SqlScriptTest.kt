package app.jarvis.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбитый не по тем точкам с запятой дамп даёт лавину синтаксических ошибок, по которым
 * невозможно понять, что не так с файлом. Здесь проверяются ровно те случаи, на которых
 * ломается наивный split(";").
 */
class SqlScriptTest {

    @Test fun plainStatementsSplitOnSemicolons() {
        val parts = SqlScript.split("CREATE TABLE t (id INT); INSERT INTO t VALUES (1); SELECT 1;")
        assertEquals(3, parts.size)
        assertEquals("CREATE TABLE t (id INT)", parts[0])
    }

    @Test fun aSemicolonInsideAStringIsNotASeparator() {
        val parts = SqlScript.split("INSERT INTO t VALUES ('a;b', 'c;d'); SELECT 1;")
        assertEquals(2, parts.size)
        assertTrue(parts[0].contains("'a;b'"))
    }

    /** Экранирование кавычки удвоением — самый частый способ в дампах. */
    @Test fun aDoubledQuoteDoesNotEndTheLiteral() {
        val parts = SqlScript.split("INSERT INTO t VALUES ('it''s; fine'); SELECT 2;")
        assertEquals(2, parts.size)
        assertTrue(parts[0].contains("it''s; fine"))
    }

    @Test fun quotedIdentifiersAreRespected() {
        val parts = SqlScript.split("""CREATE TABLE "a;b" (x INT); CREATE TABLE `c;d` (y INT);""")
        assertEquals(2, parts.size)
    }

    @Test fun commentsAreStrippedAndDoNotSplit() {
        val script = """
            -- таблица; с точкой с запятой в комментарии
            CREATE TABLE t (id INT);
            /* блочный; комментарий */
            INSERT INTO t VALUES (1);
        """.trimIndent()
        val parts = SqlScript.split(script)
        assertEquals(2, parts.size)
        assertFalse(parts.any { it.contains("комментарии") })
        assertFalse(parts.any { it.contains("блочный") })
    }

    /** Тело триггера полно точек с запятой, но это один оператор. */
    @Test fun aTriggerBodyStaysOneStatement() {
        val script = """
            CREATE TRIGGER t AFTER INSERT ON a BEGIN
              UPDATE b SET n = n + 1;
              DELETE FROM c;
            END;
            SELECT 1;
        """.trimIndent()
        val parts = SqlScript.split(script)
        assertEquals(2, parts.size)
        assertTrue(parts[0].startsWith("CREATE TRIGGER"))
        assertTrue(parts[0].contains("DELETE FROM c"))
    }

    @Test fun aTrailingStatementWithoutSemicolonIsKept() {
        val parts = SqlScript.split("CREATE TABLE t (id INT);\nINSERT INTO t VALUES (1)")
        assertEquals(2, parts.size)
        assertEquals("INSERT INTO t VALUES (1)", parts[1])
    }

    @Test fun emptyAndBlankScriptsProduceNothing() {
        assertTrue(SqlScript.split("").isEmpty())
        assertTrue(SqlScript.split("   \n\n  ").isEmpty())
        assertTrue(SqlScript.split(";;;").isEmpty())
        assertTrue(SqlScript.split("-- только комментарий").isEmpty())
    }

    /** Слово END внутри имени не должно закрывать блок. */
    @Test fun anIdentifierContainingEndIsNotABlockEnd() {
        val parts = SqlScript.split("SELECT weekend FROM calendar; SELECT 1;")
        assertEquals(2, parts.size)
    }

    @Test fun aReadOnlyScriptIsRecognisedAsChangingNothing() {
        assertFalse(SqlScript.changesAnything(SqlScript.split("SELECT 1; SELECT 2;")))
        assertTrue(SqlScript.changesAnything(SqlScript.split("SELECT 1; DROP TABLE t;")))
        assertTrue(SqlScript.changesAnything(SqlScript.split("CREATE TABLE t (id INT);")))
    }

    @Test fun theStatementKindIsReportedForDiagnostics() {
        assertEquals("CREATE", SqlScript.kindOf("CREATE TABLE t (id INT)"))
        assertEquals("INSERT", SqlScript.kindOf("  insert into t values (1)"))
    }

    /** Обрывок литерала не должен съедать остаток файла молча — он остаётся одним оператором. */
    @Test fun anUnterminatedLiteralDoesNotCrash() {
        val parts = SqlScript.split("INSERT INTO t VALUES ('незакрытая строка")
        assertEquals(1, parts.size)
    }
}
