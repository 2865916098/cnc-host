package com.cnc.host.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cnc.host.setup.CHINA_PROVINCES
import com.cnc.host.setup.WifiSetup
import com.cnc.host.ui.components.KioskTextField
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography
import kotlinx.coroutines.delay

/**
 * 引导第 2 步：设备激活。
 *
 * 首次使用时把本机登记到后台：所在省份（身份）、城市（地区）、单位名称、联系人、
 * 电话、服务器地址。激活必须联网；联网信息与表单校验都在本页完成。
 * 激活成功后先显示绿色对勾成功页，再自动进入权限配置页。
 * 后台据此维护客户资料，并按单位统计终端数量。
 */
@Composable
fun ActivationScreen(
    onActivated: () -> Unit,
    onGoToNetwork: () -> Unit,
    viewModel: RegisterViewModel = viewModel()
) {
    val context = LocalContext.current
    val ui by viewModel.ui.collectAsState()

    var online by remember { mutableStateOf(WifiSetup.isOnline(context)) }
    var networkDesc by remember { mutableStateOf(WifiSetup.currentNetworkDescription(context)) }
    var showProvinceDialog by remember { mutableStateOf(false) }
    var showCityDialog by remember { mutableStateOf(false) }

    // 每 2 秒刷新一次联网状态；页面销毁时协程自动取消
    LaunchedEffect(Unit) {
        while (true) {
            online = WifiSetup.isOnline(context)
            networkDesc = WifiSetup.currentNetworkDescription(context)
            // 网络恢复后清除「请先联网」提示，避免与绿色状态条冲突
            if (online && ui.networkRequired) viewModel.onNetworkRestored()
            delay(2000)
        }
    }

    // 激活成功：先展示成功页 1.5 秒，再自动进入下一步
    LaunchedEffect(ui.done) {
        if (ui.done) {
            delay(1500)
            onActivated()
        }
    }

    // 激活成功页：绿色对勾弹性放大出现 + 提示
    if (ui.done) {
        var appeared by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { appeared = true }
        val checkScale by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (appeared) 1f else 0.3f,
            animationSpec = androidx.compose.animation.core.spring(
                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
            ),
            label = "checkScale"
        )
        Box(
            modifier = Modifier.fillMaxSize().background(CncColors.Background),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .scale(checkScale)
                        .background(CncColors.Success.copy(alpha = 0.15f), CircleShape)
                        .border(3.dp, CncColors.Success, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✓", style = CncTypography.displayLarge, color = CncColors.Success)
                }
                Spacer(Modifier.height(20.dp))
                Text("激活成功", style = CncTypography.headlineMedium, color = CncColors.Success)
                Spacer(Modifier.height(8.dp))
                Text("即将进入权限配置…", style = CncTypography.bodyMedium, color = CncColors.TextSecondary)
            }
        }
        return
    }

    // 已选省份对应的城市列表；未选省份时为空
    val cities = CHINA_PROVINCES.firstOrNull { it.name == ui.province }?.cities.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CncColors.Background)
            .padding(28.dp)
            .verticalScroll(rememberScrollState())
    ) {
        SetupStepIndicator(currentStep = 1)
        Spacer(Modifier.height(12.dp))
        Text("设备激活", style = CncTypography.headlineMedium, color = CncColors.TextPrimary)
        Spacer(Modifier.height(8.dp))
        Text(
            "首次使用需激活，激活信息用于售后维护与设备归属管理",
            style = CncTypography.bodyMedium,
            color = CncColors.TextSecondary
        )
        Spacer(Modifier.height(14.dp))

        // 实时网络状态条：绿色=已联网，红色=网络不可用
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
                text = if (online) "网络已连接（$networkDesc）" else "网络尚未连接，网络不可用，请先连接 WiFi 或移动数据",
                style = CncTypography.bodyMedium,
                color = if (online) CncColors.Success else CncColors.Danger
            )
        }
        Spacer(Modifier.height(16.dp))

        // 所在省份（身份）→ 城市（地区）
        SelectorField(
            label = "所在省份",
            value = ui.province,
            placeholder = "请选择省份",
            enabled = !ui.loading,
            error = ui.fieldErrors.province,
            onClick = { showProvinceDialog = true }
        )
        Spacer(Modifier.height(10.dp))
        SelectorField(
            label = "城市（地区）",
            value = ui.city,
            placeholder = if (ui.province.isBlank()) "请先选择省份" else "请选择城市",
            enabled = ui.province.isNotBlank() && !ui.loading,
            error = ui.fieldErrors.city,
            onClick = { showCityDialog = true }
        )
        Spacer(Modifier.height(10.dp))

        KioskTextField(
            value = ui.unitName,
            onValueChange = viewModel::setUnitName,
            label = { Text("单位名称") },
            singleLine = true,
            enabled = !ui.loading,
            modifier = Modifier.fillMaxWidth()
        )
        FieldError(ui.fieldErrors.unitName)
        Spacer(Modifier.height(10.dp))

        KioskTextField(
            value = ui.contactName,
            onValueChange = viewModel::setContactName,
            label = { Text("联系人") },
            singleLine = true,
            enabled = !ui.loading,
            modifier = Modifier.fillMaxWidth()
        )
        FieldError(ui.fieldErrors.contactName)
        Spacer(Modifier.height(10.dp))

        KioskTextField(
            value = ui.phone,
            onValueChange = viewModel::setPhone,
            label = { Text("电话") },
            keyboardType = KeyboardType.Phone,
            singleLine = true,
            enabled = !ui.loading,
            modifier = Modifier.fillMaxWidth()
        )
        FieldError(ui.fieldErrors.phone)
        Spacer(Modifier.height(10.dp))

        KioskTextField(
            value = ui.serverAddress,
            onValueChange = viewModel::setServerAddress,
            label = { Text("服务器地址（只填 IP）") },
            placeholder = { Text("例如 192.168.1.100") },
            singleLine = true,
            enabled = !ui.loading,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "只需输入服务器 IP，http:// 与端口 8080 会自动补全；一般沿用默认值即可。",
            style = CncTypography.labelSmall,
            color = CncColors.TextSecondary
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = viewModel::discoverServer,
                enabled = !ui.discovering
            ) { Text(if (ui.discovering) "正在自动搜索服务器…" else "自动搜索服务器") }
            if (ui.discovering) {
                Spacer(Modifier.width(8.dp))
                Text("正在局域网内查找 CNC 服务端", style = CncTypography.labelSmall, color = CncColors.TextSecondary)
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                // 点击时再校验一次联网状态：未联网不发起请求，只提示并提供配置入口
                if (!WifiSetup.isOnline(context)) {
                    viewModel.onNetworkUnavailable()
                } else {
                    viewModel.activate()
                }
            },
            enabled = !ui.loading,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CncColors.Success)
        ) {
            if (ui.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = CncColors.TextPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(10.dp))
                Text("正在激活…", style = CncTypography.titleMedium)
            } else {
                Text("激活设备", style = CncTypography.titleMedium)
            }
        }

        if (ui.networkRequired) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onGoToNetwork,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("去配置网络") }
        }

        ui.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = CncTypography.bodyMedium, color = CncColors.Danger)
        }
    }

    // 省份选择弹窗：列表可滚动，点击后回填
    if (showProvinceDialog) {
        ListSelectionDialog(
            title = "选择省份",
            options = CHINA_PROVINCES.map { it.name },
            selected = ui.province,
            onSelect = {
                viewModel.setProvince(it)
                showProvinceDialog = false
            },
            onDismiss = { showProvinceDialog = false }
        )
    }

    // 城市选择弹窗：仅在已选省份后打开
    if (showCityDialog) {
        ListSelectionDialog(
            title = "选择城市（地区）",
            options = cities,
            selected = ui.city,
            onSelect = {
                viewModel.setCity(it)
                showCityDialog = false
            },
            onDismiss = { showCityDialog = false }
        )
    }
}

