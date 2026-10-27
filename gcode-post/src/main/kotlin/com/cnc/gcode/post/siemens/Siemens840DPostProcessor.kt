package com.cnc.gcode.post.siemens

import com.cnc.gcode.post.GcodeProgram
import com.cnc.gcode.post.MachineFamily
import com.cnc.gcode.post.PostOptions
import com.cnc.gcode.post.PostProcessor
import com.cnc.model.cam.MotionType
import com.cnc.model.cam.Toolpath

/**
 * Siemens SINUMERIK 840D 后处理器。
 *
 * 语法差异 vs Fanuc：
 * - 程序开始标记用 `%` 或 `_N_O1001_MPF`
 * - 刀具调用：`T="1" M6` 换刀，Fanuc 是 `T0101`
 * - 进给率：`F=0.2` 参数形式
 * - 圆弧方向用 `G2/G3`，圆心参数 `I= J= K=` （Siemens 与 Fanuc 同）
 * - 支持通道注释 `;`
 */
class Siemens840DPostProcessor : PostProcessor {

    override val machineFamily: MachineFamily = MachineFamily.SIEMENS_840D

    override fun generate(toolpath: Toolpath, options: PostOptions): GcodeProgram {
        val out = mutableListOf<String>()

        out += "%_N_O${options.programNumber}_MPF"
        if (options.emitComments) {
            out += "; CNC_HOST generated"
            out += "; Toolpath: ${toolpath.id}"
        }

        out += "G17 G${if (options.useMetric) "710" else "700"} ${if (options.absoluteMode) "G90" else "G91"} ${if (options.diameterMode) "G23" else "G22"}"
        out += "G${options.workOffset}"
        if (options.homeOnStart) out += "G74"

        val firstTool = toolpath.segments.firstOrNull()?.toolNumber ?: 1
        out += "T=\"$firstTool\" M6"

        val firstRpm = toolpath.segments.firstOrNull()?.spindleRpm?.takeIf { it > 0 } ?: 1200
        val m34 = if (options.spindleClockwise) "M3" else "M4"
        out += "$m34 S$firstRpm"

        if (options.autoCoolantOn) out += "M8"

        var lastMotion: String? = null
        var lineNum = options.lineNumberStep

        for (seg in toolpath.segments) {
            val g = when (seg.motionType) {
                MotionType.RAPID -> "G0"
                MotionType.LINEAR_FEED -> "G1"
                MotionType.ARC_FEED -> if (seg.clockwise == true) "G2" else "G3"
            }

            val tok = mutableListOf<String>()
            if (g != lastMotion) tok += g
            tok += "X=${fmt(seg.end.x, options)}"
            tok += "Z=${fmt(seg.end.y, options)}"

            if (seg.motionType == MotionType.ARC_FEED) {
                val cx = seg.center?.x ?: continue
                val cz = seg.center?.y ?: continue
                tok += "I=${fmt(cx - seg.start.x, options)}"
                tok += "K=${fmt(cz - seg.start.y, options)}"
            }

            if (seg.motionType != MotionType.RAPID) {
                val feed = if (options.feedPerMinute) seg.feedMmPerMin
                           else seg.feedMmPerMin / maxOf(seg.spindleRpm, 1)
                if (feed > 0) tok += "F=${fmt(feed, options)}"
            }

            if (seg.spindleRpm > 0) tok += "S${seg.spindleRpm}"

            val line = if (options.lineNumberStep > 0) "N$lineNum " + tok.joinToString(" ")
                       else tok.joinToString(" ")
            out += line
            lastMotion = g
            lineNum += options.lineNumberStep
        }

        if (options.autoCoolantOn) out += "M9"
        out += "M5"
        out += "M30"
        out += "%"

        return GcodeProgram(
            programNumber = options.programNumber,
            machineFamily = machineFamily,
            lines = out,
            sourceToolpathId = toolpath.id
        )
    }

    private fun fmt(v: Double, o: PostOptions) = String.format("%.${o.decimalPlaces}f", v)
}
