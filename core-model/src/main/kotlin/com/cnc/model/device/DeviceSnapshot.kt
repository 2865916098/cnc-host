package com.cnc.model.device

/**
 * 设备状态快照。
 *
 * 这是从控制卡读取的实时状态，不包含工艺数据。
 * 在模拟模式下所有字段必须标注为模拟来源。
 */
data class DeviceSnapshot(
    val mode: com.cnc.model.common.ConnectionMode,
    val connectionType: com.cnc.model.common.ConnectionType,
    val connected: Boolean,
    val xActualPositionMm: Double? = null,
    val zActualPositionMm: Double? = null,
    val xTargetPositionMm: Double? = null,
    val zTargetPositionMm: Double? = null,
    val spindleRpm: Int? = null,
    val spindleRunning: Boolean = false,
    val currentToolNumber: Int? = null,
    val inputs: Map<Int, Boolean> = emptyMap(),
    val outputs: Map<Int, Boolean> = emptyMap(),
    val estopPressed: Boolean = false,
    val alarms: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    /** 是否为模拟数据。真实设备模式下必须为 false。 */
    val isSimulated: Boolean = false
)
