package app.pwhs.blockads.ui.profile.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.data.entities.ProtectionProfile
import app.pwhs.blockads.ui.theme.TextSecondary
import app.pwhs.blockads.util.profileDescription
import app.pwhs.blockads.util.profileDisplayName
import app.pwhs.blockads.util.profileIcon

@Composable
fun ProfileItem(
    profile: ProtectionProfile,
    isActive: Boolean,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)?,
    onSchedule: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = profileIcon(profile.profileType),
                contentDescription = null,
                tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profileDisplayName(profile),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = profileDescription(profile.profileType),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }

        IconButton(onClick = onSchedule, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Schedule,
                contentDescription = stringResource(R.string.profile_add_schedule),
                tint = TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }

        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        if (isActive) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = stringResource(R.string.profile_active),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
