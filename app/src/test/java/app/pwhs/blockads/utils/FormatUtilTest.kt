package app.pwhs.blockads.utils

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tune
import app.pwhs.blockads.data.entities.ProtectionProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class FormatUtilTest {

    private lateinit var savedLocale: Locale
    private lateinit var savedZone: TimeZone

    @Before
    fun setUp() {
        savedLocale = Locale.getDefault()
        savedZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() {
        Locale.setDefault(savedLocale)
        TimeZone.setDefault(savedZone)
    }

    @Test
    fun `formatCount buckets`() {
        assertEquals("0", formatCount(0))
        assertEquals("999", formatCount(999))
        assertEquals("1.0K", formatCount(1_000))
        assertEquals("12.3K", formatCount(12_345))
        assertEquals("1.0M", formatCount(1_000_000))
        assertEquals("2.5M", formatCount(2_500_000))
        assertEquals("-5", formatCount(-5))
    }

    @Test
    fun `formatCount follows the default locale`() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("1,5K", formatCount(1_500))
    }

    @Ignore("known bug: values just under a unit boundary round up to 1000.0K / 1024.0 MB instead of the next unit")
    @Test
    fun `values just under a unit boundary move to the next unit`() {
        assertEquals("1.0M", formatCount(999_999))
        assertEquals("1.0 GB", formatDataSize(1024L * 1024 - 1))
    }

    @Test
    fun `formatDataSize buckets`() {
        assertEquals("0 KB", formatDataSize(0))
        assertEquals("1023 KB", formatDataSize(1023))
        assertEquals("1.0 MB", formatDataSize(1024))
        assertEquals("1.5 MB", formatDataSize(1536))
        assertEquals("2.0 GB", formatDataSize(2L * 1024 * 1024))
    }

    @Test
    fun `formatUptimeShort buckets`() {
        assertEquals("—", formatUptimeShort(0))
        assertEquals("—", formatUptimeShort(-1))
        assertEquals("<1m", formatUptimeShort(59_999))
        assertEquals("1m", formatUptimeShort(60_000))
        assertEquals("59m", formatUptimeShort(3_599_000))
        assertEquals("1h 0m", formatUptimeShort(3_600_000))
        assertEquals("26h 5m", formatUptimeShort((26 * 3600 + 5 * 60) * 1000L))
    }

    @Test
    fun `formatTimeSince buckets`() {
        val now = System.currentTimeMillis()
        assertTrue(formatTimeSince(now - 10_200).matches(Regex("1[01]s")))
        assertEquals("5m", formatTimeSince(now - 5 * 60_000 - 20_000))
        assertEquals("3h", formatTimeSince(now - 3 * 3_600_000 - 60_000))
        assertEquals("2d", formatTimeSince(now - 2 * 86_400_000 - 60_000))
    }

    @Test
    fun `date and time formatting use the default zone`() {
        val t = 3_723_000L
        assertEquals("01/01 01:02", formatDate(t))
        assertEquals("01:02:03", formatTimestamp(t))
        assertEquals("01", hourFormat.get()!!.format(t))
        assertEquals("Thu", dayFormat.get()!!.format(0L))
    }

    @Test
    fun `formatTime pads both fields`() {
        assertEquals("07:05", formatTime(7, 5))
        assertEquals("23:59", formatTime(23, 59))
    }

    @Test
    fun `startOfDayMillis is local midnight today`() {
        val start = startOfDayMillis()
        val cal = Calendar.getInstance().apply { timeInMillis = start }
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND))
        assertTrue(System.currentTimeMillis() - start in 0 until 86_400_000)
    }

    @Test
    fun `profileIcon maps each preset and falls back for custom`() {
        assertEquals(Icons.Default.GppGood, profileIcon(ProtectionProfile.TYPE_DEFAULT))
        assertEquals(Icons.Default.Security, profileIcon(ProtectionProfile.TYPE_STRICT))
        assertEquals(Icons.Default.FamilyRestroom, profileIcon(ProtectionProfile.TYPE_FAMILY))
        assertEquals(Icons.Default.Shield, profileIcon(ProtectionProfile.TYPE_STRICT_FAMILY))
        assertEquals(Icons.Default.SportsEsports, profileIcon(ProtectionProfile.TYPE_GAMING))
        assertEquals(Icons.Default.Tune, profileIcon(ProtectionProfile.TYPE_CUSTOM))
        assertEquals(Icons.Default.Tune, profileIcon(null))
    }
}
