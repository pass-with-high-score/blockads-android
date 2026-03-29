package app.pwhs.blockads.ui.event

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

@Composable
fun UiEventEffect(
    events: Flow<UiEvent>
) {
    val context = LocalContext.current
    val resource = LocalResources.current

    LaunchedEffect(events) {
        events.collect { event ->
            when (event) {
                is UiEvent.ToastText -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }

                is UiEvent.ToastRes -> {
                    val msg = if (event.args.isEmpty()) {
                        resource.getString(event.resId)
                    } else {
                        resource.getString(event.resId, *event.args.toTypedArray())
                    }

                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
                
                is UiEvent.ShareFile -> {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = event.mimeType
                        putExtra(android.content.Intent.EXTRA_STREAM, event.uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val chooser = android.content.Intent.createChooser(intent, "Share Logs")
                    chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(chooser)
                }
            }
        }
    }
}

fun MutableSharedFlow<UiEvent>.toast(
    @StringRes resId: Int,
    args: List<Any> = emptyList()
) {
    tryEmit(UiEvent.ToastRes(resId, args))
}

