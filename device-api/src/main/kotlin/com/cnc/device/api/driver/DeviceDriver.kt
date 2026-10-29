package com.cnc.device.api.driver

import com.cnc.device.api.DeviceController
import java.util.concurrent.ConcurrentHashMap

/**
 * 驱动连接参数。
 *
 * 全部来自服务端后台下发：驱动类型 + 键值参数（IP/端口/站号/寄存器映射等）。
 * 目标是「兼容所有控制卡仅需后台配置」——新增控制卡只需实现并注册新驱动，
 * 后台切换 driverType 即可，客户端代码不变。
 */
data class DriverConnectionParams(
    val driverType: String = DRIVER_SIMULATOR,
    val params: Map<String, String> = emptyMap()
) {
    fun get(key: String): String? = params[key]?.takeIf { it.isNotBlank() }
    fun getInt(key: String, default: Int): Int = get(key)?.toIntOrNull() ?: default
    fun getLong(key: String, default: Long): Long = get(key)?.toLongOrNull() ?: default
    fun getDouble(key: String, default: Double): Double = get(key)?.toDoubleOrNull() ?: default
    fun getBoolean(key: String, default: Boolean): Boolean =
        get(key)?.let { it.equals("true", ignoreCase = true) || it == "1" } ?: default

    companion object {
        /** 内置模拟器（无真实控制卡时的默认驱动）。 */
        const val DRIVER_SIMULATOR = "SIMULATOR"
        /** 通用 Modbus TCP 驱动（网口控制卡）。 */
        const val DRIVER_MODBUS_TCP = "MODBUS_TCP"
    }
}

/** 驱动描述信息（用于后台与客户端展示可选驱动列表）。 */
data class DeviceDriverInfo(
    val driverId: String,
    val displayName: String,
    val description: String = ""
)

/**
 * 设备驱动工厂：按连接参数创建一个 [DeviceController] 实例。
 *
 * 每个驱动实现模块注册一个工厂；创建失败应抛出异常，由调用方决定回退策略。
 */
interface DeviceDriverFactory {
    val info: DeviceDriverInfo

    /** 按后台下发的连接参数创建控制器实例（尚未连接）。 */
    fun create(params: DriverConnectionParams): DeviceController
}

/**
 * 驱动注册表。
 *
 * 模拟器为内置回退项、不在此注册；各真实驱动模块在应用启动时注册。
 * 查询到未知驱动类型时返回 null，调用方必须安全回退（默认模拟器）。
 */
object DeviceDriverRegistry {

    private val factories = ConcurrentHashMap<String, DeviceDriverFactory>()

    /** 注册驱动工厂；同 driverId 重复注册时后者覆盖前者。 */
    fun register(factory: DeviceDriverFactory) {
        factories[factory.info.driverId] = factory
    }

    /** 按驱动类型查工厂；未注册返回 null。 */
    fun factoryFor(driverType: String): DeviceDriverFactory? = factories[driverType]

    /** 当前已注册驱动列表（按 driverId 排序）。 */
    fun available(): List<DeviceDriverInfo> =
        factories.values.map { it.info }.sortedBy { it.driverId }
}
