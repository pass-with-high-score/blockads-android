package app.pwhs.blockads.ui.home.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.network.NetworkSpeed
import app.pwhs.blockads.utils.formatDataSize
import app.pwhs.blockads.utils.formatSpeed

private val DownloadCyan = Color(0xFF00BCD4)
private val UploadPurple = Color(0xFFA855F7)

@Composable
fun NetworkSpeedSection(
    networkSpeed: NetworkSpeed,
    modifier: Modifier = Modifier
) {
    val (downVal, downUnit) = splitSpeedParts(formatSpeed(networkSpeed.downloadBps))
    val (upVal, upUnit) = splitSpeedParts(formatSpeed(networkSpeed.uploadBps))

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TrafficCard(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            icon = Icons.Default.ArrowDownward,
            label = stringResource(R.string.home_downloaded),
            speedValue = downVal,
            speedUnit = downUnit,
            totalText = "Total ${formatDataSize(networkSpeed.totalDownloadedBytes / 1024)}",
            color = DownloadCyan,
            chartPoints = networkSpeed.downloadHistory,
            bobDirection = 1
        )
        TrafficCard(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            icon = Icons.Default.ArrowUpward,
            label = stringResource(R.string.home_uploaded),
            speedValue = upVal,
            speedUnit = upUnit,
            totalText = "Total ${formatDataSize(networkSpeed.totalUploadedBytes / 1024)}",
            color = UploadPurple,
            chartPoints = networkSpeed.uploadHistory,
            bobDirection = -1
        )
    }
}

private fun splitSpeedParts(formatted: String): Pair<String, String> {
    val idx = formatted.indexOf(' ')
    return if (idx > 0) {
        formatted.substring(0, idx) to formatted.substring(idx + 1)
    } else {
        formatted to ""
    }
}
