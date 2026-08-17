package app.jarvis.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Личность ассистента задаётся только инструкцией пользователя. Имя в промте по умолчанию
 * означало, что модель зовёт себя так, как решил автор приложения, — а пользователь видел
 * чужое имя в ответе на «ты кто?».
 */
class PromptDefaultsTest {
    @Test fun theDefaultInstructionClaimsNoName() {
        val text = PromptDefaults.SYSTEM.lowercase()
        listOf("jarvis", "джарвис", "меня зовут", "ты —").forEach {
            assertFalse("Промт по умолчанию не должен навязывать имя: «$it»", text.contains(it))
        }
    }

    @Test fun everyDefaultIsUsableAsIs() {
        listOf(
            PromptDefaults.SYSTEM,
            PromptDefaults.ANSWER_FIRST,
            PromptDefaults.TOOLS,
            PromptDefaults.AUTONOMY
        ).forEach { assertTrue(it.trim().length > 40) }
    }

    /** Пустое поле — это «верни как было»: пустой промт не отправляют. */
    @Test fun blankFallsBackToTheDefault() {
        assertEquals(PromptDefaults.TOOLS, PromptDefaults.orDefault("   ", PromptDefaults.TOOLS))
        assertEquals(PromptDefaults.TOOLS, PromptDefaults.orDefault("", PromptDefaults.TOOLS))
        assertEquals(PromptDefaults.TOOLS, PromptDefaults.orDefault("\n\t ", PromptDefaults.TOOLS))
    }

    @Test fun ownTextWinsAndIsTrimmed() {
        assertEquals("Ты Кузьма.", PromptDefaults.orDefault("  Ты Кузьма.  ", PromptDefaults.SYSTEM))
    }

    @Test fun theToolsPromptNamesTheToolsItDescribes() {
        listOf("read_file", "search_file", "write_file", "send_file", "create_zip", "upload_file", "download_file")
            .forEach { assertTrue("$it не упомянут", PromptDefaults.TOOLS.contains(it)) }
    }

    @Test fun theAutonomyPromptStillRequiresFinishTask() {
        assertTrue(PromptDefaults.AUTONOMY.contains("finish_task"))
        assertTrue(PromptDefaults.AUTONOMY.contains("record_progress"))
    }
}
