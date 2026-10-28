package com.cnc.server

import java.util.concurrent.ConcurrentHashMap

/**
 * 内存滑动窗口限流器（R5 安全加固）。
 *
 * 按 key（通常为客户端 IP）统计窗口内请求数，超限拒绝。
 * 进程内有效、重启清零；适用于单实例部署的车间服务端。
 */
class RateLimiter(
    private val maxRequests: Int,
    private val windowMs: Long,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private val hits = ConcurrentHashMap<String, MutableList<Long>>()

    /** @return true 放行；false 已超限。 */
    @Synchronized
    fun allow(key: String): Boolean {
        val now = nowMs()
        val list = hits.getOrPut(key) { mutableListOf() }
        list.removeAll { now - it >= windowMs }
        if (list.size >= maxRequests) return false
        list.add(now)
        return true
    }
}
