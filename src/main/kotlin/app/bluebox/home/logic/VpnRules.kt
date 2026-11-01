package app.bluebox.home.logic

enum class VpnState { On, Off, Connecting, Error }

/** What ConnectivityManager says about VPN, reduced to a state for the pill. */
object VpnRules {
    /** A VPN network that is still not validated after this long is shown as "no connection" instead of "connecting". */
    const val CONNECT_GRACE_MS = 8_000L

    /**
     * @param hasVpn a network with TRANSPORT_VPN exists
     * @param validated that network has NET_CAPABILITY_VALIDATED
     * @param unvalidatedForMs how long the VPN network has been up without validation (0 when validated or absent)
     */
    fun stateOf(hasVpn: Boolean, validated: Boolean, unvalidatedForMs: Long): VpnState = when {
        !hasVpn -> VpnState.Off
        validated -> VpnState.On
        unvalidatedForMs >= CONNECT_GRACE_MS -> VpnState.Error
        else -> VpnState.Connecting
    }
}
