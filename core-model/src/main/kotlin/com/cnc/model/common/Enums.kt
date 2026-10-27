package com.cnc.model.common

/**
 * 设备连接模式。必须明确区分模拟与真实设备，
 * 模拟模式下的轴位置、主轴反馈、IO 状态和加工结果必须标注为模拟数据。
 */
enum class ConnectionMode {
    OFFLINE,
    SIMULATOR,
    REAL_CONNECTED,
    REAL_FAULT
}

/** 通讯方式。 */
enum class ConnectionType {
    USB_SERIAL,
    TCP,
    NONE
}

/** 单位制。 */
enum class UnitSystem {
    METRIC_MM,
    IMPERIAL_INCH
}

/** 直径/半径模式（X 轴）。 */
enum class DiameterMode {
    DIAMETER,
    RADIUS
}

/** 任务执行状态。 */
enum class TaskStatus {
    DRAFT,
    READY,
    RUNNING,
    PAUSED,
    STOPPING,
    COMPLETED,
    ABORTED,
    FAULT
}
