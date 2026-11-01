package app.bluebox.home.logic

import kotlin.test.Test
import kotlin.test.assertEquals

class VpnRulesTest {
    @Test fun `no vpn network is off`() = assertEquals(VpnState.Off, VpnRules.stateOf(false, false, 0))

    @Test fun `validated vpn is on`() = assertEquals(VpnState.On, VpnRules.stateOf(true, true, 0))

    @Test fun `fresh unvalidated vpn is connecting`() = assertEquals(VpnState.Connecting, VpnRules.stateOf(true, false, 1_000))

    @Test fun `long unvalidated vpn is an error`() =
        assertEquals(VpnState.Error, VpnRules.stateOf(true, false, VpnRules.CONNECT_GRACE_MS))
}
