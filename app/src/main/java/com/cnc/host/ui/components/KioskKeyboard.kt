package com.cnc.host.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cnc.ui.theme.CncColors
import com.cnc.ui.theme.CncTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Kiosk 内嵌键盘输入框。
 *
 * 客户端为工业平板触屏场景，不调用系统输入法：
 * 本组件渲染为只读输入框，点击后在应用内弹出大按键键盘面板完成输入。
 * 系统 IME 已被 MainActivity 全局禁用，不会与本键盘冲突。
 *
 * @param keyboardType  Number/NumberPassword/Decimal/Phone 进入数字页；
 *                      Ascii/Password/Uri/Email 进入英文页；
 *                      其余（含默认 Text）进入 26 键拼音页（可切 9 键拼音/英文/数字）
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

/** 键盘工作模式：26 键拼音 / 9 键拼音（T9）/ 英文 / 数字。 */
private enum class KbdMode { Pinyin26, PinyinT9, Alpha, Numeric }

/** 根据 KeyboardType 决定初始键盘页。 */
private fun initialMode(keyboardType: KeyboardType): KbdMode = when (keyboardType) {
    KeyboardType.Number, KeyboardType.NumberPassword,
    KeyboardType.Decimal, KeyboardType.Phone -> KbdMode.Numeric
    KeyboardType.Ascii, KeyboardType.Password,
    KeyboardType.Uri, KeyboardType.Email -> KbdMode.Alpha
    else -> KbdMode.Pinyin26
}

/**
 * 内嵌键盘输入面板。
 *
 * 拼音模式：字母/数字键先进入拼音串（composing），候选栏实时刷新；
 * 点击候选或按空格上屏首选；回显区中拼音串以主题色高亮。
 * 英文模式：直接上屏，支持大小写切换与符号页。
 * 数字模式：0-9 + 小数点 + 负号，带「返回」（放弃修改收起）与「完成」（提交收起）。
 */
