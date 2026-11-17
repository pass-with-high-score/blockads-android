package app.pwhs.blockadstv.update

import android.app.Activity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.dsl.module

private class FdroidTvUpdateController : TvUpdateController {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = _state.asStateFlow()
    override val supportsInAppUpdate: Boolean = false
    override val supportsChannelSelection: Boolean = false

    override suspend fun checkForUpdate(channel: UpdateChannel, manual: Boolean) = Unit
    override suspend fun startUpdate(activity: Activity) = Unit
    override fun completeUpdate() = Unit
    override suspend fun dismissUpdate(skipVersion: Boolean) = Unit
    override fun resetState() = Unit
}

val tvUpdateModule = module {
    single<TvUpdateController> { FdroidTvUpdateController() }
}
