package app.jarvis.net

import org.jsoup.Connection
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.jsoup.UnsupportedMimeTypeException
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.CancellationException
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
            "Bing Web RSS" to { bingRss(query, limit, news = false) },
            "DuckDuckGo Lite" to { duckDuckGo(query, limit) }
        ) else listOf(
            "Bing Web RSS" to { bingRss(query, limit, news = false) },
            "DuckDuckGo Lite" to { duckDuckGo(query, limit) },
            "Google News RSS" to { googleNews(query, limit) }
        )
        attempts.forEach { (provider, call) ->
            val results = runCatching { call() }
                .onFailure { if (it is CancellationException) throw it }
                .onFailure { diagnostics += "$provider: ${describe(it)}" }
                .getOrDefault(emptyList())
            if (results.isNotEmpty()) {
                val outcome = SearchOutcome(results.distinctBy { it.url }.take(limit.coerceIn(1, 8)), provider, diagnostics)
                cache[cacheKey] = CacheEntry(System.currentTimeMillis(), outcome)
                return outcome
            }
            if (diagnostics.none { it.startsWith("$provider:") }) diagnostics += "$provider: результатов нет"
        }
        return SearchOutcome(emptyList(), "none", diagnostics)
    }

    /**
     * Читает публичную HTTPS-страницу.
     *
     * Устойчивость важнее строгости: временные сбои и 5xx повторяются с задержкой,
     * не-HTML ответы (JSON, текст) возвращаются как есть, а причина отказа
     * формулируется так, чтобы агент понял, повторять ли попытку.
     *
     * @throws IOException при временном сбое — вызывающий код помечает результат retryable.
     * @throws UnsafeUrlException если адрес запрещён политикой.
     */
    fun fetch(url: String, maxChars: Int = 10_000): String {
        val page = load(UrlPolicy.requirePublicHttps(url))
        return render(page, maxChars.coerceIn(1_000, 20_000))
    }

    private data class Page(val uri: URI, val contentType: String, val body: String, val document: Document?)

    private fun load(start: URI): Page {
        var current = start
        var attempt = 0
        var hops = 0
        val visited = mutableSetOf(start.toString())
        while (true) {
            val response = try {
                execute(current)
            } catch (error: CancellationException) {
                throw error
            } catch (error: UnsupportedMimeTypeException) {
                throw IllegalStateException("Страница отдаёт неподдерживаемый тип ${error.mimeType}")
            } catch (error: HttpStatusException) {
                // ignoreHttpErrors выключает этот путь, но редкие обёртки Jsoup всё равно его используют.
                throw IllegalStateException("Сервер ответил HTTP ${error.statusCode}")
            } catch (error: IOException) {
                if (attempt >= MAX_ATTEMPTS - 1) {
                    throw IOException("Не удалось загрузить ${current.host}: ${describe(error)}", error)
                }
                backoff(attempt++)
                continue
            }
            val status = response.statusCode()
            when {
                status in 200..299 -> return page(current, response)
                status in 300..399 -> {
                    if (hops >= MAX_REDIRECTS) throw IllegalStateException("Слишком много перенаправлений")
                    val location = response.header("Location")
                        ?: throw IllegalStateException("Сервер ответил $status без адреса перенаправления")
                    val next = UrlPolicy.requirePublicHttps(UrlPolicy.resolveRedirect(current, location))
                    if (!visited.add(next.toString())) throw IllegalStateException("Циклическое перенаправление на $next")
                    current = next
                    hops++
                }
                status == 408 || status == 425 || status == 429 || status >= 500 -> {
                    if (attempt >= MAX_ATTEMPTS - 1) {
                        throw IOException("Сервер ${current.host} отвечает HTTP $status; попробуйте позже")
                    }
                    backoff(attempt++, response.header("Retry-After")?.trim()?.toLongOrNull()?.times(1_000))
                }
                status == 401 || status == 403 || status == 407 || status == 451 ->
                    throw IllegalStateException("Доступ к странице закрыт (HTTP $status): требуется авторизация или сработала защита от ботов")
                status == 404 || status == 410 -> throw IllegalStateException("Страница не найдена (HTTP $status)")
                else -> throw IllegalStateException("Сервер ответил HTTP $status")
            }
        }
    }

    private fun execute(uri: URI): Connection.Response = Jsoup.connect(uri.toString())
        .userAgent(UrlPolicy.USER_AGENT)
        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,text/plain;q=0.8,*/*;q=0.7")
        .header("Accept-Language", UrlPolicy.ACCEPT_LANGUAGE)
        .timeout(REQUEST_TIMEOUT_MS)
        .maxBodySize(MAX_BODY_BYTES)
        .followRedirects(false)
        .ignoreHttpErrors(true)
        .ignoreContentType(true)
        .execute()

    private fun page(uri: URI, response: Connection.Response): Page {
        val contentType = response.contentType().orEmpty().lowercase(Locale.ROOT)
        val markup = contentType.isBlank() || contentType.contains("html") || contentType.contains("xml")
        return Page(
            uri = uri,
            contentType = contentType,
            body = if (markup) "" else response.body(),
            document = if (markup) runCatching { response.parse() }.getOrNull() else null
        )
    }

    private fun render(page: Page, maxChars: Int): String {
        val document = page.document
        if (document == null) {
            val textual = page.contentType.startsWith("text/") || page.contentType.contains("json") ||
                page.contentType.contains("javascript") || page.contentType.contains("csv")
            if (!textual) throw IllegalStateException("Ссылка ведёт на файл типа ${page.contentType.ifBlank { "неизвестного" }}, а не на страницу")
            return "URL: ${page.uri}\nТип: ${page.contentType}\n\n${page.body.take(maxChars)}"
        }
        document.select("script,style,noscript,svg,iframe,nav,footer,aside,form,header,template,[hidden],[aria-hidden=true]").remove()
        val title = document.title().ifBlank { page.uri.toString() }
        val root = document.selectFirst("article")
            ?: document.selectFirst("main")
            ?: document.selectFirst("[role=main]")
            ?: document.body()
        val text = root?.text().orEmpty().replace(WHITESPACE, " ").trim()
        val description = document.selectFirst("meta[name=description]")?.attr("content").orEmpty()
            .ifBlank { document.selectFirst("meta[property=og:description]")?.attr("content").orEmpty() }
        val content = when {
            text.length >= MIN_USEFUL_CHARS -> text
            description.isNotBlank() -> "$description\n\n$text\n\n[Основной текст страницы отдаётся скриптами; выше — описание из метаданных.]"
            text.isNotBlank() -> "$text\n\n[Страница почти пуста без выполнения JavaScript.]"
            else -> throw IllegalStateException("Страница не содержит читаемого текста без выполнения JavaScript")
        }
        return "Заголовок: $title\nURL: ${page.uri}\n\n${content.take(maxChars)}"
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
        val document = Jsoup.connect(url)
            .userAgent(UrlPolicy.USER_AGENT)
            .header("Accept-Language", UrlPolicy.ACCEPT_LANGUAGE)
            .timeout(REQUEST_TIMEOUT_MS)
            .maxBodySize(MAX_BODY_BYTES)
            .parser(Parser.xmlParser())
            .get()
        return document.select("item").take(limit.coerceIn(1, 8)).mapNotNull { item ->
            val target = unwrapSearchRedirect(item.selectFirst("link")?.text().orEmpty())
            if (!UrlPolicy.isPublicHttps(target)) return@mapNotNull null
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
        val response = Jsoup.connect("https://lite.duckduckgo.com/lite/")
            .data("q", query.take(500))
            .userAgent(UrlPolicy.USER_AGENT)
            .header("Accept-Language", UrlPolicy.ACCEPT_LANGUAGE)
            .timeout(REQUEST_TIMEOUT_MS)
            .maxBodySize(MAX_BODY_BYTES)
            .method(Connection.Method.POST)
            .execute()
        require(response.statusCode() == 200) { "HTTP ${response.statusCode()}" }
        val body = response.body()
        require(!body.contains("anomaly", true) && !body.contains("captcha", true)) { "поисковик запросил проверку" }
        val document = response.parse()
        val links = document.select("a.result-link, a.result__a, .result__title a")
        return links.take(limit.coerceIn(1, 8)).mapNotNull { link ->
            val target = unwrapSearchRedirect(link.absUrl("href").ifBlank { link.attr("href") })
            if (!UrlPolicy.isPublicHttps(target)) return@mapNotNull null
            val snippet = link.closest("tr")?.nextElementSibling()?.selectFirst(".result-snippet")?.text()
                ?: link.closest(".result")?.selectFirst(".result__snippet")?.text()
            SearchResult(link.text(), target, snippet.orEmpty(), provider = "DuckDuckGo Lite")
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

    private fun backoff(attempt: Int, retryAfterMillis: Long? = null) {
        val delay = retryAfterMillis?.coerceIn(500L, 10_000L) ?: (RETRY_BASE_MS shl attempt.coerceIn(0, 3))
        try {
            Thread.sleep(delay)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Чтение страницы прервано").apply { initCause(error) }
        }
    }

    private fun describe(error: Throwable): String = when (error) {
        is InterruptedIOException -> "сервер не ответил вовремя"
        else -> error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    }

    private fun encode(value: String) = URLEncoder.encode(value.take(500), "UTF-8")

    companion object {
        private const val REQUEST_TIMEOUT_MS = 30_000
        private const val MAX_BODY_BYTES = 3_000_000
        private const val MAX_ATTEMPTS = 3
        private const val MAX_REDIRECTS = 8
        private const val RETRY_BASE_MS = 700L
        private const val MIN_USEFUL_CHARS = 200
        private val WHITESPACE = Regex("\\s+")
        private val NEWS_WORDS = listOf("новост", "свеж", "сегодня", "последн", "news", "latest", "today", "breaking")
    }
}
