package com.cnc.host.sync

/**
 * 服务端 JSON 数据模型。
 *
 * 字段名与服务端 server 模块的 @Serializable 类保持一致；
 * Gson 直接按字段名映射，未出现的字段使用默认值。
 */

data class CommConfigDto(
    val protocol: String = "MODBUS_TCP",
    val ipAddress: String = "192.168.1.100",
    val port: Int = 502,
    val serialPort: String = "/dev/ttyUSB0",
    val baudRate: Int = 115200,
    val deviceId: Int = 1,
    val timeoutMs: Long = 2000,
    /** 驱动类型（SIMULATOR / MODBUS_TCP 等），由客户端驱动注册表解析。 */
    val driverType: String = "SIMULATOR",
    /** 驱动扩展参数（寄存器映射等），原样透传给驱动。 */
    val params: Map<String, String> = emptyMap()
)

data class DeviceConfigDto(
    val name: String = "数控车床 #1",
    val controlCard: String = "FANUC_0I",
    val useSimulator: Boolean = true,
    val maxTravelXmm: Double = 300.0,
    val maxTravelZmm: Double = 500.0,
    val maxRpm: Int = 3000,
    val maxFeedMmPerMin: Double = 5000.0,
    val comm: CommConfigDto = CommConfigDto()
)

data class ToolDto(
    val id: String = "",
    val toolNumber: Int = 0,
    val toolType: String = "",
    val name: String = "",
    val material: String = "",
    val nominalDiameterMm: Double = 0.0,
    val lengthMm: Double = 0.0,
    val maxRpm: Int = 3000,
    val maxFeedMmPerMin: Double = 500.0,
    val wearStatus: String = "NEW",
    val usageCount: Int = 0,
    val notes: String = ""
)

data class MaterialDto(
    val id: String = "",
    val name: String = "",
    val code: String = "",
    val density: Double = 0.0,
    val hardness: String = "",
    val recommendedCuttingSpeedMs: Double = 150.0,
    val recommendedFeedPerRev: Double = 0.2,
    val recommendedDepthMm: Double = 2.0,
    val enabled: Boolean = true,
    val notes: String = ""
)

data class PartDto(
    val id: String = "",
    val name: String = "",
    val materialId: String = "",
    val notes: String = ""
)

data class BlankDto(
    val id: String = "",
    val partId: String = "",
    val diameterMm: Double = 0.0,
    val lengthMm: Double = 0.0,
    val materialId: String = "",
    val clampType: String = "CHUCK"
)

data class ProcessStepDto(
    val id: String = "",
    val planId: String = "",
    val orderIndex: Int = 0,
    val type: String = "ROUGH_TURNING",
    val toolId: String = "",
    val cuttingSpeedMmPerMin: Double = 150.0,
    val spindleRpm: Int = 1200,
    val feedMmPerRev: Double = 0.2,
    val depthOfCutMm: Double = 5.0,
    val finishAllowanceMm: Double = 1.0,
    val approachDistanceMm: Double = 10.0,
    val retractDistanceMm: Double = 10.0,
    val safetyDistanceMm: Double = 10.0,
    val enabled: Boolean = true,
    val notes: String = ""
)

data class ProcessPlanDto(
    val id: String = "",
    val name: String = "",
    val partId: String = "",
    val stockDiameterMm: Double = 100.0,
    val stockLengthMm: Double = 50.0,
    val description: String = "",
    val steps: List<ProcessStepDto>? = emptyList()
)

data class DrawingDto(
    val id: String = "",
    val filename: String = "",
    val layers: Int = 0,
    val entities: Int = 0,
    val source: String = "SERVER",
    val dxfContent: String = ""
)

data class DrawingCorrectionDto(
    val drawingId: String = "",
    val scale: Double = 1.0,
    val offsetX: Double = 0.0,
    val offsetY: Double = 0.0,
    val rotationDeg: Double = 0.0,
    val mirrorX: Boolean = false,
    val mirrorY: Boolean = false,
    val unit: String = "MM"
)

