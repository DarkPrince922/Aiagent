package app.jarvis.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Координаты куска — единственное, по чему модель понимает, что файл дочитан не весь.
 * Ошибка здесь возвращает ровно ту проблему, ради которой постраничное чтение и делалось.
 */
class FileChunkTest {
    private fun chunk(text: String, offset: Int, total: Int) =
        FileChunk(name = "report.json", text = text, offset = offset, totalChars = total, totalLines = 1)

    @Test fun nextOffsetContinuesExactlyWhereTheChunkEnded() {
        val first = chunk("a".repeat(30_000), offset = 0, total = 204_800)
        assertEquals(30_000, first.nextOffset)
        assertTrue(first.hasMore)

        val second = chunk("b".repeat(30_000), offset = first.nextOffset, total = 204_800)
        assertEquals(60_000, second.nextOffset)
    }

    @Test fun lastChunkReportsNoMore() {
        val last = chunk("tail", offset = 204_796, total = 204_800)
        assertEquals(204_800, last.nextOffset)
        assertFalse(last.hasMore)
    }

    @Test fun emptyFileIsNotReportedAsHavingMore() {
        assertFalse(chunk("", offset = 0, total = 0).hasMore)
    }

    @Test fun aTwoHundredKilobyteReportNeedsSeveralChunksAndTheyCoverIt() {
        val total = 204_800
        var offset = 0
        var chunks = 0
        while (true) {
            val size = minOf(WorkspaceStore.DEFAULT_CHUNK_CHARS, total - offset)
            val current = chunk("x".repeat(size), offset, total)
            chunks++
            if (!current.hasMore) break
            offset = current.nextOffset
            check(chunks < 100) { "Обход не сходится" }
        }
        assertEquals(7, chunks)
        assertEquals(total, offset + minOf(WorkspaceStore.DEFAULT_CHUNK_CHARS, total - offset))
    }

    @Test fun chunkLimitsAreOrderedSensibly() {
        assertTrue(WorkspaceStore.DEFAULT_CHUNK_CHARS <= WorkspaceStore.MAX_CHUNK_CHARS)
        // Отчёт на 200 КБ должен помещаться в хранилище целиком, иначе резать нечего.
        assertTrue(WorkspaceStore.MAX_FILE_BYTES > 204_800)
    }
}
