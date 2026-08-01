package app.jarvis.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime

data class ToolResult(val text: String, val requiresConfirmation: Boolean = false, val action: (() -> Unit)? = null)

class ToolRegistry(private val context: Context) {
    fun schemas() = JSONArray().apply {
        put(schema("get_current_time", "Текущие локальные дата и время"))
        put(schema("get_battery_level", "Уровень заряда батареи"))
        put(schema("get_device_info", "Модель устройства и версия Android"))
        put(schema("open_url", "Открыть HTTPS-ссылку в браузере", JSONObject().put("url", JSONObject().put("type", "string")), listOf("url")))
        put(schema("open_map", "Показать место на карте", JSONObject().put("query", JSONObject().put("type", "string")), listOf("query")))
        put(schema("dial_phone", "Открыть набор номера без совершения звонка", JSONObject().put("number", JSONObject().put("type", "string")), listOf("number")))
        put(schema("compose_email", "Подготовить письмо", JSONObject().put("to", JSONObject().put("type", "string")).put("subject", JSONObject().put("type", "string")).put("body", JSONObject().put("type", "string")), listOf("to")))
        put(schema("share_text", "Открыть системное меню отправки текста", JSONObject().put("text", JSONObject().put("type", "string")), listOf("text")))
        put(schema("open_app_settings", "Открыть системные настройки этого приложения"))
    }

    fun execute(name: String, args: JSONObject): ToolResult = when (name) {
        "get_current_time" -> ToolResult(ZonedDateTime.now().toString())
        "get_battery_level" -> {
            val manager = context.getSystemService(BatteryManager::class.java)
            ToolResult("${manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%")
        }
        "get_device_info" -> ToolResult("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        "open_url" -> {
            val uri = Uri.parse(args.optString("url"))
            if (uri.scheme != "https") ToolResult("Отклонено: разрешены только HTTPS-ссылки")
            else ToolResult("Открыть ${uri.host}?", true) {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        "open_map" -> intentResult("Показать на карте: ${args.optString("query")}", Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(args.optString("query"))}")))
        "dial_phone" -> {
            val number = args.optString("number").filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
            if (number.isBlank()) ToolResult("Отклонено: некорректный номер") else intentResult("Открыть набор номера $number?", Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
        }
        "compose_email" -> intentResult("Подготовить письмо для ${args.optString("to")} ?", Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(args.optString("to"))}")).putExtra(Intent.EXTRA_SUBJECT, args.optString("subject")).putExtra(Intent.EXTRA_TEXT, args.optString("body")))
        "share_text" -> intentResult("Открыть меню отправки текста?", Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, args.optString("text")), null))
        "open_app_settings" -> intentResult("Открыть настройки приложения?", Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        else -> ToolResult("Неизвестный инструмент: $name")
    }

    private fun intentResult(label: String, intent: Intent) = ToolResult(label, true) {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun schema(name: String, description: String, properties: JSONObject = JSONObject(), required: List<String> = emptyList()) = JSONObject()
        .put("type", "function")
        .put("function", JSONObject().put("name", name).put("description", description).put("parameters", JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(required))))
}
