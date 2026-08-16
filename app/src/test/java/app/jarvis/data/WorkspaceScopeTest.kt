package app.jarvis.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Имя каталога задачи выводится из её идентификатора и потому обязано быть таким же
 * недоверенным вводом, как имя файла: иначе задача с подставленным id уходит из рабочей
 * папки и пишет куда угодно.
 */
class WorkspaceScopeTest {

    @Test fun anOrdinaryIdSurvivesUnchanged() {
        assertEquals("a1b2c3d4-5e6f", WorkspaceStore.safeScope("a1b2c3d4-5e6f"))
        assertEquals("task_17", WorkspaceStore.safeScope("task_17"))
    }

    @Test fun traversalCannotEscapeTheWorkspace() {
        listOf("../../shared_prefs", "..", "../..", "/etc/passwd", "a/../../b").forEach { raw ->
            val scope = WorkspaceStore.safeScope(raw)
            assertTrue("В «$scope» остались разделители", scope.none { it == '/' || it == '\\' })
            assertTrue("В «$scope» осталась точка", scope.none { it == '.' })
        }
    }

    @Test fun anEmptyOrJunkIdStillGivesAUsableFolder() {
        assertEquals("task", WorkspaceStore.safeScope(""))
        assertEquals("task", WorkspaceStore.safeScope("../.."))
        assertEquals("task", WorkspaceStore.safeScope("///"))
    }

    @Test fun theNameIsBoundedSoAPathCannotBlowUp() {
        assertTrue(WorkspaceStore.safeScope("x".repeat(500)).length <= 64)
    }

    /** Разные задачи обязаны получать разные каталоги, иначе изоляция бессмысленна. */
    @Test fun differentTasksGetDifferentFolders() {
        assertNotEquals(WorkspaceStore.safeScope("task-a"), WorkspaceStore.safeScope("task-b"))
    }
}
