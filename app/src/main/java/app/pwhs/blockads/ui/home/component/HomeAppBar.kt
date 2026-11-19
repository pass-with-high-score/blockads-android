package app.pwhs.blockads.ui.home.component

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DashboardCustomize
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    onNavigateToBrowser: (String) -> Unit = {},
    isEditing: Boolean = false,
    onToggleEdit: () -> Unit = {},
    onResetWidgets: () -> Unit = {},
) {
    val telegramUri = stringResource(R.string.telegram_link).toUri()
    val testBlockUri = stringResource(R.string.test_block_link).toUri()
    val context = LocalContext.current

    TopAppBar(
        modifier = modifier,
        navigationIcon = {
            if (!isEditing) {
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
            }
        },
        title = {
            AnimatedContent(
                targetState = Triple(isEditing, isLoading, filterLoadFailed),
                transitionSpec = {
                    (fadeIn(tween(200)) + slideInVertically { -it / 4 })
                        .togetherWith(fadeOut(tween(150)) + slideOutVertically { it / 4 })
                },
                label = "home_app_bar_title"
            ) { (editing, loading, failed) ->
                when {
                    editing -> {
                        Text(
                            text = stringResource(R.string.filter_action_edit),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    loading -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Loading filters…",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    failed -> {
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
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    else -> Spacer(modifier = Modifier.size(0.dp))
                }
            }
        },
        actions = {
            AnimatedContent(
                targetState = isEditing,
                transitionSpec = {
                    (fadeIn(tween(200)) + scaleIn(initialScale = 0.92f))
                        .togetherWith(fadeOut(tween(150)) + scaleOut(targetScale = 0.92f))
                },
                label = "home_app_bar_actions"
            ) { editing ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (editing) {
                        FilledIconButton(
                            onClick = onToggleEdit,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(42.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = stringResource(R.string.ok),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    } else {
                        IconButton(onClick = { onNavigateToBrowser("https://m.youtube.com") }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_browser),
                                contentDescription = stringResource(R.string.nav_browser),
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
                        IconButton(onClick = onToggleEdit) {
                            Icon(
                                imageVector = Icons.Default.DashboardCustomize,
                                contentDescription = stringResource(R.string.filter_action_edit),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            actionIconContentColor = MaterialTheme.colorScheme.primary
        )
    )
}