/**
 * 点击式选择字段（Kiosk 风格，不使用系统下拉框）。
 *
 * 未启用时显示灰色底与「请先选择省份」等占位提示；校验失败时边框变红。
 */
@Composable
private fun SelectorField(
    label: String,
    value: String,
    placeholder: String,
    enabled: Boolean,
    error: String?,
    onClick: () -> Unit
) {
    Column {
        Text(label, style = CncTypography.bodyMedium, color = CncColors.TextSecondary)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .border(
                    1.dp,
                    if (error != null) CncColors.Danger else CncColors.Border,
                    RoundedCornerShape(8.dp)
                )
                .background(
                    if (enabled) CncColors.Surface else CncColors.SurfaceVariant,
                    RoundedCornerShape(8.dp)
                )
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = value.ifBlank { placeholder },
                style = CncTypography.bodyLarge,
                color = if (value.isBlank()) CncColors.TextSecondary else CncColors.TextPrimary,
                modifier = Modifier.weight(1f)
            )
            Text(
                "▾",
                style = CncTypography.bodyLarge,
                color = if (enabled) CncColors.Primary else CncColors.TextDisabled
            )
        }
        FieldError(error)
    }
}

/** 字段下方的红色校验提示。 */
@Composable
private fun FieldError(message: String?) {
    if (message != null) {
        Spacer(Modifier.height(4.dp))
        Text(message, style = CncTypography.labelSmall, color = CncColors.Danger)
    }
}

/** Kiosk 风格的列表选择弹窗：标题 + 可滚动选项 + 取消。 */
@Composable
private fun ListSelectionDialog(
    title: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .fillMaxHeight(0.75f)
                .background(CncColors.Surface, RoundedCornerShape(12.dp))
                .border(1.dp, CncColors.Border, RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = CncTypography.titleLarge,
                    color = CncColors.TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDismiss) {
                    Text("取消", color = CncColors.TextSecondary)
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(options, key = { it }) { option ->
                    val isSelected = option == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isSelected) {
                                    CncColors.PrimaryVariant.copy(alpha = 0.25f)
                                } else {
                                    CncColors.Background
                                },
                                RoundedCornerShape(8.dp)
                            )
                            .border(
                                1.dp,
                                if (isSelected) CncColors.Primary else CncColors.Border,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { onSelect(option) }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Text(
                            option,
                            style = CncTypography.bodyLarge,
                            color = if (isSelected) CncColors.Primary else CncColors.TextPrimary
                        )
                    }
                }
            }
        }
    }
}
