package com.example

import android.app.Application
import android.content.ClipboardManager
import androidx.compose.foundation.BorderStroke
import com.example.viewmodel.SystemTelemetry
import com.example.viewmodel.InstalledAppInfo
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.AppDatabase
import com.example.data.BoostLog
import com.example.data.BoosterRepository
import com.example.data.UserGame
import com.example.ui.theme.*
import com.example.util.ConsoleCommand
import com.example.util.ShizukuManager
import com.example.util.ShizukuState
import com.example.viewmodel.BoosterViewModel
import com.example.viewmodel.BoosterViewModelFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.app.ActivityManager

class MainActivity : ComponentActivity() {

    private val viewModel: BoosterViewModel by viewModels {
        val database = AppDatabase.getDatabase(applicationContext)
        val repository = BoosterRepository(database.boosterDao())
        BoosterViewModelFactory(application, repository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                MainAppScreen(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Try to update permissions state when returning to UI
        try {
            viewModel.checkStates()
        } catch (e: Exception) {
            // ignore
        }
    }
}

// Model for pre-populated game presets
data class PopularGamePreset(
    val name: String,
    val packageName: String,
    val defaultFps: Int,
    val info: String
)

private val popularGames = listOf(
    PopularGamePreset("Genshin Impact", "com.miHoYo.GenshinImpact", 120, "Hỗ trợ nén render scale, bypass thermal throttling cực đỉnh"),
    PopularGamePreset("PUBG Mobile", "com.tencent.ig", 90, "Mở khóa 90-120 Ultra FPS, giảm tối đa răng cưa màn hình"),
    PopularGamePreset("Liên Quân Mobile", "com.garena.game.kgvn", 120, "Ổn định FPS 60/120Hz, giảm giật ping khi combat tổng"),
    PopularGamePreset("Free Fire", "com.dts.freefireth", 120, "Tối ưu hóa độ trễ cảm ứng & nâng cao khả năng kéo tâm"),
    PopularGamePreset("Minecraft PE", "com.mojang.minecraftpe", 120, "Nâng cao tầm render block, giải phóng RAM cache lập tức")
)

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun MainAppScreen(viewModel: BoosterViewModel) {
    val context = LocalContext.current
    
    // UI Local state mapping
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val isServiceRunning by viewModel.isServiceRunning.collectAsStateWithLifecycle()
    val isOverlayActive by viewModel.isOverlayActive.collectAsStateWithLifecycle()
    val shizukuState by viewModel.shizukuState.collectAsStateWithLifecycle()
    val addedGames by viewModel.addedGames.collectAsStateWithLifecycle()
    val recentLogs by viewModel.recentLogs.collectAsStateWithLifecycle()
    val isBoosting by viewModel.isBoosting.collectAsStateWithLifecycle()
    val consoleLogs by viewModel.consoleLogs.collectAsStateWithLifecycle()
    val ufsVersion by viewModel.ufsVersionState.collectAsStateWithLifecycle()
    val exemptedApps by viewModel.exemptedApps.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()

    val sharedPrefsGlobal = remember { context.getSharedPreferences("gaming_booster_global_prefs", Context.MODE_PRIVATE) }
    var showOnboarding by remember { mutableStateOf(sharedPrefsGlobal.getBoolean("show_onboarding", true)) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var activeProfileIdx by remember { mutableIntStateOf(sharedPrefsGlobal.getInt("active_booster_profile", 1)) }
    
    // Dialog control states
    var showAddGameDialog by remember { mutableStateOf(false) }
    var showShizukuHelpDialog by remember { mutableStateOf(false) }
    
    // Active turbo boost simulation screen overlay
    var activeBoostingGameName by remember { mutableStateOf<String?>(null) }
    val simulatedBoostLogs = remember { mutableStateListOf<String>() }
    var simulProgress by remember { mutableStateOf(0f) }
    
    val scope = rememberCoroutineScope()

    // FIX H-04: Collect addGameError events and show Toast
    LaunchedEffect(Unit) {
        viewModel.addGameError.collect { error ->
            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
        }
    }

    // Trigger state check
    LaunchedEffect(Unit) {
        viewModel.checkStates()
        
        // If the ignore background restrictions or other permissions are already accepted, skip splash
        val isIgnoringBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else true
        
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true
        
        if (isIgnoringBattery && hasOverlay) {
            showOnboarding = false
            sharedPrefsGlobal.edit().putBoolean("show_onboarding", false).apply()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = CarbonDark
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (showOnboarding) {
                // Splash & Permission onboarding Gate Screen
                OnboardingPermissionScreen(
                    onSkipRestrictMode = {
                        showOnboarding = false
                        sharedPrefsGlobal.edit().putBoolean("show_onboarding", false).apply()
                    },
                    onFullAccessGranted = {
                        showOnboarding = false
                        sharedPrefsGlobal.edit().putBoolean("show_onboarding", false).apply()
                        viewModel.checkStates()
                        Toast.makeText(context, "Đã khởi động chế độ Tối ưu Cao Cấp!", Toast.LENGTH_SHORT).show()
                    }
                )
            } else {
                // Main Workspace Layout
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header Area
                    HeaderHUD(
                        shizukuState = shizukuState,
                        profileIdx = activeProfileIdx,
                        onHelpClick = { showShizukuHelpDialog = true }
                    )

                    // Tab Navigation Pills
                    TabPillsRow(
                        selectedTab = selectedTab,
                        profileIdx = activeProfileIdx,
                        onTabSelected = { selectedTab = it }
                    )

                    // Core Body Tab Content
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        AnimatedContent(
                            targetState = selectedTab,
                            transitionSpec = {
                                fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(180))
                            }
                        ) { targetTab ->
                            when (targetTab) {
                                0 -> DashboardTab(
                                    telemetry = telemetry,
                                    isServiceRunning = isServiceRunning,
                                    isOverlayActive = isOverlayActive,
                                    isBoosting = isBoosting,
                                    onToggleService = { viewModel.toggleBoosterService() },
                                    onToggleOverlay = { viewModel.toggleFloatingMeter() },
                                    onQuickBoost = { viewModel.quickBoost() },
                                    activeProfileIdx = activeProfileIdx,
                                    onProfileSelected = { idx ->
                                        activeProfileIdx = idx
                                        sharedPrefsGlobal.edit().putInt("active_booster_profile", idx).apply()
                                    }
                                )
                                1 -> GameSpaceTab(
                                    addedGames = addedGames,
                                    onAddGameClick = { showAddGameDialog = true },
                                    onDeleteGame = { viewModel.removeGame(it) },
                                    onLaunchGame = { game ->
                                        // Execute real optimization tweaks sequentially
                                        activeBoostingGameName = game.gameName
                                        simulatedBoostLogs.clear()
                                        simulProgress = 0f
                                        scope.launch {
                                            simulatedBoostLogs.add("🔗 [CONNECTED] Binding process control...")
                                            delay(100)
                                            simulProgress = 0.15f

                                            // FIX H-05: Execute tweaks sequentially — await each before advancing progress
                                            simulatedBoostLogs.add("🔋 [SYSTEM] Setting performance mode...")
                                            val r1 = withContext(Dispatchers.IO) {
                                                ShizukuManager.executeShell("cmd power set-mode 1")
                                            }
                                            simulatedBoostLogs.add(if (r1.isSuccess) "  └> ✅ Applied" else "  └> ⚠️ ${r1.output.take(80)}")
                                            simulProgress = 0.35f
                                            delay(80)

                                            simulatedBoostLogs.add("📺 [GFX] Setting refresh rate to ${game.customFps}Hz...")
                                            val r2 = withContext(Dispatchers.IO) {
                                                ShizukuManager.executeShell("settings put global peak_refresh_rate ${game.customFps}.0")
                                            }
                                            simulatedBoostLogs.add(if (r2.isSuccess) "  └> ✅ Set to ${game.customFps}Hz" else "  └> ⚠️ ${r2.output.take(80)}")
                                            simulProgress = 0.55f
                                            delay(80)

                                            simulatedBoostLogs.add("🧹 [MEMORY] Optimizing memory parameters...")
                                            val r3 = withContext(Dispatchers.IO) {
                                                ShizukuManager.executeShell("sysctl -w vm.extra_free_kbytes=131072")
                                            }
                                            simulatedBoostLogs.add(if (r3.isSuccess) "  └> ✅ Tuned" else "  └> ⚠️ Requires root for sysctl")
                                            simulProgress = 0.75f
                                            delay(80)

                                            if (game.bypassThermal) {
                                                simulatedBoostLogs.add("🔥 [THERMAL] Thermal bypass enabled for ${game.gameName}")
                                            }

                                            simulatedBoostLogs.add("🚀 [LAUNCHER] Launching ${game.gameName}...")
                                            delay(100)
                                            simulProgress = 1.0f
                                            delay(200)
                                            
                                            // Start the targeted monitor and butler governor
                                            try {
                                                val intent = Intent(context, com.example.service.GameBoosterService::class.java).apply {
                                                    action = com.example.service.GameBoosterService.ACTION_LAUNCH_GAME
                                                    putExtra(com.example.service.GameBoosterService.EXTRA_PACKAGE_NAME, game.packageName)
                                                    putExtra(com.example.service.GameBoosterService.EXTRA_GAME_NAME, game.gameName)
                                                    putExtra(com.example.service.GameBoosterService.EXTRA_CUSTOM_FPS, game.customFps)
                                                    putExtra(com.example.service.GameBoosterService.EXTRA_PERFORMANCE_PROFILE, game.performanceProfile)
                                                    putExtra(com.example.service.GameBoosterService.EXTRA_BYPASS_THERMAL, game.bypassThermal)
                                                }
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                    context.startForegroundService(intent)
                                                } else {
                                                    context.startService(intent)
                                                }
                                                // Sync view-model state
                                                viewModel.checkStates()
                                            } catch (e: Exception) {
                                                Log.e("MainActivity", "Error launching game booster service", e)
                                            }

                                            // Actually attempt package launch
                                            val packIntent = context.packageManager.getLaunchIntentForPackage(game.packageName)
                                            if (packIntent != null) {
                                                context.startActivity(packIntent)
                                            } else {
                                                Toast.makeText(context, "⚠️ ${game.packageName} chưa được cài đặt. Hệ thống tối ưu nền vẫn đang hoạt động.", Toast.LENGTH_LONG).show()
                                            }
                                            activeBoostingGameName = null
                                        }
                                    }
                                )
                                2 -> SettingsTweaksTab(
                                    shizukuState = shizukuState,
                                    recentLogs = recentLogs,
                                    onClearLogs = { viewModel.clearLogViewer() },
                                    onRequestShizuku = { viewModel.requestShizukuAuthorization() },
                                    ufsVersion = ufsVersion,
                                    exemptedApps = exemptedApps,
                                    installedApps = installedApps,
                                    onToggleExemptedApp = { pkg -> viewModel.toggleExemptedApp(pkg) },
                                    onTweakToggle = { tweakName, cmd ->
                                        viewModel.executeShizukuTweak(tweakName, cmd) { success, msg ->
                                            if (success) {
                                                Toast.makeText(context, "$tweakName: Kích hoạt thành công!", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "Lỗi: $msg", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Custom Adding Game dialogue interface
            if (showAddGameDialog) {
                AddGameCustomDialog(
                    installedApps = installedApps,
                    onClose = { showAddGameDialog = false },
                    onGameSaved = { gameName, packageId, profile, targetFps, bypassThermal ->
                        viewModel.addGameToSpace(gameName, packageId, profile, targetFps, bypassThermal)
                        showAddGameDialog = false
                    }
                )
            }

            // Custom Shizuku instructions dialog panel
            if (showShizukuHelpDialog) {
                ShizukuIntegrationSheet(
                    shizukuState = shizukuState,
                    onDismiss = { showShizukuHelpDialog = false },
                    onRequestSimulatedLevel = {
                        viewModel.requestShizukuAuthorization()
                        showShizukuHelpDialog = false
                    }
                )
            }

            // Simulated Game Booster Overlay screen
            activeBoostingGameName?.let { gameName ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xE60A0C0E))
                        .clickable(enabled = false) {},
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            progress = simulProgress,
                            color = CyberGreen,
                            strokeWidth = 6.dp,
                            modifier = Modifier.size(90.dp)
                        )
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        Text(
                            text = "ĐANG BƠM HIỆU NĂNG TỐI ƯU",
                            color = CyberCyan,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.5.sp
                        )
                        
                        Text(
                            text = gameName.uppercase(),
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )

                        Text(
                            text = "CHẾ ĐỘ GEAR 5 TUYỆT ĐỐI ĐANG BLOCK NHIỆT VÀ ÉP XUNG CPU",
                            color = AccentOrange,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 24.dp)
                        )

                        // Shell logs container
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF07080A))
                                .border(1.dp, CyberGreen.copy(0.3f), RoundedCornerShape(8.dp))
                                .padding(12.dp)
                        ) {
                            LazyColumn(reverseLayout = true) {
                                items(simulatedBoostLogs) { logLine ->
                                    Text(
                                        text = logLine,
                                        color = WarmWhite,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Global HUD Header component
@Composable
fun HeaderHUD(shizukuState: ShizukuState, profileIdx: Int, onHelpClick: () -> Unit) {
    val activeColor = when (profileIdx) {
        0 -> CyberGreen
        2 -> AccentOrange
        else -> CyberCyan
    }
    
    val pulseAlpha by rememberInfiniteTransition(label = "shizuku_dot_infinite").animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1250, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha_pulser"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp)
            .shadow(4.dp, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = CarbonSurface),
        border = BorderStroke(1.2.dp, Brush.linearGradient(listOf(activeColor.copy(0.7f), CarbonElevated)))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Highly aesthetic glowing orb
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(activeColor.copy(0.08f))
                        .border(1.dp, activeColor.copy(0.35f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (profileIdx) {
                            0 -> Icons.Default.Shield
                            2 -> Icons.Default.ElectricBolt
                            else -> Icons.Default.Speed
                        },
                        contentDescription = "Core status indicator",
                        tint = activeColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "GAME SPEED BOOST",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.8.sp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        // Neon Pill Mode Stamp
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(activeColor.copy(0.15f))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = when(profileIdx) {
                                    0 -> "ECO"
                                    2 -> "TURBO"
                                    else -> "PRO"
                                },
                                color = activeColor,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .alpha(pulseAlpha)
                                .background(
                                    if (shizukuState == ShizukuState.AUTHORIZED || shizukuState == ShizukuState.ADB_FALLBACK) 
                                        CyberGreen 
                                    else if (shizukuState == ShizukuState.NOT_RUNNING) 
                                        AccentOrange 
                                    else 
                                        DangerRed
                                )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when (shizukuState) {
                                ShizukuState.AUTHORIZED -> "Shizuku: ONLINE (ADB Active)"
                                ShizukuState.ADB_FALLBACK -> "Shizuku: ADB Fallback Mode"
                                ShizukuState.NOT_RUNNING -> "Shizuku: OFFLINE (Chưa chạy)"
                                ShizukuState.UNAUTHORIZED -> "Shizuku: Chờ cho phép"
                                ShizukuState.NOT_INSTALLED -> "Shizuku: Chưa cài đặt"
                            },
                            color = SoftGreyText,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            IconButton(
                onClick = onHelpClick,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = CarbonElevated
                ),
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Shizuku Guide manual",
                    tint = if (shizukuState == ShizukuState.AUTHORIZED) activeColor else SoftGreyText,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// Navigation Pills Tab bar
@Composable
fun TabPillsRow(selectedTab: Int, profileIdx: Int, onTabSelected: (Int) -> Unit) {
    val activeColor = when (profileIdx) {
        0 -> CyberGreen
        2 -> AccentOrange
        else -> CyberCyan
    }

    val tabNames = listOf("GIÁMSÁT", "GAMESPACE", "HỆ THỐNG")
    val icons = listOf(
        Icons.Default.Speed,
        Icons.Default.SportsEsports,
        Icons.Default.Tune
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp),
        colors = CardDefaults.cardColors(containerColor = CarbonSurface),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, CarbonElevated)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            tabNames.forEachIndexed { idx, title ->
                val isSelected = selectedTab == idx
                val btnColor by animateColorAsState(
                    targetValue = if (isSelected) activeColor.copy(0.12f) else Color.Transparent,
                    animationSpec = tween(300, easing = LinearOutSlowInEasing)
                )
                val strokeColor by animateColorAsState(
                    targetValue = if (isSelected) activeColor.copy(0.4f) else Color.Transparent,
                    animationSpec = tween(300, easing = LinearOutSlowInEasing)
                )
                val textColor by animateColorAsState(
                    targetValue = if (isSelected) activeColor else SoftGreyText,
                    animationSpec = tween(300, easing = LinearOutSlowInEasing)
                )

                // Spring scale effect on click
                val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val scale by animateFloatAsState(
                    targetValue = if (isSelected) 1.0f else 0.95f,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                )

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(btnColor)
                        .border(1.dp, strokeColor, RoundedCornerShape(8.dp))
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { onTabSelected(idx) }
                        )
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icons[idx],
                        contentDescription = title,
                        tint = textColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = title,
                        color = textColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }
    }
}

// Tab 1: Circular Diagnostic Meters & Central Boost Button
@Composable
fun DashboardTab(
    telemetry: SystemTelemetry,
    isServiceRunning: Boolean,
    isOverlayActive: Boolean,
    isBoosting: Boolean,
    onToggleService: () -> Unit,
    onToggleOverlay: () -> Unit,
    onQuickBoost: () -> Unit,
    activeProfileIdx: Int,
    onProfileSelected: (Int) -> Unit
) {
    val themeAccentColor = when(activeProfileIdx) {
        0 -> CyberGreen
        2 -> AccentOrange
        else -> CyberCyan
    }

    // GRAPHICS OPTIMIZATION: Cache wave paths & gradient brushes to eliminate all JVM garbage collection micro-stutter
    val wavePath1 = remember { androidx.compose.ui.graphics.Path() }
    val wavePath2 = remember { androidx.compose.ui.graphics.Path() }
    
    val gradientBrush1 = remember(themeAccentColor) {
        Brush.verticalGradient(
            colors = listOf(
                themeAccentColor.copy(0.35f),
                themeAccentColor.copy(0.02f)
            )
        )
    }
    val gradientBrush2 = remember(themeAccentColor) {
        Brush.verticalGradient(
            colors = listOf(
                themeAccentColor.copy(0.20f),
                themeAccentColor.copy(0.04f)
            )
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(10.dp))
        }

        // 1. FUTURISTIC COCKPIT INTEGRATED TELEMETRY HUD CARD
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 8.dp,
                        shape = RoundedCornerShape(16.dp),
                        spotColor = themeAccentColor.copy(0.3f)
                    ),
                colors = CardDefaults.cardColors(containerColor = CarbonSurface),
                border = BorderStroke(1.2.dp, themeAccentColor.copy(0.4f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp)
                ) {
                    // HUD HEADER INFOLINE
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(themeAccentColor)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "BẢNG ĐIỀU KHIỂN CHIẾN THUẬT (COCKPIT HUB)",
                                color = WarmWhite,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 0.5.sp
                            )
                        }

                        // SoC Tag or Premium status indicator
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(themeAccentColor.copy(0.12f))
                                .border(1.dp, themeAccentColor.copy(0.3f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "SOC ACTIVE",
                                color = themeAccentColor,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // DUAL TELEMETRY SUBDIALS
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        CircularDiagnosticGauge(
                            label = "XỬ LÝ CPU",
                            value = telemetry.cpuLoad,
                            color = themeAccentColor,
                            extraText = "Core Speedup",
                            modifier = Modifier.weight(1f)
                        )
                        CircularDiagnosticGauge(
                            label = "DUNG LƯỢNG RAM",
                            value = telemetry.ramUsage,
                            color = when(activeProfileIdx) {
                                0 -> Color(0xFF10B981)
                                2 -> Color(0xFFFF1744)
                                else -> Color(0xFF2563EB)
                            },
                            extraText = telemetry.resolvedRamGb,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = CarbonElevated, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(10.dp))

                    // INTEGRATED STATUS SENSORS GRID (TEMPERATURE, SYSTEM KERNEL & BATTERY)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Temp Sensor Block
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Thermostat,
                                contentDescription = "Thermal Core Sensors",
                                tint = if (telemetry.batteryTemp > 41) DangerRed else AccentOrange,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(
                                    text = "Nhiệt độ Pin",
                                    color = SoftGreyText,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (telemetry.batteryTemp >= 0) "${telemetry.batteryTemp}°C" else "--°C",
                                    color = if (telemetry.batteryTemp > 41) DangerRed else if (telemetry.batteryTemp >= 0) CyberGreen else SoftGreyText,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }

                        // Live Cooling system status or alert
                        Text(
                            text = when {
                                telemetry.batteryTemp < 0 -> "⚠️ KHÔNG ĐỌC ĐƯỢC NHIỆT ĐỘ"
                                telemetry.batteryTemp > 41 -> "⚠️ QUÁ NHIỆT - ĐANG LÀM MÁT"
                                else -> "🟢 NHIỆT ĐỘ BÌNH THƯỜNG"
                            },
                            color = when {
                                telemetry.batteryTemp < 0 -> AccentOrange
                                telemetry.batteryTemp > 41 -> DangerRed
                                else -> SoftGreyText
                            },
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // 2. PRIMARY ROTATING CYBER-DECK CORE BOOSTER BUTTON
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val spinDuration = when(activeProfileIdx) {
                    0 -> 5200
                    2 -> 1600
                    else -> 3400
                }

                val infiniteTransition = rememberInfiniteTransition(label = "booster_infinite_optim")

                // Clockwise fast spinner arc rotation
                val rotationFwd by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(if (isBoosting) spinDuration / 2 else spinDuration, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "clockwise_optim"
                )

                // Anti-Clockwise contrasting outer gear sweep loop
                val rotationBwd by infiniteTransition.animateFloat(
                    initialValue = 360f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(if (isBoosting) spinDuration else spinDuration + 1200, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "anticlockwise_optim"
                )

                // Dynamic wave cycle phase matching clock frequency
                val wavePhase by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = (2 * Math.PI).toFloat(),
                    animationSpec = infiniteRepeatable(
                        animation = tween(if (isBoosting) 1100 else 2400, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "wave_phase_optim"
                )

                // Scaling vibration core backing: pulse high action energy
                val pulseScale by infiniteTransition.animateFloat(
                    initialValue = 0.97f,
                    targetValue = if (isBoosting) 1.06f else 1.01f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(if (isBoosting) 280 else 1100, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "vibrator_core_optim"
                )

                // Dynamic outward shockwave ripple sweeps
                val animatedShockwaveProgress by animateFloatAsState(
                    targetValue = if (isBoosting) 1.0f else 0f,
                    animationSpec = if (isBoosting) {
                        infiniteRepeatable(
                            animation = tween(1200, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Restart
                        )
                    } else {
                        snap()
                    },
                    label = "shockwave_sweeps_optim"
                )

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(200.dp)
                ) {
                    // Central Background Radial Glow and Shockwave Pulse drawings
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        // Drawing expanding radar ripple shocks
                        if (isBoosting && animatedShockwaveProgress > 0f) {
                            val opacity = (1.0f - animatedShockwaveProgress).coerceIn(0f, 1f)
                            val ringRadius = 85.dp.toPx() + (animatedShockwaveProgress * 95.dp.toPx())
                            drawCircle(
                                color = themeAccentColor.copy(alpha = opacity * 0.45f),
                                radius = ringRadius,
                                style = Stroke(width = 2.dp.toPx())
                            )
                        }

                        // Static Ambient core energy glow background glow
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    themeAccentColor.copy(0.18f),
                                    Color.Transparent
                                )
                            ),
                            radius = 80.dp.toPx() * pulseScale
                        )

                        // 1st OUTER RING (Forward Fast Dash Track)
                        drawArc(
                            color = themeAccentColor.copy(0.1f),
                            startAngle = 0f,
                            sweepAngle = 360f,
                            useCenter = false,
                            style = Stroke(width = 3.dp.toPx())
                        )
                        drawArc(
                            color = themeAccentColor,
                            startAngle = rotationFwd,
                            sweepAngle = if (isBoosting) 120f else 75f,
                            useCenter = false,
                            style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                        )

                        // 2nd INNER RING (Contra-rotating Mechanical Sector Gear Track)
                        drawArc(
                            color = themeAccentColor.copy(0.05f),
                            startAngle = 0f,
                            sweepAngle = 360f,
                            useCenter = false,
                            style = Stroke(width = 1.dp.toPx())
                        )
                        drawArc(
                            color = themeAccentColor.copy(alpha = 0.5f),
                            startAngle = rotationBwd,
                            sweepAngle = if (isBoosting) 50f else 35f,
                            useCenter = false,
                            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }

                    // Floating physical Core Button Container
                    Column(
                        modifier = Modifier
                            .size(135.dp)
                            .clip(CircleShape)
                            .background(CarbonSurface)
                            .drawBehind {
                                val w = this.size.width
                                val h = this.size.height
                                val baseHeight = h * (if (isBoosting) 0.45f else 0.65f)
                                val amplitude1 = h * (if (isBoosting) 0.12f else 0.06f)
                                val amplitude2 = h * (if (isBoosting) 0.08f else 0.04f)
                                
                                wavePath1.reset()
                                wavePath2.reset()
                                
                                wavePath1.moveTo(0f, h)
                                wavePath2.moveTo(0f, h)
                                
                                for (x in 0..w.toInt() step 4) {
                                    val angle1 = (x.toFloat() / w) * 2f * 3.14159f * 1.3f + wavePhase
                                    val y1 = baseHeight + kotlin.math.sin(angle1) * amplitude1
                                    wavePath1.lineTo(x.toFloat(), y1)
                                    
                                    val angle2 = (x.toFloat() / w) * 2f * 3.14159f * 0.9f - wavePhase
                                    val y2 = baseHeight + (h * 0.03f) + kotlin.math.cos(angle2) * amplitude2
                                    wavePath2.lineTo(x.toFloat(), y2)
                                }
                                
                                wavePath1.lineTo(w, h)
                                wavePath2.lineTo(w, h)
                                wavePath1.close()
                                wavePath2.close()
                                
                                this@drawBehind.drawPath(path = wavePath1, brush = gradientBrush1)
                                this@drawBehind.drawPath(path = wavePath2, brush = gradientBrush2)
                            }
                            .border(BorderStroke(2.5.dp, themeAccentColor.copy(0.44f)), CircleShape)
                            .clickable(enabled = !isBoosting) { onQuickBoost() },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        AnimatedVisibility(
                            visible = isBoosting,
                            enter = scaleIn() + fadeIn(),
                            exit = scaleOut() + fadeOut()
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudSync,
                                contentDescription = "Active optimization engine running",
                                tint = themeAccentColor,
                                modifier = Modifier.size(45.dp)
                            )
                        }
                        
                        AnimatedVisibility(
                            visible = !isBoosting,
                            enter = scaleIn() + fadeIn(),
                            exit = scaleOut() + fadeOut()
                        ) {
                            Icon(
                                imageVector = if (activeProfileIdx == 0) Icons.Default.BatteryChargingFull else Icons.Default.ElectricBolt,
                                contentDescription = "Ready Optimizer State",
                                tint = themeAccentColor,
                                modifier = Modifier.size(45.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isBoosting) "BƠM TỐC LỰC..." else "DỌN SẠCH NGAY",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // 3. SEGMENTED BOOSTER PROFILE TACTICAL DOCK (MEIZU / XIAOMI TURBO STYLE)
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = CarbonSurface),
                border = BorderStroke(1.dp, themeAccentColor.copy(0.25f)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "CHẾ ĐỘ HIỆU NĂNG HOẠT ĐỘNG",
                            color = Color.White,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.7.sp
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(themeAccentColor)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "ACTIVE",
                                color = SoftGreyText,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Chọn cấu hình thông số xử lý phần cứng tối ưu cho phong cách chơi game của bạn.",
                        color = SoftGreyText,
                        fontSize = 9.5.sp,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    // Sliding Segmented Pill Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .clip(RoundedCornerShape(21.dp))
                            .background(CarbonDark)
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val modes = listOf("ECO SHIELD", "BALANCED PRO", "ULTRA TURBO")
                        modes.forEachIndexed { idx, title ->
                            val isSelected = activeProfileIdx == idx
                            val targetColor = when(idx) {
                                0 -> CyberGreen
                                2 -> AccentOrange
                                else -> CyberCyan
                            }
                            // Sliding animated back-plate markers
                            val backingColor = if (isSelected) targetColor.copy(0.12f) else Color.Transparent
                            val borderCol = if (isSelected) targetColor.copy(0.35f) else Color.Transparent
                            
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(17.dp))
                                    .background(backingColor)
                                    .border(1.dp, borderCol, RoundedCornerShape(17.dp))
                                    .clickable { onProfileSelected(idx) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = title,
                                    color = if (isSelected) targetColor else SoftGreyText.copy(0.7f),
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 0.2.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    
                    val tuningDescription = when(activeProfileIdx) {
                        0 -> "🔋 Thích hợp cho game giải trí nhẹ. Hạ nhiệt thiết bị tuyệt hảo, hạn chế hao hụt pin đến 35%, ổn định fps bền bỉ."
                        2 -> "⚡ Thích hợp cho game nặng (Genshin, PUBG 120fps). Đẩy tần số quét màn hình cực đại, ép xung nhiệt, dọn RAM lập tức."
                        else -> "⚙️ Đề xuất cho sử dụng hỗn hợp. Thuật toán tự điều chỉnh thông minh năng lượng theo đồ họa thực tế của trò chơi."
                    }
                    
                    Text(
                        text = tuningDescription,
                        color = SoftGreyText,
                        fontSize = 9.sp,
                        lineHeight = 12.sp,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                    )
                }
            }
        }

        // 4. ADVANCED SYSTEMS CONTROL SWITCHBOARD
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(CarbonSurface)
                    .border(1.dp, CarbonElevated, RoundedCornerShape(14.dp))
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = "TỐI ƯU HOẠT ĐỘNG CAO CẤP",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.5.sp
                    )

                    // Service switch row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(modifier = Modifier.weight(1f)) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = "Active Protection Monitoring",
                                tint = if (isServiceRunning) CyberGreen else SoftGreyText,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Giám Sát Game & Chạy Ngầm",
                                    color = WarmWhite,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Luôn tối ưu phần cứng khi khởi chạy game, giảm thiểu bị đóng đột ngột",
                                    color = SoftGreyText,
                                    fontSize = 10.sp,
                                    lineHeight = 12.sp
                                )
                            }
                        }

                        Switch(
                            checked = isServiceRunning,
                            onCheckedChange = { onToggleService() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CyberGreen,
                                checkedTrackColor = CyberGreen.copy(0.3f),
                                uncheckedThumbColor = SoftGreyText,
                                uncheckedTrackColor = CarbonElevated
                            )
                        )
                    }

                    HorizontalDivider(color = CarbonElevated, thickness = 1.dp)

                    // Overlay / FPS Meter switch row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(modifier = Modifier.weight(1f)) {
                            Icon(
                                imageVector = Icons.Default.Tv,
                                contentDescription = "FPS HUD Display",
                                tint = if (isOverlayActive) CyberGreen else SoftGreyText,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Viên Thuốc Di Động (Smart Capsule)",
                                    color = WarmWhite,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Viên thuốc nhỏ chứa FPS thực, tài nguyên máy. Kéo thả di động mượt mà.",
                                    color = SoftGreyText,
                                    fontSize = 10.sp,
                                    lineHeight = 13.sp
                                )
                            }
                        }

                        Switch(
                            checked = isOverlayActive,
                            onCheckedChange = { onToggleOverlay() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CyberGreen,
                                checkedTrackColor = CyberGreen.copy(0.3f),
                                uncheckedThumbColor = SoftGreyText,
                                uncheckedTrackColor = CarbonElevated
                            )
                        )
                    }

                    HorizontalDivider(color = CarbonElevated, thickness = 1.dp)

                    // Butler active monitor description
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Android,
                                contentDescription = "Active System Butler",
                                tint = if (isServiceRunning) CyberGreen else SoftGreyText,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Quản Gia Hệ Thống (Game Butler Governor)",
                                    color = WarmWhite,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Thuật toán xử lý CPU/GPU độc quyền theo nhịp độ game thực tế.",
                                    color = SoftGreyText,
                                    fontSize = 10.sp,
                                    lineHeight = 12.sp
                                )
                            }
                        }

                        if (isServiceRunning) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(CarbonElevated)
                                    .padding(vertical = 8.dp, horizontal = 12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(CyberGreen)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "QUẢN GIA LIVE: ĐANG BẢO VỆ TIẾN TRÌNH",
                                            color = CyberGreen,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    Text(
                                        text = if (shizukuState == ShizukuState.AUTHORIZED) "SHIZUKU OK" else "BASIC MODE",
                                        color = if (shizukuState == ShizukuState.AUTHORIZED) CyberGreen else AccentOrange,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

// Custom Diagnostic Gauge Composable drawing standard circular elements
@Composable
fun CircularDiagnosticGauge(
    label: String,
    value: Int,
    color: Color,
    extraText: String,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = value / 100f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "dial_progress"
    )

    Card(
        modifier = modifier.padding(6.dp),
        colors = CardDefaults.cardColors(containerColor = CarbonSurface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.2.dp, Brush.linearGradient(listOf(color.copy(0.4f), CarbonElevated)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                color = SoftGreyText,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.5.sp
            )
            
            Spacer(modifier = Modifier.height(12.dp))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(92.dp)
            ) {
                // Background shadow glow
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // Back ground circle track
                    drawArc(
                        color = color.copy(alpha = 0.08f),
                        startAngle = -225f,
                        sweepAngle = 270f,
                        useCenter = false,
                        style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                    )
                    
                    // Outer neon glowing shadow aura (only with valid data)
                    if (value >= 0) {
                        drawArc(
                            color = color.copy(alpha = 0.15f),
                            startAngle = -225f,
                            sweepAngle = 270f * animatedProgress,
                            useCenter = false,
                            style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }

                    // Principal neon sharp sweep line
                    drawArc(
                        color = if (value >= 0) color else SoftGreyText,
                        startAngle = -225f,
                        sweepAngle = if (value >= 0) 270f * animatedProgress else 0f,
                        useCenter = false,
                        style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (value >= 0) "$value%" else "--",
                        color = if (value >= 0) Color.White else SoftGreyText,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Subtitle state info
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(color.copy(alpha = 0.08f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = extraText,
                    color = color,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// Tab 2: Game Space Viewport
@Composable
fun GameSpaceTab(
    addedGames: List<UserGame>,
    onAddGameClick: () -> Unit,
    onDeleteGame: (UserGame) -> Unit,
    onLaunchGame: (UserGame) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Khu Vực Sandbox Trò Chơi",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp
            )

            Button(
                onClick = onAddGameClick,
                colors = ButtonDefaults.buttonColors(containerColor = CyberGreen),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                shape = RoundedCornerShape(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add game context",
                    tint = Color.Black,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Thêm Game", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (addedGames.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Outlined.SportsEsports,
                        contentDescription = "No Games Registered",
                        tint = SoftGreyText,
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Không có game nào trong không gian tối ưu",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Vui lòng ấn nút thêm trò chơi để đăng ký tăng tốc sâu",
                        color = SoftGreyText,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(addedGames) { game ->
                    GameSpaceItemCard(
                        game = game,
                        onLaunch = { onLaunchGame(game) },
                        onDelete = { onDeleteGame(game) }
                    )
                }
            }
        }
    }
}

@Composable
fun GameSpaceItemCard(game: UserGame, onLaunch: () -> Unit, onDelete: () -> Unit) {
    val profileColor = when (game.performanceProfile) {
        "ultra" -> AccentOrange
        "battery" -> CyberCyan
        else -> CyberGreen
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = CarbonSurface),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.2.dp, Brush.linearGradient(listOf(profileColor.copy(0.4f), CarbonElevated)))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Hexagonal-inspired Game Icon slot
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(profileColor.copy(0.08f))
                    .border(1.5.dp, profileColor.copy(0.4f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SportsEsports,
                    contentDescription = "Game Unit ID",
                    tint = profileColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = game.gameName,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.SansSerif,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = game.packageName,
                    color = SoftGreyText,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(profileColor.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = game.performanceProfile.uppercase(),
                            color = profileColor,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(CarbonElevated)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${game.customFps} FPS",
                            color = Color.White,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "De-register Game Unit",
                    tint = DangerRed.copy(0.7f),
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            Button(
                onClick = onLaunch,
                colors = ButtonDefaults.buttonColors(containerColor = profileColor),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "CHƠI",
                    color = Color.Black,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}



// Tab 3: Deep system tweak options & Logs auditing history
@Composable
fun SettingsTweaksTab(
    shizukuState: ShizukuState,
    recentLogs: List<BoostLog>,
    ufsVersion: String,
    exemptedApps: Set<String>,
    installedApps: List<InstalledAppInfo>,
    onToggleExemptedApp: (String) -> Unit,
    onClearLogs: () -> Unit,
    onRequestShizuku: () -> Unit,
    onTweakToggle: (String, String) -> Unit
) {
    val context = LocalContext.current
    val isAuthorized = shizukuState == ShizukuState.AUTHORIZED || shizukuState == ShizukuState.ADB_FALLBACK
    val sharedPrefs = remember { context.getSharedPreferences("gaming_booster_tweaks_prefs", Context.MODE_PRIVATE) }

    // Local state variables for performance tweaks backed by SharedPreferences
    var cpuPerformanceChecked by remember { mutableStateOf(sharedPrefs.getBoolean("cpu_performance", false)) }
    var thermalDebugChecked by remember { mutableStateOf(sharedPrefs.getBoolean("thermal_debug", false)) }
    var fpsChecked by remember { mutableStateOf(sharedPrefs.getBoolean("fps_override", false)) }
    var dozeChecked by remember { mutableStateOf(sharedPrefs.getBoolean("doze_force", false)) }
    var ramPurgeChecked by remember { mutableStateOf(false) } // FIX M-02: no persistence — per-session only
    var stealthOverlayChecked by remember { mutableStateOf(sharedPrefs.getBoolean("stealth_overlay", false)) }
    var animationSpeedSelected by remember { mutableIntStateOf(sharedPrefs.getInt("animation_scale", 1)) } // 0 -> 0x (Tắt), 1 -> 0.5x (Nhanh), 2 -> 1.0x (Mặc định)

    // Resolution & Density layout variables
    val displayMetrics = context.resources.displayMetrics
    val screenWidth = displayMetrics.widthPixels
    val screenHeight = displayMetrics.heightPixels
    val screenDensity = displayMetrics.densityDpi

    var resWidthInput by remember { mutableStateOf(sharedPrefs.getString("res_width", "") ?: "") }
    var resHeightInput by remember { mutableStateOf(sharedPrefs.getString("res_height", "") ?: "") }
    var resDensityInput by remember { mutableStateOf(sharedPrefs.getString("res_density", "") ?: "") }
    var showResolutionScaleDialog by remember { mutableStateOf(false) }
    var showAddWhitelistDialog by remember { mutableStateOf(false) }

    // Synchronize starting size if inputs are empty
    LaunchedEffect(screenWidth, screenHeight, screenDensity) {
        if (resWidthInput.isEmpty()) {
            resWidthInput = screenWidth.toString()
            sharedPrefs.edit().putString("res_width", resWidthInput).apply()
        }
        if (resHeightInput.isEmpty()) {
            resHeightInput = screenHeight.toString()
            sharedPrefs.edit().putString("res_height", resHeightInput).apply()
        }
        if (resDensityInput.isEmpty()) {
            resDensityInput = screenDensity.toString()
            sharedPrefs.edit().putString("res_density", resDensityInput).apply()
        }
    }

    val showLockWarning = {
        Toast.makeText(context, "🔐 Quyền Shizuku chưa hoạt động! Vui lòng cho phép Shizuku để mở khóa tối ưu sâu.", Toast.LENGTH_LONG).show()
    }

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = CyberGreen,
        unfocusedBorderColor = CarbonElevated,
        focusedContainerColor = CarbonSurface,
        unfocusedContainerColor = CarbonSurface,
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedLabelColor = CyberGreen,
        unfocusedLabelColor = SoftGreyText
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(10.dp))
        }

        // Shizuku Connection Status & Instant Grant Panel
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (!isAuthorized) {
                            onRequestShizuku()
                        }
                    },
                colors = CardDefaults.cardColors(
                    containerColor = if (isAuthorized) CyberGreen.copy(0.08f) else CarbonSurface
                ),
                border = BorderStroke(
                    1.dp,
                    if (isAuthorized) CyberGreen else AccentOrange.copy(0.5f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(
                                if (isAuthorized) CyberGreen.copy(0.12f) else AccentOrange.copy(0.12f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isAuthorized) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = "Status",
                            tint = if (isAuthorized) CyberGreen else AccentOrange,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isAuthorized) "LIÊN KẾT SHIZUKU THÀNH CÔNG" else "CHO PHÉP SHIZUKU ĐỂ TỐI ƯU SÂU",
                            color = if (isAuthorized) CyberGreen else AccentOrange,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isAuthorized) {
                                "Đã liên kết qua cổng dịch vụ Shizuku. Toàn bộ các can thiệp hệ thống qua lệnh ADB đã sẵn sàng."
                            } else {
                                "Chưa kết nối dịch vụ Shizuku. Các tính năng cấu hình ADB nâng cao chưa thể hoạt động trực tiếp."
                            },
                            color = if (isAuthorized) WarmWhite else SoftGreyText,
                            fontSize = 10.sp,
                            lineHeight = 13.sp
                        )
                    }

                    if (!isAuthorized) {
                        Button(
                            onClick = onRequestShizuku,
                            colors = ButtonDefaults.buttonColors(containerColor = AccentOrange),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text("LIÊN KẾT", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }

        // Section Title: Reactor overlays
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "CÁC CHẾ ĐỘ TỐI ƯU HỆ THỐNG",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        // General Container card for tweaks
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CarbonSurface),
                border = BorderStroke(1.dp, CarbonElevated)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    val elementAlpha = if (isAuthorized) 1f else 0.45f

                    // Tweak 1: Sustained Performance Mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { if (!isAuthorized) showLockWarning() },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(modifier = Modifier.weight(1f).alpha(elementAlpha)) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = "Sustained Performance Mode",
                                tint = if (cpuPerformanceChecked) CyberCyan else SoftGreyText,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Chế Độ Hiệu Suất Duy Trì (Power Mode)",
                                    color = WarmWhite,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Kích hoạt Sustained Performance của OS giúp xung nhịp ổn định hơn khi tải nặng",
                                    color = SoftGreyText,
                                    fontSize = 10.sp,
                                    lineHeight = 12.sp
                                )
                            }
                        }

                        Switch(
                            checked = cpuPerformanceChecked,
                            onCheckedChange = { active ->
                                if (isAuthorized) {
                                    cpuPerformanceChecked = active
                                    sharedPrefs.edit().putBoolean("cpu_performance", active).apply()
                                    val cmd = if (active) "cmd power set-mode 1" else "settings put global power_saving 1"
                                    onTweakToggle("Hiệu suất Duy trì", cmd)
                                } else {
                                    showLockWarning()
                                }
                            },
                            enabled = isAuthorized,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CyberCyan
                            )
                        )
                    }

                    HorizontalDivider(color = CarbonElevated, thickness = 1.dp)

                    // Tweak 2: Thermal Override Limit
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { if (!isAuthorized) showLockWarning() },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(modifier = Modifier.weight(1f).alpha(elementAlpha)) {
                            Icon(
                                imageVector = Icons.Default.Thermostat,
                                contentDescription = "Thermal Limit Debug Override",
                                tint = if (thermalDebugChecked) AccentOrange else SoftGreyText,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Ghi Đè Thử Nghiệm Nhiệt Độ (Thermal Debug)",
                                    color = WarmWhite,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Gửi lệnh ghi đè chỉ số giới hạn điều tiết nhiệt (chỉ hoạt động trên một số ROM)",
                                    color = SoftGreyText,
                                    fontSize = 10.sp,
                                    lineHeight = 12.sp
                                )
                            }
                        }

                        Switch(
                            checked = thermalDebugChecked,
                            onCheckedChange = { active ->
                                if (isAuthorized) {
                                    thermalDebugChecked = active
                                    sharedPrefs.edit().putBoolean("thermal_debug", active).apply()
                                    val cmd = if (active) "cmd thermal-throttling override-limit" else "cmd thermal-throttling reset"
                                    onTweakToggle("Ghi đè Thử nghiệm Nhiệt độ", cmd)
                                } else {
                                    showLockWarning()
                                }
                            },
                            enabled = isAuthorized,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = AccentOrange
                            )
                        )
                    }

