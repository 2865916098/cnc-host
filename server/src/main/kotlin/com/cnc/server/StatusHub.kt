package com.cnc.server

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame

/**
 * 管理后台实时状态推送。
 *
 * 平板客户端把状态上报到 POST /api/v1/status，服务端持久化后通过
 * WebSocket 广播给所有已连接的管理后台页面。
 */
object StatusHub {

    private val sessions = mutableListOf<DefaultWebSocketServerSession>()

    fun register(session: DefaultWebSocketServerSession) {
        synchronized(sessions) { sessions.add(session) }
    }

    fun unregister(session: DefaultWebSocketServerSession) {
        synchronized(sessions) { sessions.remove(session) }
    }

    suspend fun broadcast(text: String) {
        val targets = synchronized(sessions) { sessions.toList() }
        targets.forEach { session ->
            runCatching { session.send(Frame.Text(text)) }
                .onFailure { unregister(session) }
        }
    }
}
