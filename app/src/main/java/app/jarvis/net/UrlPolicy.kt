package app.jarvis.net

import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.MalformedURLException
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.net.UnknownHostException

/** Адрес отклонён политикой: не HTTPS, синтаксис сломан или цель во внутренней сети. */
class UnsafeUrlException(message: String) : IllegalArgumentException(message)

/**
 * Единая проверка адресов для всех сетевых инструментов.
 *
 * Раньше каждый инструмент проверял URL по-своему: web_fetch отбрасывал приватные адреса,
 * а http_request довольствовался префиксом "https://". Теперь правило одно на всех.
 */
object UrlPolicy {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    const val ACCEPT_LANGUAGE = "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7"

    /**
     * Приводит пользовательский или добытый из поиска адрес к валидному URI.
     * Пробелы, кириллица в домене и отсутствующая схема больше не роняют инструмент.
     */
    fun parse(raw: String): URI {
        val trimmed = raw.trim().trim('<', '>', '"', '\'')
        if (trimmed.isBlank()) throw UnsafeUrlException("Пустой URL")
        val withScheme = if (SCHEME.containsMatchIn(trimmed)) trimmed else "https://$trimmed"
        runCatching { URI(withScheme) }.getOrNull()?.takeIf { it.host != null }?.let { return it.normalize() }
        val url = try {
            URL(withScheme)
        } catch (error: MalformedURLException) {
            throw UnsafeUrlException("Некорректный URL: ${trimmed.take(200)}")
        }
        val host = runCatching { IDN.toASCII(url.host.orEmpty()) }.getOrDefault(url.host.orEmpty())
        return try {
            URI(url.protocol, url.userInfo, host, url.port, url.path.ifBlank { "/" }, url.query, url.ref).normalize()
        } catch (error: URISyntaxException) {
            throw UnsafeUrlException("Некорректный URL: ${trimmed.take(200)}")
        }
    }

    /**
     * Проверяет, что адрес — публичный HTTPS.
     *
     * @throws UnsafeUrlException если схема не HTTPS или хост резолвится во внутреннюю сеть.
     * @throws UnknownHostException если DNS не ответил — это временный сбой, а не запрет.
     */
    fun requirePublicHttps(raw: String): URI = requirePublicHttps(parse(raw))

    fun requirePublicHttps(uri: URI): URI {
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            throw UnsafeUrlException("Разрешены только HTTPS-адреса, получено: ${uri.scheme ?: "без схемы"}")
        }
        if (uri.userInfo != null) throw UnsafeUrlException("Логин и пароль в URL не поддерживаются")
        val host = uri.host
        if (host.isNullOrBlank()) throw UnsafeUrlException("В адресе нет домена")
        val addresses = try {
            InetAddress.getAllByName(host)
        } catch (error: UnknownHostException) {
            throw UnknownHostException("Не удалось определить IP-адрес $host")
        }
        if (addresses.isEmpty()) throw UnknownHostException("Не удалось определить IP-адрес $host")
        addresses.firstOrNull { isPrivate(it) }?.let {
            throw UnsafeUrlException("$host ведёт во внутреннюю сеть (${it.hostAddress}); такие адреса запрещены")
        }
        return uri
    }

    fun isPublicHttps(raw: String): Boolean = runCatching { requirePublicHttps(raw) }.isSuccess

    /** Разворачивает Location относительно текущего адреса, включая схемо-относительные `//host/path`. */
    fun resolveRedirect(current: URI, location: String): URI {
        val target = location.trim()
        if (target.isBlank()) throw UnsafeUrlException("Сервер прислал пустое перенаправление")
        if (target.startsWith("//")) return parse("${current.scheme}:$target")
        val resolved = runCatching { current.resolve(target) }.getOrNull()
        if (resolved != null && resolved.host != null) return resolved.normalize()
        return parse(target)
    }

    /**
     * Loopback, приватные диапазоны RFC1918, link-local, CGNAT, multicast и ULA IPv6.
     */
    fun isPrivate(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) {
            return true
        }
        val bytes = address.address
        return when (address) {
            // 100.64.0.0/10 — операторский CGNAT, откуда часто доступны админки роутеров.
            is Inet4Address -> (bytes[0].toInt() and 0xFF) == 100 && (bytes[1].toInt() and 0xFF) in 64..127
            // fc00::/7 — unique local addresses.
            is Inet6Address -> (bytes[0].toInt() and 0xFE) == 0xFC
            else -> false
        }
    }

    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://")
}
