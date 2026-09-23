package app.pwhs.blockads.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ProcNetUdpParserTest {

    private val header =
        "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode ref pointer drops"

    private fun v4(sl: Int, port: String, uid: Int) =
        "  $sl: 0100007F:$port 00000000:0000 07 00000000:00000000 00:00000000 00000000  $uid        0 12345 2 0000000000000000 0"

    private fun v6(sl: Int, port: String, uid: Int) =
        "  $sl: 00000000000000000000000001000000:$port 00000000000000000000000000000000:0000 07 00000000:00000000 00:00000000 00000000 $uid 0 6789 2 0000000000000000 0"

    @Test
    fun `parses IPv4 and IPv6 rows keyed by hex port`() {
        val map = parseProcNetUdp(listOf(header, v4(0, "0035", 10123), v6(1, "D431", 10456)), ownUid = 10999)
        assertEquals(mapOf(0x35 to 10123, 0xD431 to 10456), map)
    }

    @Test
    fun `own uid is skipped and the first writer wins`() {
        val map = parseProcNetUdp(
            listOf(v4(0, "1F90", 10999), v4(1, "1F90", 10100), v4(2, "1F90", 10200)),
            ownUid = 10999,
        )
        assertEquals(mapOf(0x1F90 to 10100), map)
    }

    @Test
    fun `header, short, portless and non-numeric rows are skipped`() {
        val map = parseProcNetUdp(
            listOf(
                header,
                "",
                "  0: 0100007F:0035 00000000:0000 07",
                "  1: 0100007F 00000000:0000 07 00000000:00000000 00:00000000 00000000 10001 0 1 2 0 0",
                "  2: 0100007F:ZZZZ 00000000:0000 07 00000000:00000000 00:00000000 00000000 10002 0 1 2 0 0",
                "  3: 0100007F:0036 00000000:0000 07 00000000:00000000 00:00000000 00000000 root 0 1 2 0 0",
                v4(4, "0037", 10004),
            ),
            ownUid = -1,
        )
        assertEquals(mapOf(0x37 to 10004), map)
    }
}
