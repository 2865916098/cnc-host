package com.cnc.host.data

import com.cnc.device.api.DeviceController
import com.cnc.device.simulator.DeviceSimulatorConfig
import com.cnc.device.simulator.SimulatedDeviceController
import com.cnc.model.device.DeviceSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * 设备仓库。
 *
 * 负责提供当前使用的设备控制器。在控制卡未确定前，默认使用模拟控制卡。
 * 必须明确区分模拟与真实设备。
 */
class DeviceRepository(
    private val simulator: SimulatedDeviceController = SimulatedDeviceController(
        config = DeviceSimulatorConfig(xTravelMm = 300.0, zTravelMm = 500.0)
    )
) {

    val controller: DeviceController = simulator

    val isSimulated: Boolean get() = controller.isSimulated

    fun statusFlow(): Flow<DeviceSnapshot> = controller.statusFlow()

    suspend fun connect(): Result<Unit> = controller.connect()

    suspend fun disconnect() = controller.disconnect()

    suspend fun readStatus(): DeviceSnapshot = controller.readStatus()

    /** 获取模拟控制器以进行故障注入（仅模拟模式）。 */
    fun simulatorOrNull(): SimulatedDeviceController? =
        if (controller.isSimulated) simulator else null

    companion object {
        /**
         * 全应用共用的唯一设备实例。
         *
         * 所有页面必须通过它访问设备，否则首页、加工页、审核页会各自持有
         * 一台互不相关的模拟设备，状态无法互相对照。
         */
        val shared: DeviceRepository by lazy { DeviceRepository() }
    }
}
