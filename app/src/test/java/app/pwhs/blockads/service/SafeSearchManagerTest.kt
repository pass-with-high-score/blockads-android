package app.pwhs.blockads.service

import org.junit.Assert.*
import org.junit.Test

class SafeSearchManagerTest {

    @Test
    fun `check returns REDIRECT for google domains`() {
        val result = SafeSearchManager.check("www.google.com")
        assertEquals(SafeSearchManager.SafeSearchResult.Action.REDIRECT, result.action)
        assertEquals("forcesafesearch.google.com", result.redirectDomain)
    }

    @Test
    fun `check returns REDIRECT for google with country TLD`() {
        val result = SafeSearchManager.check("www.google.co.uk")
        assertEquals(SafeSearchManager.SafeSearchResult.Action.REDIRECT, result.action)
        assertEquals("forcesafesearch.google.com", result.redirectDomain)
    }

    @Test
    fun `check returns REDIRECT for bare google domain`() {
        val result = SafeSearchManager.check("google.com")
        assertEquals(SafeSearchManager.SafeSearchResult.Action.REDIRECT, result.action)
        assertEquals("forcesafesearch.google.com", result.redirectDomain)
    }

    @Test
    fun `check returns REDIRECT for bing domains`() {
        val result = SafeSearchManager.check("www.bing.com")
        assertEquals(SafeSearchManager.SafeSearchResult.Action.REDIRECT, result.action)
        assertEquals("strict.bing.com", result.redirectDomain)
    }

    @Test
    fun `check returns REDIRECT for bare bing domain`() {
        val result = SafeSearchManager.check("bing.com")
        assertEquals(SafeSearchManager.SafeSearchResult.Action.REDIRECT, result.action)
        assertEquals("strict.bing.com", result.redirectDomain)
    }

    @Test
    fun `check returns NONE for youtube domains (handled by YouTube Restricted Mode)`() {
        val result = SafeSearchManager.check("www.youtube.com")
        assertEquals(SafeSearchManager.SafeSearchResult.Action.NONE, result.action)
    }

    @Test
    fun `check returns NONE for youtube subdomain (handled by YouTube Restricted Mode)`() {
        val result = SafeSearchManager.check("m.youtube.com")
        assertEquals(SafeSearchManager.SafeSearchResult.Action.NONE, result.action)
    }

    @Test
    fun `isYoutubeDomain returns true for youtube domains`() {
        assertTrue(SafeSearchManager.isYoutubeDomain("youtube.com"))
        assertTrue(SafeSearchManager.isYoutubeDomain("www.youtube.com"))
        assertTrue(SafeSearchManager.isYoutubeDomain("m.youtube.com"))
    }

    @Test
    fun `isYoutubeDomain returns false for non-youtube domains`() {
        assertFalse(SafeSearchManager.isYoutubeDomain("google.com"))
        assertFalse(SafeSearchManager.isYoutubeDomain("notyoutube.com"))
        assertFalse(SafeSearchManager.isYoutubeDomain("example.com"))
    }

    @Test
    fun `check returns NONE for duckduckgo (unsupported engines are not blocked)`() {
        val result = SafeSearchManager.check("duckduckgo.com")
        assertEquals(SafeSearchManager.SafeSearchResult.Action.NONE, result.action)
    }

    @Test
    fun `check returns NONE for duckduckgo subdomain (unsupported engines are not blocked)`() {
        val result = SafeSearchManager.check("www.duckduckgo.com")
        assertEquals(SafeSearchManager.SafeSearchResult.Action.NONE, result.action)
    }

    @Test
    fun `check returns NONE for unrelated domains`() {
        val result = SafeSearchManager.check("example.com")
        assertEquals(SafeSearchManager.SafeSearchResult.Action.NONE, result.action)
        assertNull(result.redirectDomain)
    }

    @Test
    fun `check returns NONE for non-search domains`() {
        val result = SafeSearchManager.check("github.com")
        assertEquals(SafeSearchManager.SafeSearchResult.Action.NONE, result.action)
    }
}
