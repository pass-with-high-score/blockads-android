package app.pwhs.blockads.ui.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pwhs.blockads.data.AppPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SplashViewModel(
    val appPrefs: AppPreferences
) : ViewModel() {

    private val _events = MutableSharedFlow<SplashEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<SplashEvent> = _events.asSharedFlow()

    init {
        // Observe onboarding completion status
        viewModelScope.launch {
            val onboardingCompleted = appPrefs.onboardingCompleted.first()
            delay(1500)
            if (onboardingCompleted) {
                _events.emit(SplashEvent.Home)
            } else {
                _events.emit(SplashEvent.Onboarding)
            }
        }
    }
}