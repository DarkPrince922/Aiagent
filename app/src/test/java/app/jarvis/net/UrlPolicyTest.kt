package app.jarvis.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.URI

class UrlPolicyTest {
    @Test fun addsSchemeAndKeepsQuery() {
        assertEquals("https://example.com/a?b=1", UrlPolicy.parse("example.com/a?b=1").toString())
        assertEquals("https://example.com/a?b=1", UrlPolicy.parse("  https://example.com/a?b=1  ").toString())
    }

    @Test fun survivesUrlsThatBreakStrictParsing() {
        // Ссылки из поисковой выдачи регулярно содержат пробелы и кавычки — раньше это
        // роняло web_fetch с сообщением «разрешены только публичные HTTPS-адреса».
        val spaced = UrlPolicy.parse("https://example.com/путь с пробелом")
        assertEquals("example.com", spaced.host)
        assertTrue(spaced.toString().startsWith("https://example.com/"))
        assertEquals("example.com", UrlPolicy.parse("<https://example.com/x>").host)
    }

    @Test fun rejectsBlankAndNonHttps() {
        assertThrows(UnsafeUrlException::class.java) { UrlPolicy.parse("   ") }
        assertThrows(UnsafeUrlException::class.java) { UrlPolicy.requirePublicHttps("http://example.com") }
        assertThrows(UnsafeUrlException::class.java) { UrlPolicy.requirePublicHttps("ftp://example.com") }
    }

    @Test fun rejectsCredentialsInUrl() {
        assertThrows(UnsafeUrlException::class.java) { UrlPolicy.requirePublicHttps("https://user:secret@example.com/") }
    }

    @Test fun rejectsInternalNetworkTargets() {
        listOf(
            "https://127.0.0.1/admin",
            "https://10.0.0.1/",
            "https://172.16.0.1/",
            "https://192.168.1.1/",
            "https://169.254.169.254/latest/meta-data/",
            "https://100.64.1.1/",
            "https://0.0.0.0/",
            "https://[::1]/"
        ).forEach { url ->
            assertThrows("Ожидался отказ для $url", UnsafeUrlException::class.java) {
                UrlPolicy.requirePublicHttps(url)
            }
        }
    }

    @Test fun classifiesLiteralAddresses() {
        assertTrue(UrlPolicy.isPrivate(InetAddress.getByName("192.168.0.5")))
        assertTrue(UrlPolicy.isPrivate(InetAddress.getByName("100.127.255.255")))
        assertTrue(UrlPolicy.isPrivate(InetAddress.getByName("fd00::1")))
        assertFalse(UrlPolicy.isPrivate(InetAddress.getByName("8.8.8.8")))
        assertFalse(UrlPolicy.isPrivate(InetAddress.getByName("100.63.255.255")))
        assertFalse(UrlPolicy.isPrivate(InetAddress.getByName("2606:4700:4700::1111")))
    }

    @Test fun resolvesRedirectForms() {
        val current = URI("https://example.com/a/b?x=1")
        assertEquals("https://example.com/a/c", UrlPolicy.resolveRedirect(current, "c").toString())
        assertEquals("https://example.com/root", UrlPolicy.resolveRedirect(current, "/root").toString())
        assertEquals("https://cdn.example.org/x", UrlPolicy.resolveRedirect(current, "//cdn.example.org/x").toString())
        assertEquals("https://other.example/y", UrlPolicy.resolveRedirect(current, "https://other.example/y").toString())
        assertThrows(UnsafeUrlException::class.java) { UrlPolicy.resolveRedirect(current, "  ") }
    }
}
