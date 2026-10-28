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
    val dxfContent: String = ""
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
    val deviceId: String = "",
    /** 硬件唯一标识（Android ID），用于保证同一台设备只能注册一次。 */
    val hardwareId: String = "",
    /** 客户端版本号，如 1.1.0。 */
    val appVersion: String = ""
)

/** 心跳请求体（可选），用于上报客户端版本号。 */
@Serializable
data class HeartbeatRequest(val appVersion: String = "")

/** 后台修改设备信息。 */
@Serializable
data class DeviceUpdateRequest(
    val unitName: String = "",
    val contactName: String = "",
    val phone: String = ""
)

/** 客户端版本发布信息（自动更新用）。 */
@Serializable
data class AppVersionInfo(
    val versionCode: Int = 0,
    val versionName: String = "",
    val changelog: String = "",
    val sha256: String = "",
    val sizeBytes: Long = 0L,
    val publishedAt: Long = 0L
)

/** 「最新构建」候选 APK（服务端扫描构建目录得到）。 */
@Serializable
data class ApkCandidateResult(
    val found: Boolean = false,
    val path: String = "",
    val fileName: String = "",
    val versionCode: Int = 0,
    val versionName: String = "",
    val sizeBytes: Long = 0L,
    val modifiedAt: Long = 0L
)

/** 一键发布最新构建的请求体。 */
@Serializable
data class PublishLatestRequest(val changelog: String = "")

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
    val permissions: ClientPermissions = ClientPermissions(),
    /** 设备令牌：注册时签发，客户端据此访问配置/任务/状态接口（心跳响应也携带，老客户端自动补录）。 */
    val token: String = ""
)

/** 后台返回的设备列表项（含在线状态与客户端版本号）。 */
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
    val appVersion: String = "",
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

/** 后台设置全局管理员账号与密码（客户端「设置」共用同一密码）。 */
@Serializable
data class AdminPasswordUpdate(val newPassword: String = "", val newUsername: String = "")

/** 管理后台账号密码登录请求。 */
@Serializable
data class AdminLoginRequest(val username: String = "", val password: String = "")

/** 登录结果：成功时返回会话 token，后续请求以 Bearer 方式携带。 */
@Serializable
data class AdminLoginResult(
    val ok: Boolean = false,
    val message: String = "",
    val token: String = "",
    val username: String = "",
    val expiresAt: Long = 0L,
    val role: String = "",
    val permissions: List<String> = emptyList(),
    val displayName: String = ""
)

// ===================== 后台用户账号与权限 =====================

/**
 * 用户角色：
 * - SUPER_ADMIN 超级管理员（不受权限列表限制）
 * - ADMIN       管理员（按 permissions 授权）
 * - OPERATOR    操作员（按 permissions 授权）
 */
const val ROLE_SUPER_ADMIN = "SUPER_ADMIN"
const val ROLE_ADMIN = "ADMIN"
const val ROLE_OPERATOR = "OPERATOR"

/**
 * 可授权模块 key（前后端统一）：
 * overview, device, tools, materials, parts, plans, drawings,
 * devices, tasks, users, app, logs
 */
@Serializable
data class UserDto(
    val id: String,
    val username: String,
    val displayName: String,
    val role: String,
    val permissions: List<String> = emptyList(),
    val enabled: Boolean,
    val createdAt: Long,
    val lastLoginAt: Long = 0
)

@Serializable
data class CreateUserRequest(
    val username: String,
    val password: String,
    val displayName: String = "",
    val role: String = "OPERATOR",
    val permissions: List<String> = emptyList()
)

@Serializable
data class UpdateUserRequest(
    val displayName: String? = null,
    val role: String? = null,
    val permissions: List<String>? = null,
    val enabled: Boolean? = null
)

@Serializable
data class ResetPasswordRequest(val newPassword: String)

@Serializable
data class ChangePasswordRequest(val oldPassword: String, val newPassword: String)

@Serializable
data class SessionUserDto(
    val id: String,
    val username: String,
    val displayName: String,
    val role: String,
    val permissions: List<String>,
    val superAdmin: Boolean
)

// ===================== 操作日志 =====================

/** 设备上传 / 管理端查询的单条操作日志。 */
@Serializable
data class OperationLogDto(
    val timestamp: Long,
    val level: String,
    val category: String,
    val message: String,
    val taskId: String? = null,
    val details: String? = null
)

/** 设备批量上传日志的请求体。 */
@Serializable
data class UploadLogsRequest(val deviceId: String, val logs: List<OperationLogDto>)