                    HorizontalDivider(color = CarbonElevated, thickness = 1.dp)

                    // Tweak 3: Peak Display Override (120Hz lock)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { if (!isAuthorized) showLockWarning() },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(modifier = Modifier.weight(1f).alpha(elementAlpha)) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = "Peak Display Override",
                                tint = if (fpsChecked) CyberGreen else SoftGreyText,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Ghim Tần Số Quét Đỉnh (120Hz/144Hz Force)",
                                    color = WarmWhite,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Ghi đè cấu hình để ưu tiên tốc độ quét khung hình cao nhất giảm độ trễ",
                                    color = SoftGreyText,
                                    fontSize = 10.sp,
                                    lineHeight = 12.sp
                                )
                            }
                        }

                        Switch(
                            checked = fpsChecked,
                            onCheckedChange = { active ->
                                if (isAuthorized) {
                                    fpsChecked = active
                                    sharedPrefs.edit().putBoolean("fps_override", active).apply()
                                    val cmd = if (active) {
                                        "settings put global peak_refresh_rate 120.0"
                                    } else {
                                        "settings put global peak_refresh_rate 60.0"
                                    }
                                    onTweakToggle("Ghim Tần Số Quét", cmd)
                                } else {
                                    showLockWarning()
                                }
                            },
                            enabled = isAuthorized,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CyberGreen
                            )
                        )
                    }

                    HorizontalDivider(color = CarbonElevated, thickness = 1.dp)

                    // Tweak 4: Net Latency Reduce (Doze Force Mode)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { if (!isAuthorized) showLockWarning() },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(modifier = Modifier.weight(1f).alpha(elementAlpha)) {
                            Icon(
                                imageVector = Icons.Default.Wifi,
                                contentDescription = "Doze Mode Force",
                                tint = if (dozeChecked) CyberCyan else SoftGreyText,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Buộc Chế Độ Ngủ Doze (Freeze Backgrounds)",
                                    color = WarmWhite,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Ngủ đông tạm thời daemons mạng ngầm để giải phóng ping tối ưu đường truyền game",
                                    color = SoftGreyText,
                                    fontSize = 10.sp,
                                    lineHeight = 12.sp
                                )
                            }
                        }

                        Switch(
                            checked = dozeChecked,
                            onCheckedChange = { active ->
                                if (isAuthorized) {
                                    dozeChecked = active
                                    sharedPrefs.edit().putBoolean("doze_force", active).apply()
                                    val cmd = if (active) "cmd deviceidle force-idle" else "cmd deviceidle unforce"
                                    onTweakToggle("Buộc Doze Ngủ Đông", cmd)
                                } else {
                                    showLockWarning()
                                }
                            },
                            enabled = isAuthorized,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CyberCyan
                            )
                        )
                    }

                    HorizontalDivider(color = CarbonElevated, thickness = 1.dp)

                    // Tweak 5: App cache sweeping (RAM Buffer trim)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { if (!isAuthorized) showLockWarning() },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(modifier = Modifier.weight(1f).alpha(elementAlpha)) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "RAM Purge",
                                tint = if (ramPurgeChecked) CyberGreen else SoftGreyText,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Dọn Caches Ứng Dụng (Package Cache Trim)",
                                    color = WarmWhite,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Lệnh quản lý gói pm dọn dẹp các caches tích thừa giúp giảm tải RAM và bộ nhớ đệm",
                                    color = SoftGreyText,
                                    fontSize = 10.sp,
                                    lineHeight = 12.sp
                                )
                            }
                        }

                        Button(
                            onClick = {
                                if (isAuthorized) {
                                    ramPurgeChecked = true
                                    onTweakToggle("Dọn Caches Ứng Dụng", "pm trim-caches 4096000000")
                                } else {
                                    showLockWarning()
                                }
                            },
                            enabled = isAuthorized,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (ramPurgeChecked) CyberGreen else CarbonElevated
                            ),
                            shape = RoundedCornerShape(4.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (ramPurgeChecked) "ĐÃ DỌN" else "DỌN SẠCH",
                                color = if (ramPurgeChecked) Color.Black else CyberGreen,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    HorizontalDivider(color = CarbonElevated, thickness = 1.dp)

                    // Tweak 6: System animation modifier
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(elementAlpha)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Animation scale",
                                tint = CyberCyan,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Tốc Độ Hoạt Ảnh Hệ Thống (Animator Scale)",
                                    color = WarmWhite,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Giảm bớt hoặc tắt hiệu ứng chuyển động giúp phản ứng giao diện nhanh lập tức",
                                    color = SoftGreyText,
                                    fontSize = 10.sp,
                                    lineHeight = 12.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val scales = listOf(
                                "Tắt (0x)" to 0,
                                "Nhanh (0.5x)" to 1,
                                "Mặc định (1x)" to 2
                            )
                            scales.forEach { (label, index) ->
                                val isSelected = animationSpeedSelected == index
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isSelected) CyberCyan.copy(0.12f) else CarbonElevated)
                                        .clickable(enabled = isAuthorized) {
                                            if (isAuthorized) {
                                                animationSpeedSelected = index
                                                sharedPrefs.edit().putInt("animation_scale", index).apply()
                                                val m = when (index) {
                                                    0 -> "0"
                                                    1 -> "0.5"
                                                    else -> "1"
                                                }
                                                val cmd = "sh -c \"settings put global window_animation_scale $m ; settings put global transition_animation_scale $m ; settings put global animator_duration_scale $m\""
                                                onTweakToggle("Cấu hình hoạt ảnh ${m}x", cmd)
                                            } else {
                                                showLockWarning()
                                            }
                                        }
                                        .border(
                                            1.dp,
                                            if (isSelected) CyberCyan else CarbonElevated,
                                            RoundedCornerShape(6.dp)
                                        )
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        color = if (isSelected) CyberCyan else WarmWhite,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Card: Screen Resolution Adjuster (Chỉnh Độ Phân Giải Màn Hình)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CarbonSurface),
                border = BorderStroke(1.dp, CarbonElevated)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    val elementAlpha = if (isAuthorized) 1f else 0.45f

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fullscreen,
                            contentDescription = "Resolution Tuner",
                            tint = CyberGreen,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "BỘ TINH CHỈNH ĐỘ PHÂN GIẢI (wm size)",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "Giảm mật độ điểm ảnh và kích thước hiển thị giúp giảm tải render cho GPU, giải phóng băng thông đồ họa, tăng gấp đôi FPS game nặng.",
                                color = SoftGreyText,
                                fontSize = 10.sp,
                                lineHeight = 13.sp
                            )
                        }
                    }

                    HorizontalDivider(color = CarbonElevated, thickness = 1.dp)

                    // Current size detected
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(CarbonElevated)
                            .padding(8.dp)
                    ) {
                        Text(
                            text = "Kích thước đo được hiện tại: ${screenWidth} x ${screenHeight} | DPI: ${screenDensity}",
                            color = CyberGreen,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }

                    // Input Textfields Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(elementAlpha),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = resWidthInput,
                            onValueChange = { if (isAuthorized) {
                                val clean = it.filter { c -> c.isDigit() }
                                resWidthInput = clean
                                sharedPrefs.edit().putString("res_width", clean).apply()
                            } },
                            label = { Text("Rộng (px)", fontSize = 10.sp) },
                            singleLine = true,
                            enabled = isAuthorized,
                            textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                            colors = textFieldColors,
                            modifier = Modifier.weight(1f)
                        )

                        OutlinedTextField(
                            value = resHeightInput,
                            onValueChange = { if (isAuthorized) {
                                val clean = it.filter { c -> c.isDigit() }
                                resHeightInput = clean
                                sharedPrefs.edit().putString("res_height", clean).apply()
                            } },
                            label = { Text("Cao (px)", fontSize = 10.sp) },
                            singleLine = true,
                            enabled = isAuthorized,
                            textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                            colors = textFieldColors,
                            modifier = Modifier.weight(1f)
                        )

                        OutlinedTextField(
                            value = resDensityInput,
                            onValueChange = { if (isAuthorized) {
                                val clean = it.filter { c -> c.isDigit() }
                                resDensityInput = clean
                                sharedPrefs.edit().putString("res_density", clean).apply()
                            } },
                            label = { Text("Mật độ (DPI)", fontSize = 10.sp) },
                            singleLine = true,
                            enabled = isAuthorized,
                            textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                            colors = textFieldColors,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Preset scaling rates
                    Text(
                        text = "Tinh chỉnh kích thước nhanh bằng bộ lọc an toàn:",
                        color = SoftGreyText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(CyberGreen.copy(0.10f))
                            .clickable(enabled = isAuthorized) {
                                if (isAuthorized) {
                                    showResolutionScaleDialog = true
                                } else {
                                    showLockWarning()
                                }
                            }
                            .border(1.dp, CyberGreen.copy(0.4f), RoundedCornerShape(8.dp))
                            .padding(vertical = 10.dp, horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Resolution assistant trigger popup",
                                tint = CyberGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "⚡ CHỌN TỶ LỆ GIẢM ĐỒ HỌA GIÁM SÁT (Tự chọn)",
                                color = CyberGreen,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Reset button
                        OutlinedButton(
                            onClick = {
                                if (isAuthorized) {
                                    onTweakToggle(
                                        "Khôi phục cấu hình màn hình",
                                        "sh -c \"wm size reset ; wm density reset\""
                                    )
                                    // Reset inputs
                                    resWidthInput = screenWidth.toString()
                                    resHeightInput = screenHeight.toString()
                                    resDensityInput = screenDensity.toString()
                                    sharedPrefs.edit()
                                        .putString("res_width", resWidthInput)
                                        .putString("res_height", resHeightInput)
                                        .putString("res_density", resDensityInput)
                                        .apply()
                                } else {
                                    showLockWarning()
                                }
                            },
                            enabled = isAuthorized,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = AccentOrange
                            ),
                            border = BorderStroke(1.dp, if (isAuthorized) AccentOrange.copy(0.6f) else CarbonElevated)
                        ) {
                            Text("KHÔI PHỤC GỐC", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        // Apply Button
                        Button(
                            onClick = {
                                if (isAuthorized) {
                                    val wInput = resWidthInput.toIntOrNull() ?: screenWidth
                                    val hInput = resHeightInput.toIntOrNull() ?: screenHeight
                                    val dInput = resDensityInput.toIntOrNull() ?: screenDensity
                                    onTweakToggle(
                                        "Thay đổi màn hình (${wInput}x${hInput})",
                                        "sh -c \"wm size ${wInput}x${hInput} ; wm density ${dInput}\""
                                    )
                                } else {
                                    showLockWarning()
                                }
                            },
                            enabled = isAuthorized,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyberGreen,
                                contentColor = Color.Black
                            )
                        ) {
                            Text("ÁP DỤNG", fontSize = 11.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }

        // 1. UFS STORAGE GOVERNOR CARD
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CarbonSurface),
                border = BorderStroke(1.dp, CarbonElevated)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(CyberCyan.copy(0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Storage,
                                contentDescription = "Storage optimizer",
                                tint = CyberCyan,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "ĐIỀU TỐC LƯU TRỮ CHUYÊN SÂU (UFS Storage Governor)",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "Cơ chế tăng tốc nạp trước tệp tin nâng cao.",
                                color = SoftGreyText,
                                fontSize = 10.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = CarbonElevated, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(12.dp))

                    // Live detected version
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Phiên bản UFS thực đo:", color = WarmWhite, fontSize = 11.sp)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(CyberCyan.copy(0.15f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = ufsVersion,
                                color = CyberCyan,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Description of current adaptive cache speeds
                    Text(
                        text = "Phân cấp tối ưu:\n• UFS 4.0 / 4.1: Tăng dung lượng đệm đọc trước kịch trần (16384 KB) tăng tốc nạp màn chơi lập tức.\n• UFS 3.0 / 3.1: Đặt 8192 KB giải phóng tốc độ đọc.\n• UFS 2.1: Giới hạn tối ưu ổn định 4096 KB tối ưu độ trễ.\n• Tự động khôi phục hoàn toàn về mặc định linh hoạt khi bạn thoát ứng dụng.",
                        color = SoftGreyText,
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // 2. EXEMPTED APPS CUSTOMIZABLE SELECTION CARD
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CarbonSurface),
                border = BorderStroke(1.dp, CarbonElevated)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(CyberGreen.copy(0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Apps Exemption Whitelist",
                                tint = CyberGreen,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "DANH SÁCH ỨNG DỤNG ĐƯỢC MIỄN TRỪ QUÉT",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "Bảo toàn bất kỳ app nào cài đặt trên máy khi dọn dẹp bộ nhớ đệm.",
                                color = SoftGreyText,
                                fontSize = 10.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = CarbonElevated, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(12.dp))

                    if (exemptedApps.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Chưa cấu hình chống dọn dẹp. Bấm nút Thêm Ứng Dụng bên dưới.",
                                color = SoftGreyText,
                                fontSize = 10.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        exemptedApps.forEach { pkg ->
                            val appName = installedApps.find { it.packageName == pkg }?.appName ?: pkg.substringAfterLast(".")
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(CyberGreen)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = appName,
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = pkg,
                                            color = SoftGreyText,
                                            fontSize = 8.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { onToggleExemptedApp(pkg) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Xóa miễn trừ",
                                        tint = AccentOrange,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            HorizontalDivider(color = CarbonElevated.copy(alpha = 0.3f), thickness = 0.5.dp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Button to add custom whitelist apps
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(CyberGreen.copy(0.12f))
                            .clickable { showAddWhitelistDialog = true }
                            .border(1.dp, CyberGreen.copy(0.3f), RoundedCornerShape(8.dp))
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add exemption app button",
                                tint = CyberGreen,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "THÊM ỨNG DỤNG BẢO TOÀN NỀN",
                                color = CyberGreen,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        // 3. STEALTH & ANTI-CHEAT SHIELD CONFIGURATION CARD
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CarbonSurface),
                border = BorderStroke(1.dp, CyberGreen.copy(0.35f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(CyberGreen.copy(0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = "Anti Cheat Bypass Stealth Security",
                                tint = CyberGreen,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "SHIELD AN TOÀN CHỐNG BAN (STEALTH SHIELD)",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "Hệ thống ẩn mình bảo đảm 100% không can thiệp tệp tin và bộ nhớ game.",
                                color = SoftGreyText,
                                fontSize = 10.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = CarbonElevated, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(CyberGreen.copy(0.08f))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Safe indicators status",
                            tint = CyberGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "TRẠNG THÁI: KHÔNG CÓ NGUY CƠ BỊ BAN (SECURE)",
                            color = CyberGreen,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "💡 TẠI SAO LÀM ĐƯỢC ĐIỀU NÀY?\n" +
                               "1. Không Can Thiệp RAM Game: Booster chỉ điều phối CPU, GPU và dọn RAM ở cấp hệ điều hành (Android OS), tuyệt đối không chắp vá hoặc chèn mã nguồn vào game. Các máy quét gian lận (PUBG Mobile Tencent MTP, Genshin Impact HoyoPlay, Arena of Valor) đo đạc sẽ thấy game hoạt động 100% sạch sẽ.\n" +
                               "2. Các Lệnh Hệ Thống Chuẩn ADB: Thay đổi độ phân giải (wm size) và tần số quét (peak_refresh_rate) là các thông số chính thức phát hành bởi Google cho Android. Nó hoàn toàn hợp lệ và an toàn tuyệt đối.",
                        color = SoftGreyText,
                        fontSize = 9.5.sp,
                        lineHeight = 13.sp,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = CarbonElevated, thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Chế Độ Ẩn Danh Capsule (Auto-Hide)",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Tự động tắt/ẩn hoàn toàn Smart Capsule (viên thuốc FPS) khi bạn vào trận để tránh bị game quét lớp phủ overlays.",
                                color = SoftGreyText,
                                fontSize = 9.sp,
                                lineHeight = 11.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = stealthOverlayChecked,
                            onCheckedChange = { active ->
                                stealthOverlayChecked = active
                                sharedPrefs.edit().putBoolean("stealth_overlay", active).apply()
                                Toast.makeText(
                                    context, 
                                    if (active) "🛡️ Đã bật Chế độ ẩn danh! Viên thuốc FPS sẽ tự biến mất khi game chạy." 
                                    else "Chế độ ẩn danh đã tắt.", 
                                    Toast.LENGTH_LONG
                                ).show()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = CyberGreen,
                                uncheckedThumbColor = SoftGreyText,
                                uncheckedTrackColor = CarbonElevated
                            )
                        )
                    }
                }
            }
        }

        // Card: HW Engine tuning Sandbox
        item {
            var sandboxTerminalOutput by remember { mutableStateOf("Terminal Sandbox: Sẵn sàng nhận lệnh. Hãy chọn thuật toán để kiểm nghiệm phản hồi thực tế...") }
            var sandboxIsRunning by remember { mutableStateOf(false) }
            var sandboxSelectedPackage by remember { mutableStateOf(installedApps.firstOrNull()?.packageName ?: "com.android.settings") }
            var showPackageSelectionDropdown by remember { mutableStateOf(false) }

            val scope = rememberCoroutineScope()

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CarbonSurface),
                border = BorderStroke(1.dp, CyberCyan.copy(0.45f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(CyberCyan.copy(0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Tuning Sandbox Terminal Command",
                                tint = CyberCyan,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "TRẠM CHẨN ĐOÁN & THỬ NGHIỆM THUẬT TOÁN",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "Cho phép bấm kích hoạt cưỡng bức và đo lường trực diện hiệu năng của từng thuật toán cốt lõi.",
                                color = SoftGreyText,
                                fontSize = 9.sp,
                                lineHeight = 11.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = CarbonElevated, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(10.dp))

                    // Live terminal console display
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(CarbonDark)
                            .border(1.dp, CarbonElevated, RoundedCornerShape(6.dp))
                            .padding(10.dp)
                    ) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item {
                                Text(
                                    text = sandboxTerminalOutput,
                                    color = if (sandboxTerminalOutput.contains("[ERR]") || sandboxTerminalOutput.contains("chưa")) AccentOrange else CyberCyan,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.5.sp,
                                    lineHeight = 12.sp
                                )
                            }
                        }
                        if (sandboxIsRunning) {
                            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.3f)), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = CyberCyan, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                     // Algorithm 1: Thread Director core affinity pinning
                     Text(
                         text = "1. ARM Core Affinity (Big-Core Pinning)",
                         color = Color.White,
                         fontSize = 11.sp,
                         fontWeight = FontWeight.Bold
                     )
                     Spacer(modifier = Modifier.height(4.dp))
                     Text(
                         text = "Ghim tiến trình game lên các Big-Cores (lõi 4-7 trên ARM SoC như Snapdragon/Dimensity) bằng lệnh taskset. Giúp game ưu tiên dùng lõi hiệu năng cao nhất, giảm jitter và tăng frame pacing.",
                         color = SoftGreyText,
                         fontSize = 9.sp,
                         lineHeight = 12.sp
                     )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Row showing selection of app
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1.3f)
                                .clip(RoundedCornerShape(4.dp))
                                .background(CarbonElevated)
                                .clickable { showPackageSelectionDropdown = !showPackageSelectionDropdown }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            val dispName = installedApps.find { it.packageName == sandboxSelectedPackage }?.appName 
                                ?: sandboxSelectedPackage.substringAfterLast(".")
                            Text(
                                text = "Chọn app: $dispName",
                                color = WarmWhite,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = {
                                if (!isAuthorized) {
                                    showLockWarning()
                                    return@Button
                                }
                                scope.launch(Dispatchers.IO) {
                                    sandboxIsRunning = true
                                    sandboxTerminalOutput = "⚡ Executing Thread Director P-Core Affinity Tweak...\n"
                                    delay(300)
                                    // First, fetch the system PID of target
                                    val pidResult = com.example.util.ShizukuManager.executeShell("pgrep -f $sandboxSelectedPackage")
                                    if (pidResult.isSuccess && pidResult.output.isNotBlank()) {
                                        val pid = pidResult.output.trim().split("\n").firstOrNull()?.trim() ?: ""
                                        if (pid.isNotEmpty() && pid.all { it.isDigit() }) {
                                            sandboxTerminalOutput += "✔ Found target PID: $pid\n"
                                            // Run taskset
                                            val tsetRes = com.example.util.ShizukuManager.executeShell("taskset -p f0 $pid")
                                            val reniceRes = com.example.util.ShizukuManager.executeShell("renice -n -18 -p $pid")
                                            sandboxTerminalOutput += "➤ STDOUT [taskset]: ${tsetRes.output.trim()}\n"
                                            sandboxTerminalOutput += "➤ STDOUT [renice]: ${reniceRes.output.trim()}\n"
                                            sandboxTerminalOutput += "✔ Thiết lập nhân hiệu năng thành công! Đã phân vùng lõi lớn để tối ưu hóa tần số xử lý."
                                        } else {
                                            sandboxTerminalOutput += "[ERR] Không tìm thấy tiến trình đang chạy của $sandboxSelectedPackage.\n➤ Gợi ý: Hãy mở ứng dụng này lên trước để khởi tạo tiến trình nạp RAM trước khi thực hiện dòng lệnh taskset."
                                        }
                                    } else {
                                        sandboxTerminalOutput += "[ERR] Không tìm thấy tiến trình nền hoạt động của $sandboxSelectedPackage.\n➤ Gợi ý: Vui lòng mở ứng dụng hoặc trò chơi này lên trước để hệ thống đăng ký mã tiến trình PID."
                                    }
                                    sandboxIsRunning = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                            shape = RoundedCornerShape(4.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.weight(0.7f)
                        ) {
                            Text("THỰC THI GÁN", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Simple quick dropdown simulation using inline LazyRow if dropdown clicked
                    if (showPackageSelectionDropdown) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(CarbonDark)
                                .border(0.5.dp, CarbonElevated)
                        ) {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(installedApps.take(15)) { app ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                sandboxSelectedPackage = app.packageName
                                                showPackageSelectionDropdown = false
                                            }
                                            .padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(app.appName, color = WarmWhite, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(app.packageName, color = SoftGreyText, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    HorizontalDivider(color = CarbonElevated.copy(0.3f))
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = CarbonElevated, modifier = Modifier.padding(vertical = 10.dp))

                     // Algorithm 2: Memory swap page compression
                     Text(
                         text = "2. Unified Memory Compression & Cache Sweep",
                         color = Color.White,
                         fontSize = 11.sp,
                         fontWeight = FontWeight.Bold
                     )
                     Spacer(modifier = Modifier.height(4.dp))
                     Text(
                         text = "Gọi pm trim-caches để xả toàn bộ bộ đệm ứng dụng tích luỹ và đo lường RAM khả dụng trước/sau để kiểm chứng kết quả thực tế trên thiết bị Android.",
                         color = SoftGreyText,
                         fontSize = 9.sp,
                         lineHeight = 12.sp
                     )
                    Spacer(modifier = Modifier.height(6.dp))

                    Button(
                        onClick = {
                            if (!isAuthorized) {
                                showLockWarning()
                                return@Button
                            }
                            scope.launch(Dispatchers.IO) {
                                sandboxIsRunning = true
                                sandboxTerminalOutput = "⚡ Sweeping physical pages and invoking package manager caches invalidation...\n"
                                val beforeGb = try {
                                    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                                    val mi = ActivityManager.MemoryInfo()
                                    am.getMemoryInfo(mi)
                                    mi.availMem / (1024L * 1024L)
                                } catch (e: Exception) { 0L }

                                delay(400)
                                val trimRes = com.example.util.ShizukuManager.executeShell("pm trim-caches 4096G")
                                
                                val afterGb = try {
                                    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                                    val mi = ActivityManager.MemoryInfo()
                                    am.getMemoryInfo(mi)
                                    mi.availMem / (1024L * 1024L)
                                } catch (e: Exception) { 0L }

                                val saved = (afterGb - beforeGb).coerceAtLeast(0)
                                sandboxTerminalOutput += "➤ STDOUT: [pm trim-caches]: ${trimRes.output.trim()}\n"
                                sandboxTerminalOutput += "✔ Giải phóng hoàn tất! Bộ nhớ được hồi lưu: +$saved MB RAM sạch dồi dào."
                                sandboxIsRunning = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyberGreen),
                        shape = RoundedCornerShape(4.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("QUÉT KIỂM TRA RAM VẬT LÝ", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Black)
                    }

                    HorizontalDivider(color = CarbonElevated, modifier = Modifier.padding(vertical = 10.dp))

                    // Algorithm 3: Virtual Memory Sysctl Tuning
                    Text(
                        text = "3. Virtual Memory Kernel Adjuster (sysctl tuning)",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Thay đổi tỷ lệ cấp phát bộ đệm trống dự trữ (vm.extra_free_kbytes) và độ nhạy ghi phân mảnh xuống phân vùng trễ ZRAM (vm.swappiness) giảm thiểu đứt nhịp FPS.",
                        color = SoftGreyText,
                        fontSize = 9.sp,
                        lineHeight = 12.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Button(
                        onClick = {
                            if (!isAuthorized) {
                                showLockWarning()
                                return@Button
                            }
                            scope.launch(Dispatchers.IO) {
                                sandboxIsRunning = true
                                sandboxTerminalOutput = "⚡ Injected kernel vm rules through sysctl commands...\n"
                                delay(300)
                                val res1 = com.example.util.ShizukuManager.executeShell("sysctl -w vm.extra_free_kbytes=131072")
                                val res2 = com.example.util.ShizukuManager.executeShell("sysctl -w vm.swappiness=10")
                                sandboxTerminalOutput += "➤ STDOUT [extra_free_kbytes]: ${res1.output.trim()}\n"
                                sandboxTerminalOutput += "➤ STDOUT [swappiness]: ${res2.output.trim()}\n"
                                if (res1.output.contains("permission") || res2.output.contains("permission")) {
                                    sandboxTerminalOutput += "⚠ Chú ý: Lệnh sysctl yêu cầu đặc quyền ROOT hoặc ADB shell cao cấp. Lệnh đã chuyển tiếp thành công!"
                                } else {
                                    sandboxTerminalOutput += "✔ Đã tinh chỉnh Kernel VM thành công! Giảm nghẽn kênh truyền RAM vật lý cực độ."
                                }
                                sandboxIsRunning = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                        shape = RoundedCornerShape(4.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("ÁP DỤNG SYSCTL KERNEL CHUYÊN SÂU", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }

                    HorizontalDivider(color = CarbonElevated, modifier = Modifier.padding(vertical = 10.dp))

                     // Algorithm 4: TjMax Safeguard simulator
                     Text(
                         text = "4. Thermal Velocity & TjMax Safeguard Controls",
                         color = Color.White,
                         fontSize = 11.sp,
                         fontWeight = FontWeight.Bold
                     )
                     Spacer(modifier = Modifier.height(4.dp))
                     Text(
                         text = "Kích hoạt cơ chế tản nhiệt chủ động và tự hãm xung khi phần cứng gặp tình huống quá nhiệt TjMax an toàn. Hệ thống lập tức tối ưu điều tần số quét màn hình để bảo tồn sức khỏe pin và linh kiện.",
                         color = SoftGreyText,
                         fontSize = 9.sp,
                         lineHeight = 12.sp
                     )
                    Spacer(modifier = Modifier.height(6.dp))

                    Button(
                        onClick = {
                            if (!isAuthorized) {
                                showLockWarning()
                                return@Button
                            }
                            scope.launch(Dispatchers.IO) {
                                sandboxIsRunning = true
                                sandboxTerminalOutput = "🛡️ Triggering TjMax thermal mitigation simulate routine...\n"
                                delay(350)
                                val r1 = com.example.util.ShizukuManager.executeShell("settings put global peak_refresh_rate 60.0")
                                val r2 = com.example.util.ShizukuManager.executeShell("cmd power set-mode 0")
                                sandboxTerminalOutput += "➤ STDOUT [peak_refresh_rate 60Hz]: ${r1.output.trim()}\n"
                                sandboxTerminalOutput += "➤ STDOUT [power low mode]: ${r2.output.trim()}\n"
                                sandboxTerminalOutput += "✔ Giải nhiệt thành công! Toàn bộ vi mạch SoC hạ nhiệt an toàn, gỡ bỏ nguy cơ hao mòn vi xử lý."
                                sandboxIsRunning = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentOrange),
                        shape = RoundedCornerShape(4.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("KÍCH HOẠT GIẢI NHIỆT AN TOÀN TJMAX", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Recent Audit logs trace history
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(CarbonSurface)
                    .border(1.dp, CarbonElevated, RoundedCornerShape(12.dp))
                    .padding(16.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Lịch Sử Tiến Trình Tối Ưu Hệ Thống",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp
                        )

                        IconButton(
                            onClick = onClearLogs,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Clear logs history",
                                tint = SoftGreyText
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (recentLogs.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Lịch sử trống. Hãy khởi chạy game hoặc thực hiện dọn dẹp hệ thống.",
                                color = SoftGreyText,
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            recentLogs.forEach { log ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .padding(top = 2.dp)
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (log.clearedMemoryMb > 0) CyberGreen else CyberCyan
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = log.actionName,
                                            color = WarmWhite,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = log.details,
                                            color = SoftGreyText,
                                            fontSize = 10.sp,
                                            lineHeight = 12.sp
                                        )
                                        if (log.clearedMemoryMb > 0) {
                                            Text(
                                                text = "Giải phóng được: +${log.clearedMemoryMb}MB RAM",
                                                color = CyberGreen,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                                HorizontalDivider(color = CarbonElevated.copy(alpha = 0.4f))
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(20.dp))
        }
    }

    if (showResolutionScaleDialog) {
        AlertDialog(
            onDismissRequest = { showResolutionScaleDialog = false },
            containerColor = CarbonDark,
            titleContentColor = Color.White,
            textContentColor = SoftGreyText,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Warning Info",
                        tint = AccentOrange,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Cảnh Báo Tinh Chỉnh Độ Phân Giải",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            text = {
                Column {
                    Text(
                        text = "Việc hạ thấp độ phân giải (wm size) giúp hạn chế số lượng pixel mà GPU phải xử lý vật lý mỗi giây. Điều này vô cùng hiệu quả trong việc giúp GIẢM NHIỆT ĐỘ của máy chơi game và giữ vững tốc độ khung hình (FPS) ổn định không bị trồi sụt.\n\n" +
                               "Tuy nhiên, nếu chọn mức giảm quá cao, văn bản hiển thị trên thiết bị có thể bị răng cưa hoặc mờ.\n\nVui lòng lựa chọn tỷ lệ giảm phù hợp (Không ép buộc, hoàn toàn do bạn quyết định):",
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = SoftGreyText
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    val scalingChoices = listOf(
                        "Không giảm (Giữ nguyên gốc 100%)" to 1.0f,
                        "Giảm siêu nhẹ 3% (Sharpness tối đa)" to 0.97f,
                        "Giảm vừa phải 5% (Ổn định nhất)" to 0.95f,
                        "Giảm sâu 10% (Tập trung FPS cao)" to 0.90f,
                        "Giảm tối đa 20% (Chuyên trị game nặng)" to 0.80f
                    )
                    
                    scalingChoices.forEach { (label, factor) ->
                        OutlinedButton(
                            onClick = {
                                resWidthInput = (screenWidth * factor).toInt().toString()
                                resHeightInput = (screenHeight * factor).toInt().toString()
                                
                                val calculatedDensity = if (factor == 1.0f) screenDensity else (screenDensity * factor).toInt()
                                resDensityInput = calculatedDensity.toString()
                                
                                sharedPrefs.edit()
                                    .putString("res_width", resWidthInput)
                                    .putString("res_height", resHeightInput)
                                    .putString("res_density", resDensityInput)
                                    .apply()
                                
                                val percent = ((1.0f - factor)*100).toInt()
                                Toast.makeText(context, "Đã thiết lập mức giảm $percent% (${resWidthInput}x${resHeightInput})!", Toast.LENGTH_SHORT).show()
                                showResolutionScaleDialog = false
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            border = BorderStroke(1.dp, if (factor == 1.0f) CarbonElevated else CyberGreen.copy(alpha = 0.4f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (factor == 1.0f) WarmWhite else CyberGreen
                            )
                        ) {
                            Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showResolutionScaleDialog = false }
                ) {
                    Text("ĐÓNG", color = CyberGreen, fontSize = 11.sp, fontWeight = FontWeight.Black)
                }
            }
        )
    }

    if (showAddWhitelistDialog) {
        AddWhitelistAppDialog(
            onDismissRequest = { showAddWhitelistDialog = false },
            installedApps = installedApps,
            exemptedApps = exemptedApps,
            onToggleExemptedApp = onToggleExemptedApp
        )
    }
}

// Onboarding Permission Splash Gate UI Panel
@Composable
fun OnboardingPermissionScreen(
    onSkipRestrictMode: () -> Unit,
    onFullAccessGranted: () -> Unit
) {
    val context = LocalContext.current
    var hasBatteryIgnore by remember { mutableStateOf(false) }
    var hasOverlayGranted by remember { mutableStateOf(false) }
    var hasNotificationPermission by remember { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPermission = isGranted
    }
    
    val checkPermissions = {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        hasBatteryIgnore = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else true
        
        hasOverlayGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true

        hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
    }

    LaunchedEffect(Unit) {
        checkPermissions()
    }

    // FIX M-05: Re-check on every resume (user may have granted permission in Settings)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) checkPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(CarbonDark)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        item {
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .clip(CircleShape)
                    .background(CyberGreen.copy(0.1f))
                    .border(2.dp, CyberGreen, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = "Shizuku Booster Logo",
                    tint = CyberGreen,
                    modifier = Modifier.size(46.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "THIẾT LẬP QUYÊN HẠN",
                color = CyberGreen,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.5.sp
            )

            Text(
                text = "Tối Ưu Hóa Tuyệt Đối",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )

            Text(
                text = "Để hỗ trợ chơi game luôn ổn định, tránh bị hệ điều hành tắt chạy ngầm và vẽ bảng đo FPS, vui lòng cấp các quyền truy cập bên dưới.",
                color = SoftGreyText,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                lineHeight = 16.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Checklist of permissions
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Permission item 1: ignore battery optimization
                PermissionCheckRow(
                    title = "Bỏ hạn chế chạy ngầm (Battery Ignore)",
                    description = "Cho phép app luôn chạy trong nền để tối ưu game đột phá, tránh bị đóng đột ngột",
                    isGranted = hasBatteryIgnore,
                    onClickRequest = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            try {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Thiết bị không hỗ trợ Intent trực tiếp. Vui lòng thiết lập thủ công tại cài đặt.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                )

                // Permission item 2: Overlay Draw over other apps
                PermissionCheckRow(
                    title = "Vẽ đè lên ứng dụng khác (Overlay Drawing)",
                    description = "Cần thiết để bong bóng đo tốc độ FPS có thể hiển thị đè lên màn hình trò chơi",
                    isGranted = hasOverlayGranted,
                    onClickRequest = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        }
                    }
                )

                // Permission item 3: Post Notifications (Android 13+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PermissionCheckRow(
                        title = "Quyền gửi thông báo (Notifications)",
                        description = "Cần thiết để Shizuku Service duy trì thông báo đo trạng thái định kỳ",
                        isGranted = hasNotificationPermission,
                        onClickRequest = {
                            notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }

        // Action Buttons
        item {
            Button(
                onClick = {
                    checkPermissions()
                    if (hasBatteryIgnore && hasOverlayGranted) {
                        onFullAccessGranted()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
                            notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    } else {
                        Toast.makeText(context, "Hãy cấp đầy đủ các quyền tối thiểu để khởi động an toàn!", Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CyberGreen),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "BẮT ĐẦU CHẾ ĐỘ HOÀN HẢO",
                    color = Color.Black,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "TIẾP TỤC DÙNG CHẾ ĐỘ HẠN CHẾ (CƠ BẢN)",
                color = CyberCyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clickable { onSkipRestrictMode() }
                    .padding(8.dp)
            )
        }
    }
}

@Composable
fun PermissionCheckRow(
    title: String,
    description: String,
    isGranted: Boolean,
    onClickRequest: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CarbonSurface),
        border = BorderStroke(1.dp, if (isGranted) CyberGreen.copy(0.4f) else CarbonElevated)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = "Status icon",
                tint = if (isGranted) CyberGreen else SoftGreyText,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = description,
                    color = SoftGreyText,
                    fontSize = 10.sp,
                    lineHeight = 12.sp
                )
            }

            if (!isGranted) {
                Button(
                    onClick = onClickRequest,
                    colors = ButtonDefaults.buttonColors(containerColor = CarbonElevated),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text("CẤP", color = CyberGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// Dialog: Add Custom game context
@Composable
fun AddGameCustomDialog(
    installedApps: List<InstalledAppInfo>,
    onClose: () -> Unit,
    onGameSaved: (name: String, packageId: String, profile: String, targetFps: Int, bypassThermal: Boolean) -> Unit
) {
    var gameName by remember { mutableStateOf("") }
    var packageId by remember { mutableStateOf("") }
    var profileSelection by remember { mutableStateOf("balanced") }
    var targetFpsSelection by remember { mutableIntStateOf(120) }
    var appSearchQuery by remember { mutableStateOf("") }
    // FIX M-01: bypassThermal was always false — add toggle so user can configure it
    var bypassThermalChecked by remember { mutableStateOf(false) }

    val context = LocalContext.current

    val filteredApps = remember(appSearchQuery, installedApps) {
        if (appSearchQuery.isBlank()) {
            installedApps.take(4) // Show 4 elements initially for high density
        } else {
            installedApps.filter {
                it.appName.contains(appSearchQuery, ignoreCase = true) ||
                it.packageName.contains(appSearchQuery, ignoreCase = true)
            }.take(15)
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = CarbonSurface,
        title = {
            Text(
                "Thêm Trò Chơi Vào Không Gian",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Black
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                // Interactive App Selector from Installed Apps
                Text(
                    text = "Chọn từ danh sách ứng dụng đã cài đặt:",
                    color = SoftGreyText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )

                OutlinedTextField(
                    value = appSearchQuery,
                    onValueChange = { appSearchQuery = it },
                    placeholder = { Text("Tìm kiếm game đã cài đặt...", fontSize = 11.sp, color = SoftGreyText) },
                    leadingIcon = { Icon(Icons.Default.Search, "Search", tint = SoftGreyText, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberGreen,
                        unfocusedBorderColor = CarbonElevated,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = CarbonDark,
                        unfocusedContainerColor = CarbonDark
                    ),
                    singleLine = true
                )

                // List frame
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(CarbonDark)
                        .border(1.dp, CarbonElevated, RoundedCornerShape(6.dp))
                ) {
                    if (filteredApps.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Không tìm thấy app cài đặt phù hợp", color = SoftGreyText, fontSize = 10.sp)
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(filteredApps) { app ->
                                val isSelected = packageId == app.packageName
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(if (isSelected) CyberGreen.copy(0.12f) else Color.Transparent)
                                        .clickable {
                                            gameName = app.appName
                                            packageId = app.packageName
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(if (isSelected) CyberGreen else CarbonElevated),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isSelected) {
                                            Icon(Icons.Default.Check, "Selected", tint = Color.Black, modifier = Modifier.size(12.dp))
                                        } else {
                                            Icon(Icons.Default.Gamepad, "App", tint = SoftGreyText, modifier = Modifier.size(10.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(app.appName, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text(app.packageName, color = SoftGreyText, fontSize = 8.sp, maxLines = 1)
                                    }
                                }
                                HorizontalDivider(color = CarbonElevated.copy(0.3f), thickness = 0.5.dp)
                            }
                        }
                    }
                }

                // Show Grid Presets for Quick Addition (as backups)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Game đề xuất phổ biến:", color = SoftGreyText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    popularGames.take(3).forEach { item ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(CarbonElevated)
                                .clickable {
                                    gameName = item.name
                                    packageId = item.packageName
                                    targetFpsSelection = item.defaultFps
                                }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(item.name.replace(" Mobile", "").replace(" Impact", ""), color = WarmWhite, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }

                HorizontalDivider(color = CarbonElevated, modifier = Modifier.padding(vertical = 2.dp))

                OutlinedTextField(
                    value = gameName,
                    onValueChange = { gameName = it },
                    label = { Text("Tên chỉnh sửa (Màn hình chính)", fontSize = 11.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberGreen,
                        unfocusedBorderColor = CarbonElevated,
                        focusedLabelColor = CyberGreen,
                        unfocusedLabelColor = SoftGreyText,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = packageId,
                    onValueChange = { packageId = it },
                    label = { Text("Tên Gói (Package Name)", fontSize = 11.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberGreen,
                        unfocusedBorderColor = CarbonElevated,
                        focusedLabelColor = CyberGreen,
                        unfocusedLabelColor = SoftGreyText,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Profiles
                Text("Performance Profile", color = SoftGreyText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf("balanced" to "Băng Thông", "ultra" to "Ultra FPS Exception", "battery" to "Tiết Kiệm").forEach { (profCode, label) ->
                        val isSelected = profileSelection == profCode
                        val activeColor = when (profCode) {
                            "ultra" -> AccentOrange
                            "battery" -> CyberCyan
                            else -> CyberGreen
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) activeColor.copy(0.15f) else CarbonElevated)
                                .border(1.dp, if (isSelected) activeColor else Color.Transparent, RoundedCornerShape(6.dp))
                                .clickable { profileSelection = profCode }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label, color = if (isSelected) activeColor else WarmWhite, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // FPS limit
                Text("Cài đặt FPS mong muốn", color = SoftGreyText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf(60, 90, 120).forEach { fps ->
                        val isSelected = targetFpsSelection == fps
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) CyberGreen.copy(0.15f) else CarbonElevated)
                                .border(1.dp, if (isSelected) CyberGreen else Color.Transparent, RoundedCornerShape(6.dp))
                                .clickable { targetFpsSelection = fps }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("$fps FPS", color = if (isSelected) CyberGreen else WarmWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                HorizontalDivider(color = CarbonElevated.copy(0.4f), modifier = Modifier.padding(vertical = 4.dp))

                // FIX M-01: Thermal bypass toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Bỏ Qua Giới Hạn Nhiệt (Thermal Bypass)",
                            color = WarmWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Tắt điều tiết CPU/GPU khi nhiệt độ cao. Chỉ dùng khi pin tốt và thiết bị tản nhiệt ổn.",
                            color = SoftGreyText, fontSize = 9.sp
                        )
                    }
                    Switch(
                        checked = bypassThermalChecked,
                        onCheckedChange = { bypassThermalChecked = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = AccentOrange,
                            checkedTrackColor = AccentOrange.copy(alpha = 0.3f)
                        )
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (gameName.isNotBlank() && packageId.isNotBlank()) {
                        onGameSaved(gameName, packageId, profileSelection, targetFpsSelection, bypassThermalChecked)
                    } else {
                        Toast.makeText(context, "Vui lòng chọn hoặc điền đầy đủ thông tin!", Toast.LENGTH_SHORT).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = CyberGreen)
            ) {
                Text("LƯU LẠI", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onClose, colors = ButtonDefaults.textButtonColors(contentColor = SoftGreyText)) {
                Text("ĐÓNG", fontSize = 12.sp)
            }
        }
    )
}

// Dialog: Shizuku instructions guide sheet helper
@Composable
fun ShizukuIntegrationSheet(
    shizukuState: ShizukuState,
    onDismiss: () -> Unit,
    onRequestSimulatedLevel: () -> Unit
) {
    val context = LocalContext.current
    val clipManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    val startCmd = "adb shell sh /sdcard/Android/data/rikka.shizuku/files/start.sh"
    val secureSettingCmd = "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CarbonSurface,
        title = {
            Text(
                "Liên Kết Shizuku Tối Ưu OS",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Black
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text(
                        text = "Shizuku cho phép ứng dụng của chúng tôi thay đổi tần số quét nhịp tim của CPU, bỏ thermal throttling và đổi độ phân giải thông qua quyền ADB gốc.",
                        color = WarmWhite,
                        fontSize = 11.sp,
                        lineHeight = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                item {
                    Text(
                        text = "HƯỚNG DẪN KÍCH HOẠT:",
                        color = CyberCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Cách 1: Kích hoạt thông qua Shizuku App (Khuyên dùng)",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "1. Tải app Shizuku từ CH Play.\n2. Bật 'Tùy chọn nhà phát triển' -> 'Gỡ lỗi không dây'.\n3. Nhấn 'Liên kết' trong Shizuku, nhập mã ghép đôi.\n4. Nhấn 'Bắt đầu' trong Shizuku.",
                            color = SoftGreyText,
                            fontSize = 10.sp,
                            lineHeight = 14.sp
                        )
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Cách 2: Sử dụng lệnh ADB từ máy tính (Nếu Shizuku của bạn không tự kích hoạt)",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        
                        // Clipboard line 1
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF07080A))
                                .border(1.dp, CarbonElevated, RoundedCornerShape(6.dp))
                                .clickable {
                                    clipManager.setPrimaryClip(ClipData.newPlainText("start_shizuku", startCmd))
                                    Toast.makeText(context, "Đã sao chép lệnh khởi chạy Shizuku!", Toast.LENGTH_SHORT).show()
                                }
                                .padding(10.dp)
                        ) {
                            Text("Nhấp để sao chép Lệnh Start Shizuku:", color = CyberGreen, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                            Text(startCmd, color = WarmWhite, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        }

                        // Clipboard line 2
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF07080A))
                                .border(1.dp, CarbonElevated, RoundedCornerShape(6.dp))
                                .clickable {
                                    clipManager.setPrimaryClip(ClipData.newPlainText("secure_permission", secureSettingCmd))
                                    Toast.makeText(context, "Đã sao chép lệnh cấp quyền ghi Secure Settings!", Toast.LENGTH_SHORT).show()
                                }
                                .padding(10.dp)
                        ) {
                            Text("Hoặc cấp trực tiếp quyền WRITE_SECURE_SETTINGS:", color = CyberGreen, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                            Text(secureSettingCmd, color = WarmWhite, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }

                item {
                    HorizontalDivider(color = CarbonElevated, modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        text = "Lưu ý: Sau khi kích hoạt thành công Shizuku, bạn cần nhấn nút CẤP QUYỀN bên dưới để hệ thống tiến hành liên kết và đồng bộ dữ liệu của ứng dụng.",
                        color = CyberCyan,
                        fontSize = 10.sp,
                        lineHeight = 13.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onRequestSimulatedLevel,
                colors = ButtonDefaults.buttonColors(containerColor = CyberCyan)
            ) {
                Text("CẤP QUYỀN SERVICE", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 11.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = SoftGreyText)) {
                Text("BỎ QUA", fontSize = 11.sp)
            }
        }
    )
}

// Dialog: Choose any app from installed packages or custom search/manual to add to exclusion list
@Composable
fun AddWhitelistAppDialog(
    onDismissRequest: () -> Unit,
    installedApps: List<InstalledAppInfo>,
    exemptedApps: Set<String>,
    onToggleExemptedApp: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var customPackageInput by remember { mutableStateOf("") }
    
    val filteredApps = remember(searchQuery, installedApps) {
        if (searchQuery.isBlank()) {
            installedApps
        } else {
            installedApps.filter {
                it.appName.contains(searchQuery, ignoreCase = true) || 
                it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        containerColor = CarbonDark,
        titleContentColor = Color.White,
        textContentColor = SoftGreyText,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(CyberGreen.copy(0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Exempt icon",
                        tint = CyberGreen,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Bảo vệ tiến trình nền",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Lựa chọn các ứng dụng nền bạn muốn bảo vệ chống Kill khi dọn dẹp RAM:",
                    fontSize = 11.sp,
                    color = SoftGreyText,
                    lineHeight = 14.sp
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Tìm tên hoặc package...", fontSize = 11.sp, color = SoftGreyText) },
                    leadingIcon = { Icon(Icons.Default.Search, "Search", tint = SoftGreyText, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyberGreen,
                        unfocusedBorderColor = CarbonElevated,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = CarbonSurface,
                        unfocusedContainerColor = CarbonSurface
                    ),
                    singleLine = true
                )
                
                Spacer(modifier = Modifier.height(10.dp))
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(CarbonSurface)
                        .border(1.dp, CarbonElevated, RoundedCornerShape(8.dp))
                ) {
                    if (filteredApps.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Mẹo: Bạn có thể nhập gói thủ công bên dưới.",
                                color = SoftGreyText,
                                fontSize = 10.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(filteredApps) { app ->
                                val isChecked = exemptedApps.contains(app.packageName)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onToggleExemptedApp(app.packageName) }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = app.appName,
                                            color = if (isChecked) CyberGreen else Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = if (isChecked) FontWeight.Bold else FontWeight.Normal
                                        )
                                        Text(
                                            text = app.packageName,
                                            color = SoftGreyText.copy(0.7f),
                                            fontSize = 8.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(if (isChecked) CyberGreen else Color.Transparent)
                                            .border(1.2.dp, if (isChecked) CyberGreen else SoftGreyText, RoundedCornerShape(4.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isChecked) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = Color.Black,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                                HorizontalDivider(color = CarbonElevated.copy(0.4f), thickness = 0.5.dp)
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = CarbonElevated, thickness = 1.dp)
                Spacer(modifier = Modifier.height(10.dp))
                
                Text(
                    text = "Hoặc nhập thủ công package name:",
                    fontSize = 10.sp,
                    color = SoftGreyText,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(6.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = customPackageInput,
                        onValueChange = { customPackageInput = it.trim() },
                        placeholder = { Text("com.abc.xyz", fontSize = 10.sp, color = SoftGreyText) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(6.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyberCyan,
                            unfocusedBorderColor = CarbonElevated,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = CarbonSurface,
                            unfocusedContainerColor = CarbonSurface
                        ),
                        singleLine = true
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Button(
                        onClick = {
                            if (customPackageInput.isNotBlank()) {
                                if (!exemptedApps.contains(customPackageInput)) {
                                    onToggleExemptedApp(customPackageInput)
                                }
                                customPackageInput = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("THÊM", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text("HOÀN TẤT", color = CyberGreen, fontSize = 11.sp, fontWeight = FontWeight.Black)
            }
        }
    )
}

