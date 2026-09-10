package app.pwhs.blockads.service

/**
 * Represents the true lifecycle state of the VPN engine.
 * Emitted via [AdBlockVpnService.state] so UI can observe reactively.
 */
enum class VpnState {
    /** Service is not running. */
    STOPPED,

    /** Service is starting (loading filters, preparing tunnel). */
    STARTING,

    /** Tunnel is established and actively filtering traffic. */
    RUNNING,

    /** Service is in the process of shutting down. */
    STOPPING,

    /** Service is tearing down and will immediately re-start. */
    RESTARTING,
}
