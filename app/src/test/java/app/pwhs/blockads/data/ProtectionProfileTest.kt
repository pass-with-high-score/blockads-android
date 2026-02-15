package app.pwhs.blockads.data

import org.junit.Assert.*
import org.junit.Test

class ProtectionProfileTest {

    @Test
    fun `isPreset returns true for DEFAULT type`() {
        assertTrue(ProtectionProfile.isPreset(ProtectionProfile.TYPE_DEFAULT))
    }

    @Test
    fun `isPreset returns true for STRICT type`() {
        assertTrue(ProtectionProfile.isPreset(ProtectionProfile.TYPE_STRICT))
    }

    @Test
    fun `isPreset returns true for FAMILY type`() {
        assertTrue(ProtectionProfile.isPreset(ProtectionProfile.TYPE_FAMILY))
    }

    @Test
    fun `isPreset returns true for GAMING type`() {
        assertTrue(ProtectionProfile.isPreset(ProtectionProfile.TYPE_GAMING))
    }

    @Test
    fun `isPreset returns false for CUSTOM type`() {
        assertFalse(ProtectionProfile.isPreset(ProtectionProfile.TYPE_CUSTOM))
    }

    @Test
    fun `isPreset returns false for unknown type`() {
        assertFalse(ProtectionProfile.isPreset("UNKNOWN"))
    }

    @Test
    fun `DEFAULT filter URLs contain basic filter lists`() {
        val urls = ProfileManager.DEFAULT_FILTER_URLS
        assertTrue(urls.contains("https://abpvn.com/android/abpvn.txt"))
        assertTrue(urls.contains("https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"))
        assertEquals(3, urls.size)
    }

    @Test
    fun `STRICT filter URLs are superset of DEFAULT`() {
        val strict = ProfileManager.STRICT_FILTER_URLS
        val default = ProfileManager.DEFAULT_FILTER_URLS
        assertTrue(strict.containsAll(default))
        assertTrue(strict.size > default.size)
    }

    @Test
    fun `FAMILY filter URLs include adult and gambling filters`() {
        val family = ProfileManager.FAMILY_FILTER_URLS
        assertTrue(family.any { it.contains("porn-only") })
        assertTrue(family.any { it.contains("gambling-only") })
    }

    @Test
    fun `FAMILY filter URLs are superset of DEFAULT`() {
        val family = ProfileManager.FAMILY_FILTER_URLS
        val default = ProfileManager.DEFAULT_FILTER_URLS
        assertTrue(family.containsAll(default))
    }

    @Test
    fun `GAMING filter URLs are subset of DEFAULT`() {
        val gaming = ProfileManager.GAMING_FILTER_URLS
        val default = ProfileManager.DEFAULT_FILTER_URLS
        assertTrue(default.containsAll(gaming))
        assertTrue(gaming.size < default.size)
    }

    @Test
    fun `STRICT filter URLs include analytics and tracker filters`() {
        val strict = ProfileManager.STRICT_FILTER_URLS
        assertTrue(strict.any { it.contains("easyprivacy") })
        assertTrue(strict.any { it.contains("easylist.txt") })
        assertTrue(strict.any { it.contains("AdGuardSDNSFilter") })
    }

    @Test
    fun `profile type constants are distinct`() {
        val types = setOf(
            ProtectionProfile.TYPE_DEFAULT,
            ProtectionProfile.TYPE_STRICT,
            ProtectionProfile.TYPE_FAMILY,
            ProtectionProfile.TYPE_GAMING,
            ProtectionProfile.TYPE_CUSTOM
        )
        assertEquals(5, types.size)
    }

    @Test
    fun `filter URL sets have correct sizes`() {
        assertTrue(ProfileManager.STRICT_FILTER_URLS.size >= 10)
        assertTrue(ProfileManager.FAMILY_FILTER_URLS.size >= 4)
        assertTrue(ProfileManager.GAMING_FILTER_URLS.size >= 2)
        assertTrue(ProfileManager.DEFAULT_FILTER_URLS.size == 3)
    }

    @Test
    fun `enabledFilterUrls round-trips through comma separation`() {
        val urls = ProfileManager.DEFAULT_FILTER_URLS
        val joined = urls.joinToString(",")
        val parsed = joined.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        assertEquals(urls, parsed)
    }
}
