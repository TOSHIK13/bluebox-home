package app.bluebox.home.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VpnControlTest {
    @Test fun `known app uses its own on and off`() {
        val c = VpnControls.controlFor("com.tailscale.ipn", emptyList())
        assertTrue(c.canSwitch)
        assertEquals("com.tailscale.ipn.CONNECT_VPN", c.command(vpnUp = false)?.action)
        assertEquals("com.tailscale.ipn.DISCONNECT_VPN", c.command(vpnUp = true)?.action)
    }

    @Test fun `toggle only app gives the same command both ways`() {
        val c = VpnControls.controlFor("com.v2ray.ang", emptyList())
        assertEquals(c.command(true), c.command(false))
        assertTrue(c.canSwitch)
    }

    @Test fun `unknown app with a toggle activity is found by name`() {
        val c = VpnControls.controlFor("x.vpn", listOf("x.vpn.MainActivity", "x.vpn.QuickToggleShortcut"))
        assertEquals("x.vpn.QuickToggleShortcut", c.command(false)?.cls)
        assertEquals(CommandKind.Activity, c.command(false)?.kind)
    }

    @Test fun `unknown app without anything to call is only opened`() {
        val c = VpnControls.controlFor("x.vpn", listOf("x.vpn.MainActivity", "x.vpn.SettingsActivity"))
        assertFalse(c.canSwitch)
        assertNull(c.command(true))
        assertNull(c.command(false))
    }

    @Test fun `command survives storage`() {
        val c = VpnCommand(CommandKind.Broadcast, "a.B", "a.ACTION")
        assertEquals(c, decodeVpnCommand(c.encode()))
        val bare = VpnCommand(CommandKind.Activity, "a.C")
        assertEquals(bare, decodeVpnCommand(bare.encode()))
        assertNull(decodeVpnCommand("junk"))
    }

    @Test fun `owner picks override the table`() {
        val base = VpnControls.controlFor("com.tailscale.ipn", emptyList())
        val mine = VpnCommand(CommandKind.Activity, "x.Y", "x.GO")
        val c = VpnControls.withOverrides(base, mine.encode(), VpnControls.NONE, null)
        assertEquals(mine, c.on)
        assertNull(c.off)
        assertEquals(base.toggle, c.toggle)
        assertEquals(base, VpnControls.withOverrides(base, null, null, null))
    }

    @Test fun `health refines an up tunnel by the probe`() {
        assertEquals(VpnHealth.Working, VpnRules.health(VpnState.On, null))
        assertEquals(VpnHealth.Working, VpnRules.health(VpnState.On, true))
        assertEquals(VpnHealth.NoTraffic, VpnRules.health(VpnState.On, false))
        assertEquals(VpnHealth.Off, VpnRules.health(VpnState.Off, false))
        assertEquals(VpnHealth.Error, VpnRules.health(VpnState.Error, true))
        assertEquals(VpnHealth.Connecting, VpnRules.health(VpnState.Connecting, null))
    }
}
