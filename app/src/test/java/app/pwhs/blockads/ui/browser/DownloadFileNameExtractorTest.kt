package app.pwhs.blockads.ui.browser

import app.pwhs.blockads.ui.browser.data.SearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DownloadFileNameExtractorTest {

    private fun name(cd: String?, url: String = "https://cdn.test/dl", mime: String? = null) =
        extractFileName(url, cd, mime)

    @Test
    fun `quoted and bare filenames`() {
        assertEquals("report.pdf", name("attachment; filename=\"report.pdf\""))
        assertEquals("report.pdf", name("attachment; filename=report.pdf"))
        assertEquals("report.pdf", name("ATTACHMENT; FILENAME = \"report.pdf\""))
    }

    @Test
    fun `RFC 5987 extended filename is percent-decoded`() {
        assertEquals("naïve file.txt", name("attachment; filename*=UTF-8''na%C3%AFve%20file.txt"))
    }

    @Test
    fun `plus signs become spaces`() {
        assertEquals("my file.txt", name("attachment; filename=\"my+file.txt\""))
    }

    @Test
    fun `invalid percent escapes keep the raw name`() {
        assertEquals("100%.txt", name("attachment; filename=\"100%.txt\""))
    }

    @Test
    fun `path separators and reserved characters are neutralized`() {
        val traversal = name("attachment; filename=\"../../etc/passwd.txt\"")
        assertFalse(traversal.contains('/'))
        assertEquals(".._.._etc_passwd.txt", traversal)
        assertEquals("a_b_c_.txt", name("attachment; filename=\"a\\b:c*.txt\""))
    }

    @Test
    fun `quoted semicolons survive via the URLUtil fallback`() {
        assertEquals("a;b.txt", name("attachment; filename=\"a;b.txt\""))
    }

    @Test
    fun `header without an extension falls through to the filename query parameter`() {
        assertEquals("song name.mp3", name("attachment; filename=\"README\"", url = "https://cdn.test/get?filename=song%2Bname.mp3"))
    }

    @Test
    fun `falls back to the URL path`() {
        assertEquals("doc name.pdf", name(null, url = "https://cdn.test/files/doc%20name.pdf"))
    }

    @Test
    fun `no usable name falls back to a mime-derived guess`() {
        val guessed = name(null, url = "https://cdn.test/", mime = "application/pdf")
        assertEquals("downloadfile.pdf", guessed)
    }

    @Test
    fun `search engines encode queries and resolve ids`() {
        assertEquals("https://duckduckgo.com/?q=a%20%26%20b", SearchEngine.DUCKDUCKGO.buildSearchUrl(" a & b "))
        assertEquals("https://www.bing.com/search?q=x", SearchEngine.BING.buildSearchUrl("x"))
        assertEquals(SearchEngine.BRAVE, SearchEngine.fromId("BRAVE"))
        assertEquals(SearchEngine.GOOGLE, SearchEngine.fromId("nope"))
        assertEquals(SearchEngine.GOOGLE, SearchEngine.fromId(null))
    }
}
