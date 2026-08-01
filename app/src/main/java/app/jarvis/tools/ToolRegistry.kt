package app.jarvis.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import app.jarvis.data.NoteStore
import app.jarvis.data.SshProfileStore
import app.jarvis.net.SshService
import app.jarvis.net.WebService
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.security.MessageDigest
import java.time.Instant
import java.time.ZonedDateTime
import java.util.Base64
import java.util.UUID

enum class ToolRisk { READ_ONLY, CHANGES_DEVICE, REMOTE_COMMAND }
data class ToolInfo(val name: String, val title: String, val description: String, val category: String, val icon: String, val risk: ToolRisk)
data class ToolResult(val content: String, val needsConfirmation: Boolean = false, val prompt: String = "")

class ToolRegistry(
    private val context: Context,
    private val notes: NoteStore,
    private val profiles: SshProfileStore,
    private val web: WebService,
    private val ssh: SshService
) {
    val catalog = listOf(
        ToolInfo("web_search", "Поиск в интернете", "Ищет актуальные источники", "Интернет", "travel_explore", ToolRisk.READ_ONLY),
        ToolInfo("web_fetch", "Чтение страницы", "Извлекает текст HTTPS-страницы", "Интернет", "language", ToolRisk.READ_ONLY),
        ToolInfo("ssh_exec", "SSH-команды", "Выполняет команду на сохранённом сервере", "Разработка", "terminal", ToolRisk.REMOTE_COMMAND),
        ToolInfo("http_request", "HTTP-запрос", "Проверяет API и webhooks", "Разработка", "http", ToolRisk.CHANGES_DEVICE),
        ToolInfo("dns_lookup", "DNS lookup", "Определяет IP-адреса домена", "Разработка", "dns", ToolRisk.READ_ONLY),
        ToolInfo("json_format", "JSON formatter", "Проверяет и форматирует JSON", "Разработка", "data_object", ToolRisk.READ_ONLY),
        ToolInfo("calculate", "Калькулятор", "Вычисляет выражения без отправки данных", "Повседневное", "calculate", ToolRisk.READ_ONLY),
        ToolInfo("create_note", "Заметки", "Создаёт локальные заметки", "Продуктивность", "edit_note", ToolRisk.CHANGES_DEVICE),
        ToolInfo("list_notes", "Поиск заметок", "Ищет в локальных заметках", "Продуктивность", "notes", ToolRisk.READ_ONLY),
        ToolInfo("set_alarm", "Будильник", "Подготавливает системный будильник", "Продуктивность", "alarm", ToolRisk.CHANGES_DEVICE),
        ToolInfo("set_timer", "Таймер", "Подготавливает системный таймер", "Продуктивность", "timer", ToolRisk.CHANGES_DEVICE),
        ToolInfo("add_calendar_event", "Календарь", "Подготавливает событие календаря", "Продуктивность", "calendar_month", ToolRisk.CHANGES_DEVICE),
        ToolInfo("open_map", "Карты", "Находит место или строит маршрут", "Повседневное", "map", ToolRisk.CHANGES_DEVICE),
        ToolInfo("dial_phone", "Телефон", "Открывает набор номера", "Коммуникации", "phone", ToolRisk.CHANGES_DEVICE),
        ToolInfo("compose_email", "Почта", "Подготавливает письмо", "Коммуникации", "mail", ToolRisk.CHANGES_DEVICE),
        ToolInfo("share_text", "Поделиться", "Открывает системное меню отправки", "Коммуникации", "share", ToolRisk.CHANGES_DEVICE),
        ToolInfo("clipboard_read", "Буфер обмена", "Читает текст после подтверждения", "Устройство", "content_paste", ToolRisk.CHANGES_DEVICE),
        ToolInfo("clipboard_write", "Копировать", "Копирует текст после подтверждения", "Устройство", "content_copy", ToolRisk.CHANGES_DEVICE),
        ToolInfo("device_status", "Устройство", "Показывает сеть, батарею и память", "Устройство", "phone_android", ToolRisk.READ_ONLY)
    )

    fun schemas(): JSONArray = JSONArray().apply {
        put(schema("get_current_time", "Текущие локальные дата, время и часовой пояс"))
        put(schema("device_status", "Состояние устройства, сети, батареи и хранилища"))
        put(schema("web_search", "Найти актуальную информацию в интернете. Возвращает заголовки, ссылки и фрагменты.", props("query" to "string", "limit" to "integer"), listOf("query")))
        put(schema("web_fetch", "Прочитать содержимое публичной HTTPS-страницы по URL", props("url" to "string"), listOf("url")))
        put(schema("list_ssh_profiles", "Список доступных SSH-профилей без секретов"))
        put(schema("ssh_exec", "Выполнить команду на сервере из SSH-профиля. Всегда требует подтверждения пользователя.", props("profile" to "string", "command" to "string"), listOf("profile", "command")))
        put(schema("http_request", "Выполнить HTTPS-запрос для проверки API. Требует подтверждения.", props("url" to "string", "method" to "string", "body" to "string"), listOf("url")))
        put(schema("dns_lookup", "Получить IP-адреса публичного домена", props("host" to "string"), listOf("host")))
        put(schema("calculate", "Посчитать арифметическое выражение с + - * / % и скобками", props("expression" to "string"), listOf("expression")))
        put(schema("json_format", "Проверить и красиво отформатировать JSON", props("json" to "string"), listOf("json")))
        put(schema("hash_text", "Вычислить SHA-256 текста", props("text" to "string"), listOf("text")))
        put(schema("base64", "Кодировать или декодировать Base64", props("operation" to "string", "text" to "string"), listOf("operation", "text")))
        put(schema("generate_uuid", "Создать случайный UUID"))
        put(schema("list_notes", "Найти локальные заметки", props("query" to "string")))
        put(schema("create_note", "Создать локальную заметку после подтверждения", props("title" to "string", "body" to "string"), listOf("title", "body")))
        put(schema("delete_note", "Удалить заметку по id после подтверждения", props("id" to "integer"), listOf("id")))
        put(schema("open_url", "Открыть публичную HTTPS-ссылку в браузере", props("url" to "string"), listOf("url")))
        put(schema("open_map", "Показать место на карте", props("query" to "string"), listOf("query")))
        put(schema("dial_phone", "Открыть набор номера без совершения звонка", props("number" to "string"), listOf("number")))
        put(schema("compose_email", "Подготовить письмо", props("to" to "string", "subject" to "string", "body" to "string"), listOf("to")))
        put(schema("share_text", "Открыть меню отправки текста", props("text" to "string"), listOf("text")))
        put(schema("clipboard_read", "Прочитать текст из буфера обмена после подтверждения"))
        put(schema("clipboard_write", "Скопировать текст в буфер после подтверждения", props("text" to "string"), listOf("text")))
        put(schema("set_alarm", "Подготовить будильник", props("hour" to "integer", "minute" to "integer", "label" to "string"), listOf("hour", "minute")))
        put(schema("set_timer", "Подготовить таймер", props("seconds" to "integer", "label" to "string"), listOf("seconds")))
        put(schema("add_calendar_event", "Подготовить событие календаря", props("title" to "string", "start_epoch_ms" to "integer", "end_epoch_ms" to "integer", "location" to "string"), listOf("title", "start_epoch_ms")))
        put(schema("open_app_settings", "Открыть настройки приложения Jarvis"))
    }

    fun execute(name: String, args: JSONObject, confirmed: Boolean = false): ToolResult = try {
        when (name) {
            "get_current_time" -> done(ZonedDateTime.now().toString())
            "device_status" -> done(deviceStatus())
            "web_search" -> done(web.search(args.string("query"), args.optInt("limit", 5)).joinToString("\n\n") { "${it.title}\n${it.url}\n${it.snippet}" }.ifBlank { "Ничего не найдено" })
            "web_fetch" -> done(web.fetch(args.string("url")))
            "list_ssh_profiles" -> done(profiles.all().joinToString("\n") { "${it.id}: ${it.name} — ${it.username}@${it.host}:${it.port}" }.ifBlank { "SSH-профили не настроены" })
            "ssh_exec" -> dangerous(confirmed, "SSH ${args.string("profile")}: ${args.string("command")}") {
                val profile = profiles.find(args.string("profile")) ?: error("SSH-профиль не найден")
                val result = ssh.execute(profile, args.string("command"))
                "exit=${result.exitCode}\nfingerprint=${result.fingerprint}\n${result.output}"
            }
            "http_request" -> dangerous(confirmed, "${args.optString("method", "GET").uppercase()} ${args.string("url")}") { simpleHttp(args) }
            "dns_lookup" -> done(InetAddress.getAllByName(args.string("host")).joinToString("\n") { it.hostAddress ?: "" })
            "calculate" -> done(ExpressionParser(args.string("expression")).parse().toString())
            "json_format" -> done(formatJson(args.string("json")))
            "hash_text" -> done(MessageDigest.getInstance("SHA-256").digest(args.string("text").toByteArray()).joinToString("") { "%02x".format(it) })
            "base64" -> done(if (args.string("operation").equals("decode", true)) String(Base64.getDecoder().decode(args.string("text")), Charsets.UTF_8) else Base64.getEncoder().encodeToString(args.string("text").toByteArray()))
            "generate_uuid" -> done(UUID.randomUUID().toString())
            "list_notes" -> done(notes.list(args.optString("query")).joinToString("\n\n") { "#${it.id} ${it.title}\n${it.body}" }.ifBlank { "Заметок не найдено" })
            "create_note" -> dangerous(confirmed, "Создать заметку «${args.string("title")}"?") { "Создана заметка #${notes.create(args.string("title"), args.string("body"))}" }
            "delete_note" -> dangerous(confirmed, "Удалить заметку #${args.getLong("id") }?") { if (notes.delete(args.getLong("id"))) "Заметка удалена" else "Заметка не найдена" }
            "open_url" -> confirmIntent(confirmed, "Открыть ${args.string("url")}?", Intent(Intent.ACTION_VIEW, publicUri(args.string("url"))))
            "open_map" -> confirmIntent(confirmed, "Показать на карте: ${args.string("query")}?", Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(args.string("query"))}")))
            "dial_phone" -> {
                val number = args.string("number").filter { it.isDigit() || it in "+*#" }
                require(number.isNotBlank()) { "Некорректный номер" }
                confirmIntent(confirmed, "Открыть набор номера $number?", Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
            }
            "compose_email" -> confirmIntent(confirmed, "Подготовить письмо для ${args.string("to")}?", Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(args.string("to"))}")).putExtra(Intent.EXTRA_SUBJECT, args.optString("subject")).putExtra(Intent.EXTRA_TEXT, args.optString("body")))
            "share_text" -> confirmIntent(confirmed, "Открыть меню отправки?", Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, args.string("text")), null))
            "clipboard_read" -> dangerous(confirmed, "Разрешить Jarvis прочитать буфер обмена?") { (context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()).orEmpty().ifBlank { "Буфер пуст" } }
            "clipboard_write" -> dangerous(confirmed, "Скопировать текст в буфер обмена?") { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Jarvis", args.string("text"))); "Текст скопирован" }
            "set_alarm" -> confirmIntent(confirmed, "Создать будильник ${args.getInt("hour").toString().padStart(2, '0')}:${args.getInt("minute").toString().padStart(2, '0')}?", Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, args.getInt("hour")).putExtra(AlarmClock.EXTRA_MINUTES, args.getInt("minute")).putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("label")))
            "set_timer" -> confirmIntent(confirmed, "Запустить таймер на ${args.getInt("seconds")} сек.?", Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, args.getInt("seconds")).putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("label")))
            "add_calendar_event" -> confirmIntent(confirmed, "Добавить событие «${args.string("title")}"?", Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.Events.TITLE, args.string("title")).putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, args.getLong("start_epoch_ms")).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, args.optLong("end_epoch_ms", args.getLong("start_epoch_ms") + 3_600_000)).putExtra(CalendarContract.Events.EVENT_LOCATION, args.optString("location")))
            "open_app_settings" -> confirmIntent(confirmed, "Открыть настройки Jarvis?", Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            else -> done("Ошибка: неизвестный инструмент $name")
        }
    } catch (error: Exception) {
        done("Ошибка инструмента $name: ${error.message ?: error.javaClass.simpleName}")
    }

    private fun done(content: String) = ToolResult(content.take(64_000))
    private fun dangerous(confirmed: Boolean, prompt: String, action: () -> String) = if (!confirmed) ToolResult("", true, prompt) else done(action())
    private fun confirmIntent(confirmed: Boolean, prompt: String, intent: Intent) = dangerous(confirmed, prompt) { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "Действие открыто на устройстве" }
    private fun publicUri(raw: String): Uri { val uri = Uri.parse(raw); require(uri.scheme == "https" && !uri.host.isNullOrBlank()) { "Разрешены только HTTPS-ссылки" }; return uri }

    private fun deviceStatus(): String {
        val battery = context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val network = when { caps == null -> "offline"; caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"; caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"; caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"; else -> "connected" }
        val storage = Environment.getDataDirectory()
        return "${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}; battery $battery%; network $network; storage ${(storage.freeSpace / 1_073_741_824.0).toInt()} GB free; now ${Instant.now()}"
    }

    private fun simpleHttp(args: JSONObject): String {
        val uri = publicUri(args.string("url"))
        val method = args.optString("method", "GET").uppercase()
        require(method in setOf("GET", "POST", "PUT", "PATCH", "DELETE")) { "Метод не поддерживается" }
        val connection = java.net.URL(uri.toString()).openConnection() as java.net.HttpURLConnection
        return try {
            connection.requestMethod = method; connection.connectTimeout = 15_000; connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json, text/plain, */*")
            val body = args.optString("body")
            if (body.isNotBlank() && method !in setOf("GET", "DELETE")) { connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json; charset=utf-8"); connection.outputStream.use { it.write(body.toByteArray()) } }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            "HTTP $status\n${connection.headerFields.filterKeys { it != null }.entries.joinToString("\n") { "${it.key}: ${it.value.joinToString()}" }}\n\n${text.take(30_000)}"
        } finally { connection.disconnect() }
    }

    private fun formatJson(raw: String): String = runCatching { JSONObject(raw).toString(2) }.recoverCatching { JSONArray(raw).toString(2) }.getOrElse { error("Некорректный JSON: ${it.message}") }
    private fun JSONObject.string(name: String) = getString(name).trim()
    private fun props(vararg values: Pair<String, String>) = JSONObject().apply { values.forEach { (name, type) -> put(name, JSONObject().put("type", type)) } }
    private fun schema(name: String, description: String, properties: JSONObject = JSONObject(), required: List<String> = emptyList()) = JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", description).put("parameters", JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(required)).put("additionalProperties", false)))
}

internal class ExpressionParser(private val source: String) {
    private var index = 0
    fun parse(): Double { val value = expression(); skip(); require(index == source.length) { "Неожиданный символ в позиции ${index + 1}" }; return value }
    private fun expression(): Double { var value = term(); while (true) { skip(); value = when { take('+') -> value + term(); take('-') -> value - term(); else -> return value } } }
    private fun term(): Double { var value = factor(); while (true) { skip(); value = when { take('*') -> value * factor(); take('/') -> value / factor(); take('%') -> value % factor(); else -> return value } } }
    private fun factor(): Double { skip(); if (take('+')) return factor(); if (take('-')) return -factor(); if (take('(')) { val value = expression(); require(take(')')) { "Нет закрывающей скобки" }; return value }; val start = index; while (index < source.length && (source[index].isDigit() || source[index] == '.')) index++; require(start != index) { "Ожидалось число" }; return source.substring(start, index).toDouble() }
    private fun take(char: Char): Boolean { skip(); return if (index < source.length && source[index] == char) { index++; true } else false }
    private fun skip() { while (index < source.length && source[index].isWhitespace()) index++ }
}
