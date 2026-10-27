package com.cnc.host.sync

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 服务器地址规范化与局域网自动发现。
 *
 * 现场只需要输入服务器 IP（如 192.168.28.18），
 * 「http://」前缀与端口 8080 由系统自动补全。
 */
object ServerAddress {

    const val DEFAULT_PORT = 8080

    /**
     * 把用户输入规范化为完整 baseUrl。
     * 支持：`192.168.1.10`、`192.168.1.10:8080`、`http://192.168.1.10:8080/`、主机名等。
     */
    fun normalize(raw: String, defaultPort: Int = DEFAULT_PORT): String {
        var s = raw.trim()
        if (s.isEmpty()) return ""
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        s = s.trimEnd('/')
        // 去掉协议头后检查是否带端口（IPv6 简化为不处理，工业现场用 IPv4）
        val schemeEnd = s.indexOf("://") + 3
        val hostPart = s.substring(schemeEnd)
        if (!hostPart.contains(":")) s = "$s:$defaultPort"
        return s
    }

    /**
     * 在局域网内自动搜索 CNC 服务端：对本机 WiFi 所在 /24 网段的 8080 端口
     * 并发探测 /api/v1/health，返回第一台响应的服务器地址（规范化的 baseUrl）。
     *
     * 找不到返回 null；全程挂起在 IO 线程，通常 2 秒内完成。
     */
    suspend fun discoverOnLan(context: Context, port: Int = DEFAULT_PORT): String? =
        withContext(Dispatchers.IO) {
            val wifiIp = localWifiIp(context) ?: return@withContext null
            val prefix = wifiIp.substringBeforeLast('.')
            // 并发探测 1..254，排除本机
            val selfLast = wifiIp.substringAfterLast('.').toIntOrNull()
            coroutineScope {
                (1..254)
                    .filter { it != selfLast }
                    .map { last ->
                        async {
                            val host = "$prefix.$last"
                            if (probeServer(host, port)) normalize(host) else null
                        }
                    }
                    .awaitAll()
                    .firstOrNull { it != null }
            }
        }

    /** 本机 WiFi IPv4 地址（如 192.168.28.50）；未连 WiFi 返回 null。 */
    private fun localWifiIp(context: Context): String? = runCatching {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wm.connectionInfo?.ipAddress ?: 0
        if (ip == 0) return null
        // little-endian
        "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
    }.getOrNull()

    /** 探测指定主机端口上是否运行 CNC 服务端。 */
    private fun probeServer(host: String, port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 400)
            // 端口通后请求健康检查，确认是 CNC 服务端而不是其它服务
            val out = socket.getOutputStream()
            val req = "GET /api/v1/health HTTP/1.1\r\nHost: $host:$port\r\nConnection: close\r\n\r\n"
            out.write(req.toByteArray(Charsets.US_ASCII))
            out.flush()
            socket.soTimeout = 400
            val buf = ByteArray(512)
            val n = socket.getInputStream().read(buf)
            n > 0 && String(buf, 0, n, Charsets.ISO_8859_1).contains("cnc-server")
        }
    }.getOrDefault(false)
}
