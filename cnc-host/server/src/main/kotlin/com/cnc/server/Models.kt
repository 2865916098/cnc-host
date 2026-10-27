package com.cnc.server

import kotlinx.serialization.Serializable

/**
 * 服务端数据模型。
 *
 * 字段命名与 Android 客户端 Room 实体保持一致，客户端可直接映射。
 * 所有 *Draft 类型用于新增/编辑（id 为空时由服务端生成）。
 */

@Serializable
data class CommConfig(
    val protocol: String = "MODBUS_TCP",
    val ipAddress: String = "192.168.1.100",
    val port: Int = 502,
    val serialPort: String = "/dev/ttyUSB0",
    val baudRate: Int = 115200,
    val deviceId: Int = 1,
    val timeoutMs: Long = 2000
)

@Serializable
data class DeviceConfig(
    val name: String = "数控车床 #1",
    val controlCard: String = "FANUC_0I",
    val useSimulator: Boolean = true,
    val maxTravelXmm: Double = 300.0,
    val maxTravelZmm: Double = 500.0,
    val maxRpm: Int = 3000,
    val maxFeedMmPerMin: Double = 5000.0,
    val comm: CommConfig = CommConfig()
)

@Serializable
data class ToolConfig(
    val id: String = "",
    val toolNumber: Int = 1,
    val toolType: String = "TURNING",
    val name: String = "",
    val material: String = "CARBIDE",
    val nominalDiameterMm: Double = 0.0,
    val lengthMm: Double = 0.0,
    val maxRpm: Int = 3000,
    val maxFeedMmPerMin: Double = 500.0,
    val wearStatus: String = "NEW",
    val usageCount: Int = 0,
    val notes: String = ""
)

@Serializable
data class MaterialConfig(
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

@Serializable
data class PartConfig(
    val id: String = "",
    val name: String = "",
    val materialId: String = "",
    val notes: String = ""
)

@Serializable
data class BlankConfig(
    val id: String = "",
    val partId: String = "",
    val diameterMm: Double = 80.0,
    val lengthMm: Double = 120.0,
    val materialId: String = "",
    val clampType: String = "CHUCK"
)

@Serializable
data class ProcessStepConfig(
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

@Serializable
data class ProcessPlanConfig(
    val id: String = "",
    val name: String = "",
    val partId: String = "",
    val stockDiameterMm: Double = 100.0,
    val stockLengthMm: Double = 50.0,
    val description: String = "",
    val steps: List<ProcessStepConfig> = emptyList()
)

@Serializable
data class DrawingConfig(
    val id: String = "",
    val filename: String = "",
    val layers: Int = 0,
    val entities: Int = 0,
    val source: String = "SERVER",
    /**
     * DXF 原文。管理后台可直接粘贴文本，或上传 .dxf 文件。
     * 客户端拉取后存入 Room，用于 CAM 生成。
     */
    val dxfContent: String = "",
    /**
     * 关联的工艺方案 ID。客户端简易模式据此读取毛坯尺寸与切削参数。
     * 空串表示未关联，客户端回退到默认参数。
     */
    val planId: String = ""
)

@Serializable
data class DrawingCorrectionConfig(
    val drawingId: String = "",
    val scale: Double = 1.0,
    val offsetX: Double = 0.0,
    val offsetY: Double = 0.0,
    val rotationDeg: Double = 0.0,
    val mirrorX: Boolean = false,
    val mirrorY: Boolean = false,
    val unit: String = "MM"
)

@Serializable
data class TaskRecord(
    val id: String = "",
    val partName: String = "",
    val planId: String = "",
    val status: String = "SUCCESS",
    val durationSec: Double = 0.0,
    val operator: String = "",
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L
)

/** 客户端上报的实时状态（服务端转发给管理后台 WebSocket）。 */
@Serializable
data class DeviceStatus(
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

/** 一次拉取的完整配置快照。 */
@Serializable
data class ConfigSnapshot(
    val version: Long = 0,
    val device: DeviceConfig = DeviceConfig(),
    val tools: List<ToolConfig> = emptyList(),
    val materials: List<MaterialConfig> = emptyList(),
    val parts: List<PartConfig> = emptyList(),
    val blanks: List<BlankConfig> = emptyList(),
    val plans: List<ProcessPlanConfig> = emptyList(),
    val drawings: List<DrawingConfig> = emptyList(),
    val drawingCorrections: List<DrawingCorrectionConfig> = emptyList()
)

@Serializable
data class AuthRequest(val token: String = "")

@Serializable
data class SimpleResult(val ok: Boolean = true, val message: String = "", val id: String = "")

@Serializable
data class VersionResult(val version: Long = 0)

// ===================== 设备注册与权限 =====================

/**
 * 客户端功能权限。后台可按设备/单位逐项开关。
 * 默认全部开启，注册后由管理员自定义。
 */
@Serializable
data class ClientPermissions(
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

/** 客户端注册请求。注册前无需管理口令。 */
@Serializable
data class RegisterRequest(
    val unitName: String = "",
    val contactName: String = "",
    val phone: String = "",
    val serverAddress: String = "",
    val deviceId: String = ""
)

/** 注册后返回给客户端的设备信息（含权限）。 */
@Serializable
data class DeviceInfo(
    val id: String = "",
    val unitName: String = "",
    val contactName: String = "",
    val phone: String = "",
    val serverAddress: String = "",
    val registeredAt: Long = 0L,
    val lastSeen: Long = 0L,
    val online: Boolean = false,
    val permissions: ClientPermissions = ClientPermissions()
)

/** 后台返回的设备列表项（含在线状态）。 */
@Serializable
data class DeviceListItem(
    val id: String = "",
    val unitName: String = "",
    val contactName: String = "",
    val phone: String = "",
    val serverAddress: String = "",
    val registeredAt: Long = 0L,
    val lastSeen: Long = 0L,
    val online: Boolean = false,
    val permissions: ClientPermissions = ClientPermissions()
)

/** 后台更新设备权限。 */
@Serializable
data class DevicePermissionsUpdate(
    val permissions: ClientPermissions = ClientPermissions()
)

/** 客户端校验管理员密码。 */
@Serializable
data class AdminVerifyRequest(val password: String = "")

/** 后台设置全局管理员密码。 */
@Serializable
data class AdminPasswordUpdate(val newPassword: String = "")
