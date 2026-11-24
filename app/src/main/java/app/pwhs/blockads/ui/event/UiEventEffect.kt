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
                    val shared = app.pwhs.blockads.utils.ExportHelper.shareUri(
                        context = context,
                        uri = event.uri,
                        mimeType = event.mimeType,
                        chooserTitle = "Share Logs"
                    )
                    if (!shared) {
                        try {
                            val ext = if (event.mimeType == "text/csv") "csv" else "txt"
                            val fileName = "blockads_export_${System.currentTimeMillis()}.$ext"
                            app.pwhs.blockads.utils.ExportHelper.copyUriToDownloads(context, event.uri, fileName, event.mimeType)
                            Toast.makeText(context, "Saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
                        } catch (e2: Exception) {
                            timber.log.Timber.e(e2, "Failed to copy export to Downloads fallback")
                        }
                    }
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