data class ConfigDto(
    val version: Long = 0,
    val device: DeviceConfigDto? = DeviceConfigDto(),
    val tools: List<ToolDto>? = emptyList(),
    val materials: List<MaterialDto>? = emptyList(),
    val parts: List<PartDto>? = emptyList(),
    val blanks: List<BlankDto>? = emptyList(),
    val plans: List<ProcessPlanDto>? = emptyList(),
    val drawings: List<DrawingDto>? = emptyList(),
    val drawingCorrections: List<DrawingCorrectionDto>? = emptyList()
)

data class TaskDto(
    val id: String = "",
    val partName: String = "",
    val planId: String = "",
    val status: String = "SUCCESS",
    val durationSec: Double = 0.0,
    val operator: String = "",
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L
)

data class StatusDto(
    val deviceId: String = "tablet-1",
    val connected: Boolean = false,
    val mode: String = "OFFLINE",
    val isSimulated: Boolean = true,
    val taskName: String = "",
    val progress: Double = 0.0,
    val message: String = "",
    val xMm: Double? = null,
    val zMm: Double? = null,
    val spindleRpm: Int? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class SimpleOkDto(val ok: Boolean = false, val message: String = "", val id: String = "")

/** 配置版本号（/config/version 轻量探测）。 */
data class ConfigVersionDto(val version: Long = 0L)

// ===================== 设备注册与权限 =====================

data class ClientPermissionsDto(
    val taskSelect: Boolean = true,
    val previewStart: Boolean = true,
    val monitor: Boolean = true,
    val alarms: Boolean = true,
    val advanced: Boolean = true,
    val serverSync: Boolean = true,
    val drawings: Boolean = true,
    val cam: Boolean = true,
    val machining: Boolean = true,
    val io: Boolean = true,
    val history: Boolean = true
)

data class RegisterRequestDto(
    val unitName: String = "",
    val contactName: String = "",
    val phone: String = "",
    /** 设备归属省份（如「广东省」），后台按地区统计终端数量。 */
    val province: String = "",
    /** 设备归属城市（如「深圳市」）。 */
    val city: String = "",
    val serverAddress: String = "",
    val deviceId: String = "",
    /** 硬件唯一标识（Android ID）：同一台设备只做一次激活登记，重复激活沿用原记录。 */
    val hardwareId: String = "",
    /** 当前客户端版本号，后台可见。 */
    val appVersion: String = ""
)

/** 心跳上报体（携带版本号）。 */
data class HeartbeatRequestDto(val appVersion: String = "")

/** 服务端发布的客户端版本信息（自动更新）。 */
data class AppVersionDto(
    val versionCode: Int = 0,
    val versionName: String = "",
    val changelog: String = "",
    val sha256: String = "",
    val sizeBytes: Long = 0L,
    val publishedAt: Long = 0L
)

data class DeviceInfoDto(
    val id: String = "",
    val unitName: String = "",
    val contactName: String = "",
    val phone: String = "",
    /** 设备归属省份（旧服务端未返回时为空白，保持向后兼容）。 */
    val province: String = "",
    /** 设备归属城市（旧服务端未返回时为空白，保持向后兼容）。 */
    val city: String = "",
    val serverAddress: String = "",
    val registeredAt: Long = 0L,
    val lastSeen: Long = 0L,
    val online: Boolean = false,
    val permissions: ClientPermissionsDto = ClientPermissionsDto(),
    /** 服务端签发的设备令牌：凭它访问配置/任务/状态接口。 */
    val token: String = ""
)

data class AdminVerifyRequestDto(val password: String = "")

// ===================== 操作日志 =====================

/** 单条操作日志（客户端 ↔ 服务端）。 */
data class OperationLogDto(
    val timestamp: Long,
    val level: String,
    val category: String,
    val message: String,
    val taskId: String? = null,
    val details: String? = null
)

/** 批量上传日志的请求体。 */
data class UploadLogsRequestDto(val deviceId: String, val logs: List<OperationLogDto>)
