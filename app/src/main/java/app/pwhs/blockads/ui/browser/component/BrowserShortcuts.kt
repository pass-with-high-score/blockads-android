package app.pwhs.blockads.ui.browser.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pwhs.blockads.R

data class QuickShortcut(
    val title: String,
    val url: String,
    val iconRes: Int? = null,
    val vectorIcon: ImageVector? = null,
    val iconTint: Color = Color.Unspecified,
    val bgColor: Color = Color.Transparent
)

private val HOME_SHORTCUTS = listOf(
    QuickShortcut("YouTube", "https://m.youtube.com", iconRes = R.drawable.ic_brand_youtube),
    QuickShortcut("Google", "https://www.google.com", iconRes = R.drawable.ic_brand_google),
    QuickShortcut("Facebook", "https://m.facebook.com", iconRes = R.drawable.ic_brand_facebook),
    QuickShortcut("TikTok", "https://www.tiktok.com", iconRes = R.drawable.ic_brand_tiktok),
    QuickShortcut("Reddit", "https://www.reddit.com", iconRes = R.drawable.ic_brand_reddit),
    QuickShortcut("X", "https://x.com", iconRes = R.drawable.ic_brand_x),
    QuickShortcut("ChatGPT", "https://chatgpt.com", iconRes = R.drawable.ic_brand_chatgpt),
)

@Composable
fun BrowserShortcuts(
    onSelectShortcut: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenMenu: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showBanner by remember { mutableStateOf(true) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF231826), // Dark Plum Top
                        Color(0xFF1B121F), // Deeper Plum
                        Color(0xFF130D16)  // Bottom AMOLED Plum
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Bar
            HomeTopBar(onOpenMenu = onOpenMenu)

            Spacer(modifier = Modifier.height(28.dp))

            // Hero Brand Logo
            HomeHeroBrand()

            Spacer(modifier = Modifier.height(24.dp))

            // Capsule Search Omnibox
            HomeSearchCapsule(onOpenSearch = onOpenSearch)

            Spacer(modifier = Modifier.height(20.dp))

            // Feature / Protection Banner Carousel
            AnimatedVisibility(visible = showBanner) {
                HomeFeatureBanner(onDismiss = { showBanner = false })
            }

            if (showBanner) {
                Spacer(modifier = Modifier.height(24.dp))
            }

            // Speed Dial Grid (4 items per row)
            HomeSpeedDialGrid(
                shortcuts = HOME_SHORTCUTS,
                onSelect = onSelectShortcut
            )

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun HomeTopBar(onOpenMenu: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Shield Protection Status Badge
        Surface(
            color = Color(0xFF10B981).copy(alpha = 0.15f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.clip(RoundedCornerShape(12.dp))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = Color(0xFF10B981),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.browser_home_protecting),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = Color(0xFF10B981)
                )
            }
        }

        // More Menu Button (3 Dots)
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF332438))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                .clickable { onOpenMenu() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.browser_action_menu),
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun HomeHeroBrand() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displayMedium.copy(
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.SansSerif,
                letterSpacing = (-1).sp
            ),
            color = Color.White
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.browser_home_hero_tagline),
            style = MaterialTheme.typography.labelSmall.copy(
                letterSpacing = 0.5.sp,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            ),
            color = Color.White.copy(alpha = 0.55f)
        )
    }
}

@Composable
private fun HomeSearchCapsule(onOpenSearch: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(27.dp))
            .background(Color(0xFF2E2032))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(27.dp))
            .clickable { onOpenSearch() }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(20.dp)
            )

            Text(
                text = stringResource(R.string.browser_home_search_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun HomeFeatureBanner(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color(0xFF0284C7), // Bright Cyan Blue
                        Color(0xFF4F46E5)  // Indigo Purple
                    )
                )
            )
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Column {
                    Text(
                        text = stringResource(R.string.browser_home_banner_title),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.browser_home_banner_subtitle),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }

            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.browser_action_close),
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .clickable { onDismiss() }
            )
        }
    }
}

@Composable
private fun HomeSpeedDialGrid(
    shortcuts: List<QuickShortcut>,
    onSelect: (String) -> Unit
) {
    // 4 items per row
    val rows = shortcuts.chunked(4)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        for (row in rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                for (item in row) {
                    SpeedDialItem(
                        shortcut = item,
                        onClick = { onSelect(item.url) },
                        modifier = Modifier.weight(1f)
                    )
                }
                // Fill remainder if last row has less than 4 to keep symmetrical grid
                for (i in row.size until 4) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SpeedDialItem(
    shortcut: QuickShortcut,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(Color(0xFF261D2B))
                .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (shortcut.iconRes != null) {
                Image(
                    painter = androidx.compose.ui.res.painterResource(id = shortcut.iconRes),
                    contentDescription = shortcut.title,
                    modifier = Modifier.size(50.dp)
                )
            } else if (shortcut.vectorIcon != null) {
                Icon(
                    imageVector = shortcut.vectorIcon,
                    contentDescription = shortcut.title,
                    tint = shortcut.iconTint,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
        Text(
            text = shortcut.title,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 11.5.sp
            ),
            color = Color.White.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
