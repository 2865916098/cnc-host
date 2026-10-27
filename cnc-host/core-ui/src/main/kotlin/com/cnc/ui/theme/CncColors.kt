package com.cnc.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 工业 HMI 色彩规范。
 *
 * 深石墨色背景、深灰色面板、高对比度文字。
 * 蓝色 = 一般操作，绿色 = 确认正常，黄色 = 警告，红色 = 报警/危险。
 * 不能仅靠颜色区分状态，还需配合文字和图标。
 */
object CncColors {
    // 背景
    val Background = Color(0xFF1A1D21)
    val Surface = Color(0xFF252830)
    val SurfaceVariant = Color(0xFF2D313A)
    val Panel = Color(0xFF1F2228)

    // 文字
    val TextPrimary = Color(0xFFE8EAED)
    val TextSecondary = Color(0xFF9AA0A6)
    val TextDisabled = Color(0xFF5F6368)

    // 功能色
    val Primary = Color(0xFF4A9EFF)
    val PrimaryVariant = Color(0xFF2B7DE9)
    val Success = Color(0xFF34D399)
    val Warning = Color(0xFFFBBF24)
    val Danger = Color(0xFFF87171)
    val Info = Color(0xFF60A5FA)

    // 边框
    val Border = Color(0xFF3C4049)
    val BorderStrong = Color(0xFF4A4F5A)

    // 模拟模式标识
    val Simulator = Color(0xFFF59E0B)
}
