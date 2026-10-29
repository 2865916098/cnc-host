package com.cnc.device.modbus

import com.cnc.device.api.DeviceController
import com.cnc.device.api.driver.DeviceDriverFactory
import com.cnc.device.api.driver.DeviceDriverInfo
import com.cnc.device.api.driver.DriverConnectionParams

/**
 * 通用 Modbus TCP 驱动工厂。
 *
 * 连接参数（后台下发，键值均为字符串）：
 * - host / port / unitId / timeoutMs：连接参数（兼容 ipAddress / deviceId 键名）
 * - maxRpm：主轴转速上限
 * - 其余键原样传给 [ModbusRegisterMap.from] 作为寄存器映射配置
 */
class ModbusTcpDriverFactory : DeviceDriverFactory {

    override val info = DeviceDriverInfo(
        driverId = DriverConnectionParams.DRIVER_MODBUS_TCP,
        displayName = "通用 Modbus TCP",
        description = "网口控制卡通用驱动；寄存器映射由后台参数配置，适配新卡无需改代码"
    )

    override fun create(params: DriverConnectionParams): DeviceController {
        val host = params.get("host") ?: params.get("ipAddress") ?: "192.168.1.100"
        val port = params.getInt("port", 502)
        val timeoutMs = params.getInt("timeoutMs", 2000)
        val unitId = params.getInt("unitId", params.getInt("deviceId", 1))
        val maxRpm = params.getInt("maxRpm", 3000)
        return ModbusTcpDeviceController(
            client = ModbusClient(SocketModbusTransport(host, port, timeoutMs), unitId),
            map = ModbusRegisterMap.from(params.params),
            maxSpindleRpm = maxRpm
        )
    }
}
