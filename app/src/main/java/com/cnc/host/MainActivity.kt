package com.cnc.host

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cnc.host.navigation.Screen
import com.cnc.host.navigation.advancedNavItems
import com.cnc.host.navigation.simpleNavItems
import com.cnc.host.service.KioskService
import com.cnc.host.setup.PermissionsHolder
import com.cnc.host.setup.SetupPrefs
import com.cnc.host.update.AppUpdate
import com.cnc.host.update.AppUpdateDialog
import com.cnc.host.sync.ServerPrefs
import com.cnc.host.ui.advanced.AdvancedHubScreen
import com.cnc.host.ui.advanced.PinDialog
import com.cnc.host.ui.advanced.ServerSyncScreen
import com.cnc.host.ui.alarms.AlarmsScreen
import com.cnc.host.ui.cam.CamScreen
import com.cnc.host.ui.comm.CommScreen
import com.cnc.host.ui.components.AppNavigationRail
import com.cnc.host.ui.components.TopStatusBar
import com.cnc.host.ui.device.DeviceScreen
import com.cnc.host.ui.drawingparse.DrawingParseScreen
import com.cnc.host.ui.drawings.DrawingsScreen
import com.cnc.host.ui.history.HistoryScreen
import com.cnc.host.ui.home.HomeScreen
import com.cnc.host.ui.home.HomeViewModel
import com.cnc.host.ui.io.IoScreen
import com.cnc.host.ui.machining.MachiningScreen
import com.cnc.host.ui.materials.MaterialScreen
import com.cnc.host.ui.part.PartScreen
import com.cnc.host.ui.process.ProcessScreen
import com.cnc.host.ui.review.ReviewScreen
import com.cnc.host.ui.settings.SettingsScreen
import com.cnc.host.ui.setup.KioskSettingsOverlay
import com.cnc.host.ui.setup.NetworkSetupScreen
import com.cnc.host.ui.setup.PermissionsSetupScreen
import com.cnc.host.ui.setup.RegisterScreen
import com.cnc.host.ui.simple.SimpleAlarmsScreen
import com.cnc.host.ui.simple.SimpleMonitorScreen
import com.cnc.host.ui.simple.SimplePreviewScreen
import com.cnc.host.ui.simple.SimpleTasksScreen
import com.cnc.host.ui.simulation.SimulationScreen
import com.cnc.host.ui.tools.ToolScreen
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTheme
import com.cnc.ui.theme.CncTypography

/** 从图纸库跳到 CAM 时使用的路由前缀（带 drawingId 参数）。 */
private const val CAM_DRAWING_ROUTE_PREFIX = "cam_drawing"

class MainActivity : ComponentActivity() {

    companion object {
        /** 主界面是否在前台，供 KioskService 看门狗判断是否需要重新拉起。 */
        @Volatile
        var isResumed: Boolean = false
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        // 回到前台（必定可见）时确保保活服务在运行；后台场景下不会启动，避免 FGS 超时崩溃
        runCatching { KioskService.start(this) }
        // 从系统安装器返回（取消或安装失败）时恢复看门狗
        AppUpdate.onInstallerReturned()
    }

    override fun onPause() {
        super.onPause()
        isResumed = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 手动打开软件视为恢复正常运行：复位「彻底退出」标记并确保保活服务在运行
        SetupPrefs.setExited(this, false)
        runCatching { KioskService.start(this) }
        // 拦截返回键：Kiosk 模式下不允许通过返回键退出软件（对话框/导航的内部返回仍正常）
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 拦截，不退出
            }
        })
        enableEdgeToEdge()
        // 客户端统一使用内嵌键盘，禁止系统输入法弹出
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        hideSystemBars()
        setContent {
            CncTheme {
                CncHostApp()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 下拉通知栏/上滑手势会短暂唤出系统栏，重新获得焦点时立刻再隐藏
        if (hasFocus) hideSystemBars()
    }

    /** Kiosk 沉浸式：隐藏状态栏与导航栏，页面不被系统 UI 遮挡，也减少误触退出的入口。 */
    private fun hideSystemBars() {
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 用户按 Home/多任务键离开时快速拉回前台（看门狗 700ms 兜底）；
        // 更新安装期间不拉回，避免顶掉系统安装界面
        if (!SetupPrefs.isExited(this) && !AppUpdate.installing) {
            window.decorView.postDelayed({
                if (!isResumed && !SetupPrefs.isExited(this)) {
                    runCatching {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        )
                    }
                }
            }, 300L)
        }
    }
}

