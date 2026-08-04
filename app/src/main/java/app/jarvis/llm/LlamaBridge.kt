package app.jarvis.llm

/**
 * Тонкая обёртка над libjarvisllm.so.
 *
 * Строки ходят массивами байт: JNI отдаёт modified UTF-8, где эмодзи кодируются суррогатной
 * парой, а токенизатор ждёт обычный UTF-8.
 */
internal object LlamaBridge {
    @Volatile private var loaded = false

    @Synchronized fun ensureLoaded(): Boolean {
        if (loaded) return true
        return runCatching {
            System.loadLibrary("jarvisllm")
            nativeInit()
            loaded = true
            true
        }.getOrElse {
            // На устройстве без arm64 библиотеки просто нет — это не повод падать.
            false
        }
    }

    fun load(path: String, contextTokens: Int, threads: Int): Long =
        nativeLoad(path.toByteArray(Charsets.UTF_8), contextTokens, threads)

    fun release(handle: Long) = nativeRelease(handle)

    fun contextTokens(handle: Long): Int = nativeContextTokens(handle)

    fun countTokens(handle: Long, text: String): Int = nativeCountTokens(handle, text.toByteArray(Charsets.UTF_8))

    fun cancel(handle: Long) = nativeCancel(handle)

    /** @return сгенерированный текст или null, если промпт не помещается в контекст. */
    fun generate(
        handle: Long,
        prompt: String,
        grammar: String?,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        seed: Int
    ): String? = nativeGenerate(
        handle,
        prompt.toByteArray(Charsets.UTF_8),
        grammar?.toByteArray(Charsets.UTF_8),
        maxTokens,
        temperature,
        topP,
        topK,
        seed
    )?.toString(Charsets.UTF_8)

    private external fun nativeInit()
    private external fun nativeLoad(pathUtf8: ByteArray, contextTokens: Int, threads: Int): Long
    private external fun nativeRelease(handle: Long)
    private external fun nativeContextTokens(handle: Long): Int
    private external fun nativeCountTokens(handle: Long, textUtf8: ByteArray): Int
    private external fun nativeCancel(handle: Long)
    private external fun nativeGenerate(
        handle: Long,
        promptUtf8: ByteArray,
        grammarUtf8: ByteArray?,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        seed: Int
    ): ByteArray?
}
