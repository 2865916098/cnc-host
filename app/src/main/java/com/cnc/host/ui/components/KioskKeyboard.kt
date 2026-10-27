package com.cnc.host.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography

/**
 * Kiosk 内嵌键盘输入框。
 *
 * 客户端为工业平板触屏场景，不调用系统输入法：
 * 本组件渲染为只读输入框，点击后在应用内弹出大按键键盘面板完成输入。
 * 系统 IME 已被 MainActivity 全局禁用，不会与本键盘冲突。
 *
 * @param keyboardType  Number/NumberPassword/Decimal/Phone 进入数字页；
 *                      其余进入字母页（可随时点「123」切到数字符号页）
 * @param isPassword    为 true 时回显与面板均以 • 掩码
 */
@Composable
fun KioskTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    singleLine: Boolean = true,
    enabled: Boolean = true
) {
    var editing by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        if (label != null) {
            label()
            Spacer(Modifier.height(4.dp))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .border(
                    1.dp,
                    if (editing) CncColors.Primary else CncColors.Border,
                    RoundedCornerShape(8.dp)
                )
                .background(
                    if (enabled) CncColors.Surface else CncColors.SurfaceVariant,
                    RoundedCornerShape(8.dp)
                )
                .clickable(enabled = enabled) { editing = true }
                .padding(horizontal = 14.dp, vertical = 14.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (value.isEmpty() && placeholder != null) {
                placeholder()
            } else {
                Text(
                    text = if (isPassword) "•".repeat(value.length) else value,
                    style = CncTypography.bodyLarge,
                    color = CncColors.TextPrimary
                )
            }
        }
    }

    if (editing) {
        KioskKeyboardDialog(
            title = null,
            initialValue = value,
            keyboardType = keyboardType,
            isPassword = isPassword,
            onConfirm = {
                onValueChange(it)
                editing = false
            },
            onDismiss = { editing = false }
        )
    }
}

/** 内嵌键盘输入面板：数字页 + 字母页（QWERTY）+ 常用符号。 */
@Composable
fun KioskKeyboardDialog(
    title: String?,
    initialValue: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialValue) }
    var uppercase by remember { mutableStateOf(false) }
    var numericPage by remember {
        mutableStateOf(
            keyboardType == KeyboardType.Number || keyboardType == KeyboardType.NumberPassword ||
                keyboardType == KeyboardType.Decimal || keyboardType == KeyboardType.Phone
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .background(CncColors.Surface, RoundedCornerShape(12.dp))
                .border(1.dp, CncColors.Border, RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            if (title != null) {
                Text(title, style = CncTypography.titleMedium, color = CncColors.TextPrimary)
                Spacer(Modifier.height(8.dp))
            }
            // 当前输入回显
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .background(CncColors.Background, RoundedCornerShape(8.dp))
                    .border(1.dp, CncColors.Primary, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                Text(
                    text = if (isPassword) "•".repeat(text.length) else text,
                    style = CncTypography.titleMedium,
                    color = if (text.isEmpty()) CncColors.TextSecondary else CncColors.TextPrimary
                )
            }
            Spacer(Modifier.height(12.dp))

            if (numericPage) {
                NumericPad(
                    onKey = { text += it },
                    onBackspace = { text = text.dropLast(1) },
                    onClear = { text = "" },
                    onSwitchToAlpha = { numericPage = false },
                    onDone = { onConfirm(text) }
                )
            } else {
                AlphaPad(
                    uppercase = uppercase,
                    onToggleCase = { uppercase = !uppercase },
                    onKey = { text += it },
                    onBackspace = { text = text.dropLast(1) },
                    onClear = { text = "" },
                    onSwitchToNumeric = { numericPage = true },
                    onConfirm = { onConfirm(text) }
                )
            }
        }
    }
}

@Composable
private fun Key(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    danger: Boolean = false
) {
    val bg = when {
        danger -> CncColors.Danger.copy(alpha = 0.15f)
        else -> CncColors.Panel
    }
    val fg = when {
        danger -> CncColors.Danger
        accent -> CncColors.Primary
        else -> CncColors.TextPrimary
    }
    Box(
        modifier = modifier
            .height(56.dp)
            .background(bg, RoundedCornerShape(8.dp))
            .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = CncTypography.titleMedium, color = fg)
    }
}

/** 数字页：IP / 端口 / 参数 / 电话场景。 */
@Composable
private fun NumericPad(
    onKey: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onSwitchToAlpha: () -> Unit,
    onDone: () -> Unit
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(".", "0", "-")
    )
    rows.forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { k -> Key(k, { onKey(k) }, Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(8.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(":", "_", "@").forEach { k -> Key(k, { onKey(k) }, Modifier.weight(1f)) }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Key("ABC", onSwitchToAlpha, Modifier.weight(1f))
        Key("⌫", onBackspace, Modifier.weight(1f), danger = true)
        Key("清空", onClear, Modifier.weight(1f), danger = true)
        Key("完成", onDone, Modifier.weight(2f), accent = true)
    }
}

/** 字母页：QWERTY + 大小写 + 空格。 */
@Composable
private fun AlphaPad(
    uppercase: Boolean,
    onToggleCase: () -> Unit,
    onKey: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onSwitchToNumeric: () -> Unit,
    onConfirm: () -> Unit
) {
    val rows = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("z", "x", "c", "v", "b", "n", "m")
    )
    rows.forEachIndexed { index, row ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (index == 2) {
                Key(if (uppercase) "⇧大写" else "⇧小写", onToggleCase, Modifier.weight(1.4f), accent = uppercase)
            }
            row.forEach { k ->
                val label = if (uppercase) k.uppercase() else k
                Key(label, { onKey(label) }, Modifier.weight(1f))
            }
            if (index == 2) {
                Key("⌫", onBackspace, Modifier.weight(1.4f), danger = true)
            }
        }
        Spacer(Modifier.height(6.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Key("123", onSwitchToNumeric, Modifier.weight(1.2f))
        Key("空格", { onKey(" ") }, Modifier.weight(3f))
        Key("清空", onClear, Modifier.weight(1.2f), danger = true)
        Key("完成", onConfirm, Modifier.weight(1.6f), accent = true)
    }
}
