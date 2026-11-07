package app.bluebox.home.logic

import kotlin.test.Test
import kotlin.test.assertEquals

class VpnRulesTest {
    @Test fun `no vpn network is off`() = assertEquals(VpnState.Off, VpnRules.stateOf(false, false, 0))

    @Test fun `validated vpn is on`() = assertEquals(VpnState.On, VpnRules.stateOf(true, true, 0))

    @Test fun `fresh unvalidated vpn is connecting`() = assertEquals(VpnState.Connecting, VpnRules.stateOf(true, false, 1_000))

    @Test fun `long unvalidated vpn is an error`() =
        assertEquals(VpnState.Error, VpnRules.stateOf(true, false, VpnRules.CONNECT_GRACE_MS))

    @Test fun `duration formats minutes and hours`() {
        assertEquals("05:09", VpnRules.duration(309_000))
        assertEquals("1:05:09", VpnRules.duration(3_909_000))
    }

    @Test fun `bytes pick a unit`() {
        assertEquals("0 Б", VpnRules.bytes(0))
        assertEquals("512 КБ", VpnRules.bytes(512 * 1024L))
        assertEquals("1,5 МБ", VpnRules.bytes(1_572_864))
    }
}
