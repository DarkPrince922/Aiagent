package app.jarvis.net

/** Строка, вычитанная с сервера: идентификатор и то, что о нём известно. */
data class RemoteEntry(val id: String, val detail: String)

/**
 * Команды, которыми приложение подсматривает за сервером.
 *
 * Живут отдельно от экрана по двум причинам: это обычный shell, который стоит проверять
 * настоящим shell'ом, а не глазами, и подстановка чужих строк в командную строку — то самое
 * место, где рождаются инъекции. Всё, что приходит от сервера или из поля ввода, проходит
 * через [quote] или чистится посимвольно.
 */
object TerminalProbe {

    /** Колонки разделяем табуляцией: в именах сессий и в тексте команд её не бывает. */
    private const val SEPARATOR = '\t'

    /**
     * Открытые окна tmux.
     *
     * Отсутствие tmux — не ошибка, а «сессий нет»: сервер без него обслуживается обычным
     * nohup-запуском, и экран должен показывать пустой список, а не сообщение о сбое.
     */
    const val SESSIONS =
        "tmux list-sessions -F '#{session_name}\t#{session_windows} окон\t#{?session_attached,подключена,отключена}' 2>/dev/null || true"

    /** Последние операции агента: каталог, состояние и начало команды. */
    val OPERATIONS: String = """
        base="${'$'}HOME/.cache/jarvis-agent/ops"
        [ -d "${'$'}base" ] || exit 0
        for dir in ${'$'}(ls -1t "${'$'}base" 2>/dev/null | head -20); do
          op="${'$'}base/${'$'}dir"
          if [ -f "${'$'}op/exit" ]; then state="код ${'$'}(tr -cd '0-9' < "${'$'}op/exit")"
          elif [ -f "${'$'}op/pid" ] && kill -0 "${'$'}(cat "${'$'}op/pid")" 2>/dev/null; then state="выполняется"
          else state="прервана"; fi
          printf '%s\t%s\t%s\n' "${'$'}dir" "${'$'}state" "${'$'}(head -c 120 "${'$'}op/command.sh" 2>/dev/null | tr '\n' ' ')"
        done
    """.trimIndent()

    /** Содержимое окна: последние [lines] строк вместе с историей прокрутки. */
    fun capture(session: String, lines: Int = 400): String =
        "tmux capture-pane -p -t ${quote(session)} -S -${lines.coerceIn(50, 2_000)}"

    /** Ввод в живое окно: ровно то же, что набрал бы человек за клавиатурой сервера. */
    fun sendKeys(session: String, command: String): String =
        "tmux send-keys -t ${quote(session)} ${quote(command)} Enter"

    /**
     * Своё окно с обычной оболочкой.
     *
     * Сессии задач заняты самой командой: в них нет приглашения, и введённый текст ушёл бы в
     * stdin работающего процесса. Для работы руками нужно отдельное окно, и оно должно
     * переиспользоваться, а не плодиться на каждое нажатие.
     */
    fun createSession(session: String): String =
        "tmux has-session -t ${quote(session)} 2>/dev/null || tmux new-session -d -s ${quote(session)}"

    /** Имя окна, которое приложение открывает для человека. */
    const val USER_SESSION = "jarvis-term"

    /**
     * Один каталог операции: команда, затем хвосты вывода.
     *
     * Имя каталога — hex-хэш, но приходит оно из вывода сервера, поэтому чистится так же
     * строго, как имя файла: подставлять непроверенную строку в путь нельзя.
     */
    fun operation(id: String): String {
        val safe = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(80)
        require(safe.isNotEmpty()) { "Пустой идентификатор операции" }
        return """
            op="${'$'}HOME/.cache/jarvis-agent/ops/$safe"
            printf '=== КОМАНДА ===\n'
            head -c 4000 "${'$'}op/command.sh" 2>/dev/null
            printf '\n=== ВЫВОД ===\n'
            tail -c 24000 "${'$'}op/stdout" 2>/dev/null
            printf '\n=== ОШИБКИ ===\n'
            tail -c 8000 "${'$'}op/stderr" 2>/dev/null
        """.trimIndent()
    }

    /**
     * Кавычит аргумент для sh.
     *
     * Имя сессии приходит с сервера, команда — из поля ввода. Без кавычек пробел, `;` или
     * `$(...)` превращали бы одну команду в несколько.
     */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** Разбирает построчный вывод команд выше. Мусорные строки отбрасываются молча. */
    fun parse(raw: String, limit: Int = 30): List<RemoteEntry> =
        raw.lineSequence()
            .map { it.trim('\r', '\n', ' ') }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split(SEPARATOR)
                val id = parts.firstOrNull()?.trim().orEmpty()
                if (id.isBlank()) return@mapNotNull null
                RemoteEntry(id, parts.drop(1).joinToString(" · ") { it.trim() }.ifBlank { "—" })
            }
            .take(limit)
            .toList()
}
