package io.acanthis.app.core.network

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaUrlRebaseTest {

    @Test
    fun normalizeBaseUrlTrimsWhitespaceAndTrailingSlashes() {
        assertEquals("https://api.example.com", normalizeBaseUrl("  https://api.example.com///  "))
        assertEquals("http://localhost:5301/v1", normalizeBaseUrl("http://localhost:5301/v1/"))
    }

    @Test
    fun stripTrailingV1PrefixRemovesOnlyTerminalV1() {
        assertEquals("https://api.example.com", stripTrailingV1Prefix("https://api.example.com/v1/"))
        assertEquals("https://api.example.com", stripTrailingV1Prefix("https://api.example.com/v1"))
        assertEquals("https://api.example.com/v1/sub", stripTrailingV1Prefix("https://api.example.com/v1/sub/"))
    }

    @Test
    fun rebaseMediaUrlHandlesEmptyAndNullInputs() {
        assertEquals("", rebaseMediaUrlWithBase(null, "http://10.0.2.2:5301"))
        assertEquals("", rebaseMediaUrlWithBase("", "http://10.0.2.2:5301"))
        assertEquals("", rebaseMediaUrlWithBase("   ", "http://10.0.2.2:5301"))
    }

    @Test
    fun rebaseMediaUrlPrefixesRelativeV1Paths() {
        val result = rebaseMediaUrlWithBase(
            rawUrl = "/v1/linovelib/illustration/0123456789abcdef0123456789abcdef",
            apiBaseUrl = "https://acanthis.example.com/v1/",
        )
        assertEquals(
            "https://acanthis.example.com/v1/linovelib/illustration/0123456789abcdef0123456789abcdef",
            result,
        )
    }

    @Test
    fun rebaseMediaUrlRewritesCrossHostV1UrlsWithQueryAndPreservesNonV1Urls() {
        val rebased = rebaseMediaUrlWithBase(
            rawUrl = "http://old-host:5301/proxy/v1/linovelib/cover/novel/123?w=400#top",
            apiBaseUrl = "http://10.0.2.2:5301/v1",
        )
        assertEquals("http://10.0.2.2:5301/v1/linovelib/cover/novel/123?w=400", rebased)

        val external = rebaseMediaUrlWithBase(
            rawUrl = "https://cdn.example.net/assets/cover.jpg",
            apiBaseUrl = "http://10.0.2.2:5301",
        )
        assertEquals("https://cdn.example.net/assets/cover.jpg", external)

        val invalid = rebaseMediaUrlWithBase(
            rawUrl = "not-a-valid-url",
            apiBaseUrl = "http://10.0.2.2:5301",
        )
        assertEquals("not-a-valid-url", invalid)
    }
}
