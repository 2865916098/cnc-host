package com.cnc.gcode.post

import com.cnc.model.cam.Toolpath

/**
 * 机床后处理器接口。
 *
 * 将抽象的 [Toolpath] 转换为具体机床方言的 G/M 代码。
 * 不同厂商（Fanuc / Siemens / Haas / Mazak / ...）的 G-code 语法差异较大：
 * - 绝对/增量编码（G90/G91）默认值不同
 * - 直径/半径编码（G07/G08）默认值不同
 * - 进给率单位（mm/min vs mm/rev）
 * - 特殊循环语法（G71 粗车循环各厂商写法不同）
 *
 * @see PostOptions 控制输出风格
 * @see com.cnc.gcode.post.fanuc.Fanuc0iPostProcessor
 */
interface PostProcessor {

    /** 机床方言标识。 */
    val machineFamily: MachineFamily

    /** 生成 G-code 程序。 */
    fun generate(toolpath: Toolpath, options: PostOptions = PostOptions()): GcodeProgram
}

/** 支持的机床家族。 */
enum class MachineFamily(val label: String) {
    FANUC_0I("FANUC 0i / 0-MA"),
    SIEMENS_840D("Siemens SINUMERIK 840D"),
    HAAS("Haas Automation"),
    MAZAK("Mazak SmoothX")
}

/** 后处理器输出选项。 */
data class PostOptions(
    /** 程序号（O 号）。Fanuc 等需整数程序号。 */
    val programNumber: Int = 1001,
    /** 程序名（注释）。 */
    val programName: String = "CNC_HOST_PART",
    /** 单位：G21 公制（mm）/ G20 英制（inch）。 */
    val useMetric: Boolean = true,
    /** 绝对坐标 G90（true）或增量 G91（false）。默认绝对。 */
    val absoluteMode: Boolean = true,
    /** 直径编码 G07（true，两轴车床默认）或半径编码 G08。 */
    val diameterMode: Boolean = true,
    /** 进给单位：true=mm/min G98，false=mm/rev G99。车床常用 G99。 */
    val feedPerMinute: Boolean = false,
    /** 行号步长（0 = 不输出行号）。Fanuc 常用 5/10。 */
    val lineNumberStep: Int = 5,
    /** 冷却液：true=自动开（M08），false=手动。 */
    val autoCoolantOn: Boolean = true,
    /** 主轴旋转方向。true=M03 正转，false=M04 反转。 */
    val spindleClockwise: Boolean = true,
    /** 输出注释行。 */
    val emitComments: Boolean = true,
    /** 精度（小数点后几位）。 */
    val decimalPlaces: Int = 3,
    /** 在程序开头输出 G54 工件坐标系设置。 */
    val workOffset: Int = 54,
    /** 在程序开头输出 G28 回原点指令。 */
    val homeOnStart: Boolean = false,
    /** 在程序结尾输出 M02（true）或 M30（false）。M30 带程序结束复位。 */
    val useM30: Boolean = true
)

/** G-code 程序输出。 */
data class GcodeProgram(
    /** 程序号（O 号）。 */
    val programNumber: Int,
    /** 机床家族。 */
    val machineFamily: MachineFamily,
    /** 输出的完整行列表（不含 \n）。 */
    val lines: List<String>,
    /** 原始 Toolpath id，便于追溯版本。 */
    val sourceToolpathId: String,
    /** 生成时间戳。 */
    val generatedAtMs: Long = System.currentTimeMillis()
) {
    /** 合并为 .nc 文本。 */
    fun toText(): String = lines.joinToString("\n") + "\n"

    /** 行数（不含空行和注释计数 = 可执行指令数）。 */
    val instructionCount: Int
        get() = lines.filter { line ->
            val trimmed = line.trim().uppercase()
            trimmed.isNotEmpty() &&
                !trimmed.startsWith("(") &&
                !trimmed.startsWith("%") &&
                !trimmed.startsWith("O")
        }.size
}
