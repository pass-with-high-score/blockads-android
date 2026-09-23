package app.pwhs.blockads.ui.httpsfiltering.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.httpsfiltering.CertStatus

@Composable
fun SetupGuideCard(
    certExported: Boolean,
    certStatus: CertStatus,
    isRootAvailable: Boolean = false,
    onExport: () -> Unit,
    onOpenSettings: () -> Unit,
    onInstallRoot: () -> Unit = {},
    onVerifyCert: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = stringResource(R.string.https_filtering_ca_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.https_filtering_ca_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Step 1: Save Certificate ─────────────────────────────
            StepItem(
                stepNumber = 1,
                title = stringResource(R.string.https_filtering_step1_title),
                description = stringResource(R.string.https_filtering_step1_desc),
                isDone = certExported,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onExport,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (certExported) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            ) {
                Icon(
                    imageVector = Icons.Default.FileDownload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (certExported) {
                        stringResource(R.string.https_filtering_cert_saved)
                    } else {
                        stringResource(R.string.https_filtering_export_ca)
                    },
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(16.dp))

            // ── Step 2: Install Certificate ──────────────────────────
            StepItem(
                stepNumber = 2,
                title = stringResource(R.string.https_filtering_step2_title),
                description = stringResource(R.string.https_filtering_step2_desc),
                isDone = certStatus == CertStatus.INSTALLED,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Install instructions
            Text(
                text = stringResource(R.string.https_filtering_install_steps),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight,
                modifier = Modifier.padding(start = 4.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (isRootAvailable && certStatus != CertStatus.INSTALLED) {
                Button(
                    onClick = onInstallRoot,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Security,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.https_filtering_root_install),
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.https_filtering_open_settings),
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(16.dp))

            // ── Step 3: Verify Certificate ───────────────────────────
            StepItem(
                stepNumber = 3,
                title = stringResource(R.string.https_filtering_step3_title),
                description = stringResource(R.string.https_filtering_step3_desc),
                isDone = certStatus == CertStatus.INSTALLED,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Cert status badge
            CertStatusBadge(certStatus = certStatus)

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onVerifyCert,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                enabled = certStatus != CertStatus.CHECKING
            ) {
                if (certStatus == CertStatus.CHECKING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Verified,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.https_filtering_verify_cert),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
