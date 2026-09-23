package app.pwhs.blockads.utils

import android.content.Context
import android.content.res.AssetFileDescriptor
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

class BlocklistInfoTest {

    @Test
    fun `fromAsset exposes the descriptor window and close releases it`() {
        val afd = mockk<AssetFileDescriptor>(relaxed = true) {
            every { parcelFileDescriptor.fd } returns 42
            every { startOffset } returns 1024L
            every { length } returns 4096L
        }
        val context = mockk<Context> { every { assets.openFd("list.bin") } returns afd }

        val info = BlocklistInfo.fromAsset(context, "list.bin")!!

        assertEquals(42L, info.fd)
        assertEquals(1024L, info.startOffset)
        assertEquals(4096L, info.length)
        info.close()
        verify { afd.close() }
    }

    @Test
    fun `fromAsset returns null when the asset cannot be opened`() {
        val context = mockk<Context> { every { assets.openFd(any()) } throws FileNotFoundException("gone") }
        assertNull(BlocklistInfo.fromAsset(context, "missing.bin"))
    }

    @Test
    fun `close swallows errors and tolerates a missing descriptor`() {
        val afd = mockk<AssetFileDescriptor> { every { close() } throws IOException("boom") }
        BlocklistInfo(fd = 1, startOffset = 0, length = 0, afd = afd).close()
        BlocklistInfo(fd = 1, startOffset = 0, length = 0).close()
    }
}
