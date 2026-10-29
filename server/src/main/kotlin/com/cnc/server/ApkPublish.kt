package com.cnc.server

import java.io.File
import java.util.zip.ZipFile

/**
 * 二进制 AndroidManifest.xml（AXML）版本信息读取。
 *
 * 只解析「发布 APK」必需的两个属性：manifest 元素上的
 * android:versionCode（资源 ID 0x0101021b，整型）与
 * android:versionName（0x0101021c，字符串）。
 *
 * 格式要点（Android 源码 ResXMLTree）：
 * - 文件头：RES_XML_TYPE(0x0003)，随后是一系列 chunk
 * - STRING_POOL(0x0001)：字符串常量池（UTF-8/UTF-16，长度前缀）
 * - START_ELEMENT(0x0102)：元素开始标签，属性为 20 字节定长结构
 *
 * 仅用 JDK ZipFile，无第三方依赖。解析失败返回 null（调用方跳过该 APK）。
 */
object AxmlVersionReader {

    data class VersionInfo(val versionCode: Int, val versionName: String)

    /** 从 APK 文件读取版本信息；非 APK 或解析失败返回 null。 */
    fun read(apk: File): VersionInfo? = runCatching {
        val bytes = ZipFile(apk).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml") ?: return null
            zip.getInputStream(entry).use { it.readBytes() }
        }
        parse(bytes)
    }.getOrNull()

    /** 解析二进制 AndroidManifest.xml 字节流。 */
    fun parse(bytes: ByteArray): VersionInfo? {
        val r = Reader(bytes)
        if (bytes.size < 8) return null
        if (r.u16(0) != RES_XML_TYPE) return null
        val fileSize = r.u32(4)
        var pos = r.u16(2) // 文件头 headerSize，通常 8
        val end = minOf(fileSize, bytes.size)

        var strings: List<String> = emptyList()

        while (pos + 8 <= end) {
            val type = r.u16(pos)
            val headerSize = r.u16(pos + 2)
            val size = r.u32(pos + 4)
            if (size < 8 || pos + size > bytes.size) return null
            when (type) {
                RES_STRING_POOL_TYPE -> strings = parseStringPool(bytes, pos)
                RES_XML_START_ELEMENT_TYPE -> {
                    val nameIdx = r.u32(pos + headerSize + 4) // ns(4) 之后是 name
                    val name = strings.getOrNull(nameIdx)
                    if (name == "manifest") {
                        return parseManifestAttributes(bytes, pos, headerSize, strings, r)
                    }
                }
            }
            pos += size
        }
        return null
    }

    private fun parseManifestAttributes(
        bytes: ByteArray, chunkPos: Int, headerSize: Int, strings: List<String>, r: Reader
    ): VersionInfo? {
        // 元素扩展区：ns(4) name(4) attrStart(2) attrSize(2) attrCount(2) id/class/style(6)
        val ext = chunkPos + headerSize
        val attrOffset = r.u16(ext + 8)   // 相对扩展区起点
        val attrSize = r.u16(ext + 10)    // 单个属性字节数（通常 20）
        val attrCount = r.u16(ext + 12)
        val attrBase = ext + attrOffset
        var versionCode = 0
        var versionName = ""
        for (i in 0 until attrCount) {
            val a = attrBase + i * attrSize
            if (a + ATTR_SIZE > bytes.size) break
            val attrNameIdx = r.u32(a + 4)
            val attrName = strings.getOrNull(attrNameIdx) ?: continue
            if (attrName != "versionCode" && attrName != "versionName") continue
            // typedValue(Res_value)：size(2) res0(1) dataType(1) data(4)，位于属性结构偏移 12 处
            val dataType = bytes[a + 15].toInt() and 0xFF
            val data = r.u32(a + 16)
            when (attrName) {
                "versionCode" -> if (dataType in TYPE_FIRST_INT..TYPE_LAST_INT) versionCode = data
                "versionName" -> versionName = when (dataType) {
                    TYPE_STRING -> strings.getOrNull(data) ?: ""
                    in TYPE_FIRST_INT..TYPE_LAST_INT -> data.toString()
                    else -> ""
                }
            }
        }
        if (versionCode <= 0 && versionName.isBlank()) return null
        return VersionInfo(versionCode, versionName)
    }

    // ===== 字符串池 =====

    private fun parseStringPool(bytes: ByteArray, pos: Int): List<String> {
        val r = Reader(bytes)
        val headerSize = r.u16(pos + 2)
        val stringCount = r.u32(pos + 8)
        val flags = r.u32(pos + 16)
        val stringsStart = r.u32(pos + 20)
        val utf8 = (flags and UTF8_FLAG) != 0
        val offsetsBase = pos + headerSize
        val dataBase = pos + stringsStart
        return (0 until stringCount).map { i ->
            val off = r.u32(offsetsBase + i * 4)
            decodeString(bytes, dataBase + off, utf8)
        }
    }

    private fun decodeString(bytes: ByteArray, pos: Int, utf8: Boolean): String {
        return if (utf8) {
            val p = skipLen8(bytes, pos) // 跳过 UTF-16 长度前缀
            val (byteLen, np) = readLength8(bytes, p)
            String(bytes, np, byteLen, Charsets.UTF_8)
        } else {
            val (charLen, np) = readLength16(bytes, pos)
            String(bytes, np, charLen * 2, Charsets.UTF_16LE)
        }
    }

    private fun readLength8(bytes: ByteArray, p: Int): Pair<Int, Int> {
        val b = bytes[p].toInt() and 0xFF
        return if ((b and 0x80) != 0) {
            val b2 = bytes[p + 1].toInt() and 0xFF
            ((b and 0x7F) shl 8 or b2) to p + 2
        } else b to p + 1
    }

    private fun skipLen8(bytes: ByteArray, p: Int): Int = readLength8(bytes, p).second

    private fun readLength16(bytes: ByteArray, p: Int): Pair<Int, Int> {
        val r = Reader(bytes)
        val v = r.u16(p)
        return if ((v and 0x8000) != 0) {
            ((v and 0x7FFF) shl 16 or r.u16(p + 2)) to p + 4
        } else v to p + 2
    }

    /** 小端读取工具。 */
    private class Reader(private val b: ByteArray) {
        fun u16(off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
        fun u32(off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
    }

    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val UTF8_FLAG = 1 shl 8
    private const val ATTR_SIZE = 20
    private const val TYPE_STRING = 0x03
    private const val TYPE_FIRST_INT = 0x10
    private const val TYPE_LAST_INT = 0x1F
}

// ============================================================
// 构建产物扫描：找到「当前最新版本」的 APK 供一键发布
// ============================================================

data class ApkCandidate(
    val path: String,
    val fileName: String,
    val versionCode: Int,
    val versionName: String,
    val sizeBytes: Long,
    val modifiedAt: Long
)

/**
 * 扫描候选目录里的 APK 构建产物，解析出版本号后按 versionCode（再按修改时间）取最新。
 *
 * 目录来源：
 * - 环境变量 CNC_APK_DIR（Windows 下用 `;` 分隔多个目录）
 * - 缺省：服务端工作目录下的 ../app/build/outputs/apk/release、../app/build/outputs/apk/debug、apk-inbox/
 *
 * versionReader 可注入，便于单元测试不依赖真实 APK。
 */
class ApkScanner(
    private val versionReader: (File) -> AxmlVersionReader.VersionInfo? = { AxmlVersionReader.read(it) },
    private val dirs: List<File> = defaultDirs()
) {

    /** 所有可解析出版本号的候选 APK。 */
    fun scan(): List<ApkCandidate> =
        dirs.asSequence()
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
            .mapNotNull { f ->
                versionReader(f)?.let { v ->
                    ApkCandidate(
                        path = f.absolutePath,
                        fileName = f.name,
                        versionCode = v.versionCode,
                        versionName = v.versionName,
                        sizeBytes = f.length(),
                        modifiedAt = f.lastModified()
                    )
                }
            }
            .filter { it.versionCode > 0 }
            .toList()

    /** 最新候选：versionCode 最大；并列取修改时间最新。 */
    fun latest(): ApkCandidate? =
        scan().maxWithOrNull(compareBy<ApkCandidate> { it.versionCode }.thenBy { it.modifiedAt })

    companion object {
        fun defaultDirs(workDir: File = File(System.getProperty("user.dir"))): List<File> {
            val env = System.getenv("CNC_APK_DIR")
            if (!env.isNullOrBlank()) {
                return env.split(';').map { it.trim() }.filter { it.isNotEmpty() }.map { File(it) }
            }
            return listOf(
                File(workDir, "../app/build/outputs/apk/release"),
                File(workDir, "../app/build/outputs/apk/debug"),
                File(workDir, "apk-inbox")
            )
        }
    }
}
