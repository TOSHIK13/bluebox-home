package app.bluebox.home.logic

enum class VpnState { On, Off, Connecting, Error }

/** Off / connecting / working (traffic passes) / up but nothing passes / no connection. */
enum class VpnHealth { Off, Connecting, Working, NoTraffic, Error }

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

    /**
     * What the icon shows: the tunnel state refined by a real probe through it.
     * @param probeOk result of the last request sent through the tunnel (null = not measured yet)
     */
    fun health(state: VpnState, probeOk: Boolean?): VpnHealth = when (state) {
        VpnState.Off -> VpnHealth.Off
        VpnState.Connecting -> VpnHealth.Connecting
        VpnState.Error -> VpnHealth.Error
        VpnState.On -> if (probeOk == false) VpnHealth.NoTraffic else VpnHealth.Working
    }

    /** "1:05:09" / "05:09". */
    fun duration(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        val h = s / 3600; val m = s % 3600 / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    /** Traffic in Russian units: "0 Б", "512 КБ", "1,2 МБ", "3,45 ГБ". */
    fun bytes(b: Long): String {
        val v = b.coerceAtLeast(0).toDouble()
        return when {
            v < 1024 -> "${v.toLong()} Б"
            v < 1024 * 1024 -> "%d КБ".format((v / 1024).toLong())
            v < 1024.0 * 1024 * 1024 -> "%.1f МБ".format(java.util.Locale("ru"), v / (1024 * 1024))
            else -> "%.2f ГБ".format(java.util.Locale("ru"), v / (1024.0 * 1024 * 1024))
        }
    }
}
