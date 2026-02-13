package app.pwhs.blockads.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.onboarding.component.CompletionStep
import app.pwhs.blockads.ui.onboarding.component.DnsServerStep
import app.pwhs.blockads.ui.onboarding.component.OnboardingPageContent
import app.pwhs.blockads.ui.onboarding.component.PermissionStep
import app.pwhs.blockads.ui.onboarding.component.ProtectionLevelStep
import app.pwhs.blockads.ui.onboarding.data.OnboardingPage
import app.pwhs.blockads.ui.theme.AccentBlue
import app.pwhs.blockads.ui.theme.NeonGreen
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.NavGraphs
import com.ramcosta.composedestinations.generated.destinations.HomeScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

private const val TOTAL_PAGES = 7

@Destination<RootGraph>
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    navigator: DestinationsNavigator,
    viewModel: OnboardingViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val selectedProtectionLevel by viewModel.selectedProtectionLevel.collectAsState()
    val selectedDnsProvider by viewModel.selectedDnsProvider.collectAsState()

    val pagerState = rememberPagerState(pageCount = { TOTAL_PAGES })
    val isLastPage = pagerState.currentPage == TOTAL_PAGES - 1

    // VPN permission launcher
    var vpnPermissionGranted by remember { mutableStateOf(VpnService.prepare(context) == null) }
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        vpnPermissionGranted = VpnService.prepare(context) == null
    }

    // Notification permission (Android 13+)
    var notificationPermissionGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
            } else true
        )
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationPermissionGranted = granted
    }

    // Battery optimization
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    var batteryOptimizationExcluded by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }
    val batteryOptLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        batteryOptimizationExcluded = powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun skipToHome() {
        scope.launch {
            viewModel.completeOnboarding()
            navigator.navigate(HomeScreenDestination) {
                popUpTo(NavGraphs.root) { inclusive = true }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {},
                actions = {
                    AnimatedVisibility(
                        visible = !isLastPage,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        TextButton(onClick = { skipToHome() }) {
                            Text(
                                text = stringResource(R.string.onboarding_skip),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Pager content
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { page ->
                when (page) {
                    // Step 1: Welcome + Privacy promise
                    0 -> OnboardingPageContent(
                        OnboardingPage(
                            icon = Icons.Filled.Shield,
                            title = stringResource(R.string.onboarding_title_1),
                            description = stringResource(R.string.onboarding_desc_1)
                        )
                    )
                    // Step 2: Protection level
                    1 -> ProtectionLevelStep(
                        selectedLevel = selectedProtectionLevel,
                        onLevelSelected = { viewModel.selectProtectionLevel(it) }
                    )
                    // Step 3: DNS server
                    2 -> DnsServerStep(
                        selectedProvider = selectedDnsProvider,
                        onProviderSelected = { viewModel.selectDnsProvider(it) }
                    )
                    // Step 4: VPN permission
                    3 -> PermissionStep(
                        icon = Icons.Filled.VpnKey,
                        title = stringResource(R.string.onboarding_vpn_title),
                        description = stringResource(R.string.onboarding_vpn_desc),
                        buttonText = stringResource(R.string.onboarding_vpn_grant),
                        isGranted = vpnPermissionGranted,
                        grantedText = stringResource(R.string.onboarding_permission_granted),
                        onRequestPermission = {
                            val intent = VpnService.prepare(context)
                            if (intent != null) {
                                vpnPermissionLauncher.launch(intent)
                            } else {
                                vpnPermissionGranted = true
                            }
                        }
                    )
                    // Step 5: Notification permission (Android 13+)
                    4 -> PermissionStep(
                        icon = Icons.Filled.Notifications,
                        title = stringResource(R.string.onboarding_notification_title),
                        description = stringResource(R.string.onboarding_notification_desc),
                        buttonText = stringResource(R.string.onboarding_notification_grant),
                        accentColor = AccentBlue,
                        isGranted = notificationPermissionGranted,
                        grantedText = stringResource(R.string.onboarding_permission_granted),
                        onRequestPermission = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermissionLauncher.launch(
                                    Manifest.permission.POST_NOTIFICATIONS
                                )
                            } else {
                                notificationPermissionGranted = true
                            }
                        }
                    )
                    // Step 6: Battery optimization
                    5 -> PermissionStep(
                        icon = Icons.Filled.BatteryChargingFull,
                        title = stringResource(R.string.onboarding_battery_title),
                        description = stringResource(R.string.onboarding_battery_desc),
                        buttonText = stringResource(R.string.onboarding_battery_grant),
                        accentColor = NeonGreen,
                        isGranted = batteryOptimizationExcluded,
                        grantedText = stringResource(R.string.onboarding_permission_granted),
                        onRequestPermission = {
                            @Suppress("BatteryLife")
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = android.net.Uri.parse("package:${context.packageName}")
                            }
                            if (intent.resolveActivity(context.packageManager) != null) {
                                batteryOptLauncher.launch(intent)
                            }
                        }
                    )
                    // Completion
                    6 -> CompletionStep()
                }
            }

            // Page indicators
            Row(
                modifier = Modifier.padding(vertical = 24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(TOTAL_PAGES) { index ->
                    val isSelected = pagerState.currentPage == index
                    val width by animateFloatAsState(
                        targetValue = if (isSelected) 24f else 8f,
                        animationSpec = tween(300), label = "indicator_width"
                    )
                    val color by animateColorAsState(
                        targetValue = if (isSelected) NeonGreen else MaterialTheme.colorScheme.surfaceVariant,
                        animationSpec = tween(300), label = "indicator_color"
                    )
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .height(8.dp)
                            .width(width.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                }
            }

            // Action button
            Button(
                onClick = {
                    if (isLastPage) {
                        skipToHome()
                    } else {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonGreen,
                    contentColor = Color.Black
                )
            ) {
                Text(
                    text = if (isLastPage) stringResource(R.string.onboarding_get_started)
                    else stringResource(R.string.onboarding_next),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
