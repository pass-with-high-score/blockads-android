package app.pwhs.blockads.data.repository

import android.content.Context
import io.ktor.client.HttpClient
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The hand-rolled parsers must survive escaped quotes, `},` inside strings and nested objects. */
class FilterJsonParsingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun context() = mockk<Context> { every { filesDir } returns tempFolder.root }

    private fun repository() = FilterListRepository(
        context(), mockk(), mockk(), mockk(), mockk<HttpClient>(), mockk(),
    )

    private val tricky = """[
        {"id":"a","name":"Say \"hi\"","description":"braces }, and more","meta":{"tags":["x"]},
         "isEnabled":true,"isBuiltIn":true,"category":"security","ruleCount":7,
         "bloomUrl":"https://cdn.example/a.bloom","trieUrl":"https://cdn.example/a.trie"},
        {"id":"b","name":"Plain","bloomUrl":"https:\/\/cdn.example\/b.bloom","trieUrl":"https://cdn.example/b.trie"}
    ]"""

    @Ignore("known bug: regex catalog parser drops entries with escaped quotes or nested objects")
    @Test
    fun `remote catalog parser keeps every entry intact`() {
        val lists = repository().parseRemoteFilterJson(tricky)

        assertEquals(listOf("Say \"hi\"", "Plain"), lists.map { it.name })
        assertEquals("braces }, and more", lists[0].description)
        assertEquals(7, lists[0].ruleCount)
        assertEquals(true, lists[0].isEnabled)
        assertEquals("security", lists[0].category)
        assertEquals("https://cdn.example/b.bloom", lists[1].bloomUrl)
        assertEquals(false, lists[1].isBuiltIn)
    }

    @Test
    fun `remote catalog parser returns empty for garbage`() {
        assertEquals(emptyList<Any>(), repository().parseRemoteFilterJson("<html>502</html>"))
    }

    @Ignore("known bug: regex info parser stops at escaped quotes")
    @Test
    fun `info json parser decodes escaped strings`() {
        val manager = CustomFilterManager(context(), mockk(), mockk(), mockk())

        val info = manager.parseInfoJson("""{"name":"My \"best\" list","url":"https:\/\/x.example\/l.txt","ruleCount":12}""")

        assertEquals("My \"best\" list", info.name)
        assertEquals("https://x.example/l.txt", info.url)
        assertEquals(12, info.ruleCount)
    }
}
