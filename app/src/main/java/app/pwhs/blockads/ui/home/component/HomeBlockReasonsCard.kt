package app.pwhs.blockads.ui.home.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.entities.BlockReasonStat
import app.pwhs.blockads.ui.theme.AccentBlue
import app.pwhs.blockads.ui.theme.DangerRed
import app.pwhs.blockads.ui.theme.SecurityOrange
import app.pwhs.blockads.ui.theme.TextSecondary
import app.pwhs.blockads.ui.theme.UpstreamDnsPurple
import app.pwhs.blockads.ui.theme.WhitelistAmber
import app.pwhs.blockads.utils.formatCount

@Composable
fun HomeBlockReasonsCard(
    reasons: List<BlockReasonStat>,
    onViewStatsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (reasons.isEmpty()) return

    val totalCount = reasons.sumOf { it.count }.coerceAtLeast(1)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.home_widget_block_reasons),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        text = stringResource(R.string.home_widget_24h_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onViewStatsClick)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = stringResource(R.string.home_widget_stats_link),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp),
                )
            }
        }

        Card(
            onClick = onViewStatsClick,
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Stacked bar breakdown
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    reasons.forEachIndexed { index, reason ->
                        val weight = (reason.count.toFloat() / totalCount.toFloat()).coerceAtLeast(0.03f)
                        Box(
                            modifier = Modifier
                                .weight(weight)
                                .fillMaxHeight()
                                .background(reasonCategoryColor(reason.category, index)),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    reasons.forEachIndexed { index, reason ->
                        val color = reasonCategoryColor(reason.category, index)
                        val percent = (reason.count * 100f / totalCount).toInt()
                        val displayName = if (reason.filterId != null) {
                            reason.displayName.ifBlank { stringResource(R.string.stats_block_reason_filter_list) }
                        } else {
                            when (reason.category) {
                                "CUSTOM" -> stringResource(R.string.stats_block_reason_custom)
                                "FIREWALL" -> stringResource(R.string.stats_block_reason_firewall)
                                "SECURITY" -> stringResource(R.string.stats_block_reason_security)
                                "DNS" -> stringResource(R.string.stats_block_reason_upstream)
                                "AD" -> stringResource(R.string.stats_block_reason_filter_list)
                                else -> reason.displayName
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(color),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onBackground,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${formatCount(reason.count)} ($percent%)",
                                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                color = color,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun reasonCategoryColor(category: String, index: Int): Color = when (category) {
    "SECURITY" -> SecurityOrange
    "FIREWALL" -> DangerRed
    "CUSTOM" -> WhitelistAmber
    "DNS" -> UpstreamDnsPurple
    else -> when (index % 4) {
        0 -> AccentBlue
        1 -> UpstreamDnsPurple
        2 -> SecurityOrange
        else -> DangerRed
    }
}
