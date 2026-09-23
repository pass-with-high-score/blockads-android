package app.pwhs.blockads.ui.home.component

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.home.HomeViewModel
import app.pwhs.blockads.ui.theme.DangerRed
import app.pwhs.blockads.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeAppBar(
    modifier: Modifier = Modifier,
    isLoading: Boolean,
    filterLoadFailed: Boolean,
    viewModel: HomeViewModel,
    onNavigateToStatisticsScreen: () -> Unit,
    onNavigateToLogScreen: () -> Unit,
    onNavigateToBrowser: (String) -> Unit = {}
) {
    val telegramUri = stringResource(R.string.telegram_link).toUri()
    val testBlockUri = stringResource(R.string.test_block_link).toUri()
    val context = LocalContext.current

    TopAppBar(
        modifier = modifier,
        navigationIcon = {
           Row {
               IconButton(onClick = {
                   val intent = Intent(Intent.ACTION_VIEW).apply {
                       data = testBlockUri
                   }
                   context.startActivity(intent)
               }) {
                   Icon(
                       painter = painterResource(R.drawable.ic_bug),
                       contentDescription = stringResource(R.string.test_block_ads),
                       tint = MaterialTheme.colorScheme.primary,
                       modifier = Modifier.size(24.dp)
                   )
               }
               IconButton(onClick = {
                   val intent = Intent(Intent.ACTION_VIEW).apply {
                       data = telegramUri
                   }
                   context.startActivity(intent)
               }) {
                   Icon(
                       painter = painterResource(R.drawable.ic_telegram),
                       contentDescription = stringResource(R.string.settings_telegram),
                       tint = MaterialTheme.colorScheme.primary,
                       modifier = Modifier.size(24.dp)
                   )
               }
           }
        },
        title = {
            if (isLoading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Loading filters…",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            } else if (filterLoadFailed) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(DangerRed.copy(alpha = 0.1f))
                        .clickable { viewModel.retryLoadFilter() }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Retry",
                        tint = DangerRed,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Filter load failed · Tap to retry",
                        style = MaterialTheme.typography.bodySmall,
                        color = DangerRed,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = { onNavigateToBrowser("https://m.youtube.com") }) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings_youtube),
                    contentDescription = "YouTube Ad-Free",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
            IconButton(onClick = onNavigateToStatisticsScreen) {
                Icon(
                    painter = painterResource(R.drawable.ic_chart_bar),
                    contentDescription = stringResource(R.string.nav_statistics),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
            IconButton(onClick = onNavigateToLogScreen) {
                Icon(
                    painter = painterResource(R.drawable.ic_history),
                    contentDescription = stringResource(R.string.nav_logs),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            actionIconContentColor = MaterialTheme.colorScheme.primary
        )
    )
}