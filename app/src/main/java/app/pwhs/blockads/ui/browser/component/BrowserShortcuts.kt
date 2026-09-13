package app.pwhs.blockads.ui.browser.component

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class QuickShortcut(
    val title: String,
    val url: String,
    val iconRes: Int? = null,
    val vectorIcon: ImageVector? = null,
    val iconTint: Color = Color.Unspecified,
    val bgColor: Color
)

private val HOME_SHORTCUTS = listOf(
    QuickShortcut("Google", "https://www.google.com", iconRes = app.pwhs.blockads.R.drawable.ic_brand_google, bgColor = Color.White),
    QuickShortcut("YouTube", "https://m.youtube.com", iconRes = app.pwhs.blockads.R.drawable.ic_settings_youtube, iconTint = Color.White, bgColor = Color(0xFFFF0000)),
    QuickShortcut("Facebook", "https://m.facebook.com", iconRes = app.pwhs.blockads.R.drawable.ic_brand_facebook, bgColor = Color(0xFF1877F2)),
    QuickShortcut("Reddit", "https://www.reddit.com", iconRes = app.pwhs.blockads.R.drawable.ic_reddit, iconTint = Color(0xFFFF4500), bgColor = Color.White),
    QuickShortcut("TikTok", "https://www.tiktok.com", iconRes = app.pwhs.blockads.R.drawable.ic_brand_tiktok, bgColor = Color.Black),
    QuickShortcut("X", "https://x.com", iconRes = app.pwhs.blockads.R.drawable.ic_brand_x, bgColor = Color.Black),
    QuickShortcut("ChatGPT", "https://chatgpt.com", iconRes = app.pwhs.blockads.R.drawable.ic_brand_chatgpt, bgColor = Color(0xFF10A37F)),
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
        // Shield Pro Badge Button
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
                imageVector = Icons.Default.Shield,
                contentDescription = "Shield Pro",
                tint = Color(0xFF10B981),
                modifier = Modifier.size(20.dp)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Scanner Icon Button
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF332438))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = "QR Scanner",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp)
                )
            }

            // Settings / Tune Button
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
                    imageVector = Icons.Default.Tune,
                    contentDescription = "Settings",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp)
                )
            }
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
            text = "BlockAds",
            style = MaterialTheme.typography.displayMedium.copy(
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.SansSerif,
                letterSpacing = (-1).sp
            ),
            color = Color.White
        )
        Text(
            text = "Trình duyệt bảo vệ quyền riêng tư",
            style = MaterialTheme.typography.labelSmall.copy(
                letterSpacing = 0.5.sp,
                fontSize = 11.sp
            ),
            color = Color.White.copy(alpha = 0.45f)
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
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                // VPN / Shield Badge
                Surface(
                    color = Color(0xFF10B981).copy(alpha = 0.18f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "VPN",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp
                        ),
                        color = Color(0xFF10B981),
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }

                // Google G logo placeholder
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .background(Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "G",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFF4285F4)
                    )
                }

                Text(
                    text = "Tìm kiếm hoặc nhập URL...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.45f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // AI Action Badge
            Surface(
                color = Color(0xFFD946EF).copy(alpha = 0.25f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "AI",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color(0xFFF472B6),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
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
                        text = "Bảo vệ 100% không quảng cáo",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Lọc sạch video ads, popups & mã theo dõi",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }

            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Đóng",
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
                // Fill remainder if last row has less than 4
                if (row.size < 4) {
                    // Add Button in last available slot
                    AddShortcutItem(modifier = Modifier.weight(1f))
                    for (i in (row.size + 1) until 4) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
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
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(shortcut.bgColor)
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (shortcut.iconRes != null) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(id = shortcut.iconRes),
                    contentDescription = shortcut.title,
                    tint = shortcut.iconTint,
                    modifier = Modifier.size(32.dp)
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

@Composable
private fun AddShortcutItem(modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF2C2030))
                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Thêm lối tắt",
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            text = "Thêm",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 11.5.sp
            ),
            color = Color.White.copy(alpha = 0.45f),
            textAlign = TextAlign.Center
        )
    }
}
