package com.cnc.host.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cnc.host.navigation.Screen
import com.cnc.host.navigation.mainNavItems
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography

/**
 * 左侧导航栏（约占宽度 15%）。
 */
@Composable
fun AppNavigationRail(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    items: List<Screen> = mainNavItems
) {
    Column(
        modifier = modifier
            .width(180.dp)
            .fillMaxHeight()
            .background(CncColors.Surface)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items.forEach { screen ->
            NavItem(
                screen = screen,
                selected = screen.route == currentRoute,
                onClick = { onNavigate(screen.route) }
            )
        }
    }
}

@Composable
private fun NavItem(
    screen: Screen,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bg = if (selected) CncColors.PrimaryVariant else Color.Transparent
    val fg = if (selected) CncColors.TextPrimary else CncColors.TextSecondary
    Text(
        text = screen.label,
        style = CncTypography.labelLarge,
        color = fg,
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    )
}
