package app.pwhs.blockads.ui.wireguard

/**
 * One-shot UI events for the WireGuard list screen.
 */
sealed class WireGuardUiEvent {
    /** A new profile was imported and saved. */
    data class ProfileImported(val name: String) : WireGuardUiEvent()

    /** A profile was deleted. */
    data class ProfileDeleted(val name: String) : WireGuardUiEvent()

    /** The active profile was changed. */
    data class ProfileActivated(val name: String) : WireGuardUiEvent()

    /** A profile was renamed. */
    data object ProfileRenamed : WireGuardUiEvent()

    /** WireGuard toggled on/off (routing mode flip). */
    data class WireGuardToggled(val enabled: Boolean) : WireGuardUiEvent()

    /** HTTPS filtering was turned off because WireGuard was enabled. */
    data object HttpsFilteringDisabledForWg : WireGuardUiEvent()
}
