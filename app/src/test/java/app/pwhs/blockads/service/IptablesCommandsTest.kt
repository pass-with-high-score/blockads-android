package app.pwhs.blockads.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

class IptablesCommandsTest {

    @Test
    fun `ipv4 rules exempt own and whitelisted uids before redirecting DNS`() {
        val cmds = IptablesManager.buildIpv4Commands(uid = 10123, blockDoT = true, whitelistUids = listOf(10200))
        assertEquals(
            listOf(
                "iptables -t nat -N BLOCKADS_DNS 2>/dev/null || true",
                "iptables -t nat -A BLOCKADS_DNS -m owner --uid-owner 10123 -j RETURN",
                "iptables -t nat -A BLOCKADS_DNS -m owner --uid-owner 10200 -j RETURN",
                "iptables -t nat -A BLOCKADS_DNS -p udp --dport 53 -j REDIRECT --to-ports 15353",
                "iptables -t nat -A BLOCKADS_DNS -p tcp --dport 53 -j REDIRECT --to-ports 15353",
                "iptables -t nat -A OUTPUT -j BLOCKADS_DNS",
                "iptables -t filter -N BLOCKADS_DOT 2>/dev/null || true",
                "iptables -t filter -A BLOCKADS_DOT -m owner --uid-owner 10123 -j RETURN",
                "iptables -t filter -A BLOCKADS_DOT -m owner --uid-owner 10200 -j RETURN",
                "iptables -t filter -A BLOCKADS_DOT -p tcp --dport 853 -j REJECT",
                "iptables -t filter -A OUTPUT -j BLOCKADS_DOT",
            ),
            cmds
        )
    }

    @Test
    fun `ipv6 rules mirror ipv4 and skip DoT blocking when disabled`() {
        val v4 = IptablesManager.buildIpv4Commands(10123, blockDoT = false, whitelistUids = emptyList())
        val v6 = IptablesManager.buildIpv6Commands(10123, blockDoT = false, whitelistUids = emptyList())
        assertEquals(v4.map { it.replaceFirst("iptables", "ip6tables") }, v6)
        assertTrue(v6.none { "853" in it })
    }

    @Test
    fun `teardown removes every chain it created`() {
        val cmds = IptablesManager.teardownCommands()
        for (bin in listOf("iptables", "ip6tables")) {
            assertTrue(cmds.contains("$bin -t nat -X BLOCKADS_DNS 2>/dev/null"))
            assertTrue(cmds.contains("$bin -t filter -X BLOCKADS_DOT 2>/dev/null"))
        }
    }

    @Ignore("which Private DNS mode to restore (saved value vs system default) needs a decision")
    @Test
    fun `teardown does not overwrite the user's Private DNS mode`() {
        val forced = IptablesManager.teardownCommands().filter { it.startsWith("settings put global private_dns_mode") }
        assertFalse(
            "teardown forces a Private DNS mode regardless of what the user had set: $forced",
            forced.any { it.endsWith(" opportunistic") }
        )
    }

    @Test
    fun `root mitm redirects selected browser tcp 80 and 443 to the mitm port`() {
        val cmds = IptablesManager.buildIpv4Commands(
            uid = 10123,
            blockDoT = false,
            whitelistUids = emptyList(),
            mitmUids = listOf(10200),
            blockQuic = true,
        )
        assertTrue(
            cmds.contains(
                "iptables -t nat -A BLOCKADS_DNS -m owner --uid-owner 10200 -p tcp --dport 80 -j REDIRECT --to-ports 15354"
            )
        )
        assertTrue(
            cmds.contains(
                "iptables -t nat -A BLOCKADS_DNS -m owner --uid-owner 10200 -p tcp --dport 443 -j REDIRECT --to-ports 15354"
            )
        )
        assertTrue(
            cmds.contains(
                "iptables -t filter -A BLOCKADS_DOT -m owner --uid-owner 10200 -p udp --dport 443 -j REJECT"
            )
        )
    }

    @Test
    fun `root mitm leaves QUIC alone when http3 filtering is off`() {
        val cmds = IptablesManager.buildIpv4Commands(
            uid = 10123,
            blockDoT = false,
            whitelistUids = emptyList(),
            mitmUids = listOf(10200),
            blockQuic = false,
        )
        assertTrue(cmds.none { "udp --dport 443" in it })
        assertTrue(cmds.contains("iptables -t filter -A OUTPUT -j BLOCKADS_DOT"))
    }

    @Test
    fun `ipv6 rejects browser http and https to force the ipv4 mitm path`() {
        val cmds = IptablesManager.buildIpv6Commands(
            uid = 10123,
            blockDoT = false,
            whitelistUids = emptyList(),
            mitmUids = listOf(10200),
            blockQuic = true,
        )
        assertTrue(
            cmds.contains(
                "ip6tables -t filter -A BLOCKADS_DOT -m owner --uid-owner 10200 -p tcp --dport 80 -j REJECT"
            )
        )
        assertTrue(
            cmds.contains(
                "ip6tables -t filter -A BLOCKADS_DOT -m owner --uid-owner 10200 -p tcp --dport 443 -j REJECT"
            )
        )
        assertTrue(
            cmds.contains(
                "ip6tables -t filter -A BLOCKADS_DOT -m owner --uid-owner 10200 -p udp --dport 443 -j REJECT"
            )
        )
    }
}
