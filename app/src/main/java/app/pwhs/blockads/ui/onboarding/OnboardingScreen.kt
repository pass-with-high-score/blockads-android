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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pwhs.blockads.R
import app.pwhs.blockads.ui.component.rememberIsExpandedLayout
import app.pwhs.blockads.ui.onboarding.component.CompletionStep
import app.pwhs.blockads.ui.onboarding.component.CrashReportingStep
import app.pwhs.blockads.ui.onboarding.component.DnsServerStep
import app.pwhs.blockads.ui.onboarding.component.OnboardingHeroPane
import app.pwhs.blockads.ui.onboarding.component.OnboardingPageContent
import app.pwhs.blockads.ui.onboarding.component.PermissionStep
import app.pwhs.blockads.ui.onboarding.data.OnboardingPage
import app.pwhs.blockads.ui.theme.AccentBlue
import app.pwhs.blockads.utils.AppConstants.TOTAL_PAGES
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = koinViewModel(),
    onNavigateToHome: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val selectedDnsProvider by viewModel.selectedDnsProvider.collectAsStateWithLifecycle()

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
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) ==
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
    val initialBatteryOptimizationExcluded =
        powerManager.isIgnoringBatteryOptimizations(context.packageName)
    var batteryOptimizationExcluded by remember {
        mutableStateOf(initialBatteryOptimizationExcluded)
    }
    val batteryOptLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        batteryOptimizationExcluded =
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    val isBatteryOptSupported = remember {
        val intent1 = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = "package:${context.packageName}".toUri()
        }
        val intent2 = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        intent1.resolveActivity(context.packageManager) != null || intent2.resolveActivity(context.packageManager) != null
    }

    fun skipToHome() {
        scope.launch {
            viewModel.completeOnboarding()
            onNavigateToHome()
        }
    }

    Scaffold(
        modifier = modifier,
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
        val renderPager: @Composable (Boolean, Modifier) -> Unit = { isTwoPane, pagerModifier ->
            HorizontalPager(
                state = pagerState,
                modifier = pagerModifier
            ) { page ->
                when (page) {
                    // Step 1: Welcome + Privacy promise
                    0 -> OnboardingPageContent(
                        page = OnboardingPage(
                            icon = Icons.Filled.Shield,
                            title = stringResource(R.string.onboarding_title_1),
                            description = stringResource(R.string.onboarding_desc_1)
                        ),
                        isTwoPane = isTwoPane
                    )
                    // Step 2: DNS server
                    1 -> DnsServerStep(
                        selectedProvider = selectedDnsProvider,
                        onProviderSelect = { viewModel.selectDnsProvider(it) },
                        isTwoPane = isTwoPane
                    )
                    // Step 3: VPN permission
                    2 -> PermissionStep(
                        icon = Icons.Filled.VpnKey,
                        title = stringResource(R.string.onboarding_vpn_title),
                        description = stringResource(R.string.onboarding_vpn_desc),
                        buttonText = stringResource(R.string.onboarding_vpn_grant),
                        isGranted = vpnPermissionGranted,
                        grantedText = stringResource(R.string.onboarding_permission_granted),
                        isTwoPane = isTwoPane,
                        onRequestPermission = {
                            val intent = VpnService.prepare(context)
                            if (intent != null) {
                                vpnPermissionLauncher.launch(intent)
                            } else {
                                vpnPermissionGranted = true
                            }
                        }
                    )
                    // Step 4: Notification permission (Android 13+)
                    3 -> PermissionStep(
                        icon = Icons.Filled.Notifications,
                        title = stringResource(R.string.onboarding_notification_title),
                        description = stringResource(R.string.onboarding_notification_desc),
                        buttonText = stringResource(R.string.onboarding_notification_grant),
                        accentColor = AccentBlue,
                        isGranted = notificationPermissionGranted,
                        grantedText = stringResource(R.string.onboarding_permission_granted),
                        isTwoPane = isTwoPane,
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
                    // Step 5: Battery optimization
                    4 -> PermissionStep(
                        icon = Icons.Filled.BatteryChargingFull,
                        title = stringResource(R.string.onboarding_battery_title),
                        description = stringResource(R.string.onboarding_battery_desc),
                        buttonText = if (isBatteryOptSupported) {
                            stringResource(R.string.onboarding_battery_grant)
                        } else {
                            stringResource(R.string.onboarding_battery_unsupported)
                        },
                        accentColor = MaterialTheme.colorScheme.primary,
                        isGranted = batteryOptimizationExcluded,
                        grantedText = stringResource(R.string.onboarding_permission_granted),
                        isSupported = isBatteryOptSupported,
                        isTwoPane = isTwoPane,
                        onRequestPermission = {
                            try {
                                @Suppress("BatteryLife")
                                val intent =
                                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = "package:${context.packageName}".toUri()
                                    }
                                batteryOptLauncher.launch(intent)
                            } catch (e: Exception) {
                                try {
                                    val fallbackIntent =
                                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    batteryOptLauncher.launch(fallbackIntent)
                                } catch (e2: Exception) {
                                    e2.printStackTrace()
                                }
                            }
                        }
                    )
                    // Step 6: Crash Reporting Options
                    5 -> CrashReportingStep(
                        onOptInChoiceMade = { isOptIn ->
                            viewModel.setCrashReportingEnabled(isOptIn)
                            scope.launch {
                                pagerState.animateScrollToPage(6)
                            }
                        },
                        isTwoPane = isTwoPane
                    )
                    // Completion
                    6 -> CompletionStep(isTwoPane = isTwoPane)
                }
            }
        }

        val onNextClick: () -> Unit = {
            if (isLastPage) {
                skipToHome()
            } else {
                scope.launch {
                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                }
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val isTwoPane = rememberIsExpandedLayout(maxWidth)

            if (isTwoPane) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OnboardingHeroPane(
                        currentPage = pagerState.currentPage,
                        totalPages = TOTAL_PAGES,
                        modifier = Modifier
                            .weight(0.42f)
                            .fillMaxHeight()
                    )

                    Column(
                        modifier = Modifier
                            .weight(0.58f)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        renderPager(
                            true,
                            Modifier
                                .weight(1f)
                                .widthIn(max = 560.dp)
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 560.dp)
                                .padding(horizontal = 16.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OnboardingPageIndicators(pagerState = pagerState)

                            Button(
                                onClick = onNextClick,
                                modifier = Modifier
                                    .widthIn(min = 160.dp, max = 220.dp)
                                    .height(52.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = Color.White
                                )
                            ) {
                                Text(
                                    text = if (isLastPage) stringResource(R.string.onboarding_get_started)
                                    else stringResource(R.string.onboarding_next),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    renderPager(false, Modifier.weight(1f))

                    OnboardingPageIndicators(
                        pagerState = pagerState,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )

                    Button(
                        onClick = onNextClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = Color.White
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
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OnboardingPageIndicators(
    pagerState: PagerState,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(TOTAL_PAGES) { index ->
            val isSelected = pagerState.currentPage == index
            val width by animateFloatAsState(
                targetValue = if (isSelected) 24f else 8f,
                animationSpec = tween(300),
                label = "indicator_width"
            )
            val color by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                animationSpec = tween(300),
                label = "indicator_color"
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
}