/**
 * 主界面。
 *
 * 首次启动（未注册）时先引导「网络配置 → 设备注册」；
 * 注册完成后进入主界面。主界面默认是傻瓜式简易模式：底部 4 个页签
 * （任务 / 预览下发 / 运行监控 / 报警状态）。高级配置通过顶部「高级模式」+ PIN 进入，
 * 使用左侧导航栏。顶部右侧「设置」按钮需管理员密码，可修改服务器地址或临时退出到安卓桌面。
 *
 * 各功能入口按后台下发的权限显隐。
 */
@Composable
fun CncHostApp() {
    val context = LocalContext.current
    // 自动更新：发现新版本时全局弹窗
    AppUpdateDialog()
    var setupCompleted by rememberSaveable {
        mutableStateOf(SetupPrefs.load(context).setupCompleted)
    }

    // 首次启动引导流程：0=网络配置，1=权限与自启，2=设备注册
    var setupStep by rememberSaveable { mutableIntStateOf(0) }

    if (!setupCompleted) {
        when (setupStep) {
            0 -> NetworkSetupScreen(onNext = { setupStep = 1 })
            1 -> PermissionsSetupScreen(onNext = { setupStep = 2 })
            else -> RegisterScreen(onRegistered = {
                setupCompleted = true
                PermissionsHolder.load(context)
            })
        }
        return
    }

    // 进入主应用后加载一次缓存的权限（心跳会持续更新）
    LaunchedEffect(Unit) { PermissionsHolder.load(context) }

    val perms by PermissionsHolder.flow.collectAsState()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val rawRoute = backStackEntry?.destination?.route ?: Screen.SimpleTasks.route
    // 从图纸库进入 CAM 的临时路由，导航栏仍高亮 CAM
    val currentRoute = if (rawRoute.startsWith(CAM_DRAWING_ROUTE_PREFIX)) Screen.Cam.route else rawRoute

    var advancedUnlocked by rememberSaveable { mutableStateOf(false) }
    var showPinDialog by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    val inSimpleMode = simpleNavItems.any { it.route == currentRoute }
    val showAdvancedRail = advancedUnlocked && !inSimpleMode

    // 从高级模式返回简易页（含系统返回键）时自动锁定
    LaunchedEffect(currentRoute) {
        if (advancedUnlocked && inSimpleMode) advancedUnlocked = false
    }

    val homeViewModel: HomeViewModel = viewModel()
    val snapshot by homeViewModel.snapshot.collectAsState()

    // 按权限过滤底部页签
    val visibleSimpleItems = simpleNavItems.filter { screen ->
        when (screen) {
            Screen.SimpleTasks -> perms.taskSelect
            Screen.SimplePreview -> perms.previewStart
            Screen.SimpleMonitor -> perms.monitor
            Screen.SimpleAlarms -> perms.alarms
            else -> true
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopStatusBar(
            snapshot = snapshot,
            onAdvanced = if (perms.advanced) {
                {
                    if (showAdvancedRail) {
                        advancedUnlocked = false
                        navController.navigate(Screen.SimpleTasks.route) {
                            popUpTo(Screen.SimpleTasks.route) { inclusive = false }
                            launchSingleTop = true
                        }
                    } else {
                        showPinDialog = true
                    }
                }
            } else null,
            advancedLabel = if (showAdvancedRail) "返回简易模式" else "高级模式",
            onSettings = { showSettings = true }
        )

        Row(modifier = Modifier.weight(1f).fillMaxWidth().background(CncColors.Background)) {
            if (showAdvancedRail) {
                AppNavigationRail(
                    currentRoute = currentRoute,
                    items = advancedNavItems,
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(Screen.Advanced.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }

            NavHost(
                navController = navController,
                startDestination = Screen.SimpleTasks.route,
                modifier = Modifier.weight(1f).fillMaxSize()
            ) {
                // ===== 简易模式 =====
                composable(Screen.SimpleTasks.route) {
                    SimpleTasksScreen(onOpenPreview = {
                        navController.navigate(Screen.SimplePreview.route) { launchSingleTop = true }
                    })
                }
                composable(Screen.SimplePreview.route) {
                    SimplePreviewScreen(onOpenMonitor = {
                        navController.navigate(Screen.SimpleMonitor.route) { launchSingleTop = true }
                    })
                }
                composable(Screen.SimpleMonitor.route) {
                    SimpleMonitorScreen(onOpenPreview = {
                        navController.navigate(Screen.SimplePreview.route) { launchSingleTop = true }
                    })
                }
                composable(Screen.SimpleAlarms.route) { SimpleAlarmsScreen() }

                // ===== 高级模式 =====
                composable(Screen.Advanced.route) {
                    AdvancedHubScreen(onNavigate = { route ->
                        navController.navigate(route) { launchSingleTop = true }
                    })
                }
                composable(Screen.ServerSync.route) { ServerSyncScreen() }

                // ===== 旧有高级页面 =====
                composable(Screen.Home.route) {
                    HomeScreen(onNavigate = { r -> navController.navigate(r) })
                }
                composable(Screen.Drawings.route) {
                    DrawingsScreen(onOpenInCam = { id -> navController.navigate("$CAM_DRAWING_ROUTE_PREFIX/$id") })
                }
                composable(Screen.DrawingParse.route) { DrawingParseScreen() }
                composable(Screen.Part.route) { PartScreen() }
                composable(Screen.Materials.route) { MaterialScreen() }
                composable(Screen.Tools.route) { ToolScreen() }
                composable(Screen.Process.route) { ProcessScreen() }
                composable(Screen.Cam.route) { CamScreen() }
                composable(
                    route = "$CAM_DRAWING_ROUTE_PREFIX/{drawingId}",
                    arguments = listOf(navArgument("drawingId") { type = NavType.StringType })
                ) { entry ->
                    CamScreen(drawingId = entry.arguments?.getString("drawingId"))
                }
                composable(Screen.Simulation.route) { SimulationScreen() }
                composable(Screen.Review.route) { ReviewScreen() }
                composable(Screen.Machining.route) { MachiningScreen() }
                composable(Screen.Io.route) { IoScreen() }
                composable(Screen.Alarms.route) { AlarmsScreen() }
                composable(Screen.Comm.route) { CommScreen() }
                composable(Screen.Device.route) { DeviceScreen() }
                composable(Screen.History.route) { HistoryScreen() }
                composable(Screen.Settings.route) { SettingsScreen() }
            }
        }

        if (!showAdvancedRail && visibleSimpleItems.isNotEmpty()) {
            SimpleBottomBar(
                currentRoute = currentRoute,
                items = visibleSimpleItems,
                onNavigate = { route ->
                    navController.navigate(route) {
                        popUpTo(Screen.SimpleTasks.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
    }

    if (showPinDialog) {
        PinDialog(
            expectedPin = ServerPrefs.load(context).advancedPin,
            onDismiss = { showPinDialog = false },
            onSuccess = {
                showPinDialog = false
                advancedUnlocked = true
                navController.navigate(Screen.Advanced.route) { launchSingleTop = true }
            }
        )
    }

    if (showSettings) {
        KioskSettingsOverlay(
            onDismiss = { showSettings = false },
            onExitApp = {
                showSettings = false
                // 彻底退出：不再被看门狗拉起，任务也从最近列表移除
                (context as? ComponentActivity)?.finishAndRemoveTask()
            }
        )
    }
}

/** 简易模式底部页签（按权限过滤后的可见项）。 */
@Composable
private fun SimpleBottomBar(
    currentRoute: String,
    items: List<Screen>,
    onNavigate: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(CncColors.Surface),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { screen ->
            val selected = screen.route == currentRoute
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onNavigate(screen.route) }
                    .background(
                        if (selected) CncColors.PrimaryVariant.copy(alpha = 0.25f) else Color.Transparent,
                        RoundedCornerShape(8.dp)
                    ),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    screen.label,
                    style = CncTypography.titleMedium,
                    color = if (selected) CncColors.Primary else CncColors.TextSecondary
                )
            }
        }
    }
}
