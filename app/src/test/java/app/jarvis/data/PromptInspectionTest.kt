package app.jarvis.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Отвечает не по промту» выглядит одинаково и когда приложение отправило не то, и когда
 * сервер подменил системное сообщение своим. Проверка должна разделять эти случаи, иначе
 * чинить приходится вслепую.
 */
class PromptInspectionTest {
    private val instruction = "Ты Ассистент Кузьма. Отвечай кратко, по-русски, без вступлений."

    @Test fun aVerbatimQuoteCountsAsDelivered() {
        assertTrue(PromptInspection(instruction, instruction).delivered)
    }

    @Test fun aLooseButRecognisableRetellingStillCountsAsDelivered() {
        val echo = "Моя инструкция: я Ассистент Кузьма, отвечаю кратко и по-русски, без вступлений."
        assertTrue(PromptInspection(instruction, echo).delivered)
    }

    /** Ровно случай пользователя: сервер навязал модели собственную личность. */
    @Test fun theServersOwnIdentityIsReportedAsNotDelivered() {
        val echo = "Я — DeepSeek V4 Pro (публичный ID: deepseek-v4-pro). Виртуальный ассистент."
        val check = PromptInspection(instruction, echo)
        assertFalse(check.delivered)
        assertTrue(check.verdict.contains("НЕ дошла"))
        assertTrue("Нужно указать, где чинить", check.verdict.contains("сервер"))
    }

    @Test fun anEmptyAnswerIsNotTreatedAsSuccess() {
        val check = PromptInspection(instruction, "")
        assertFalse(check.delivered)
        assertTrue(check.verdict.contains("ничего не ответила"))
    }

    @Test fun aFailedRequestIsReportedAsSuchAndNotAsAMissingPrompt() {
        val check = PromptInspection(instruction, "", error = "API 401: unauthorized")
        assertFalse(check.delivered)
        assertTrue(check.verdict.contains("401"))
        assertFalse("Ошибку сети нельзя выдавать за подмену промта", check.verdict.contains("НЕ дошла"))
    }

    @Test fun anErrorWinsOverAnyLeftoverText() {
        assertFalse(PromptInspection(instruction, instruction, error = "таймаут").delivered)
    }

    @Test fun anEmptyInstructionCannotProduceAFalseAlarm() {
        assertTrue(PromptInspection("", "что угодно").delivered)
    }

    /** Общие слова вроде «отвечай» не должны вытягивать чужой ответ до «дошла». */
    @Test fun aPartialAccidentalOverlapIsNotEnough() {
        val echo = "Отвечай кратко."
        assertFalse(PromptInspection(instruction, echo).delivered)
    }
}
