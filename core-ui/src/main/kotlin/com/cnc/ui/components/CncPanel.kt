package com.cnc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography

/**
 * 统一面板容器。深灰色背景、圆角边框。
 */
@Composable
fun CncPanel(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp))
            .background(CncColors.Panel)
            .padding(12.dp)
    ) {
        if (title != null) {
            Text(
                text = title,
                style = CncTypography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        content()
    }
}

