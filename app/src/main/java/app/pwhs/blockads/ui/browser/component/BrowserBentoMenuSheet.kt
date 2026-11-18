package app.pwhs.blockads.ui.browser.component

import android.content.Intent
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shield
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserBentoMenuSheet(
    isVisible: Boolean,
    blockedCount: Int,
    adBlockEnabled: Boolean,
    popupBlockEnabled: Boolean = true,
    isDesktopMode: Boolean,
    isAutoPipEnabled: Boolean = true,
    ruleVersion: Long,
    ruleDomainsCount: Int,
    isCheckingRuleUpdates: Boolean,
    onDismiss: () -> Unit,
    onToggleAdBlock: () -> Unit,
    onTogglePopupBlock: () -> Unit = {},
    onToggleDesktopMode: () -> Unit,
    onToggleAutoPip: () -> Unit = {},
    onEnterPip: () -> Unit,
    onClearData: () -> Unit,
    onOpenExternal: () -> Unit,
    onShare: () -> Unit,
    onCloseBrowser: () -> Unit,
    onCheckRuleUpdates: () -> Unit,
    onActivateElementPicker: () -> Unit = {},
    onNavigateToElementRules: () -> Unit = {},
    onOpenDebugLogger: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (!isVisible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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

                // Right Column: Toggle AdBlock + Toggle Popup Block
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    BentoToggleCard(
                        title = stringResource(R.string.browser_adblock_title),
                        subtitle = if (adBlockEnabled) stringResource(R.string.browser_status_active) else stringResource(R.string.browser_status_paused),
                        icon = Icons.Default.Shield,
                        checked = adBlockEnabled,
                        onCheckedChange = { onToggleAdBlock() },
                        activeColor = Color(0xFF10B981),
                        modifier = Modifier.weight(1f)
                    )

                    BentoToggleCard(
                        title = stringResource(R.string.browser_popup_title),
                        subtitle = if (popupBlockEnabled) stringResource(R.string.browser_status_active) else stringResource(R.string.browser_status_paused),
                        icon = Icons.AutoMirrored.Filled.OpenInNew,
                        checked = popupBlockEnabled,
                        onCheckedChange = { onTogglePopupBlock() },
                        activeColor = Color(0xFFEC4899),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Row 2: Desktop Mode Toggle Card
            BentoToggleCard(
                title = stringResource(R.string.browser_desktop_title),
                subtitle = if (isDesktopMode) stringResource(R.string.browser_desktop_active) else stringResource(R.string.browser_desktop_inactive),
                icon = Icons.Default.Computer,
                checked = isDesktopMode,
                onCheckedChange = { onToggleDesktopMode() },
                activeColor = Color(0xFF6366F1),
                modifier = Modifier.fillMaxWidth()
            )

            // Row 3: Auto-PiP vs Background Audio Mode Toggle
            BentoToggleCard(
                title = if (isAutoPipEnabled) stringResource(R.string.browser_pip_mode_pip) else stringResource(R.string.browser_pip_mode_audio),
                subtitle = if (isAutoPipEnabled)
                    stringResource(R.string.browser_pip_desc_pip)
                else
                    stringResource(R.string.browser_pip_desc_audio),
                icon = if (isAutoPipEnabled) Icons.Default.PictureInPictureAlt else Icons.Default.PlayArrow,
                checked = isAutoPipEnabled,
                onCheckedChange = { onToggleAutoPip() },
                activeColor = Color(0xFFD946EF),
                modifier = Modifier.fillMaxWidth()
            )

            // Direct PiP action if user wants to enter PiP right now
            BentoPipCard(onEnterPip = {
                onDismiss()
                onEnterPip()
            })

            // Row 4: 3 Squircle Quick Action Buttons
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
                }
            )

            // Row 5: Block Element CTA
            BentoCtaButton(
                title = stringResource(R.string.browser_pick_element_title),
                subtitle = stringResource(R.string.browser_pick_element_desc),
                icon = Icons.Default.Block,
                onClick = {
                    onDismiss()
                    onActivateElementPicker()
                }
            )

            // Row 6: Manage Rules CTA
            BentoCtaButton(
                title = stringResource(R.string.browser_manage_rules_title),
                subtitle = stringResource(R.string.browser_manage_rules_desc),
                icon = Icons.Default.FilterList,
                onClick = {
                    onDismiss()
                    onNavigateToElementRules()
                }
            )

            // Row 7: HTTP & Scriptlet Debug Logger
            BentoCtaButton(
                title = stringResource(R.string.https_debug_logs_title),
                subtitle = stringResource(R.string.https_debug_logs_subtitle),
                icon = Icons.Default.BugReport,
                onClick = {
                    onDismiss()
                    onOpenDebugLogger()
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
                    text = stringResource(R.string.browser_exit_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}
