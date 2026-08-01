package app.jarvis.net

import org.jsoup.Jsoup
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

data class SearchResult(val title: String, val url: String, val snippet: String)

class WebService {
    fun search(query: String, limit: Int = 5): List<SearchResult> {
        require(query.isNotBlank()) { "Пустой поисковый запрос" }
        val document = Jsoup.connect("https://html.duckduckgo.com/html/")
            .data("q", query.take(500))
            .userAgent(USER_AGENT)
            .timeout(20_000)
            .maxBodySize(1_500_000)
            .post()
        return document.select(".result").take(limit.coerceIn(1, 8)).mapNotNull { result ->
            val link = result.selectFirst(".result__a") ?: return@mapNotNull null
            val target = unwrapDuckDuckGo(link.absUrl("href"))
            if (!isPublicHttps(target)) return@mapNotNull null
            SearchResult(link.text(), target, result.selectFirst(".result__snippet")?.text().orEmpty())
        }
    }

    fun fetch(url: String, maxChars: Int = 18_000): String {
        require(isPublicHttps(url)) { "Разрешены только публичные HTTPS-адреса" }
        val document = fetchFollowingSafeRedirects(url)
        document.select("script,style,noscript,svg,nav,footer,form").remove()
        val title = document.title().ifBlank { url }
        val text = (document.selectFirst("article") ?: document.body()).text().replace(Regex("\\s+"), " ").trim()
        return "Заголовок: $title\nURL: ${document.location()}\n\n${text.take(maxChars.coerceIn(1_000, 30_000))}"
    }

    private fun unwrapDuckDuckGo(url: String): String = runCatching {
        val uri = URI(url)
        val value = uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("uddg=") }?.substringAfter('=')
        if (value == null) url else java.net.URLDecoder.decode(value, "UTF-8")
    }.getOrDefault(url)

    private fun fetchFollowingSafeRedirects(start: String): org.jsoup.nodes.Document {
        var current = start
        repeat(6) {
            require(isPublicHttps(current)) { "Перенаправление ведёт на непубличный адрес" }
            val response = Jsoup.connect(current).userAgent(USER_AGENT).timeout(25_000).followRedirects(false).maxBodySize(2_500_000).execute()
            if (response.statusCode() !in 300..399) return response.parse()
            val location = response.header("Location") ?: error("Пустое перенаправление")
            current = URI(current).resolve(location).toString()
        }
        error("Слишком много перенаправлений")
    }

    private fun isPublicHttps(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        if (uri.scheme != "https" || uri.userInfo != null || uri.host.isNullOrBlank()) return false
        InetAddress.getAllByName(uri.host).all { address -> !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress && !address.isSiteLocalAddress && !isUniqueLocalV6(address) }
    }.getOrDefault(false)

    private fun isUniqueLocalV6(address: InetAddress): Boolean = address is Inet6Address && (address.address[0].toInt() and 0xFE) == 0xFC

    companion object { private const val USER_AGENT = "Mozilla/5.0 (Android) Jarvis/0.2" }
}
