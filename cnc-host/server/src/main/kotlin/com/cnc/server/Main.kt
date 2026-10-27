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

    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        cncServerModule(token)
    }.start(wait = true)
}
