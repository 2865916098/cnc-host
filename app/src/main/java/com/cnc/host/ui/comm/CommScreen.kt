package com.cnc.host.ui.comm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography

/** 通讯配置页面。 */
@Composable
fun CommScreen(modifier: Modifier = Modifier, vm: CommViewModel = viewModel()) {
    val c by vm.config.collectAsState()
    val result by vm.testResult.collectAsState()

    Column(modifier.fillMaxSize().background(CncColors.Background).padding(16.dp)) {
        Text("通讯配置", style = CncTypography.headlineMedium, color = CncColors.TextPrimary)
        Spacer(Modifier.height(12.dp))

        Column(
            Modifier.fillMaxWidth()
                .background(CncColors.Surface, RoundedCornerShape(8.dp))
                .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp)).padding(16.dp)
        ) {
            Text("协议选择", style = CncTypography.titleMedium, fontWeight = FontWeight.Bold, color = CncColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CommProtocol.values().forEach { p ->
                    FilterChip(selected = c.protocol == p, onClick = { vm.update { it.copy(protocol = p) } }, label = { Text(p.name.replace('_', ' ')) })
                }
            }
            Spacer(Modifier.height(12.dp))

            if (c.protocol == CommProtocol.MODBUS_TCP || c.protocol == CommProtocol.SERIAL_TCP) {
                TcpFields(c, vm)
            } else {
                SerialFields(c, vm)
            }

            Spacer(Modifier.height(16.dp))
            Button(onClick = { vm.testConnection() }) { Text("测试连接") }
            result?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = CncTypography.bodySmall, color = Color(0xFFFFA500))
            }
        }
    }
}

@Composable
private fun TcpFields(c: CommConfig, vm: CommViewModel) {
    var ip by remember(c.ipAddress) { mutableStateOf(c.ipAddress) }
    var port by remember(c.port) { mutableStateOf(c.port.toString()) }
    var timeout by remember(c.timeoutMs) { mutableStateOf(c.timeoutMs.toString()) }

    Text("IP 地址", style = CncTypography.bodySmall, color = CncColors.TextPrimary)
    OutlinedTextField(ip, onValueChange = { ip = it; vm.update { cfg -> cfg.copy(ipAddress = it) } }, Modifier.fillMaxWidth(), singleLine = true)
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Text("端口", style = CncTypography.bodySmall, color = CncColors.TextPrimary)
            OutlinedTextField(port, onValueChange = { port = it.toIntOrNull()?.toString() ?: port; it.toIntOrNull()?.let { v -> vm.update { cfg -> cfg.copy(port = v) } } },
                Modifier.fillMaxWidth(), singleLine = true)
        }
        Column(Modifier.fillMaxWidth()) {
            Text("超时 (ms)", style = CncTypography.bodySmall, color = CncColors.TextPrimary)
            OutlinedTextField(timeout, onValueChange = { timeout = it; it.toLongOrNull()?.let { v -> vm.update { cfg -> cfg.copy(timeoutMs = v) } } },
                Modifier.fillMaxWidth(), singleLine = true)
        }
    }
}

@Composable
private fun SerialFields(c: CommConfig, vm: CommViewModel) {
    var port by remember(c.serialPort) { mutableStateOf(c.serialPort) }
    var baud by remember(c.baudRate) { mutableStateOf(c.baudRate.name) }

    Text("串口设备", style = CncTypography.bodySmall, color = CncColors.TextPrimary)
    OutlinedTextField(port, onValueChange = { port = it; vm.update { cfg -> cfg.copy(serialPort = it) } }, Modifier.fillMaxWidth(), singleLine = true)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        BaudRate.values().forEach { b ->
            FilterChip(selected = c.baudRate == b,
                onClick = { vm.update { cfg -> cfg.copy(baudRate = b) } },
                label = { Text(b.name.removePrefix("B")) })
        }
    }
}
