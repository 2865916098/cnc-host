package com.cnc.host.ui.setup

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import com.cnc.host.ui.components.KioskTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.cnc.host.setup.WifiSetup
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography
import kotlinx.coroutines.delay

/**
 * 首次启动第 1 步：网络配置（全程在 App 内完成，不跳系统设置）。
 *
 * - 顶部实时显示联网状态（WiFi 名称 / 移动数据 / 未连接），每 2 秒轮询刷新
 * - 两个选项卡：「连接 WiFi」（应用内扫描连接）与「使用移动数据」（跳系统设置开启）
 * - 只有网络已连接且可用时才出现「下一步」按钮
 */
@Composable
fun NetworkSetupScreen(onNext: () -> Unit) {
    val context = LocalContext.current

    var online by remember { mutableStateOf(WifiSetup.isOnline(context)) }
    var networkDesc by remember { mutableStateOf(WifiSetup.currentNetworkDescription(context)) }
    var wifiEnabled by remember { mutableStateOf(WifiSetup.isWifiEnabled(context)) }
    var hasPermission by remember { mutableStateOf(WifiSetup.hasScanPermission(context)) }
    var scanning by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var networks by remember { mutableStateOf<List<WifiSetup.WifiItem>>(emptyList()) }
    // 0=连接 WiFi，1=使用移动数据
    var tab by remember { mutableIntStateOf(0) }

    // 待连接的热点（弹出密码输入框）；null 表示无
    var pendingConnect by remember { mutableStateOf<WifiSetup.WifiItem?>(null) }
    var connectingSsid by remember { mutableStateOf<String?>(null) }
    var connectError by remember { mutableStateOf<String?>(null) }

    fun refreshScan() {
        if (!WifiSetup.hasScanPermission(context)) return
        scanning = true
        scanError = null
        WifiSetup.startScan(
            context,
            onResult = {
                scanning = false
                networks = it
                if (it.isEmpty()) scanError = "未发现附近热点，请确认 WiFi 已开启后重试"
            },
            onError = {
                scanning = false
                scanError = it
            }
        )
    }

    // 权限申请：有权限则自动扫描，无权限则自动申请
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasPermission = WifiSetup.hasScanPermission(context)
        if (hasPermission) refreshScan()
    }
    LaunchedEffect(Unit) {
        if (WifiSetup.hasScanPermission(context)) {
            hasPermission = true
            refreshScan()
        } else {
            permissionLauncher.launch(WifiSetup.requiredPermissions())
        }
    }

    // 每 2 秒轮询联网状态（连接成功/断开都会实时反映到状态条与「下一步」按钮）
    LaunchedEffect(Unit) {
        while (true) {
            online = WifiSetup.isOnline(context)
            networkDesc = WifiSetup.currentNetworkDescription(context)
            wifiEnabled = WifiSetup.isWifiEnabled(context)
            delay(2000)
        }
    }

    // 从系统面板返回时刷新状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                online = WifiSetup.isOnline(context)
                networkDesc = WifiSetup.currentNetworkDescription(context)
                wifiEnabled = WifiSetup.isWifiEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CncColors.Background)
            .padding(28.dp)
    ) {
        SetupStepIndicator(currentStep = 0)
        Spacer(Modifier.height(12.dp))
        Text("网络配置", style = CncTypography.headlineMedium, color = CncColors.TextPrimary)
        Spacer(Modifier.height(8.dp))
        Text(
            "激活设备需要联网，请选择 WiFi 或移动数据完成网络连接。",
            style = CncTypography.bodyMedium,
            color = CncColors.TextSecondary
        )
        Spacer(Modifier.height(14.dp))

        // 实时网络状态条：绿色=已连接可用，红色=未连接
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (online) CncColors.Success.copy(alpha = 0.15f) else CncColors.Danger.copy(alpha = 0.15f),
                    RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (online) "网络已连接，可以激活（$networkDesc）" else "网络尚未连接（$networkDesc）",
                style = CncTypography.bodyMedium,
                color = if (online) CncColors.Success else CncColors.Danger,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(14.dp))

        // 选项卡：连接 WiFi / 使用移动数据
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CncColors.Surface, RoundedCornerShape(10.dp))
                .padding(4.dp)
        ) {
            listOf("连接 WiFi", "使用移动数据").forEachIndexed { index, label ->
                val selected = tab == index
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (selected) CncColors.PrimaryVariant.copy(alpha = 0.3f) else CncColors.Surface,
                            RoundedCornerShape(8.dp)
                        )
                        .border(
                            1.dp,
                            if (selected) CncColors.Primary else CncColors.Border,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { tab = index }
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        label,
                        style = CncTypography.titleMedium,
                        color = if (selected) CncColors.Primary else CncColors.TextSecondary
                    )
                }
                if (index == 0) Spacer(Modifier.width(6.dp))
            }
        }
        Spacer(Modifier.height(14.dp))

        if (tab == 1) {
            // 移动数据：应用无法直接开关，引导到系统设置
            Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Text(
                    "请确保移动数据已开启。开启后本页会自动检测到网络并出现「下一步」。",
                    style = CncTypography.bodyMedium,
                    color = CncColors.TextSecondary
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_DATA_USAGE_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }.onFailure {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_WIRELESS_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("打开移动数据设置") }
            }
        } else if (!wifiEnabled) {
            Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Button(
                    onClick = {
                        runCatching {
                            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                Intent(Settings.Panel.ACTION_WIFI)
                            } else {
                                Intent(Settings.ACTION_WIFI_SETTINGS)
                            }
                            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CncColors.Primary)
                ) { Text("开启 WiFi", style = CncTypography.titleMedium) }
            }
        } else if (!hasPermission) {
            Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                OutlinedButton(
                    onClick = { permissionLauncher.launch(WifiSetup.requiredPermissions()) },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("授权扫描附近 WiFi") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "附近 WiFi",
                    style = CncTypography.titleMedium,
                    color = CncColors.TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (connectingSsid != null) {
                    Text("正在连接 $connectingSsid …", style = CncTypography.labelSmall, color = CncColors.Primary)
                    Spacer(Modifier.width(8.dp))
                }
                OutlinedButton(onClick = { refreshScan() }, enabled = !scanning) {
                    Text(if (scanning) "扫描中…" else "刷新")
                }
            }
            if (scanError != null) {
                Spacer(Modifier.height(6.dp))
                Text(scanError!!, style = CncTypography.labelSmall, color = CncColors.Danger)
            }
            if (connectError != null) {
                Spacer(Modifier.height(6.dp))
                Text(connectError!!, style = CncTypography.labelSmall, color = CncColors.Danger)
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(networks, key = { it.ssid }) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CncColors.Surface, RoundedCornerShape(8.dp))
                            .clickable(enabled = connectingSsid == null) {
                                connectError = null
                                if (item.secured) {
                                    pendingConnect = item
                                } else {
                                    connectingSsid = item.ssid
                                    WifiSetup.connect(context, item, "", object : WifiSetup.ConnectCallback {
                                        override fun onConnected() { connectingSsid = null }
                                        override fun onFailed(reason: String) {
                                            connectingSsid = null
                                            connectError = "连接 ${item.ssid} 失败：$reason"
                                        }
                                    })
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(item.ssid, style = CncTypography.bodyLarge, color = CncColors.TextPrimary, modifier = Modifier.weight(1f))
                        Text(
                            (if (item.secured) "需密码 · " else "开放 · ") + "信号 ${item.level}%",
                            style = CncTypography.labelSmall,
                            color = CncColors.TextSecondary
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        // 网络配好才允许进入下一步：连接成功后自动出现
        if (online) {
            Button(
                onClick = onNext,
                modifier = Modifier.fillMaxWidth().height(60.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CncColors.Success)
            ) { Text("下一步", style = CncTypography.titleMedium) }
        } else {
            Text(
                "连接网络后将自动出现「下一步」按钮",
                style = CncTypography.labelSmall,
                color = CncColors.TextSecondary
            )
        }
    }

    // 密码输入对话框
    pendingConnect?.let { item ->
        var password by remember(item.ssid) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pendingConnect = null },
            containerColor = CncColors.Surface,
            title = { Text("连接 ${item.ssid}", color = CncColors.TextPrimary) },
            text = {
                KioskTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("WiFi 密码") },
                    singleLine = true,
                    isPassword = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingConnect = null
                        connectingSsid = item.ssid
                        WifiSetup.connect(context, item, password, object : WifiSetup.ConnectCallback {
                            override fun onConnected() { connectingSsid = null }
                            override fun onFailed(reason: String) {
                                connectingSsid = null
                                connectError = "连接 ${item.ssid} 失败：$reason"
                            }
                        })
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CncColors.Primary)
                ) { Text("连接") }
            },
            dismissButton = {
                TextButton(onClick = { pendingConnect = null }) { Text("取消", color = CncColors.TextSecondary) }
            }
        )
    }
}
