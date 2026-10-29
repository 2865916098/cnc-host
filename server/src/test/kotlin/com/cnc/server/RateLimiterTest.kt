package com.cnc.server

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R5：滑动窗口限流器行为测试（注入时钟，不依赖真实等待）。 */
class RateLimiterTest {

    @Test
    fun `窗口内超限拒绝，窗口滑动后放行`() {
        var now = 0L
        val limiter = RateLimiter(maxRequests = 3, windowMs = 60_000, nowMs = { now })

        assertTrue(limiter.allow("ip1"))
        assertTrue(limiter.allow("ip1"))
        assertTrue(limiter.allow("ip1"))
        assertFalse(limiter.allow("ip1"), "第 4 次应被拒绝")

        // 其他 key 不受影响
        assertTrue(limiter.allow("ip2"))

        // 窗口滑动后旧记录过期，恢复放行
        now += 61_000
        assertTrue(limiter.allow("ip1"))
    }
}
