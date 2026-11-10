package app.bluebox.home.logic

/** How Home asks another app to start or stop its VPN. */
enum class CommandKind { Activity, Broadcast }

/** One request: an exported component of the VPN app, optionally with an action. */
data class VpnCommand(val kind: CommandKind, val cls: String, val action: String? = null)

/** Stored form "Activity|class|action" (action may be empty). */
fun VpnCommand.encode(): String = "${kind.name}|$cls|${action.orEmpty()}"

fun decodeVpnCommand(s: String): VpnCommand? {
    val p = s.split('|')
    if (p.size != 3 || p[1].isEmpty()) return null
    val kind = CommandKind.values().firstOrNull { it.name == p[0] } ?: return null
    return VpnCommand(kind, p[1], p[2].ifEmpty { null })
}

/**
 * What Home can do with a VPN app. [on]/[off] are separate commands, [toggle] is one that flips the state itself;
 * all null = the only thing possible is opening the app.
 */
data class VpnControl(val on: VpnCommand? = null, val off: VpnCommand? = null, val toggle: VpnCommand? = null) {
    val canSwitch: Boolean get() = toggle != null || (on != null && off != null)

    /** The command for "make it the other way round" given whether the tunnel is up now; null = open the app instead. */
    fun command(vpnUp: Boolean): VpnCommand? = if (vpnUp) off ?: toggle else on ?: toggle
}

/**
 * Android has no API to switch another app's VPN, so it is done through what the app exposes itself:
 * known apps by a table of their documented intents, any other VPN app by an exported "toggle" component found by name.
 * Apps that expose nothing are only opened.
 */
object VpnControls {
    /** Service permission that marks an app as a VPN provider. */
    const val BIND_VPN = "android.permission.BIND_VPN_SERVICE"

    private val clash = "com.github.kr328.clash.ExternalControlActivity".let { c ->
        VpnControl(
            on = VpnCommand(CommandKind.Activity, c, "com.github.kr328.clash.intent.action.START_CLASH"),
            off = VpnCommand(CommandKind.Activity, c, "com.github.kr328.clash.intent.action.STOP_CLASH"),
            toggle = VpnCommand(CommandKind.Activity, c, "com.github.kr328.clash.intent.action.TOGGLE_CLASH"),
        )
    }

    private val tailscale = VpnControl(
        on = VpnCommand(CommandKind.Broadcast, "com.tailscale.ipn.IPNReceiver", "com.tailscale.ipn.CONNECT_VPN"),
        off = VpnCommand(CommandKind.Broadcast, "com.tailscale.ipn.IPNReceiver", "com.tailscale.ipn.DISCONNECT_VPN"),
    )

    private val v2ray = VpnControl(toggle = VpnCommand(CommandKind.Activity, "com.v2ray.ang.ui.ScSwitchActivity"))

    /** Documented by the apps themselves; not checked against every version, so the owner sees the result on the card. */
    private val known: Map<String, VpnControl> = mapOf(
        "com.github.kr328.clash" to clash,
        "com.github.metacubex.clash.meta" to clash,
        "com.github.metacubex.clash.alpha" to clash,
        "com.tailscale.ipn" to tailscale,
        "com.v2ray.ang" to v2ray,
    )

    const val NONE = "-"

    /** [base] with the owner's picks laid over it: a stored command replaces the slot, [NONE] empties it, null keeps it. */
    fun withOverrides(base: VpnControl, on: String?, off: String?, toggle: String?): VpnControl {
        fun pick(stored: String?, def: VpnCommand?) = when (stored) { null -> def; NONE -> null; else -> decodeVpnCommand(stored) ?: def }
        return VpnControl(pick(on, base.on), pick(off, base.off), pick(toggle, base.toggle))
    }

    /** Names of exported components that flip a VPN (a launcher-shortcut / quick-tile entry point). */
    private val toggleName = Regex("(?i)(quick)?toggle|scswitch|switchvpn|vpnswitch|quickswitch")

    /**
     * Control for [pkg]: the table first, else the first of its exported [activities] whose name says "toggle",
     * else a control with nothing in it (open the app).
     */
    fun controlFor(pkg: String, activities: List<String>): VpnControl {
        known[pkg]?.let { return it }
        val name = activities.firstOrNull { toggleName.containsMatchIn(it.substringAfterLast('.')) } ?: return VpnControl()
        return VpnControl(toggle = VpnCommand(CommandKind.Activity, name))
    }
}
