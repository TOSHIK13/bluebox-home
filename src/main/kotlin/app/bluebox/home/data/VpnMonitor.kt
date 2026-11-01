package app.bluebox.home.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.bluebox.home.logic.VpnRules
import app.bluebox.home.logic.VpnState

/** Watches VPN networks and reports a [VpnState]; callbacks arrive on the main thread. */
class VpnMonitor(context: Context, private val onState: (VpnState) -> Unit) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val caps = HashMap<Network, NetworkCapabilities>()
    private var upSince = 0L
    private var started = false
    private val recheck = Runnable { publish() }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) { caps[network] = nc; publish() }
        override fun onLost(network: Network) { caps.remove(network); publish() }
    }

    fun start() {
        if (started) return
        started = true
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        cm.registerNetworkCallback(req, callback)
        publish()
    }

    fun stop() {
        if (!started) return
        started = false
        main.removeCallbacks(recheck)
        runCatching { cm.unregisterNetworkCallback(callback) }
        caps.clear()
    }

    private fun publish() {
        main.removeCallbacks(recheck)
        val vpn = caps.values.firstOrNull()
        val validated = vpn?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        if (vpn == null || validated) upSince = 0L else if (upSince == 0L) upSince = SystemClock.elapsedRealtime()
        val waited = if (upSince == 0L) 0L else SystemClock.elapsedRealtime() - upSince
        val state = VpnRules.stateOf(vpn != null, validated, waited)
        onState(state)
        if (state == VpnState.Connecting) main.postDelayed(recheck, VpnRules.CONNECT_GRACE_MS - waited + 50)
    }
}
