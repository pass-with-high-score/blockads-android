package app.pwhs.blockads.ui.splash

sealed interface SplashEvent {
    data object Home : SplashEvent
    data object Onboarding : SplashEvent
}