@Composable
fun KioskKeyboardDialog(
    title: String?,
    initialValue: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(initialMode(keyboardType)) }
    var text by remember { mutableStateOf(initialValue) }
    var composing by remember { mutableStateOf("") }
    var uppercase by remember { mutableStateOf(false) }
    var symbolPage by remember { mutableStateOf(false) }
    var dictReady by remember { mutableStateOf(PinyinEngine.ready) }

    // 首次打开键盘时后台加载词库；加载完成后触发重组刷新候选
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { PinyinEngine.load(context.applicationContext) }
        dictReady = PinyinEngine.ready
    }

    val candidates = remember(composing, mode, dictReady) {
        if (composing.isEmpty()) emptyList()
        else when (mode) {
            KbdMode.Pinyin26 -> PinyinEngine.query(composing)
            KbdMode.PinyinT9 -> PinyinEngine.queryT9(composing)
            else -> emptyList()
        }
    }

    fun switchMode(next: KbdMode) {
        composing = ""
        mode = next
    }

    /** 上屏候选词并清空拼音串，可继续输入 */
    fun commitCandidate(candidate: String) {
        text += candidate
        composing = ""
    }

    /** 空格：拼音模式下提交首选候选，否则输入空格 */
    fun onSpace() {
        if ((mode == KbdMode.Pinyin26 || mode == KbdMode.PinyinT9) && composing.isNotEmpty()) {
            candidates.firstOrNull()?.let { commitCandidate(it) }
        } else {
            text += " "
        }
    }

    fun onBackspace() {
        if (composing.isNotEmpty()) composing = composing.dropLast(1)
        else text = text.dropLast(1)
    }

    fun onClear() {
        text = ""
        composing = ""
    }

    /** 完成：若有未上屏拼音串，优先提交首选候选，无候选则按原字母上屏 */
    fun onDone() {
        if (composing.isNotEmpty()) {
            val first = candidates.firstOrNull()
            text += first ?: composing
            composing = ""
        }
        onConfirm(text)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // 横屏平板上 9 键共 5 行按键，固定高度会超出屏幕；
        // 这里限定为屏幕高度的 94%，按键行用 weight 均分剩余空间，保证任何屏幕都不溢出
        val screenHeightDp = LocalConfiguration.current.screenHeightDp
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .height((screenHeightDp * 0.94f).dp)
                .background(CncColors.Surface, RoundedCornerShape(12.dp))
                .border(1.dp, CncColors.Border, RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            if (title != null) {
                Text(title, style = CncTypography.titleMedium, color = CncColors.TextPrimary)
                Spacer(Modifier.height(8.dp))
            }
            // 当前输入回显：已确认文本 + 高亮的拼音串
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .background(CncColors.Background, RoundedCornerShape(8.dp))
                    .border(1.dp, CncColors.Primary, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                if (isPassword) {
                    Text(
                        text = "•".repeat(text.length + composing.length),
                        style = CncTypography.titleMedium,
                        color = CncColors.TextPrimary
                    )
                } else {
                    Row {
                        Text(
                            text = text,
                            style = CncTypography.titleMedium,
                            color = if (text.isEmpty()) CncColors.TextSecondary else CncColors.TextPrimary
                        )
                        if (composing.isNotEmpty()) {
                            Text(
                                text = composing,
                                style = CncTypography.titleMedium,
                                color = CncColors.Primary
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // 候选栏：仅拼音模式且有拼音串时显示，横向滚动，最多 9 个
            if ((mode == KbdMode.Pinyin26 || mode == KbdMode.PinyinT9) && composing.isNotEmpty()) {
                CandidateBar(candidates = candidates, onPick = { commitCandidate(it) })
                Spacer(Modifier.height(8.dp))
            }

            when (mode) {
                KbdMode.Pinyin26 -> Pinyin26Pad(
                    onLetter = { composing += it },
                    onBackspace = ::onBackspace,
                    onSpace = ::onSpace,
                    onClear = ::onClear,
                    onDone = ::onDone,
                    onSwitch = ::switchMode
                )
                KbdMode.PinyinT9 -> T9Pad(
                    onDigit = { composing += it },
                    onSymbol = { text += it },
                    onBackspace = ::onBackspace,
                    onSpace = ::onSpace,
                    onClear = ::onClear,
                    onDone = ::onDone,
                    onSwitch = ::switchMode
                )
                KbdMode.Alpha -> AlphaPad(
                    uppercase = uppercase,
                    symbolPage = symbolPage,
                    onToggleCase = { uppercase = !uppercase },
                    onToggleSymbols = { symbolPage = !symbolPage },
                    onKey = { text += it },
                    onBackspace = ::onBackspace,
                    onSpace = ::onSpace,
                    onDone = ::onDone,
                    onSwitch = ::switchMode
                )
                KbdMode.Numeric -> NumericPad(
                    onKey = { text += it },
                    onBackspace = ::onBackspace,
                    onClear = ::onClear,
                    onSwitch = ::switchMode,
                    onBack = onDismiss,
                    onDone = ::onDone
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
            .fillMaxHeight()
            .heightIn(min = 44.dp)
            .background(bg, RoundedCornerShape(8.dp))
            .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = CncTypography.titleMedium, color = fg)
    }
}

/** 候选栏：横向滚动，最多展示 9 个候选，点击上屏。 */
@Composable
private fun CandidateBar(candidates: List<String>, onPick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        candidates.take(9).forEach { c ->
            Box(
                modifier = Modifier
                    .height(44.dp)
                    .background(CncColors.PrimaryVariant.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                    .border(1.dp, CncColors.Primary, RoundedCornerShape(8.dp))
                    .clickable { onPick(c) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(c, style = CncTypography.titleMedium, color = CncColors.TextPrimary)
            }
        }
    }
}

/** 26 键拼音：QWERTY 三行，字母进入拼音串；底部可切 9 键/英文/数字。 */
@Composable
private fun ColumnScope.Pinyin26Pad(
    onLetter: (String) -> Unit,
    onBackspace: () -> Unit,
    onSpace: () -> Unit,
    onClear: () -> Unit,
    onDone: () -> Unit,
    onSwitch: (KbdMode) -> Unit
) {
    val rows = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("z", "x", "c", "v", "b", "n", "m")
    )
    rows.forEachIndexed { index, row ->
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { k -> Key(k, { onLetter(k) }, Modifier.weight(1f)) }
            if (index == 2) {
                Key("⌫", onBackspace, Modifier.weight(1.4f), danger = true)
            }
        }
        Spacer(Modifier.height(6.dp))
    }
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Key("英", { onSwitch(KbdMode.Alpha) }, Modifier.weight(1f))
        Key("9键", { onSwitch(KbdMode.PinyinT9) }, Modifier.weight(1f))
        Key("123", { onSwitch(KbdMode.Numeric) }, Modifier.weight(1f))
        Key("空格", onSpace, Modifier.weight(2.4f))
        Key("清空", onClear, Modifier.weight(1.2f), danger = true)
        Key("完成", onDone, Modifier.weight(1.4f), accent = true)
    }
}

/** T9 键帽：大号数字 + 小号字母提示。 */
@Composable
private fun T9Key(
    digit: String,
    letters: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false
) {
    val bg = if (danger) CncColors.Danger.copy(alpha = 0.15f) else CncColors.Panel
    Box(
        modifier = modifier
            .fillMaxHeight()
            .heightIn(min = 44.dp)
            .background(bg, RoundedCornerShape(8.dp))
            .border(1.dp, CncColors.Border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                digit,
                style = CncTypography.titleMedium,
                color = if (danger) CncColors.Danger else CncColors.TextPrimary
            )
            if (letters.isNotEmpty()) {
                Text(letters, style = CncTypography.labelSmall, color = CncColors.TextSecondary)
            }
        }
    }
}

/** 9 键拼音（T9）：数字进入拼音串，1 输入常用标点，0 等同空格/提交首选。 */
@Composable
private fun ColumnScope.T9Pad(
    onDigit: (String) -> Unit,
    onSymbol: (String) -> Unit,
    onBackspace: () -> Unit,
    onSpace: () -> Unit,
    onClear: () -> Unit,
    onDone: () -> Unit,
    onSwitch: (KbdMode) -> Unit
) {
    // 标点连按轮换：1 键依次在 。，、？！；： 之间切换
    val punctuations = listOf("。", "，", "、", "？", "！", "；", "：")
    var punctIndex by remember { mutableStateOf(-1) }

    val rows = listOf(
        listOf(Triple("1", "符", null), Triple("2", "abc", "2"), Triple("3", "def", "3")),
        listOf(Triple("4", "ghi", "4"), Triple("5", "jkl", "5"), Triple("6", "mno", "6")),
        listOf(Triple("7", "pqrs", "7"), Triple("8", "tuv", "8"), Triple("9", "wxyz", "9"))
    )
    rows.forEach { row ->
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (digit, letters, value) ->
                T9Key(
                    digit = digit,
                    letters = letters,
                    onClick = {
                        if (value == null) {
                            // 标点轮换
                            punctIndex = (punctIndex + 1) % punctuations.size
                            onSymbol(punctuations[punctIndex])
                        } else {
                            punctIndex = -1
                            onDigit(value)
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        T9Key("英", "", { onSwitch(KbdMode.Alpha) }, Modifier.weight(1f))
        T9Key("0", "空格", onSpace, Modifier.weight(1f))
        T9Key("⌫", "", onBackspace, Modifier.weight(1f), danger = true)
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Key("拼音", { onSwitch(KbdMode.Pinyin26) }, Modifier.weight(1f))
        Key("123", { onSwitch(KbdMode.Numeric) }, Modifier.weight(1f))
        Key("清空", onClear, Modifier.weight(1f), danger = true)
        Key("完成", onDone, Modifier.weight(1.6f), accent = true)
    }
}

/** 英文页：QWERTY + 大小写切换 + 符号页。 */
@Composable
private fun ColumnScope.AlphaPad(
    uppercase: Boolean,
    symbolPage: Boolean,
    onToggleCase: () -> Unit,
    onToggleSymbols: () -> Unit,
    onKey: (String) -> Unit,
    onBackspace: () -> Unit,
    onSpace: () -> Unit,
    onDone: () -> Unit,
    onSwitch: (KbdMode) -> Unit
) {
    val letterRows = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("z", "x", "c", "v", "b", "n", "m")
    )
    val symbolRows = listOf(
        listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")"),
        listOf("-", "_", "=", "+", "[", "]", "{", "}", ";", ":"),
        listOf("'", "\"", ",", ".", "<", ">", "?", "/", "\\", "|")
    )
    val rows = if (symbolPage) symbolRows else letterRows
    rows.forEachIndexed { index, row ->
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (index == 2 && !symbolPage) {
                Key(
                    if (uppercase) "⇧大写" else "⇧小写",
                    onToggleCase,
                    Modifier.weight(1.4f),
                    accent = uppercase
                )
            }
            row.forEach { k ->
                val label = if (!symbolPage && uppercase) k.uppercase() else k
                Key(label, { onKey(label) }, Modifier.weight(1f))
            }
            if (index == 2) {
                Key("⌫", onBackspace, Modifier.weight(1.4f), danger = true)
            }
        }
        Spacer(Modifier.height(6.dp))
    }
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Key("拼音", { onSwitch(KbdMode.Pinyin26) }, Modifier.weight(1f))
        Key("9键", { onSwitch(KbdMode.PinyinT9) }, Modifier.weight(1f))
        Key("123", { onSwitch(KbdMode.Numeric) }, Modifier.weight(1f))
        Key("符", onToggleSymbols, Modifier.weight(1f), accent = symbolPage)
        Key("空格", onSpace, Modifier.weight(2f))
        Key("完成", onDone, Modifier.weight(1.4f), accent = true)
    }
}

/** 数字页：0-9 + 小数点 + 负号，带返回（放弃收起）与完成（提交收起）。 */
@Composable
private fun ColumnScope.NumericPad(
    onKey: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onSwitch: (KbdMode) -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(".", "0", "-")
    )
    rows.forEach { row ->
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { k -> Key(k, { onKey(k) }, Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(8.dp))
    }
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(":", "_", "@").forEach { k -> Key(k, { onKey(k) }, Modifier.weight(1f)) }
        Key("清空", onClear, Modifier.weight(1f), danger = true)
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Key("ABC", { onSwitch(KbdMode.Alpha) }, Modifier.weight(1f))
        Key("拼音", { onSwitch(KbdMode.Pinyin26) }, Modifier.weight(1f))
        Key("返回", onBack, Modifier.weight(1f))
        Key("⌫", onBackspace, Modifier.weight(1f), danger = true)
        Key("完成", onDone, Modifier.weight(1.6f), accent = true)
    }
}
