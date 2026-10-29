package com.cnc.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 * CNC 上位机服务端入口。
 *
 * 环境变量：
 * - PORT            监听端口，默认 8080
 * - CNC_ADMIN_TOKEN 管理口令，默认 cnc-admin-2026（部署时务必修改）
 * - CNC_DB          SQLite 文件路径，默认 data/cnc-server.db
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val token = System.getenv("CNC_ADMIN_TOKEN")?.ifBlank { null } ?: "cnc-admin-2026"
    val dbPath = System.getenv("CNC_DB")?.ifBlank { null } ?: "data/cnc-server.db"

    Db.init(dbPath)

    println("CNC 服务端启动：0.0.0.0:$port")
    println("管理后台: http://<服务器IP>:$port/admin/")
    println("数据库:   $dbPath")
    println("管理口令: 由环境变量 CNC_ADMIN_TOKEN 配置（默认值仅用于本地调试）")

    // R5：默认凭据仍在使用时打印醒目警告（不改变默认值本身，保持兼容）
    val defaultToken = System.getenv("CNC_ADMIN_TOKEN").isNullOrBlank()
    if (defaultToken) {
        println("⚠⚠⚠ 安全警告：CNC_ADMIN_TOKEN 未配置，正在使用内置默认管理口令！")
        println("⚠⚠⚠ 生产环境必须设置 CNC_ADMIN_TOKEN 环境变量，否则任何人均可接管管理后台。")
    }
    if (Db.isDefaultAdminCredentialInUse()) {
        println("⚠⚠⚠ 安全警告：管理后台仍在使用默认账号密码 admin/123456！")
        println("⚠⚠⚠ 请立即登录管理后台修改管理员密码，避免未授权访问。")
    }

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        cncServerModule(token)
    }.start(wait = true)
}
