package app.pwhs.blockads.utils

import app.pwhs.blockads.data.entities.WireGuardPeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireGuardRoutingTest {

    private fun peer(vararg allowed: String) =
        WireGuardPeer(publicKey = "k", allowedIPs = allowed.toList())

    @Test
    fun splitCidr_routesExactlyThatCidr_notFullTunnel() {
        val r = wgRoutingFromPeers(listOf(peer("10.13.13.0/24")))
        assertEquals(listOf(WgRoute("10.13.13.0", 24, false)), r.routes)
        assertFalse(r.isFullTunnel)
    }

    @Test
    fun defaultRoute_isFullTunnel() {
        val r = wgRoutingFromPeers(listOf(peer("0.0.0.0/0")))
        assertEquals(listOf(WgRoute("0.0.0.0", 0, false)), r.routes)
        assertTrue(r.isFullTunnel)
    }

    @Test
    fun ipv4MappedV6_isSkipped_netipNeverMatchesItAgainstV4() {
        val r = wgRoutingFromPeers(listOf(peer("::ffff:192.168.1.1/128", "10.13.13.0/24")))
        assertEquals(listOf(WgRoute("10.13.13.0", 24, false)), r.routes)
        assertEquals(listOf("::ffff:192.168.1.1/128"), r.skippedEntries)
    }

    @Test
    fun v6WithZoneId_isSkipped_netipRejectsZonesInPrefixes() {
        val r = wgRoutingFromPeers(listOf(peer("fe80::1%wlan0/64", "10.13.13.0/24")))
        assertEquals(listOf(WgRoute("10.13.13.0", 24, false)), r.routes)
        assertEquals(listOf("fe80::1%wlan0/64"), r.skippedEntries)
    }

    @Test
    fun mixedV4AndV6_bothFamiliesRouted() {
        val r = wgRoutingFromPeers(listOf(peer("10.13.13.0/24", "fd00::/64")))
        assertEquals(
            listOf(WgRoute("10.13.13.0", 24, false), WgRoute("fd00::", 64, true)),
            r.routes,
        )
        assertFalse(r.isFullTunnel)
    }

    @Test
    fun emptyAllowedIps_routesNothing() {
        // No routes, never a 0/0 fallback into a tunnel that claims nothing; [WgRouting.issue] stops the connect.
        val r = wgRoutingFromPeers(listOf(peer()))
        assertTrue(r.routes.isEmpty())
        assertFalse(r.isFullTunnel)
    }

    @Test
    fun duplicateCidrsAcrossPeers_areDeduped() {
        val r = wgRoutingFromPeers(listOf(peer("10.13.13.0/24"), peer("10.13.13.0/24")))
        assertEquals(listOf(WgRoute("10.13.13.0", 24, false)), r.routes)
    }

    @Test
    fun malformedEntries_areSkipped_validKept() {
        val r = wgRoutingFromPeers(listOf(peer("garbage", "10.13.13.0/24", "10.0.0.0/999", "")))
        assertEquals(listOf(WgRoute("10.13.13.0", 24, false)), r.routes)
    }

    @Test
    fun missingPrefix_defaultsPerFamily() {
        assertEquals(WgRoute("10.13.13.5", 32, false), parseCidrToRoute("10.13.13.5"))
        assertEquals(WgRoute("fd00::1", 128, true), parseCidrToRoute("fd00::1"))
    }

    @Test
    fun parseCidr_rejectsBadInput() {
        assertNull(parseCidrToRoute(""))
        assertNull(parseCidrToRoute("999.1.1.1/24"))
        assertNull(parseCidrToRoute("10.0.0.0/33"))
        assertNull(parseCidrToRoute("nope"))
        assertNull(parseCidrToRoute("abcde::1"))
        assertNull(parseCidrToRoute("1:2:3:4:5:6:7:8:9"))
        assertNull(parseCidrToRoute(":1:2:3"))
    }

    @Test
    fun validIpv6Literals_stillAccepted() {
        assertEquals(WgRoute("::", 0, true), parseCidrToRoute("::/0"))
        assertEquals(WgRoute("fd00::", 64, true), parseCidrToRoute("fd00::/64"))
        assertEquals(WgRoute("2001:db8:1:2:3:4:5:6", 128, true), parseCidrToRoute("2001:db8:1:2:3:4:5:6"))
    }

    @Test
    fun emptyPeerList_routesNothing() {
        val r = wgRoutingFromPeers(emptyList())
        assertTrue(r.routes.isEmpty())
        assertFalse(r.isFullTunnel)
        assertTrue(r.peers.isEmpty())
    }

    @Test
    fun allEntriesInvalid_routesNothing_andReportsThem() {
        val r = wgRoutingFromPeers(listOf(peer("garbage", "")))
        assertTrue(r.routes.isEmpty())
        assertFalse(r.isFullTunnel)
        assertEquals(listOf("garbage"), r.skippedEntries)
    }

    @Test
    fun malformedEntries_areReportedAsSkipped() {
        val r = wgRoutingFromPeers(listOf(peer("garbage", "10.13.13.0/24", "10.0.0.0/999")))
        assertEquals(listOf("garbage", "10.0.0.0/999"), r.skippedEntries)
    }

    @Test
    fun validOnly_hasNoSkipped() {
        val r = wgRoutingFromPeers(listOf(peer("10.13.13.0/24")))
        assertTrue(r.skippedEntries.isEmpty())
    }

    @Test
    fun peers_carryCanonicalAllowedIps_forTheGoEngine() {
        // wireguard-go rejects a bare IP and one bad entry fails the device, so Go gets the canonical entries.
        val p = WireGuardPeer(
            publicKey = "k",
            endpoint = "vpn.example:51820",
            allowedIPs = listOf("10.13.13.5", "10.13.13.1/24", "fe80::1%wlan0/64", "2001:0DB8::0001/128"),
        )
        val r = wgRoutingFromPeers(listOf(p))
        assertEquals(listOf("10.13.13.5/32", "10.13.13.0/24", "2001:db8::1/128"), r.peers.single().allowedIPs)
        assertEquals("vpn.example:51820", r.peers.single().endpoint)
        assertEquals("k", r.peers.single().publicKey)
    }

    @Test
    fun peers_keepTheirOwnAllowedIps() {
        val r = wgRoutingFromPeers(listOf(peer("10.13.13.0/24"), peer("fd00::/64")))
        assertEquals(listOf("10.13.13.0/24"), r.peers[0].allowedIPs)
        assertEquals(listOf("fd00::/64"), r.peers[1].allowedIPs)
    }

    @Test
    fun peers_withOnlyBadEntries_endUpWithNone() {
        val r = wgRoutingFromPeers(listOf(peer("garbage", "10.13.13.0/24"), peer("nope")))
        assertEquals(listOf("10.13.13.0/24"), r.peers[0].allowedIPs)
        assertTrue(r.peers[1].allowedIPs.isEmpty())
    }

    @Test
    fun hostBitsInCidr_areMaskedToNetworkAddress() {
        // Android's addRoute throws "Bad address" unless host bits are zero.
        assertEquals(WgRoute("10.13.13.0", 24, false), parseCidrToRoute("10.13.13.1/24"))
        assertEquals(WgRoute("fd00::", 64, true), parseCidrToRoute("fd00::1/64"))
    }

    @Test
    fun hostBitsMasked_onNonByteAlignedPrefixes() {
        assertEquals(WgRoute("10.13.13.128", 25, false), parseCidrToRoute("10.13.13.190/25"))
        assertEquals(WgRoute("10.0.0.0", 12, false), parseCidrToRoute("10.1.2.3/12"))
        assertEquals(WgRoute("2001:db8:abcd:1000::", 52, true), parseCidrToRoute("2001:db8:abcd:1234::1/52"))
    }

    @Test
    fun zeroPrefix_masksToDefaultRoute_andIsFullTunnel() {
        assertEquals(WgRoute("0.0.0.0", 0, false), parseCidrToRoute("1.2.3.4/0"))
        assertEquals(WgRoute("::", 0, true), parseCidrToRoute("fd00::1/0"))
        assertTrue(wgRoutingFromPeers(listOf(peer("1.2.3.4/0"))).isFullTunnel)
    }

    @Test
    fun hostRoutes_keepFullAddress() {
        assertEquals(WgRoute("10.13.13.5", 32, false), parseCidrToRoute("10.13.13.5/32"))
        assertEquals(WgRoute("fd00::1", 128, true), parseCidrToRoute("fd00::1/128"))
    }

    @Test
    fun sameSubnetWithDifferentHostBits_dedupesAfterMasking() {
        val r = wgRoutingFromPeers(listOf(peer("10.13.13.1/24", "10.13.13.5/24")))
        assertEquals(listOf(WgRoute("10.13.13.0", 24, false)), r.routes)
        assertTrue(r.skippedEntries.isEmpty())
    }

    @Test
    fun addressesAreCanonicalized() {
        assertEquals(WgRoute("2001:db8::1", 128, true), parseCidrToRoute("2001:0DB8:0000:0000:0000:0000:0000:0001"))
    }

    @Test
    fun ipv4MappedV6_withoutDots_isSkipped() {
        // Dotless ::ffff:192.168.1.1; netip never matches it against a v4 prefix.
        val r = wgRoutingFromPeers(listOf(peer("::ffff:c0a8:101/128", "10.13.13.0/24")))
        assertEquals(listOf(WgRoute("10.13.13.0", 24, false)), r.routes)
        assertEquals(listOf("::ffff:c0a8:101/128"), r.skippedEntries)
    }

    @Test
    fun issue_nullForUsableConfig() {
        assertNull(wgRoutingFromPeers(listOf(peer("10.13.13.0/24"), peer())).issue)
    }

    @Test
    fun issue_noAllowedIps_whenNoPeerClaimsAnything() {
        assertEquals(WgConfigIssue.NoAllowedIps, wgRoutingFromPeers(listOf(peer(), peer(" "))).issue)
        assertEquals(WgConfigIssue.NoAllowedIps, wgRoutingFromPeers(emptyList()).issue)
    }

    @Test
    fun issue_malformed_winsEvenWhenOtherEntriesAreUsable() {
        val r = wgRoutingFromPeers(listOf(peer("10.13.13.0/24", "fe80::1%wlan0/64")))
        assertEquals(WgConfigIssue.MalformedAllowedIps(listOf("fe80::1%wlan0/64")), r.issue)
    }

    @Test
    fun allowedIpError_agreesWithTheRouteParser() {
        assertNull(WireGuardValidators.allowedIp("10.13.13.1"))
        assertNull(WireGuardValidators.allowedIp("10.13.13.1/24"))
        assertEquals("Zone ids are not supported", WireGuardValidators.allowedIp("fe80::1%wlan0/64"))
        assertEquals("Prefix out of range", WireGuardValidators.allowedIp("10.0.0.0/33"))
        assertEquals("Invalid IP", WireGuardValidators.allowedIp("10.0.0"))
        assertEquals("Not supported by WireGuard", WireGuardValidators.allowedIp("::ffff:c0a8:101/128"))
    }

    @Test
    fun tunRoutes_v4OnlyFullTunnel_alsoCapturesIpv6() {
        val r = wgRoutingFromPeers(listOf(peer("0.0.0.0/0")))
        assertEquals(listOf(WgRoute("0.0.0.0", 0, false), WgRoute("::", 0, true)), r.tunRoutes)
        assertEquals(listOf("0.0.0.0/0"), r.peers[0].allowedIPs) // wireguard-go still drops the IPv6
    }

    @Test
    fun tunRoutes_splitTunnel_leavesIpv6Alone() {
        val r = wgRoutingFromPeers(listOf(peer("10.13.13.0/24")))
        assertEquals(r.routes, r.tunRoutes)
    }

    @Test
    fun tunRoutes_fullTunnelWithIpv6Route_isUnchanged() {
        val r = wgRoutingFromPeers(listOf(peer("0.0.0.0/0", "fd00::/8")))
        assertEquals(r.routes, r.tunRoutes)
    }
}
