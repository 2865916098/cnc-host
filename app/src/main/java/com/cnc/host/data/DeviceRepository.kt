package com.cnc.host.data

import com.cnc.device.api.DeviceController
import com.cnc.device.api.driver.DeviceDriverRegistry
import com.cnc.device.api.driver.DriverConnectionParams
import com.cnc.device.modbus.ModbusTcpDriverFactory
import com.cnc.device.simulator.DeviceSimulatorConfig
import com.cnc.device.simulator.SimulatedDeviceController
import com.cnc.host.sync.DeviceConfigDto
import com.cnc.model.device.DeviceSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * 设备仓库。
 *
 * 负责按服务端后台下发的配置选择设备驱动：
 * - `useSimulator=true`、未配置、驱动类型未知或创建失败 → 回退内置模拟器，
 *   保证无真实控制卡时全功能可用；
 * - 否则经 [DeviceDriverRegistry] 创建对应驱动（当前支持通用 Modbus TCP）。
 *
 * 新增控制卡只需新增驱动模块并在后台切换驱动类型，客户端代码不变。
 * 必须明确区分模拟与真实设备（[DeviceController.isSimulated]）。
 */
class DeviceRepository(
    private val simulator: SimulatedDeviceController = SimulatedDeviceController(
        config = DeviceSimulatorConfig(xTravelMm = 300.0, zTravelMm = 500.0)
    )
) {

    @Volatile
    private var active: DeviceController = simulator

    val controller: DeviceController get() = active

    val isSimulated: Boolean get() = active.isSimulated

    /**
     * 应用服务端下发的设备配置（同步成功后调用）。
     *
     * 驱动不变时无操作；切换驱动前先断开旧控制器。
     * 任何解析/创建异常都安全回退模拟器，绝不因配置错误导致无设备可用。
     */
    suspend fun applyServerConfig(device: DeviceConfigDto?) {
        val target = resolveController(device)
        if (target === active) return
        runCatching { active.disconnect() }
        active = target
    }

    private fun resolveController(device: DeviceConfigDto?): DeviceController {
        if (device == null || device.useSimulator) return simulator
        val comm = device.comm
        val params = DriverConnectionParams(
            driverType = comm.driverType,
            params = comm.params + mapOf(
                "host" to comm.ipAddress,
                "port" to comm.port.toString(),
                "unitId" to comm.deviceId.toString(),
                "timeoutMs" to comm.timeoutMs.toString(),
                "maxRpm" to device.maxRpm.toString()
            )
        )
        val factory = DeviceDriverRegistry.factoryFor(params.driverType) ?: return simulator
        return runCatching { factory.create(params) }.getOrElse { simulator }
    }

    fun statusFlow(): Flow<DeviceSnapshot> = active.statusFlow()

    suspend fun connect(): Result<Unit> = active.connect()

    suspend fun disconnect() = active.disconnect()

    suspend fun readStatus(): DeviceSnapshot = active.readStatus()

    /** 获取模拟控制器以进行故障注入（仅模拟模式）。 */
    fun simulatorOrNull(): SimulatedDeviceController? =
        if (active.isSimulated) simulator else null

    companion object {
        init {
            // 注册内置真实驱动；后续新增驱动在此登记
            DeviceDriverRegistry.register(ModbusTcpDriverFactory())
        }

        /**
         * 全应用共用的唯一设备实例。
         *
         * 所有页面必须通过它访问设备，否则首页、加工页、审核页会各自持有
         * 一台互不相关的模拟设备，状态无法互相对照。
         */
        val shared: DeviceRepository by lazy { DeviceRepository() }
    }
}
