package com.cnc.host.setup

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * App 内 WiFi 配置辅助。
 *
 * 目标：首次启动时不跳转到系统设置，在软件内部完成 WiFi 扫描与连接。
 *
 * 能力边界（Android 平台限制）：
 * - Android 10+ 通过 [WifiNetworkSpecifier] 连接指定热点，系统会弹一次「允许连接」对话框，
 *   整个过程不离开本应用。
 * - 扫描热点需要权限：Android 13+ 用 NEARBY_WIFI_DEVICES（声明 neverForLocation），
 *   更低版本需要定位权限且系统定位开关需打开。
 * - 开启/关闭 WiFi 开关本身在 Android 10+ 上不允许应用直接操作，
 *   此时弹出系统 WiFi 面板（浮层，不跳转到设置应用）。
 */
object WifiSetup {

    /** 扫描到的热点。 */
    data class WifiItem(
        val ssid: String,
        val secured: Boolean,
        /** 信号强度 0-100 */
        val level: Int
    )

    /** 连接结果回调。 */
    interface ConnectCallback {
        fun onConnected()
        fun onFailed(reason: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 扫描 WiFi 需要的运行时权限（按系统版本）。 */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasScanPermission(context: Context): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /** Android 12 及以下扫描还要求系统定位开关打开。 */
    fun locationServiceReady(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return true
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return runCatching {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
    }

    fun isWifiEnabled(context: Context): Boolean =
        wifiManager(context).isWifiEnabled

    /**
     * 是否已连接可用网络（WiFi / 移动数据 / 有线）。
     *
     * 工业现场通常是无外网的隔离局域网，因此这里**不要求 NET_CAPABILITY_VALIDATED**，
     * 只要存在具备 INTERNET 能力的活动网络即视为已联网。服务器是否真正可达，
     * 由后续请求结果判断（激活/心跳失败时会明确提示）。
     */
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** 当前网络描述，用于界面展示（如「WiFi：MyHome」「移动数据」「未联网」）。 */
    fun currentNetworkDescription(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return "未联网"
        val caps = cm.getNetworkCapabilities(network) ?: return "未联网"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val ssid = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        (caps.transportInfo as? WifiInfo)?.ssid?.removePrefix("\"")?.removeSuffix("\"")
                    } else null
                }.getOrNull()
                if (ssid.isNullOrBlank() || ssid == "<unknown ssid>") "WiFi 已连接" else "WiFi：$ssid"
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据已连接"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线网络已连接"
            else -> "已连接"
        }
    }

    /**
     * 扫描附近 WiFi。
     *
     * @param onResult 去重并按信号排序后的热点列表
     * @param onError  扫描不可用时的原因提示
     */
    @SuppressLint("MissingPermission")
    fun startScan(context: Context, onResult: (List<WifiItem>) -> Unit, onError: (String) -> Unit) {
        val app = context.applicationContext
        if (!hasScanPermission(app)) {
            onError("缺少附近 WiFi 权限")
            return
        }
        val wm = wifiManager(app)
        if (!wm.isWifiEnabled) {
            onError("WiFi 未开启")
            return
        }
        if (!locationServiceReady(app)) {
            onError("系统定位服务未开启，无法扫描热点")
            return
        }

        var receiver: BroadcastReceiver? = null
        var delivered = false

        fun deliver() {
            if (delivered) return
            delivered = true
            receiver?.let { runCatching { app.unregisterReceiver(it) } }
            receiver = null
            val items = runCatching { wm.scanResults }
                .getOrDefault(emptyList<ScanResult>())
                .filter { it.SSID.isNotBlank() }
                .groupBy { it.SSID }
                .map { (ssid, group) ->
                    val best = group.maxBy { it.level }
                    WifiItem(
                        ssid = ssid,
                        secured = isSecured(best),
                        level = WifiManager.calculateSignalLevel(best.level, 100)
                    )
                }
                .sortedByDescending { it.level }
            mainHandler.post { onResult(items) }
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) deliver()
            }
        }
        runCatching {
            app.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        }
        val started = runCatching { wm.startScan() }.getOrDefault(false)
        // startScan 可能被系统限流（返回 false），8 秒后兜底返回最近一次缓存结果
        mainHandler.postDelayed({ deliver() }, if (started) 8000L else 1200L)
    }

    private fun isSecured(result: ScanResult): Boolean {
        val caps = result.capabilities ?: return false
        return caps.contains("WPA") || caps.contains("WEP") || caps.contains("PSK") ||
            caps.contains("SAE") || caps.contains("EAP")
    }

    /**
     * 连接指定 WiFi（Android 10+，系统弹出一次连接确认框，不离开本应用）。
     *
     * 连接成功后把进程绑定到该网络，保证后续注册请求走新连上的 WiFi。
     */
    fun connect(context: Context, item: WifiItem, password: String, callback: ConnectCallback) {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            callback.onFailed("当前系统版本不支持应用内连接，请使用系统网络面板")
            return
        }
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(item.ssid)
            .apply {
                if (item.secured && password.isNotBlank()) setWpa2Passphrase(password)
            }
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runCatching { cm.bindProcessToNetwork(network) }
                mainHandler.post { callback.onConnected() }
            }

            override fun onUnavailable() {
                mainHandler.post { callback.onFailed("连接被拒绝或热点不可用") }
            }
        }
        activeCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        activeCallback = networkCallback
        runCatching { cm.requestNetwork(request, networkCallback, CONNECT_TIMEOUT_MS) }
            .onFailure {
                activeCallback = null
                callback.onFailed(it.message ?: "无法发起连接")
            }
    }

    /** 解除进程网络绑定（进入主界面后恢复正常路由，此时系统网络已由系统管理）。 */
    fun releaseBinding(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        runCatching { cm.bindProcessToNetwork(null) }
        activeCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        activeCallback = null
    }

    private var activeCallback: ConnectivityManager.NetworkCallback? = null

    private fun wifiManager(context: Context): WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private const val CONNECT_TIMEOUT_MS = 30_000
}
