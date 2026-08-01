package app.jarvis.net

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val source: String = "",
    val publishedAt: String = "",
    val provider: String = ""
)

data class SearchOutcome(val results: List<SearchResult>, val provider: String, val diagnostics: List<String>, val cached: Boolean = false)

class WebService {
    private data class CacheEntry(val time: Long, val outcome: SearchOutcome)
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    fun search(query: String, limit: Int = 5, kind: String = "auto"): SearchOutcome {
        require(query.isNotBlank()) { "Пустой поисковый запрос" }
        val news = kind.equals("news", true) || (kind.equals("auto", true) && NEWS_WORDS.any { query.contains(it, true) })
        val cacheKey = "${if (news) "news" else "web"}:${query.lowercase(Locale.ROOT)}:${limit.coerceIn(1, 8)}"
        cache[cacheKey]?.takeIf { System.currentTimeMillis() - it.time < if (news) 300_000 else 900_000 }?.let { return it.outcome.copy(cached = true) }
        val diagnostics = mutableListOf<String>()
        val attempts: List<Pair<String, () -> List<SearchResult>>> = if (news) listOf(
            "Google News RSS" to { googleNews(query, limit) },
            "Bing News RSS" to { bingRss(query, limit, news = true) },
            "Bing Web RSS" to { bingRss(query, limit, news = false) }
        ) else listOf(
            "Bing Web RSS" to { bingRss(query, limit, news = false) },
            "DuckDuckGo" to { duckDuckGo(query, limit) }
        )
        attempts.forEach { (provider, call) ->
            val results = runCatching { call() }.onFailure { diagnostics += "$provider: ${it.message ?: it.javaClass.simpleName}" }.getOrDefault(emptyList())
            if (results.isNotEmpty()) {
                val outcome = SearchOutcome(results.distinctBy { it.url }.take(limit.coerceIn(1, 8)), provider, diagnostics)
                cache[cacheKey] = CacheEntry(System.currentTimeMillis(), outcome)
                return outcome
            }
            diagnostics += "$provider: результатов нет"
        }
        return SearchOutcome(emptyList(), "none", diagnostics)
    }

    fun fetch(url: String, maxChars: Int = 10_000): String {
        require(isPublicHttps(url)) { "Разрешены только публичные HTTPS-адреса" }
        val document = fetchFollowingSafeRedirects(url)
        document.select("script,style,noscript,svg,nav,footer,form,[hidden]").remove()
        val title = document.title().ifBlank { url }
        val text = (document.selectFirst("article") ?: document.body()).text().replace(Regex("\\s+"), " ").trim()
        return "Заголовок: $title\nURL: ${document.location()}\n\n${text.take(maxChars.coerceIn(1_000, 15_000))}"
    }

    private fun googleNews(query: String, limit: Int): List<SearchResult> {
        val locale = Locale.getDefault()
        val language = locale.language.takeIf { it.length == 2 } ?: "en"
        val country = locale.country.takeIf { it.length == 2 } ?: "US"
        val url = "https://news.google.com/rss/search?q=${encode(query)}&hl=$language&gl=$country&ceid=$country:$language"
        return rss(url, "Google News RSS", limit)
    }

    private fun bingRss(query: String, limit: Int, news: Boolean): List<SearchResult> {
        val base = if (news) "https://www.bing.com/news/search" else "https://www.bing.com/search"
        return rss("$base?format=rss&q=${encode(query)}", if (news) "Bing News RSS" else "Bing Web RSS", limit)
    }

    private fun rss(url: String, provider: String, limit: Int): List<SearchResult> {
        val document = Jsoup.connect(url).userAgent(USER_AGENT).timeout(20_000).maxBodySize(1_500_000).parser(Parser.xmlParser()).get()
        return document.select("item").take(limit.coerceIn(1, 8)).mapNotNull { item ->
            val target = unwrapSearchRedirect(item.selectFirst("link")?.text().orEmpty())
            if (!isPublicHttps(target)) return@mapNotNull null
            SearchResult(
                title = item.selectFirst("title")?.text().orEmpty(),
                url = target,
                snippet = item.selectFirst("description")?.text().orEmpty(),
                source = (item.getElementsByTag("source").firstOrNull() ?: item.getElementsByTag("News:Source").firstOrNull())?.text().orEmpty(),
                publishedAt = item.selectFirst("pubDate")?.text().orEmpty(),
                provider = provider
            )
        }
    }

    private fun duckDuckGo(query: String, limit: Int): List<SearchResult> {
        val response = Jsoup.connect("https://html.duckduckgo.com/html/").data("q", query.take(500)).userAgent(USER_AGENT).timeout(20_000).maxBodySize(1_500_000).method(org.jsoup.Connection.Method.POST).execute()
        require(response.statusCode() == 200 && !response.body().contains("anomaly", true) && !response.body().contains("captcha", true)) { "поисковик запросил проверку" }
        val document = response.parse()
        return document.select(".result").take(limit.coerceIn(1, 8)).mapNotNull { result ->
            val link = result.selectFirst(".result__a") ?: return@mapNotNull null
            val target = unwrapSearchRedirect(link.absUrl("href"))
            if (!isPublicHttps(target)) null else SearchResult(link.text(), target, result.selectFirst(".result__snippet")?.text().orEmpty(), provider = "DuckDuckGo")
        }
    }

    private fun unwrapSearchRedirect(url: String): String = runCatching {
        val uri = URI(url)
        val params = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
            val pieces = part.split('=', limit = 2)
            if (pieces.size == 2) pieces[0] to URLDecoder.decode(pieces[1], "UTF-8") else null
        }.toMap()
        when {
            params["uddg"] != null -> params.getValue("uddg")
            uri.host?.contains("bing.com", true) == true && params["url"] != null -> params.getValue("url")
            else -> url
        }
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
    private fun encode(value: String) = URLEncoder.encode(value.take(500), "UTF-8")

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Android) Jarvis/0.3"
        private val NEWS_WORDS = listOf("новост", "свеж", "сегодня", "последн", "news", "latest", "today", "breaking")
    }
}
