package app.pwhs.blockads.ui.browser.component

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserBentoMenuSheet(
    isVisible: Boolean,
    blockedCount: Int,
    adBlockEnabled: Boolean,
    isDesktopMode: Boolean,
    ruleVersion: Long,
    ruleDomainsCount: Int,
    isCheckingRuleUpdates: Boolean,
    onDismiss: () -> Unit,
    onToggleAdBlock: () -> Unit,
    onToggleDesktopMode: () -> Unit,
    onEnterPip: () -> Unit,
    onClearData: () -> Unit,
    onOpenExternal: () -> Unit,
    onShare: () -> Unit,
    onHome: () -> Unit,
    onCloseBrowser: () -> Unit,
    onCheckRuleUpdates: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isVisible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1E1724), // Dark Plum Background
        contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .background(Color.White.copy(alpha = 0.24f), CircleShape)
            )
        },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            BentoHeader()

            // Row 1: Bento Grid (Stats Card on Left + 2 Toggle Cards on Right)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Left Card: Adblock Stats & Rule Version
                BentoStatsCard(
                    blockedCount = blockedCount,
                    ruleVersion = ruleVersion,
                    ruleDomainsCount = ruleDomainsCount,
                    isCheckingRuleUpdates = isCheckingRuleUpdates,
                    onCheckRuleUpdates = onCheckRuleUpdates,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )

                // Right Column: Toggle AdBlock + Toggle Desktop Mode
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    BentoToggleCard(
                        title = "Chặn Quảng cáo",
                        subtitle = if (adBlockEnabled) "Đang kích hoạt" else "Đã tạm dừng",
                        icon = Icons.Default.Shield,
                        checked = adBlockEnabled,
                        onCheckedChange = { onToggleAdBlock() },
                        activeColor = Color(0xFF10B981),
                        modifier = Modifier.weight(1f)
                    )

                    BentoToggleCard(
                        title = "Bản Máy tính",
                        subtitle = if (isDesktopMode) "Bật giao diện PC" else "Bản di động",
                        icon = Icons.Default.Computer,
                        checked = isDesktopMode,
                        onCheckedChange = { onToggleDesktopMode() },
                        activeColor = Color(0xFF6366F1),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Row 2: Picture-in-Picture Card
            BentoPipCard(onEnterPip = {
                onDismiss()
                onEnterPip()
            })

            // Row 3: 4 Squircle Quick Action Buttons
            BentoQuickActionsRow(
                onClearData = {
                    onDismiss()
                    onClearData()
                },
                onShare = {
                    onDismiss()
                    onShare()
                },
                onOpenExternal = {
                    onDismiss()
                    onOpenExternal()
                },
                onHome = {
                    onDismiss()
                    onHome()
                }
            )

            // Row 4: Set as Default Browser CTA
            BentoCtaButton(
                title = "Đặt làm trình duyệt mặc định",
                subtitle = "Bảo vệ liên tục khi mở mọi liên kết",
                icon = Icons.Default.Star,
                onClick = {
                    runCatching {
                        val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                        context.startActivity(intent)
                    }
                }
            )

            // Row 5: Exit Browser
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable {
                        onDismiss()
                        onCloseBrowser()
                    }
                    .padding(vertical = 12.dp, horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Đóng trình duyệt",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}
