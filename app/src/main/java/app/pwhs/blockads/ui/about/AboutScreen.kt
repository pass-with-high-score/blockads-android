package app.pwhs.blockads.ui.about

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.pwhs.blockads.BuildConfig
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.settings.component.SettingIconBadge
import app.pwhs.blockads.ui.settings.component.SettingsCard
import app.pwhs.blockads.ui.theme.DarkBackground
import app.pwhs.blockads.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = { }
) {
    val context = LocalContext.current
    val dividerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.about_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.accessibility_navigate_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // App icon with glow
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                Color.Transparent
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(DarkBackground),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(4.dp))

            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.about_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Privacy promise card
            SettingsCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingIconBadge(
                        painter = painterResource(R.drawable.ic_settings_https),
                        tint = Color(0xFF059669)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = stringResource(R.string.about_no_data),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Links in a single Grouped Card
            SettingsCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    val linkItems = listOf(
                        LinkConfig(
                            icon = Icons.Filled.Code,
                            tint = Color(0xFF2563EB),
                            title = stringResource(R.string.about_github),
                            onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/pass-with-high-score/blockads-android".toUri()))
                            }
                        ),
                        LinkConfig(
                            icon = Icons.Filled.Language,
                            tint = Color(0xFF059669),
                            title = stringResource(R.string.about_website),
                            onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, "https://blockads.pwhs.app/".toUri()))
                            }
                        ),
                        LinkConfig(
                            icon = Icons.Filled.PrivacyTip,
                            tint = Color(0xFF7C3AED),
                            title = stringResource(R.string.about_privacy_policy),
                            onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, "https://blockads.pwhs.app/privacy".toUri()))
                            }
                        ),
                        LinkConfig(
                            icon = Icons.Filled.Email,
                            tint = Color(0xFFEA580C),
                            title = stringResource(R.string.about_contact),
                            onClick = {
                                val intent = Intent(Intent.ACTION_SENDTO).apply {
                                    data = "mailto:support@pwhs.app".toUri()
                                }
                                context.startActivity(intent)
                            }
                        ),
                        LinkConfig(
                            icon = Icons.Filled.Gavel,
                            tint = Color(0xFF64748B),
                            title = stringResource(R.string.about_licenses),
                            onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/pass-with-high-score/blockads-android?tab=GPL-3.0-1-ov-file".toUri()))
                            }
                        ),
                    )

                    linkItems.forEachIndexed { index, item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { item.onClick() }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SettingIconBadge(
                                icon = item.icon,
                                tint = item.tint
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        if (index < linkItems.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = dividerColor
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(96.dp))
        }
    }
}

private data class LinkConfig(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val tint: Color,
    val title: String,
    val onClick: () -> Unit
)

