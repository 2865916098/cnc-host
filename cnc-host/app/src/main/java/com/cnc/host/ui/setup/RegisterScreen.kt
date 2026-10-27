package com.cnc.host.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography

/**
 * 第 2 步：设备注册。
 *
 * 注册成功后后台即可看到该设备归属的单位与联系人，
 * 便于售后维护与客户管理。
 */
@Composable
fun RegisterScreen(
    onRegistered: () -> Unit,
    viewModel: RegisterViewModel = viewModel()
) {
    val ui by viewModel.ui.collectAsState()

    LaunchedEffect(ui.done) {
        if (ui.done) onRegistered()
    }

    if (ui.done) return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CncColors.Background)
            .padding(28.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("第 2 步：设备注册", style = CncTypography.headlineMedium, color = CncColors.TextPrimary)
        Spacer(Modifier.height(8.dp))
        Text(
            "填写信息后注册到后台。默认获得全部功能权限，管理员可在后台按设备调整。",
            style = CncTypography.bodyMedium,
            color = CncColors.TextSecondary
        )
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = ui.unitName,
            onValueChange = viewModel::setUnitName,
            label = { Text("单位名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = ui.contactName,
            onValueChange = viewModel::setContactName,
            label = { Text("联系人姓名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = ui.phone,
            onValueChange = viewModel::setPhone,
            label = { Text("联系电话") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = ui.serverAddress,
            onValueChange = viewModel::setServerAddress,
            label = { Text("服务器地址") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "例如 http://192.168.1.10:8080",
            style = CncTypography.labelSmall,
            color = CncColors.TextSecondary
        )

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = viewModel::register,
            enabled = !ui.loading,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CncColors.Success)
        ) { Text(if (ui.loading) "注册中…" else "注册", style = CncTypography.titleMedium) }

        if (ui.error != null) {
            Spacer(Modifier.height(12.dp))
            Text(ui.error!!, style = CncTypography.bodyMedium, color = CncColors.Danger)
        }
    }
}
