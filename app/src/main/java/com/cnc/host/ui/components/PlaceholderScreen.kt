package com.cnc.host.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography

/**
 * 占位页面。
 *
 * 用于尚未实现的导航项。必须明确标注为未实现，不得伪装为已完成功能。
 */
@Composable
fun PlaceholderScreen(title: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CncColors.Background)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = CncTypography.headlineMedium,
            color = CncColors.TextPrimary
        )
        Text(
            text = "该页面尚未实现（待开发）。",
            style = CncTypography.bodyMedium,
            color = CncColors.TextDisabled,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
