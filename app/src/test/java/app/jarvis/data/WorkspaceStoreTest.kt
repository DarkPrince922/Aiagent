package app.jarvis.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceStoreTest {
    @Test fun keepsPlainTextAndJsonNames() {
        assertEquals("notes.txt", WorkspaceStore.safeName("notes.txt"))
        assertEquals("report.json", WorkspaceStore.safeName("report.json"))
        assertEquals("plan.md", WorkspaceStore.safeName("plan.md"))
    }

    @Test fun directoriesAreDroppedEntirely() {
        // Имя приходит от модели: выход за рабочую папку не должен быть возможен в принципе.
        listOf(
            "../../shared_prefs/provider.xml",
            "/data/data/app.jarvis/databases/agent_tasks.db",
            "..\\..\\windows\\style.txt",
            "sub/dir/deep.json"
        ).forEach { raw ->
            val safe = WorkspaceStore.safeName(raw)
            assertFalse("$raw -> $safe", safe.contains('/'))
            assertFalse("$raw -> $safe", safe.contains('\\'))
            assertFalse("$raw -> $safe", safe.contains(".."))
        }
        assertEquals("deep.json", WorkspaceStore.safeName("sub/dir/deep.json"))
    }

    @Test fun binaryExtensionsBecomeText() {
        // Агент работает с текстом; исполняемое или бинарное расширение ему ни к чему.
        assertEquals("payload.txt", WorkspaceStore.safeName("payload.apk"))
        assertEquals("script.txt", WorkspaceStore.safeName("script.sh"))
        assertEquals("photo.txt", WorkspaceStore.safeName("photo.png"))
    }

    @Test fun namelessInputStillProducesAFile() {
        assertEquals("file.txt", WorkspaceStore.safeName(""))
        assertEquals("file.txt", WorkspaceStore.safeName("   "))
        assertEquals("file.txt", WorkspaceStore.safeName("..."))
        assertEquals("file.txt", WorkspaceStore.safeName("/"))
    }

    @Test fun longNamesAreTruncatedButKeepExtension() {
        val safe = WorkspaceStore.safeName("a".repeat(300) + ".json")
        assertTrue(safe.endsWith(".json"))
        assertTrue("Слишком длинное имя: ${safe.length}", safe.length <= 90)
    }

    @Test fun extensionlessNameGetsTxt() {
        assertEquals("readme.txt", WorkspaceStore.safeName("readme"))
    }
}
