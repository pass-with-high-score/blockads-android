package app.pwhs.blockads.ui.httpsfiltering.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.httpsfiltering.CertStatus

@Composable
fun CertificateStatusCard(
    certStatus: CertStatus,
    isRootAvailable: Boolean,
    onOpenWizard: () -> Unit,
    onVerifyCert: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (certStatus) {
                CertStatus.INSTALLED -> MaterialTheme.colorScheme.surface
                CertStatus.CHECKING -> MaterialTheme.colorScheme.surface
                CertStatus.NOT_INSTALLED, CertStatus.UNKNOWN -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
            }
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Header with status
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            when (certStatus) {
                                CertStatus.INSTALLED -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                CertStatus.CHECKING -> MaterialTheme.colorScheme.surfaceVariant
                                CertStatus.NOT_INSTALLED, CertStatus.UNKNOWN -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    when (certStatus) {
                        CertStatus.INSTALLED -> {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        CertStatus.CHECKING -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.5.dp
                            )
                        }
                        CertStatus.NOT_INSTALLED, CertStatus.UNKNOWN -> {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.https_filtering_ca_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = when (certStatus) {
                            CertStatus.INSTALLED -> stringResource(R.string.https_filtering_cert_installed)
                            CertStatus.CHECKING -> stringResource(R.string.https_filtering_cert_checking)
                            CertStatus.NOT_INSTALLED, CertStatus.UNKNOWN -> stringResource(R.string.https_filtering_cert_not_installed)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (certStatus) {
                            CertStatus.INSTALLED -> MaterialTheme.colorScheme.primary
                            CertStatus.CHECKING -> MaterialTheme.colorScheme.onSurfaceVariant
                            CertStatus.NOT_INSTALLED, CertStatus.UNKNOWN -> MaterialTheme.colorScheme.error
                        },
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = when (certStatus) {
                    CertStatus.INSTALLED -> "Chứng chỉ CA đã được cài đặt và tin cậy. Lọc quảng cáo nâng cao HTTPS đã sẵn sàng."
                    CertStatus.CHECKING -> "Đang kiểm tra chứng chỉ trong kho lưu trữ an toàn của thiết bị..."
                    CertStatus.NOT_INSTALLED, CertStatus.UNKNOWN -> "Cần cài đặt chứng chỉ CA BlockAds để giải mã và loại bỏ quảng cáo trên các kết nối web HTTPS."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Actions
            when (certStatus) {
                CertStatus.INSTALLED -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onVerifyCert,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Kiểm tra lại",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }

                        TextButton(onClick = onOpenWizard) {
                            Text(
                                text = "Cài đặt lại",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
                CertStatus.CHECKING -> {
                    // Just wait
                }
                CertStatus.NOT_INSTALLED, CertStatus.UNKNOWN -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onOpenWizard,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "Cài đặt chứng chỉ",
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        OutlinedButton(
                            onClick = onVerifyCert,
                            modifier = Modifier.height(46.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
