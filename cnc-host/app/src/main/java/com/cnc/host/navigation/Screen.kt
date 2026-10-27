package com.cnc.host.navigation

/** 应用路由定义。 */
sealed class Screen(val route: String, val label: String) {
    // ===== 简易模式（日常操作） =====
    data object SimpleTasks : Screen("simple_tasks", "任务")
    data object SimplePreview : Screen("simple_preview", "预览下发")
    data object SimpleMonitor : Screen("simple_monitor", "运行监控")
    data object SimpleAlarms : Screen("simple_alarms", "报警状态")

    // ===== 高级模式（配置与调试，PIN 保护） =====
    data object Advanced : Screen("advanced", "高级功能")
    data object ServerSync : Screen("server_sync", "服务端同步")
    data object Home : Screen("home", "设备总览")
    data object Drawings : Screen("drawings", "图纸管理")
    data object DrawingParse : Screen("drawing_parse", "图纸解析")
    data object Part : Screen("part", "零件与毛坯")
    data object Materials : Screen("materials", "材料库")
    data object Tools : Screen("tools", "刀具库")
    data object Process : Screen("process", "工艺规划")
    data object Cam : Screen("cam", "CAM 刀路")
    data object Simulation : Screen("simulation", "刀路仿真")
    data object Review : Screen("review", "加工审核")
    data object Machining : Screen("machining", "加工控制")
    data object Io : Screen("io", "IO 监控")
    data object Alarms : Screen("alarms", "报警中心")
    data object Comm : Screen("comm", "通讯配置")
    data object Device : Screen("device", "设备配置")
    data object History : Screen("history", "任务历史")
    data object Settings : Screen("settings", "系统设置")
}

/** 底部主导航（简易模式的 4 个日常操作）。 */
val simpleNavItems = listOf(
    Screen.SimpleTasks,
    Screen.SimplePreview,
    Screen.SimpleMonitor,
    Screen.SimpleAlarms
)

/** 高级模式左侧导航。 */
val advancedNavItems = listOf(
    Screen.Advanced,
    Screen.ServerSync,
    Screen.Drawings,
    Screen.DrawingParse,
    Screen.Part,
    Screen.Materials,
    Screen.Tools,
    Screen.Process,
    Screen.Cam,
    Screen.Simulation,
    Screen.Review,
    Screen.Machining,
    Screen.Io,
    Screen.Alarms,
    Screen.Comm,
    Screen.Device,
    Screen.Home,
    Screen.History,
    Screen.Settings
)

/** 兼容旧代码：默认导航项。 */
val mainNavItems = advancedNavItems
