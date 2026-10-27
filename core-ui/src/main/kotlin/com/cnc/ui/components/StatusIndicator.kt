package com.cnc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography

/** 状态点颜色。 */
enum class StatusDotColor(val color: Color) {
    GREEN(CncColors.Success),
    YELLOW(CncColors.Warning),
    RED(CncColors.Danger),
    GRAY(CncColors.TextDisabled),
    BLUE(CncColors.Primary),
    ORANGE(CncColors.Simulator)
}

/**
 * 状态指示点 + 文字。
 *
 * 不能仅靠颜色区分状态，必须配合文字说明。
 */
@Composable
fun StatusIndicator(
    color: StatusDotColor,
    label: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Spacer(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color.color)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = CncTypography.labelLarge,
            color = color.color
        )
    }
}
