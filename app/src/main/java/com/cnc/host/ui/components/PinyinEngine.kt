package com.cnc.host.ui.components

import android.content.Context

/**
 * 内嵌拼音引擎（无第三方依赖）。
 *
 * 词库位于 assets/pinyin_dict.txt，格式：`拼音:候选1,候选2,...`，
 * 键为全拼（音节间空格分隔；单字为单音节），候选按词频从高到低排列。
 *
 * 查询策略：
 * - 26 键全拼：精确匹配（哈希表 O(1)）+ 键前缀扫描（模糊匹配，如 "nih" → "ni hao"）
 * - 9 键 T9：把每个词条的全拼映射成数字串建索引，同样走精确 + 前缀匹配
 * 键列表按长度升序排序，短词（更接近完全匹配）优先返回；词库规模约 6 千词条，
 * 前缀全表扫描每次按键仅几千次字符串比较，耗时 < 5ms。
 *
 * 线程模型：[load] 幂等且可安全并发调用，建议在 IO 线程执行；
 * 加载完成后 [ready] 置 true，查询方法在主线程调用即可。
 */
object PinyinEngine {

    /** 单次返回的最大候选数 */
    private const val MAX_CANDIDATES = 60

    /** 全拼（不含空格）→ 候选列表，按词频降序 */
    private val exact = LinkedHashMap<String, List<String>>(8192)

    /** 全拼键列表（按长度升序），用于前缀扫描 */
    @Volatile
    private var keysAsc: List<String> = emptyList()

    /** T9 数字串 → 候选列表（同数字序列的不同拼音合并，去重保序） */
    private val t9Exact = HashMap<String, List<String>>(8192)

    /** T9 键列表（按长度升序），用于前缀扫描 */
    @Volatile
    private var t9KeysAsc: List<String> = emptyList()

    /** 词库是否加载完成 */
    @Volatile
    var ready: Boolean = false
        private set

    /** 词条总数（单字按字计数，词组按词计数） */
    @Volatile
    var entryCount: Int = 0
        private set

    private var loading = false

    /**
     * 从 assets 加载词库到内存。幂等；重复调用或并发调用只会真正加载一次。
     * 文件约 50KB，加载耗时一般在 50ms 以内。
     */
    @Synchronized
    fun load(context: Context) {
        if (ready || loading) return
        loading = true
        try {
            // 先收集到临时结构，全部成功后再一次性发布，保证查询线程看到的是完整数据
            val exactTmp = LinkedHashMap<String, MutableList<String>>(8192)
            // 同一数字串可能对应多个拼音音节（64 → mi / ni），按音节分组保存，
            // 发布时交错合并，避免某一组把候选栏占满
            val t9Tmp = LinkedHashMap<String, MutableList<MutableList<String>>>(8192)
            var count = 0
            context.assets.open("pinyin_dict.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { raw ->
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) return@forEach
                    val sep = line.indexOf(':')
                    if (sep <= 0) return@forEach
                    // 键统一为小写、去空格（"ni hao" → "nihao"），便于无前缀分隔输入
                    val key = line.substring(0, sep).replace(" ", "").lowercase()
                    val words = line.substring(sep + 1)
                        .split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    if (key.isEmpty() || words.isEmpty()) return@forEach
                    val merged = exactTmp.getOrPut(key) { mutableListOf() }
                    words.forEach { w -> if (w !in merged) merged.add(w) }
                    count += words.size
                    // 同步构建 T9 索引：按音节分组
                    val digits = toT9(key)
                    if (digits.isNotEmpty()) {
                        t9Tmp.getOrPut(digits) { mutableListOf() }.add(words.toMutableList())
                    }
                }
            }
            exact.clear()
            exactTmp.forEach { (k, v) -> exact[k] = v.toList() }
            keysAsc = exact.keys.sortedBy { it.length }
            t9Exact.clear()
            t9Tmp.forEach { (k, groups) ->
                // 交错合并：各音节组轮流取一个（米、你、密、尼……），
                // 与主流 9 键输入法一致，常用字更早露出
                val merged = mutableListOf<String>()
                val seen = HashSet<String>()
                val maxLen = groups.maxOf { it.size }
                for (idx in 0 until maxLen) {
                    for (g in groups) {
                        val w = g.getOrNull(idx) ?: continue
                        if (seen.add(w)) merged.add(w)
                    }
                }
                t9Exact[k] = merged
            }
            t9KeysAsc = t9Exact.keys.sortedBy { it.length }
            entryCount = count
            ready = true
        } finally {
            loading = false
        }
    }

    /**
     * 26 键全拼查询：输入连续字母（如 "nihao" 或部分 "nih"），返回按词频排序的候选。
     * 精确匹配优先，随后是按长度升序的前缀匹配（模糊补全）。未加载完成时返回空表。
     */
    fun query(input: String): List<String> {
        if (!ready) return emptyList()
        val s = input.lowercase().filter { it in 'a'..'z' }
        if (s.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        exact[s]?.let(out::addAll)
        for (k in keysAsc) {
            if (out.size >= MAX_CANDIDATES) break
            if (k.length > s.length && k.startsWith(s)) exact[k]?.let(out::addAll)
        }
        return out.take(MAX_CANDIDATES)
    }

    /**
     * 9 键 T9 查询：输入数字序列（如 "64"，6=mno、4=ghi），返回候选。
     * 数字串先映射回词条索引做精确匹配，再做前缀匹配补全。
     */
    fun queryT9(digits: String): List<String> {
        if (!ready) return emptyList()
        val s = digits.filter { it in '2'..'9' }
        if (s.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        t9Exact[s]?.let(out::addAll)
        for (k in t9KeysAsc) {
            if (out.size >= MAX_CANDIDATES) break
            if (k.length > s.length && k.startsWith(s)) t9Exact[k]?.let(out::addAll)
        }
        return out.take(MAX_CANDIDATES)
    }

    /** 字母 → T9 数字（ü 以 v 表示，v 归入 8） */
    private fun toT9(pinyin: String): String {
        val sb = StringBuilder(pinyin.length)
        for (c in pinyin) {
            val d = when (c) {
                in 'a'..'c' -> '2'
                in 'd'..'f' -> '3'
                in 'g'..'i' -> '4'
                in 'j'..'l' -> '5'
                in 'm'..'o' -> '6'
                in 'p'..'s' -> '7'
                in 't'..'v' -> '8'
                in 'w'..'z' -> '9'
                else -> return ""
            }
            sb.append(d)
        }
        return sb.toString()
    }
}
