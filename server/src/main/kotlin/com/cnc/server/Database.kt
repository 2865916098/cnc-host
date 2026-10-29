package com.cnc.server

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** 会话 token 解析出的登录用户（权限拦截与 /users/me 使用）。 */
data class SessionUser(
    val id: String,
    val username: String,
    val displayName: String,
    val role: String,
    val permissions: List<String>,
    val enabled: Boolean,
    /** 会话所属租户（ADR-0002），路由层据此校验请求头租户上下文。 */
    val tenantId: String = Db.DEFAULT_TENANT
)

/**
 * SQLite 数据访问层。
 *
 * 使用 JDBC 直接访问，避免引入 ORM 带来的额外构建复杂度。
 * 所有配置写操作都会递增 config_version，客户端据此做增量同步判断。
 */
object Db {

    /** 多租户：私有化部署恒为该租户（ADR-0002）。 */
    const val DEFAULT_TENANT = "default"

    /** 解析租户上下文：null/空白回退默认租户。 */
    fun normalizeTenant(raw: String?): String =
        raw?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_TENANT

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var url: String

    @Synchronized
    fun init(path: String) {
        val file = File(path)
        file.parentFile?.mkdirs()
        url = "jdbc:sqlite:${file.absolutePath}"
        withConn { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS meta(
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS device_config(
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        json TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS tools(
                        id TEXT PRIMARY KEY,
                        tenant_id TEXT NOT NULL DEFAULT 'default',
                        toolNumber INTEGER NOT NULL,
                        toolType TEXT NOT NULL,
                        name TEXT NOT NULL,
                        material TEXT NOT NULL,
                        nominalDiameterMm REAL NOT NULL,
                        lengthMm REAL NOT NULL,
                        maxRpm INTEGER NOT NULL,
                        maxFeedMmPerMin REAL NOT NULL,
                        wearStatus TEXT NOT NULL,
                        usageCount INTEGER NOT NULL,
                        notes TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS materials(
                        id TEXT PRIMARY KEY,
                        tenant_id TEXT NOT NULL DEFAULT 'default',
                        name TEXT NOT NULL,
                        code TEXT NOT NULL,
                        density REAL NOT NULL,
                        hardness TEXT NOT NULL,
                        recommendedCuttingSpeedMs REAL NOT NULL,
                        recommendedFeedPerRev REAL NOT NULL,
                        recommendedDepthMm REAL NOT NULL,
                        enabled INTEGER NOT NULL,
                        notes TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS parts(
                        id TEXT PRIMARY KEY,
                        tenant_id TEXT NOT NULL DEFAULT 'default',
                        name TEXT NOT NULL,
                        materialId TEXT NOT NULL,
                        notes TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS blanks(
                        id TEXT PRIMARY KEY,
                        tenant_id TEXT NOT NULL DEFAULT 'default',
                        partId TEXT NOT NULL,
                        diameterMm REAL NOT NULL,
                        lengthMm REAL NOT NULL,
                        materialId TEXT NOT NULL,
                        clampType TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS process_plans(
                        id TEXT PRIMARY KEY,
                        tenant_id TEXT NOT NULL DEFAULT 'default',
                        name TEXT NOT NULL,
                        partId TEXT NOT NULL,
                        stockDiameterMm REAL NOT NULL,
                        stockLengthMm REAL NOT NULL,
                        description TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS process_steps(
                        id TEXT PRIMARY KEY,
                        tenant_id TEXT NOT NULL DEFAULT 'default',
                        planId TEXT NOT NULL,
                        orderIndex INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        toolId TEXT NOT NULL,
                        cuttingSpeedMmPerMin REAL NOT NULL,
                        spindleRpm INTEGER NOT NULL,
                        feedMmPerRev REAL NOT NULL,
                        depthOfCutMm REAL NOT NULL,
                        finishAllowanceMm REAL NOT NULL,
                        approachDistanceMm REAL NOT NULL,
                        retractDistanceMm REAL NOT NULL,
                        safetyDistanceMm REAL NOT NULL,
                        enabled INTEGER NOT NULL,
                        notes TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS drawings(
                        id TEXT PRIMARY KEY,
                        tenant_id TEXT NOT NULL DEFAULT 'default',
                        filename TEXT NOT NULL,
                        layers INTEGER NOT NULL,
                        entities INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        dxfContent TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS drawing_corrections(
                        drawingId TEXT PRIMARY KEY,
                        tenant_id TEXT NOT NULL DEFAULT 'default',
                        scale REAL NOT NULL,
                        offsetX REAL NOT NULL,
                        offsetY REAL NOT NULL,
                        rotationDeg REAL NOT NULL,
                        mirrorX INTEGER NOT NULL,
                        mirrorY INTEGER NOT NULL,
                        unit TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS task_history(
                        id TEXT PRIMARY KEY,
                        tenant_id TEXT NOT NULL DEFAULT 'default',
                        partName TEXT NOT NULL,
                        planId TEXT NOT NULL,
                        status TEXT NOT NULL,
                        durationSec REAL NOT NULL,
                        operator TEXT NOT NULL,
                        startedAt INTEGER NOT NULL,
                        finishedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS operation_logs(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        tenant_id TEXT NOT NULL DEFAULT 'default',
                        device_id TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        level TEXT NOT NULL,
                        category TEXT NOT NULL,
                        message TEXT NOT NULL,
                        task_id TEXT,
                        details TEXT
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_op_logs_device ON operation_logs(device_id, timestamp DESC)"
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS devices(
                        id TEXT PRIMARY KEY,
                        tenant_id TEXT NOT NULL DEFAULT 'default',
                        unit_name TEXT NOT NULL,
                        contact_name TEXT NOT NULL,
                        phone TEXT NOT NULL,
                        server_address TEXT NOT NULL,
                        registered_at INTEGER NOT NULL,
                        last_seen INTEGER NOT NULL,
                        online INTEGER NOT NULL,
                        permissions TEXT NOT NULL,
                        hardware_id TEXT NOT NULL DEFAULT '',
                        app_version TEXT NOT NULL DEFAULT ''
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS admin_sessions(
                        token TEXT PRIMARY KEY,
                        tenant_id TEXT NOT NULL DEFAULT 'default',
                        username TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS users(
                        id TEXT PRIMARY KEY,
                        tenant_id TEXT NOT NULL DEFAULT 'default',
                        username TEXT NOT NULL,
                        password_hash TEXT NOT NULL,
                        display_name TEXT NOT NULL,
                        role TEXT NOT NULL,
                        permissions TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        last_login_at INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }
        // 旧库迁移：为 devices 表补充 hardware_id / app_version 列（已存在则忽略）
        withConn { c ->
            c.createStatement().use { st ->
                runCatching { st.executeUpdate("ALTER TABLE devices ADD COLUMN hardware_id TEXT NOT NULL DEFAULT ''") }
                runCatching { st.executeUpdate("ALTER TABLE devices ADD COLUMN app_version TEXT NOT NULL DEFAULT ''") }
                // 设备令牌：客户端凭此访问配置/任务/状态接口（管理口令不下发到平板）
                runCatching { st.executeUpdate("ALTER TABLE devices ADD COLUMN device_token TEXT NOT NULL DEFAULT ''") }
                // 设备激活填写的地区（老记录保持空串，界面显示「未填写」）
                runCatching { st.executeUpdate("ALTER TABLE devices ADD COLUMN province TEXT NOT NULL DEFAULT ''") }
                runCatching { st.executeUpdate("ALTER TABLE devices ADD COLUMN city TEXT NOT NULL DEFAULT ''") }
                // 老设备回填令牌，客户端下次心跳时自动取回
                st.executeUpdate("UPDATE devices SET device_token = lower(hex(randomblob(16))) WHERE device_token = ''")
                // 同一台设备（按硬件标识）只能注册一次（必须在列迁移之后创建）
                st.executeUpdate(
                    "CREATE UNIQUE INDEX IF NOT EXISTS idx_devices_hardware_id ON devices(hardware_id) WHERE hardware_id != ''"
                )
                // 用户体系迁移：会话关联用户 id（老库已存在则忽略）
                runCatching {
                    st.executeUpdate("ALTER TABLE admin_sessions ADD COLUMN user_id TEXT NOT NULL DEFAULT ''")
                }
            }
        }
        // 多租户迁移（ADR-0002）：全部业务表补 tenant_id 列；唯一索引升级为 (tenant_id, 原键)
        withConn { c ->
            c.createStatement().use { st ->
                listOf(
                    "tools", "materials", "parts", "blanks", "process_plans", "process_steps",
                    "drawings", "drawing_corrections", "task_history", "operation_logs",
                    "devices", "users", "admin_sessions"
                ).forEach { table ->
                    runCatching {
                        st.executeUpdate("ALTER TABLE $table ADD COLUMN tenant_id TEXT NOT NULL DEFAULT '$DEFAULT_TENANT'")
                    }
                }
                // users 用户名唯一性升级为 (tenant_id, username)：SQLite 无法删除列级
                // UNIQUE 约束，老库（建表 sql 含 UNIQUE）需重建表，新库已是目标结构
                val usersSql = st.executeQuery(
                    "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'users'"
                ).use { rs -> if (rs.next()) rs.getString(1) else null }
                if (usersSql != null && usersSql.contains("UNIQUE", ignoreCase = true)) {
                    st.executeUpdate(
                        """
                        CREATE TABLE users_new(
                            id TEXT PRIMARY KEY,
                            tenant_id TEXT NOT NULL DEFAULT 'default',
                            username TEXT NOT NULL,
                            password_hash TEXT NOT NULL,
                            display_name TEXT NOT NULL,
                            role TEXT NOT NULL,
                            permissions TEXT NOT NULL,
                            enabled INTEGER NOT NULL,
                            created_at INTEGER NOT NULL,
                            last_login_at INTEGER NOT NULL DEFAULT 0
                        )
                        """.trimIndent()
                    )
                    st.executeUpdate(
                        """
                        INSERT INTO users_new(id, tenant_id, username, password_hash, display_name, role, permissions, enabled, created_at, last_login_at)
                        SELECT id, tenant_id, username, password_hash, display_name, role, permissions, enabled, created_at, last_login_at FROM users
                        """.trimIndent()
                    )
                    st.executeUpdate("DROP TABLE users")
                    st.executeUpdate("ALTER TABLE users_new RENAME TO users")
                }
                st.executeUpdate(
                    "CREATE UNIQUE INDEX IF NOT EXISTS idx_users_tenant_username ON users(tenant_id, username)"
                )
                // devices 硬件标识唯一索引升级为 (tenant_id, hardware_id)
                st.executeUpdate("DROP INDEX IF EXISTS idx_devices_hardware_id")
                st.executeUpdate(
                    "CREATE UNIQUE INDEX IF NOT EXISTS idx_devices_tenant_hardware ON devices(tenant_id, hardware_id) WHERE hardware_id != ''"
                )
                // 日志查询索引带租户前缀
                st.executeUpdate("DROP INDEX IF EXISTS idx_op_logs_device")
                st.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_op_logs_tenant_device ON operation_logs(tenant_id, device_id, timestamp DESC)"
                )
            }
        }
        // 种子数据必须在全部列迁移之后执行：老库表结构在迁移前不含 tenant_id 列
        seedIfEmpty()
        // 管理员账号/密码：首次启动写默认值，已存在则保留后台设置
        withConn { c ->
            if (c.getMeta("admin_username") == null) c.putMeta("admin_username", DEFAULT_ADMIN_USERNAME)
            val pwd = c.getMeta("admin_password")
            // 旧版本默认密码 admin123 自动迁移为新默认 123456；用户改过的密码保留
            if (pwd == null || pwd == LEGACY_DEFAULT_ADMIN_PASSWORD) c.putMeta("admin_password", DEFAULT_ADMIN_PASSWORD)
        }
        // 首次启动把现有管理员账号迁移为 SUPER_ADMIN 用户，保证 admin/123456 仍可登录
        seedUsersIfEmpty()
    }

    fun <T> withConn(block: (Connection) -> T): T =
        DriverManager.getConnection(url).use(block)

    private fun Connection.tx(block: () -> Unit) {
        autoCommit = false
        try {
            block()
            commit()
        } catch (e: Exception) {
            rollback()
            throw e
        } finally {
            autoCommit = true
        }
    }

    private fun Connection.getMeta(key: String): String? =
        prepareStatement("SELECT value FROM meta WHERE key = ?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    private fun Connection.putMeta(key: String, value: String) {
        prepareStatement("INSERT OR REPLACE INTO meta(key, value) VALUES(?, ?)").use { ps ->
            ps.setString(1, key)
            ps.setString(2, value)
            ps.executeUpdate()
        }
    }

    fun version(): Long = withConn { c ->
        c.getMeta("config_version")?.toLongOrNull() ?: 1L
    }

    private fun Connection.bumpVersion() {
        val next = (getMeta("config_version")?.toLongOrNull() ?: 1L) + 1L
        putMeta("config_version", next.toString())
    }

    /** 一次读取完整配置（按租户隔离；device 为服务器级全局机床配置）。 */
    fun snapshot(tenantId: String = DEFAULT_TENANT): ConfigSnapshot = withConn { c ->
        ConfigSnapshot(
            version = c.getMeta("config_version")?.toLongOrNull() ?: 1L,
            device = readDevice(c),
            tools = readTools(c, tenantId),
            materials = readMaterials(c, tenantId),
            parts = readParts(c, tenantId),
            blanks = readBlanks(c, tenantId),
            plans = readPlans(c, tenantId),
            drawings = readDrawings(c, tenantId),
            drawingCorrections = readCorrections(c, tenantId)
        )
    }

    // ===== 设备 =====

    private fun readDevice(c: Connection): DeviceConfig {
        val raw = c.prepareStatement("SELECT json FROM device_config WHERE id = 1").use { ps ->
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        } ?: return DeviceConfig()
        return runCatching { json.decodeFromString<DeviceConfig>(raw) }.getOrElse { DeviceConfig() }
    }

    fun saveDevice(config: DeviceConfig) = withConn { c ->
        c.tx {
            c.prepareStatement("INSERT OR REPLACE INTO device_config(id, json) VALUES(1, ?)").use { ps ->
                ps.setString(1, json.encodeToString(DeviceConfig.serializer(), config))
                ps.executeUpdate()
            }
            c.bumpVersion()
        }
    }

    // ===== 刀具 =====

    private fun readTools(c: Connection, tenantId: String): List<ToolConfig> =
        c.prepareStatement("SELECT * FROM tools WHERE tenant_id = ? ORDER BY toolNumber").use { ps ->
            ps.setString(1, tenantId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        ToolConfig(
                            id = rs.getString("id"),
                            toolNumber = rs.getInt("toolNumber"),
                            toolType = rs.getString("toolType"),
                            name = rs.getString("name"),
                            material = rs.getString("material"),
                            nominalDiameterMm = rs.getDouble("nominalDiameterMm"),
                            lengthMm = rs.getDouble("lengthMm"),
                            maxRpm = rs.getInt("maxRpm"),
                            maxFeedMmPerMin = rs.getDouble("maxFeedMmPerMin"),
                            wearStatus = rs.getString("wearStatus"),
                            usageCount = rs.getInt("usageCount"),
                            notes = rs.getString("notes")
                        )
                    )
                }
            }
        }

    fun upsertTool(tool: ToolConfig, tenantId: String = DEFAULT_TENANT): ToolConfig = withConn { c ->
        val id = tool.id.ifBlank { "tool-${UUID.randomUUID().toString().take(8)}" }
        val saved = tool.copy(id = id)
        c.tx {
            c.prepareStatement(
                """
                INSERT OR REPLACE INTO tools(
                    id, tenant_id, toolNumber, toolType, name, material, nominalDiameterMm, lengthMm,
                    maxRpm, maxFeedMmPerMin, wearStatus, usageCount, notes
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, id)
                ps.setString(2, tenantId)
                ps.setInt(3, saved.toolNumber)
                ps.setString(4, saved.toolType)
                ps.setString(5, saved.name)
                ps.setString(6, saved.material)
                ps.setDouble(7, saved.nominalDiameterMm)
                ps.setDouble(8, saved.lengthMm)
                ps.setInt(9, saved.maxRpm)
                ps.setDouble(10, saved.maxFeedMmPerMin)
                ps.setString(11, saved.wearStatus)
                ps.setInt(12, saved.usageCount)
                ps.setString(13, saved.notes)
                ps.executeUpdate()
            }
            c.bumpVersion()
        }
        saved
    }

    fun deleteTool(id: String, tenantId: String = DEFAULT_TENANT) = deleteById("tools", id, tenantId)

    // ===== 材料 =====

    private fun readMaterials(c: Connection, tenantId: String): List<MaterialConfig> =
        c.prepareStatement("SELECT * FROM materials WHERE tenant_id = ? ORDER BY name").use { ps ->
            ps.setString(1, tenantId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        MaterialConfig(
                            id = rs.getString("id"),
                            name = rs.getString("name"),
                            code = rs.getString("code"),
                            density = rs.getDouble("density"),
                            hardness = rs.getString("hardness"),
                            recommendedCuttingSpeedMs = rs.getDouble("recommendedCuttingSpeedMs"),
                            recommendedFeedPerRev = rs.getDouble("recommendedFeedPerRev"),
                            recommendedDepthMm = rs.getDouble("recommendedDepthMm"),
                            enabled = rs.getInt("enabled") != 0,
                            notes = rs.getString("notes")
                        )
                    )
                }
            }
        }

    fun upsertMaterial(material: MaterialConfig, tenantId: String = DEFAULT_TENANT): MaterialConfig = withConn { c ->
        val id = material.id.ifBlank { "mat-${UUID.randomUUID().toString().take(8)}" }
        val saved = material.copy(id = id)
        c.tx {
            c.prepareStatement(
                """
                INSERT OR REPLACE INTO materials(
                    id, tenant_id, name, code, density, hardness, recommendedCuttingSpeedMs,
                    recommendedFeedPerRev, recommendedDepthMm, enabled, notes
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, id)
                ps.setString(2, tenantId)
                ps.setString(3, saved.name)
                ps.setString(4, saved.code)
                ps.setDouble(5, saved.density)
                ps.setString(6, saved.hardness)
                ps.setDouble(7, saved.recommendedCuttingSpeedMs)
                ps.setDouble(8, saved.recommendedFeedPerRev)
                ps.setDouble(9, saved.recommendedDepthMm)
                ps.setInt(10, if (saved.enabled) 1 else 0)
                ps.setString(11, saved.notes)
                ps.executeUpdate()
            }
            c.bumpVersion()
        }
        saved
    }

    fun deleteMaterial(id: String, tenantId: String = DEFAULT_TENANT) = deleteById("materials", id, tenantId)

    // ===== 零件 / 毛坯 =====

    private fun readParts(c: Connection, tenantId: String): List<PartConfig> =
        c.prepareStatement("SELECT * FROM parts WHERE tenant_id = ? ORDER BY name").use { ps ->
            ps.setString(1, tenantId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        PartConfig(
                            id = rs.getString("id"),
                            name = rs.getString("name"),
                            materialId = rs.getString("materialId"),
                            notes = rs.getString("notes")
                        )
                    )
                }
            }
        }

    fun upsertPart(part: PartConfig, tenantId: String = DEFAULT_TENANT): PartConfig = withConn { c ->
        val id = part.id.ifBlank { "part-${UUID.randomUUID().toString().take(8)}" }
        val saved = part.copy(id = id)
        c.tx {
            c.prepareStatement(
                "INSERT OR REPLACE INTO parts(id, tenant_id, name, materialId, notes) VALUES(?,?,?,?,?)"
            ).use { ps ->
                ps.setString(1, id)
                ps.setString(2, tenantId)
                ps.setString(3, saved.name)
                ps.setString(4, saved.materialId)
                ps.setString(5, saved.notes)
                ps.executeUpdate()
            }
            c.bumpVersion()
        }
        saved
    }

    fun deletePart(id: String, tenantId: String = DEFAULT_TENANT) = deleteById("parts", id, tenantId)

    private fun readBlanks(c: Connection, tenantId: String): List<BlankConfig> =
        c.prepareStatement("SELECT * FROM blanks WHERE tenant_id = ?").use { ps ->
            ps.setString(1, tenantId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        BlankConfig(
                            id = rs.getString("id"),
                            partId = rs.getString("partId"),
                            diameterMm = rs.getDouble("diameterMm"),
                            lengthMm = rs.getDouble("lengthMm"),
                            materialId = rs.getString("materialId"),
                            clampType = rs.getString("clampType")
                        )
                    )
                }
            }
        }

    fun upsertBlank(blank: BlankConfig, tenantId: String = DEFAULT_TENANT): BlankConfig = withConn { c ->
        val id = blank.id.ifBlank { "blank-${UUID.randomUUID().toString().take(8)}" }
        val saved = blank.copy(id = id)
        c.tx {
            c.prepareStatement(
                """
                INSERT OR REPLACE INTO blanks(id, tenant_id, partId, diameterMm, lengthMm, materialId, clampType)
                VALUES(?,?,?,?,?,?,?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, id)
                ps.setString(2, tenantId)
                ps.setString(3, saved.partId)
                ps.setDouble(4, saved.diameterMm)
                ps.setDouble(5, saved.lengthMm)
                ps.setString(6, saved.materialId)
                ps.setString(7, saved.clampType)
                ps.executeUpdate()
            }
            c.bumpVersion()
        }
        saved
    }

    fun deleteBlank(id: String, tenantId: String = DEFAULT_TENANT) = deleteById("blanks", id, tenantId)

    // ===== 工艺方案 =====

    private fun readPlans(c: Connection, tenantId: String): List<ProcessPlanConfig> {
        val steps = c.prepareStatement(
            "SELECT * FROM process_steps WHERE tenant_id = ? ORDER BY planId, orderIndex"
        ).use { ps ->
            ps.setString(1, tenantId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        ProcessStepConfig(
                            id = rs.getString("id"),
                            planId = rs.getString("planId"),
                            orderIndex = rs.getInt("orderIndex"),
                            type = rs.getString("type"),
                            toolId = rs.getString("toolId"),
                            cuttingSpeedMmPerMin = rs.getDouble("cuttingSpeedMmPerMin"),
                            spindleRpm = rs.getInt("spindleRpm"),
                            feedMmPerRev = rs.getDouble("feedMmPerRev"),
                            depthOfCutMm = rs.getDouble("depthOfCutMm"),
                            finishAllowanceMm = rs.getDouble("finishAllowanceMm"),
                            approachDistanceMm = rs.getDouble("approachDistanceMm"),
                            retractDistanceMm = rs.getDouble("retractDistanceMm"),
                            safetyDistanceMm = rs.getDouble("safetyDistanceMm"),
                            enabled = rs.getInt("enabled") != 0,
                            notes = rs.getString("notes")
                        )
                    )
                }
            }
        }.groupBy { it.planId }

        return c.prepareStatement("SELECT * FROM process_plans WHERE tenant_id = ? ORDER BY name").use { ps ->
            ps.setString(1, tenantId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val id = rs.getString("id")
                        add(
                            ProcessPlanConfig(
                                id = id,
                                name = rs.getString("name"),
                                partId = rs.getString("partId"),
                                stockDiameterMm = rs.getDouble("stockDiameterMm"),
                                stockLengthMm = rs.getDouble("stockLengthMm"),
                                description = rs.getString("description"),
                                steps = steps[id].orEmpty()
                            )
                        )
                    }
                }
            }
        }
    }

    fun upsertPlan(plan: ProcessPlanConfig, tenantId: String = DEFAULT_TENANT): ProcessPlanConfig = withConn { c ->
        val id = plan.id.ifBlank { "plan-${UUID.randomUUID().toString().take(8)}" }
        val saved = plan.copy(id = id)
        c.tx {
            c.prepareStatement(
                """
                INSERT OR REPLACE INTO process_plans(id, tenant_id, name, partId, stockDiameterMm, stockLengthMm, description)
                VALUES(?,?,?,?,?,?,?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, id)
                ps.setString(2, tenantId)
                ps.setString(3, saved.name)
                ps.setString(4, saved.partId)
                ps.setDouble(5, saved.stockDiameterMm)
                ps.setDouble(6, saved.stockLengthMm)
                ps.setString(7, saved.description)
                ps.executeUpdate()
            }
            c.prepareStatement("DELETE FROM process_steps WHERE planId = ? AND tenant_id = ?").use { ps ->
                ps.setString(1, id)
                ps.setString(2, tenantId)
                ps.executeUpdate()
            }
            saved.steps.forEachIndexed { index, step ->
                val stepId = step.id.ifBlank { "$id-step-$index" }
                c.prepareStatement(
                    """
                    INSERT OR REPLACE INTO process_steps(
                        id, tenant_id, planId, orderIndex, type, toolId, cuttingSpeedMmPerMin, spindleRpm,
                        feedMmPerRev, depthOfCutMm, finishAllowanceMm, approachDistanceMm,
                        retractDistanceMm, safetyDistanceMm, enabled, notes
                    ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, stepId)
                    ps.setString(2, tenantId)
                    ps.setString(3, id)
                    ps.setInt(4, step.orderIndex.takeIf { it > 0 } ?: index)
                    ps.setString(5, step.type)
                    ps.setString(6, step.toolId)
                    ps.setDouble(7, step.cuttingSpeedMmPerMin)
                    ps.setInt(8, step.spindleRpm)
                    ps.setDouble(9, step.feedMmPerRev)
                    ps.setDouble(10, step.depthOfCutMm)
                    ps.setDouble(11, step.finishAllowanceMm)
                    ps.setDouble(12, step.approachDistanceMm)
                    ps.setDouble(13, step.retractDistanceMm)
                    ps.setDouble(14, step.safetyDistanceMm)
                    ps.setInt(15, if (step.enabled) 1 else 0)
                    ps.setString(16, step.notes)
                    ps.executeUpdate()
                }
            }
            c.bumpVersion()
        }
        saved
    }

    fun deletePlan(id: String, tenantId: String = DEFAULT_TENANT) = withConn { c ->
        c.tx {
            c.prepareStatement("DELETE FROM process_steps WHERE planId = ? AND tenant_id = ?").use { ps ->
                ps.setString(1, id); ps.setString(2, tenantId); ps.executeUpdate()
            }
            c.prepareStatement("DELETE FROM process_plans WHERE id = ? AND tenant_id = ?").use { ps ->
                ps.setString(1, id); ps.setString(2, tenantId); ps.executeUpdate()
            }
            c.bumpVersion()
        }
    }

    // ===== 图纸 =====

    private fun readDrawings(c: Connection, tenantId: String): List<DrawingConfig> =
        c.prepareStatement("SELECT * FROM drawings WHERE tenant_id = ? ORDER BY filename").use { ps ->
            ps.setString(1, tenantId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        DrawingConfig(
                            id = rs.getString("id"),
                            filename = rs.getString("filename"),
                            layers = rs.getInt("layers"),
                            entities = rs.getInt("entities"),
                            source = rs.getString("source"),
                            dxfContent = rs.getString("dxfContent")
                        )
                    )
                }
            }
        }

    fun upsertDrawing(drawing: DrawingConfig, tenantId: String = DEFAULT_TENANT): DrawingConfig = withConn { c ->
        val id = drawing.id.ifBlank { "drawing-${UUID.randomUUID().toString().take(8)}" }
        val saved = drawing.copy(id = id, source = drawing.source.ifBlank { "SERVER" })
        c.tx {
            c.prepareStatement(
                """
                INSERT OR REPLACE INTO drawings(id, tenant_id, filename, layers, entities, source, dxfContent)
                VALUES(?,?,?,?,?,?,?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, id)
                ps.setString(2, tenantId)
                ps.setString(3, saved.filename)
                ps.setInt(4, saved.layers)
                ps.setInt(5, saved.entities)
                ps.setString(6, saved.source)
                ps.setString(7, saved.dxfContent)
                ps.executeUpdate()
            }
            c.bumpVersion()
        }
        saved
    }

    fun deleteDrawing(id: String, tenantId: String = DEFAULT_TENANT) = withConn { c ->
        c.tx {
            c.prepareStatement("DELETE FROM drawings WHERE id = ? AND tenant_id = ?").use { ps ->
                ps.setString(1, id); ps.setString(2, tenantId); ps.executeUpdate()
            }
            c.prepareStatement("DELETE FROM drawing_corrections WHERE drawingId = ? AND tenant_id = ?").use { ps ->
                ps.setString(1, id); ps.setString(2, tenantId); ps.executeUpdate()
            }
            c.bumpVersion()
        }
    }

    private fun readCorrections(c: Connection, tenantId: String): List<DrawingCorrectionConfig> =
        c.prepareStatement("SELECT * FROM drawing_corrections WHERE tenant_id = ?").use { ps ->
            ps.setString(1, tenantId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        DrawingCorrectionConfig(
                            drawingId = rs.getString("drawingId"),
                            scale = rs.getDouble("scale"),
                            offsetX = rs.getDouble("offsetX"),
                            offsetY = rs.getDouble("offsetY"),
                            rotationDeg = rs.getDouble("rotationDeg"),
                            mirrorX = rs.getInt("mirrorX") != 0,
                            mirrorY = rs.getInt("mirrorY") != 0,
                            unit = rs.getString("unit")
                        )
                    )
                }
            }
        }

    fun upsertCorrection(correction: DrawingCorrectionConfig, tenantId: String = DEFAULT_TENANT): DrawingCorrectionConfig = withConn { c ->
        c.tx {
            c.prepareStatement(
                """
                INSERT OR REPLACE INTO drawing_corrections(
                    drawingId, tenant_id, scale, offsetX, offsetY, rotationDeg, mirrorX, mirrorY, unit
                ) VALUES(?,?,?,?,?,?,?,?,?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, correction.drawingId)
                ps.setString(2, tenantId)
                ps.setDouble(3, correction.scale)
                ps.setDouble(4, correction.offsetX)
                ps.setDouble(5, correction.offsetY)
                ps.setDouble(6, correction.rotationDeg)
                ps.setInt(7, if (correction.mirrorX) 1 else 0)
                ps.setInt(8, if (correction.mirrorY) 1 else 0)
                ps.setString(9, correction.unit)
                ps.executeUpdate()
            }
            c.bumpVersion()
        }
        correction
    }

    // ===== 任务记录 =====

    fun tasks(limit: Int = 200, tenantId: String = DEFAULT_TENANT): List<TaskRecord> = withConn { c ->
        c.prepareStatement("SELECT * FROM task_history WHERE tenant_id = ? ORDER BY finishedAt DESC LIMIT ?").use { ps ->
            ps.setString(1, tenantId)
            ps.setInt(2, limit)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        TaskRecord(
                            id = rs.getString("id"),
                            partName = rs.getString("partName"),
                            planId = rs.getString("planId"),
                            status = rs.getString("status"),
                            durationSec = rs.getDouble("durationSec"),
                            operator = rs.getString("operator"),
                            startedAt = rs.getLong("startedAt"),
                            finishedAt = rs.getLong("finishedAt")
                        )
                    )
                }
            }
        }
    }

    fun upsertTask(task: TaskRecord, tenantId: String = DEFAULT_TENANT): TaskRecord = withConn { c ->
        val id = task.id.ifBlank { "task-${UUID.randomUUID().toString().take(8)}" }
        val saved = task.copy(id = id)
        c.prepareStatement(
            """
            INSERT OR REPLACE INTO task_history(
                id, tenant_id, partName, planId, status, durationSec, operator, startedAt, finishedAt
            ) VALUES(?,?,?,?,?,?,?,?,?)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, id)
            ps.setString(2, tenantId)
            ps.setString(3, saved.partName)
            ps.setString(4, saved.planId)
            ps.setString(5, saved.status)
            ps.setDouble(6, saved.durationSec)
            ps.setString(7, saved.operator)
            ps.setLong(8, saved.startedAt)
            ps.setLong(9, saved.finishedAt)
            ps.executeUpdate()
        }
        saved
    }

    // ===== 客户端状态缓存 =====

    fun latestStatus(): DeviceStatus? = withConn { c ->
        c.getMeta("last_status")?.let {
            runCatching { json.decodeFromString<DeviceStatus>(it) }.getOrNull()
        }
    }

    fun saveStatus(status: DeviceStatus, tenantId: String = DEFAULT_TENANT) = withConn { c ->
        c.putMeta("last_status", json.encodeToString(DeviceStatus.serializer(), status))
        // 同步更新对应设备的在线时间
        c.heartbeatOnly(status.deviceId, tenantId)
    }

    // ===== 设备注册 / 权限 / 管理员密码 =====

    private fun decodePermissions(raw: String): ClientPermissions =
        runCatching { json.decodeFromString<ClientPermissions>(raw) }.getOrDefault(ClientPermissions())

    /** 按硬件标识查设备（用于激活幂等判定）。 */
    fun findByHardwareId(hardwareId: String, tenantId: String = DEFAULT_TENANT): DeviceInfo? = withConn { c ->
        if (hardwareId.isBlank()) return@withConn null
        var id: String? = null
        c.prepareStatement("SELECT id FROM devices WHERE hardware_id = ? AND tenant_id = ?").use { ps ->
            ps.setString(1, hardwareId)
            ps.setString(2, tenantId)
            ps.executeQuery().use { rs -> if (rs.next()) id = rs.getString(1) }
        }
        id?.let { getDevice(it, tenantId) }
    }

    /**
     * 设备注册（激活）：按 hardwareId 幂等。
     *
     * - hardwareId 为空：保持旧行为，按 deviceId 新建/替换并签发新令牌；
     * - hardwareId 已存在：更新该设备的单位/联系人/电话/地区/服务地址/版本，
     *   保留原 id、注册时间与 device_token，返回更新后的设备信息；
     * - hardwareId 不存在：新建记录并签发 device_token。
     *
     * 不返回 409：客户端「清除数据」后本地令牌丢失，若继续拒绝会导致该设备
     * 永久无法使用；同一硬件标识重复激活本就属于同一台设备，幂等更新才是
     * 正确的正式流程。查询与写入在同一连接内完成，避免与唯一索引冲突。
     */
    fun registerDevice(req: RegisterRequest, tenantId: String = DEFAULT_TENANT): DeviceInfo = withConn { c ->
        val now = System.currentTimeMillis()
        if (req.hardwareId.isNotBlank()) {
            var existingId: String? = null
            var existingToken = ""
            var existingRegisteredAt = 0L
            var existingPerms = ClientPermissions()
            c.prepareStatement(
                "SELECT id, registered_at, permissions, device_token FROM devices WHERE hardware_id = ? AND tenant_id = ?"
            ).use { ps ->
                ps.setString(1, req.hardwareId)
                ps.setString(2, tenantId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        existingId = rs.getString("id")
                        existingRegisteredAt = rs.getLong("registered_at")
                        existingPerms = decodePermissions(rs.getString("permissions"))
                        existingToken = rs.getString("device_token").orEmpty()
                    }
                }
            }
            val id = existingId
            if (id != null) {
                c.prepareStatement(
                    """
                    UPDATE devices SET unit_name = ?, contact_name = ?, phone = ?,
                        province = ?, city = ?, server_address = ?, app_version = ?,
                        last_seen = ?, online = 1
                    WHERE id = ?
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, req.unitName)
                    ps.setString(2, req.contactName)
                    ps.setString(3, req.phone)
                    ps.setString(4, req.province)
                    ps.setString(5, req.city)
                    ps.setString(6, req.serverAddress)
                    ps.setString(7, req.appVersion)
                    ps.setLong(8, now)
                    ps.setString(9, id)
                    ps.executeUpdate()
                }
                return@withConn DeviceInfo(
                    id = id,
                    unitName = req.unitName,
                    contactName = req.contactName,
                    phone = req.phone,
                    province = req.province,
                    city = req.city,
                    serverAddress = req.serverAddress,
                    registeredAt = existingRegisteredAt,
                    lastSeen = now,
                    online = true,
                    permissions = existingPerms,
                    token = existingToken
                )
            }
        }
        val id = req.deviceId.ifBlank { "dev-${UUID.randomUUID().toString().take(8)}" }
        val existingPerms = c.getDeviceRaw(id, tenantId)?.let { decodePermissions(it) } ?: ClientPermissions()
        // 签发设备令牌：客户端后续用它访问配置/任务/状态接口
        val token = UUID.randomUUID().toString().replace("-", "")
        c.prepareStatement(
            """
            INSERT OR REPLACE INTO devices(
                id, tenant_id, unit_name, contact_name, phone, province, city, server_address,
                registered_at, last_seen, online, permissions, hardware_id, app_version, device_token
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, id)
            ps.setString(2, tenantId)
            ps.setString(3, req.unitName)
            ps.setString(4, req.contactName)
            ps.setString(5, req.phone)
            ps.setString(6, req.province)
            ps.setString(7, req.city)
            ps.setString(8, req.serverAddress)
            ps.setLong(9, now)
            ps.setLong(10, now)
            ps.setInt(11, 1)
            ps.setString(12, json.encodeToString(ClientPermissions.serializer(), existingPerms))
            ps.setString(13, req.hardwareId)
            ps.setString(14, req.appVersion)
            ps.setString(15, token)
            ps.executeUpdate()
        }
        DeviceInfo(
            id = id, unitName = req.unitName, contactName = req.contactName,
            phone = req.phone, province = req.province, city = req.city,
            serverAddress = req.serverAddress,
            registeredAt = now, lastSeen = now, online = true,
            permissions = existingPerms, token = token
        )
    }

    /** 后台修改设备信息（单位/联系人/电话/地区）。 */
    fun updateDeviceInfo(id: String, req: DeviceUpdateRequest, tenantId: String = DEFAULT_TENANT): Boolean = withConn { c ->
        c.prepareStatement(
            "UPDATE devices SET unit_name = ?, contact_name = ?, phone = ?, province = ?, city = ? WHERE id = ? AND tenant_id = ?"
        ).use { ps ->
            ps.setString(1, req.unitName)
            ps.setString(2, req.contactName)
            ps.setString(3, req.phone)
            ps.setString(4, req.province)
            ps.setString(5, req.city)
            ps.setString(6, id)
            ps.setString(7, tenantId)
            ps.executeUpdate() > 0
        }
    }

    /** 注销设备：删除后该设备才能重新注册。 */
    fun deleteDevice(id: String, tenantId: String = DEFAULT_TENANT): Boolean = withConn { c ->
        c.prepareStatement("DELETE FROM devices WHERE id = ? AND tenant_id = ?").use { ps ->
            ps.setString(1, id)
            ps.setString(2, tenantId)
            ps.executeUpdate() > 0
        }
    }

    fun getDevice(id: String, tenantId: String = DEFAULT_TENANT): DeviceInfo? = withConn { c ->
        val permsRaw = c.getDeviceRaw(id, tenantId) ?: return@withConn null
        var info: DeviceInfo? = null
        c.prepareStatement(
            "SELECT unit_name, contact_name, phone, province, city, server_address, registered_at, last_seen, online, device_token FROM devices WHERE id = ? AND tenant_id = ?"
        ).use { ps ->
            ps.setString(1, id)
            ps.setString(2, tenantId)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    info = DeviceInfo(
                        id = id,
                        unitName = rs.getString("unit_name"),
                        contactName = rs.getString("contact_name"),
                        phone = rs.getString("phone"),
                        province = rs.getString("province").orEmpty(),
                        city = rs.getString("city").orEmpty(),
                        serverAddress = rs.getString("server_address"),
                        registeredAt = rs.getLong("registered_at"),
                        lastSeen = rs.getLong("last_seen"),
                        online = isOnline(rs.getLong("last_seen")),
                        permissions = decodePermissions(permsRaw),
                        token = rs.getString("device_token") ?: ""
                    )
                }
            }
        }
        info
    }

    fun listDevices(tenantId: String = DEFAULT_TENANT): List<DeviceListItem> = withConn { c ->
        c.prepareStatement(
            "SELECT id, unit_name, contact_name, phone, province, city, server_address, registered_at, last_seen, online, app_version, permissions FROM devices WHERE tenant_id = ? ORDER BY registered_at DESC"
        ).use { ps ->
            ps.setString(1, tenantId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        DeviceListItem(
                            id = rs.getString("id"),
                            unitName = rs.getString("unit_name"),
                            contactName = rs.getString("contact_name"),
                            phone = rs.getString("phone"),
                            province = rs.getString("province").orEmpty(),
                            city = rs.getString("city").orEmpty(),
                            serverAddress = rs.getString("server_address"),
                            registeredAt = rs.getLong("registered_at"),
                            lastSeen = rs.getLong("last_seen"),
                            online = isOnline(rs.getLong("last_seen")),
                            appVersion = rs.getString("app_version"),
                            permissions = decodePermissions(rs.getString("permissions"))
                        )
                    )
                }
            }
        }
    }

    /** 按「单位 + 省份 + 城市」统计终端数量（后台客户维护用）。 */
    fun orgSummary(tenantId: String = DEFAULT_TENANT): List<OrgSummaryItem> = withConn { c ->
        c.prepareStatement(
            "SELECT unit_name, province, city, last_seen FROM devices WHERE tenant_id = ?"
        ).use { ps ->
            ps.setString(1, tenantId)
            ps.executeQuery().use { rs ->
                val groups = LinkedHashMap<Triple<String, String, String>, MutableList<Long>>()
                while (rs.next()) {
                    val key = Triple(
                        rs.getString("unit_name").orEmpty(),
                        rs.getString("province").orEmpty(),
                        rs.getString("city").orEmpty()
                    )
                    groups.getOrPut(key) { mutableListOf() }.add(rs.getLong("last_seen"))
                }
                groups.map { (key, lastSeenList) ->
                    OrgSummaryItem(
                        orgName = key.first,
                        province = key.second,
                        city = key.third,
                        deviceCount = lastSeenList.size,
                        onlineCount = lastSeenList.count { isOnline(it) },
                        lastSeen = lastSeenList.maxOrNull() ?: 0L
                    )
                }.sortedByDescending { it.deviceCount }
            }
        }
    }

    /** 在线判定：心跳周期 10 秒，25 秒内无心跳视为离线（按读取时实时计算）。 */
    private fun isOnline(lastSeen: Long): Boolean =
        lastSeen > 0 && System.currentTimeMillis() - lastSeen < 25_000L

    fun updateDevicePermissions(id: String, permissions: ClientPermissions, tenantId: String = DEFAULT_TENANT): Boolean = withConn { c ->
        val changed = c.prepareStatement("UPDATE devices SET permissions = ? WHERE id = ? AND tenant_id = ?").use { ps ->
            ps.setString(1, json.encodeToString(ClientPermissions.serializer(), permissions))
            ps.setString(2, id)
            ps.setString(3, tenantId)
            ps.executeUpdate()
        }
        changed > 0
    }

    /** 心跳：刷新在线时间，并顺带更新客户端版本号。 */
    fun heartbeat(id: String, appVersion: String = "", tenantId: String = DEFAULT_TENANT): DeviceInfo? = withConn { c ->
        val now = System.currentTimeMillis()
        if (appVersion.isNotBlank()) {
            c.prepareStatement("UPDATE devices SET last_seen = ?, online = 1, app_version = ? WHERE id = ? AND tenant_id = ?").use { ps ->
                ps.setLong(1, now); ps.setString(2, appVersion); ps.setString(3, id); ps.setString(4, tenantId); ps.executeUpdate()
            }
        } else {
            c.prepareStatement("UPDATE devices SET last_seen = ?, online = 1 WHERE id = ? AND tenant_id = ?").use { ps ->
                ps.setLong(1, now); ps.setString(2, id); ps.setString(3, tenantId); ps.executeUpdate()
            }
        }
        getDevice(id, tenantId)
    }

    // ===== 客户端版本发布（自动更新） =====

    /** 发布新版本 APK 的元信息（文件本身存放在磁盘 apk 目录）。 */
    fun publishAppVersion(info: AppVersionInfo) = withConn { c ->
        c.putMeta("app_version_code", info.versionCode.toString())
        c.putMeta("app_version_name", info.versionName)
        c.putMeta("app_changelog", info.changelog)
        c.putMeta("app_sha256", info.sha256)
        c.putMeta("app_size_bytes", info.sizeBytes.toString())
        c.putMeta("app_published_at", info.publishedAt.toString())
    }

    /** 当前发布的版本；从未发布返回 null。 */
    fun getAppVersion(): AppVersionInfo? = withConn { c ->
        val code = c.getMeta("app_version_code")?.toIntOrNull() ?: return@withConn null
        AppVersionInfo(
            versionCode = code,
            versionName = c.getMeta("app_version_name") ?: "",
            changelog = c.getMeta("app_changelog") ?: "",
            sha256 = c.getMeta("app_sha256") ?: "",
            sizeBytes = c.getMeta("app_size_bytes")?.toLongOrNull() ?: 0L,
            publishedAt = c.getMeta("app_published_at")?.toLongOrNull() ?: 0L
        )
    }

    private fun Connection.heartbeatOnly(id: String, tenantId: String) {
        if (id.isBlank()) return
        val now = System.currentTimeMillis()
        prepareStatement("UPDATE devices SET last_seen = ?, online = 1 WHERE id = ? AND tenant_id = ?").use { ps ->
            ps.setLong(1, now); ps.setString(2, id); ps.setString(3, tenantId); ps.executeUpdate()
        }
    }

    private fun Connection.getDeviceRaw(id: String, tenantId: String): String? =
        prepareStatement("SELECT permissions FROM devices WHERE id = ? AND tenant_id = ?").use { ps ->
            ps.setString(1, id)
            ps.setString(2, tenantId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    /** 设备令牌校验：注册设备签发的令牌，允许访问配置/任务/状态接口。 */
    fun isValidDeviceToken(token: String): Boolean = withConn { c ->
        if (token.isBlank()) return@withConn false
        c.prepareStatement("SELECT 1 FROM devices WHERE device_token = ? AND device_token != '' LIMIT 1").use { ps ->
            ps.setString(1, token)
            ps.executeQuery().use { rs -> rs.next() }
        }
    }

    /** 按设备令牌反查设备 id（设备令牌「仅可查询自身」鉴权用）；令牌无效返回 null。 */
    fun deviceIdByToken(token: String): String? = withConn { c ->
        if (token.isBlank()) return@withConn null
        c.prepareStatement("SELECT id FROM devices WHERE device_token = ? AND device_token != '' LIMIT 1").use { ps ->
            ps.setString(1, token)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    /** 设备令牌所属租户（路由层租户上下文校验用）；令牌无效返回 null。 */
    fun tenantIdOfDeviceToken(token: String): String? = withConn { c ->
        if (token.isBlank()) return@withConn null
        c.prepareStatement("SELECT tenant_id FROM devices WHERE device_token = ? AND device_token != '' LIMIT 1").use { ps ->
            ps.setString(1, token)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    /** 校验客户端传入的管理员密码。默认密码 123456，可在后台修改。 */
    fun verifyAdminPassword(password: String): Boolean = withConn { c ->
        val stored = c.getMeta("admin_password") ?: DEFAULT_ADMIN_PASSWORD
        stored == password
    }

    fun setAdminPassword(newPassword: String) = withConn { c ->
        c.putMeta("admin_password", newPassword)
    }

    fun adminUsername(): String = withConn { c ->
        c.getMeta("admin_username") ?: DEFAULT_ADMIN_USERNAME
    }

    fun setAdminUsername(newUsername: String) = withConn { c ->
        c.putMeta("admin_username", newUsername)
    }

    /** 校验后台登录账号密码。优先 users 表；默认租户下老库无对应用户时才回退到 meta 旧账号（兼容升级）。 */
    fun verifyAdminAccount(username: String, password: String, tenantId: String = DEFAULT_TENANT): Boolean {
        if (verifyUserAccount(username, password, tenantId) != null) return true
        // users 表中已存在该账号时不再回退，避免禁用/改密后的账号被 meta 旧口令绕过
        if (findUserByUsername(username, tenantId) != null) return false
        // meta 旧账号是私有化部署的服务器级回退，非默认租户禁用
        if (tenantId != DEFAULT_TENANT) return false
        return withConn { c ->
            val u = c.getMeta("admin_username") ?: DEFAULT_ADMIN_USERNAME
            val p = c.getMeta("admin_password") ?: DEFAULT_ADMIN_PASSWORD
            u == username && p == password
        }
    }

    const val DEFAULT_ADMIN_USERNAME = "admin"
    const val DEFAULT_ADMIN_PASSWORD = "123456"
    private const val LEGACY_DEFAULT_ADMIN_PASSWORD = "admin123"

    /**
     * R5 安全检查：默认管理口令是否仍在使用。
     * 满足任一即视为默认口令在用：meta 未改过密码，或任一超管账号仍能用 123456 登录。
     */
    fun isDefaultAdminCredentialInUse(): Boolean {
        // init() 会把默认密码写入 meta，因此按「值仍为默认」判断，而非判空
        val metaDefault = withConn { c ->
            val stored = c.getMeta("admin_password")
            stored == null || stored == DEFAULT_ADMIN_PASSWORD
        }
        if (metaDefault) return true
        val hashes = withConn { c ->
            c.prepareStatement("SELECT password_hash FROM users WHERE role = 'SUPER_ADMIN'").use { ps ->
                ps.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }
        return hashes.any { verifyPassword(DEFAULT_ADMIN_PASSWORD, it) }
    }

    // ===== 密码哈希（PBKDF2WithHmacSHA256）=====

    private const val PBKDF2_ITERATIONS = 120_000
    private const val PBKDF2_KEY_BITS = 256

    /** 生成 PBKDF2 密码哈希，存储格式：pbkdf2$120000$<saltBase64>$<hashBase64>。 */
    fun hashPassword(plain: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(plain, salt, PBKDF2_ITERATIONS)
        val encoder = Base64.getEncoder()
        return "pbkdf2\$$PBKDF2_ITERATIONS\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(hash)}"
    }

    /** 校验明文密码与存储哈希（常量时间比较）。 */
    fun verifyPassword(plain: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        return runCatching {
            val salt = Base64.getDecoder().decode(parts[2])
            val expected = Base64.getDecoder().decode(parts[3])
            MessageDigest.isEqual(expected, pbkdf2(plain, salt, iterations))
        }.getOrDefault(false)
    }

    private fun pbkdf2(plain: String, salt: ByteArray, iterations: Int): ByteArray {
        // 清空口令副本，减少明文在内存中的驻留时间
        val spec = PBEKeySpec(plain.toCharArray(), salt, iterations, PBKDF2_KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    // ===== 管理后台登录会话 =====

    /** 会话有效期：7 天。 */
    const val SESSION_TTL_MS = 7L * 24 * 3600 * 1000

    /** 登录成功创建会话，返回 token。 */
    fun createSession(username: String, userId: String = "", tenantId: String = DEFAULT_TENANT): String = withConn { c ->
        // 顺手清理过期会话
        c.prepareStatement("DELETE FROM admin_sessions WHERE expires_at < ?").use { ps ->
            ps.setLong(1, System.currentTimeMillis()); ps.executeUpdate()
        }
        val token = "sess-" + UUID.randomUUID().toString().replace("-", "")
        val now = System.currentTimeMillis()
        c.prepareStatement(
            "INSERT INTO admin_sessions(token, tenant_id, username, created_at, expires_at, user_id) VALUES(?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, token)
            ps.setString(2, tenantId)
            ps.setString(3, username)
            ps.setLong(4, now)
            ps.setLong(5, now + SESSION_TTL_MS)
            ps.setString(6, userId)
            ps.executeUpdate()
        }
        token
    }

    /** 校验会话 token 是否有效（未过期）。 */
    fun isValidSession(token: String): Boolean = withConn { c ->
        c.prepareStatement("SELECT expires_at FROM admin_sessions WHERE token = ?").use { ps ->
            ps.setString(1, token)
            ps.executeQuery().use { rs ->
                rs.next() && rs.getLong(1) >= System.currentTimeMillis()
            }
        }
    }

    /** 登出：删除会话。 */
    fun deleteSession(token: String) = withConn { c ->
        c.prepareStatement("DELETE FROM admin_sessions WHERE token = ?").use { ps ->
            ps.setString(1, token); ps.executeUpdate()
        }
    }

    // ===== 用户账号与权限 =====

    private data class UserRow(val user: UserDto, val passwordHash: String)

    private fun ResultSet.getUserRow(): UserRow = UserRow(
        user = UserDto(
            id = getString("id"),
            username = getString("username"),
            displayName = getString("display_name"),
            role = getString("role"),
            permissions = decodePermissionList(getString("permissions")),
            enabled = getInt("enabled") != 0,
            createdAt = getLong("created_at"),
            lastLoginAt = getLong("last_login_at")
        ),
        passwordHash = getString("password_hash")
    )

    private fun decodePermissionList(raw: String): List<String> =
        runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())

    private fun encodePermissionList(list: List<String>): String = json.encodeToString(list)

    fun listUsers(tenantId: String = DEFAULT_TENANT): List<UserDto> = withConn { c ->
        c.prepareStatement("SELECT * FROM users WHERE tenant_id = ? ORDER BY created_at, username").use { ps ->
            ps.setString(1, tenantId)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getUserRow().user) }
            }
        }
    }

    fun findUserByUsername(username: String, tenantId: String = DEFAULT_TENANT): UserDto? = withConn { c ->
        c.prepareStatement("SELECT * FROM users WHERE username = ? AND tenant_id = ?").use { ps ->
            ps.setString(1, username)
            ps.setString(2, tenantId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getUserRow().user else null }
        }
    }

    fun findUserById(id: String, tenantId: String = DEFAULT_TENANT): UserDto? = withConn { c ->
        c.prepareStatement("SELECT * FROM users WHERE id = ? AND tenant_id = ?").use { ps ->
            ps.setString(1, id)
            ps.setString(2, tenantId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getUserRow().user else null }
        }
    }

    fun createUser(
        username: String,
        password: String,
        displayName: String,
        role: String,
        permissions: List<String>,
        tenantId: String = DEFAULT_TENANT
    ): UserDto {
        val name = username.trim()
        require(name.isNotEmpty()) { "用户名不能为空" }
        // 未显式指定权限时按角色默认权限填充（ADR-0003）
        val effectivePermissions = permissions.ifEmpty { defaultPermissionsForRole(role) }
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        withConn { c ->
            val exists = c.prepareStatement("SELECT 1 FROM users WHERE username = ? AND tenant_id = ?").use { ps ->
                ps.setString(1, name)
                ps.setString(2, tenantId)
                ps.executeQuery().use { rs -> rs.next() }
            }
            if (exists) throw IllegalArgumentException("用户名已存在")
            try {
                c.prepareStatement(
                    """
                    INSERT INTO users(id, tenant_id, username, password_hash, display_name, role, permissions, enabled, created_at, last_login_at)
                    VALUES(?,?,?,?,?,?,?,?,?,0)
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, id)
                    ps.setString(2, tenantId)
                    ps.setString(3, name)
                    ps.setString(4, hashPassword(password))
                    ps.setString(5, displayName.ifBlank { name })
                    ps.setString(6, role)
                    ps.setString(7, encodePermissionList(effectivePermissions))
                    ps.setInt(8, 1)
                    ps.setLong(9, now)
                    ps.executeUpdate()
                }
            } catch (e: java.sql.SQLException) {
                // 并发创建时唯一索引冲突同样按「用户名已存在」处理
                if (e.message?.contains("UNIQUE", ignoreCase = true) == true) {
                    throw IllegalArgumentException("用户名已存在")
                }
                throw e
            }
        }
        return findUserById(id, tenantId) ?: throw IllegalStateException("创建用户失败")
    }

    fun updateUser(
        id: String,
        displayName: String,
        role: String,
        permissions: List<String>,
        enabled: Boolean,
        tenantId: String = DEFAULT_TENANT
    ): UserDto? {
        val changed = withConn { c ->
            c.prepareStatement(
                "UPDATE users SET display_name = ?, role = ?, permissions = ?, enabled = ? WHERE id = ? AND tenant_id = ?"
            ).use { ps ->
                ps.setString(1, displayName)
                ps.setString(2, role)
                ps.setString(3, encodePermissionList(permissions))
                ps.setInt(4, if (enabled) 1 else 0)
                ps.setString(5, id)
                ps.setString(6, tenantId)
                ps.executeUpdate()
            }
        }
        return if (changed > 0) findUserById(id, tenantId) else null
    }

    fun deleteUser(id: String, tenantId: String = DEFAULT_TENANT): Boolean = withConn { c ->
        // 同步清理该用户的会话（含老版本 user_id 为空、仅按用户名关联的会话）
        c.prepareStatement(
            "DELETE FROM admin_sessions WHERE user_id = ? OR (user_id = '' AND username = (SELECT username FROM users WHERE id = ? AND tenant_id = ?))"
        ).use { ps ->
            ps.setString(1, id)
            ps.setString(2, id)
            ps.setString(3, tenantId)
            ps.executeUpdate()
        }
        c.prepareStatement("DELETE FROM users WHERE id = ? AND tenant_id = ?").use { ps ->
            ps.setString(1, id)
            ps.setString(2, tenantId)
            ps.executeUpdate() > 0
        }
    }

    /** 删除某用户的全部会话（改密后强制重新登录）。 */
    fun deleteUserSessions(userId: String, username: String): Int = withConn { c ->
        c.prepareStatement(
            "DELETE FROM admin_sessions WHERE user_id = ? OR (user_id = '' AND username = ?)"
        ).use { ps ->
            ps.setString(1, userId)
            ps.setString(2, username)
            ps.executeUpdate()
        }
    }

    fun resetUserPassword(id: String, newPassword: String, tenantId: String = DEFAULT_TENANT): Boolean = withConn { c ->
        c.prepareStatement("UPDATE users SET password_hash = ? WHERE id = ? AND tenant_id = ?").use { ps ->
            ps.setString(1, hashPassword(newPassword))
            ps.setString(2, id)
            ps.setString(3, tenantId)
            ps.executeUpdate() > 0
        }
    }

    fun changeOwnPassword(id: String, oldPassword: String, newPassword: String, tenantId: String = DEFAULT_TENANT): Boolean {
        val row = withConn { c ->
            c.prepareStatement("SELECT * FROM users WHERE id = ? AND tenant_id = ?").use { ps ->
                ps.setString(1, id)
                ps.setString(2, tenantId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getUserRow() else null }
            }
        } ?: return false
        if (!verifyPassword(oldPassword, row.passwordHash)) return false
        return resetUserPassword(id, newPassword, tenantId)
    }

    fun countSuperAdmins(tenantId: String = DEFAULT_TENANT): Int = withConn { c ->
        c.prepareStatement("SELECT COUNT(*) FROM users WHERE role = 'SUPER_ADMIN' AND tenant_id = ?").use { ps ->
            ps.setString(1, tenantId)
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    /** 账号密码校验（仅启用用户），成功后刷新最后登录时间。 */
    fun verifyUserAccount(username: String, password: String, tenantId: String = DEFAULT_TENANT): SessionUser? {
        val row = withConn { c ->
            c.prepareStatement("SELECT * FROM users WHERE username = ? AND tenant_id = ?").use { ps ->
                ps.setString(1, username)
                ps.setString(2, tenantId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getUserRow() else null }
            }
        } ?: return null
        val user = row.user
        if (!user.enabled || !verifyPassword(password, row.passwordHash)) return null
        withConn { c ->
            c.prepareStatement("UPDATE users SET last_login_at = ? WHERE id = ? AND tenant_id = ?").use { ps ->
                ps.setLong(1, System.currentTimeMillis())
                ps.setString(2, user.id)
                ps.setString(3, tenantId)
                ps.executeUpdate()
            }
        }
        return SessionUser(user.id, user.username, user.displayName, user.role, user.permissions, true, tenantId)
    }

    /** 会话 token → 关联用户；老会话 user_id 为空时按用户名反查（均在会话所属租户内）。 */
    fun getSessionUser(token: String): SessionUser? {
        if (token.isBlank()) return null
        val row = withConn { c ->
            c.prepareStatement(
                "SELECT user_id, username, tenant_id FROM admin_sessions WHERE token = ? AND expires_at >= ?"
            ).use { ps ->
                ps.setString(1, token)
                ps.setLong(2, System.currentTimeMillis())
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        Triple(
                            rs.getString("user_id"),
                            rs.getString("username"),
                            rs.getString("tenant_id")
                        )
                    } else {
                        null
                    }
                }
            }
        } ?: return null
        val tenant = normalizeTenant(row.third)
        val user = if (row.first.isNotBlank()) findUserById(row.first, tenant) else findUserByUsername(row.second, tenant)
        if (user == null || !user.enabled) return null
        return SessionUser(user.id, user.username, user.displayName, user.role, user.permissions, user.enabled, tenant)
    }

    /** 兼容旧「管理员账号密码」设置：同步更新种子超管账号（固定默认租户），避免旧口令继续可用。 */
    fun syncLegacyAdminAccount(oldUsername: String, newUsername: String, newPassword: String): Boolean {
        if (oldUsername.isBlank()) return false
        return withConn { c ->
            c.prepareStatement(
                "UPDATE users SET username = ?, password_hash = ? WHERE role = 'SUPER_ADMIN' AND username = ? AND tenant_id = ?"
            ).use { ps ->
                ps.setString(1, newUsername)
                ps.setString(2, hashPassword(newPassword))
                ps.setString(3, oldUsername)
                ps.setString(4, DEFAULT_TENANT)
                ps.executeUpdate() > 0
            }
        }
    }

    // ===== 通用 =====

    private fun deleteById(table: String, id: String, tenantId: String) = withConn { c ->
        c.tx {
            c.prepareStatement("DELETE FROM $table WHERE id = ? AND tenant_id = ?").use { ps ->
                ps.setString(1, id)
                ps.setString(2, tenantId)
                ps.executeUpdate()
            }
            c.bumpVersion()
        }
    }

    // ===== 种子数据 =====

    private fun seedIfEmpty() {
        withConn { c ->
            val seeded = c.getMeta("seeded")
            val toolCount = c.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM tools").use { rs -> rs.next(); rs.getInt(1) }
            }
            if (seeded == "1" || toolCount > 0) return@withConn
            c.tx {
                c.putMeta("config_version", "1")
                c.putMeta("seeded", "1")
                c.prepareStatement("INSERT OR REPLACE INTO device_config(id, json) VALUES(1, ?)").use { ps ->
                    ps.setString(1, json.encodeToString(DeviceConfig.serializer(), DeviceConfig()))
                    ps.executeUpdate()
                }
                // 直接调用各 upsert 会打开新连接，这里在同一个事务内写入
                seedTools(c)
                seedMaterials(c)
                seedParts(c)
                seedPlans(c)
                seedDrawings(c)
                seedTasks(c)
            }
        }
    }

    /** 空用户表时，把 meta 中的管理员账号迁移为 SUPER_ADMIN，保证老账号仍可登录。 */
    private fun seedUsersIfEmpty() {
        withConn { c ->
            val count = c.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM users").use { rs -> rs.next(); rs.getInt(1) }
            }
            if (count > 0) return@withConn
            val username = c.getMeta("admin_username") ?: DEFAULT_ADMIN_USERNAME
            val password = c.getMeta("admin_password") ?: DEFAULT_ADMIN_PASSWORD
            c.prepareStatement(
                """
                INSERT INTO users(id, tenant_id, username, password_hash, display_name, role, permissions, enabled, created_at, last_login_at)
                VALUES(?,?,?,?,?,?,?,?,?,0)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, UUID.randomUUID().toString())
                ps.setString(2, DEFAULT_TENANT)
                ps.setString(3, username)
                ps.setString(4, hashPassword(password))
                ps.setString(5, "超级管理员")
                ps.setString(6, "SUPER_ADMIN")
                ps.setString(7, "[]")
                ps.setInt(8, 1)
                ps.setLong(9, System.currentTimeMillis())
                ps.executeUpdate()
            }
        }
    }

    private fun seedTools(c: Connection) {
        val tools = listOf(
            ToolConfig("srv-t1", 1, "TURNING", "外圆车刀 T01", "CARBIDE", 0.0, 120.0, 3000, 500.0, "NEW", 0),
            ToolConfig("srv-t2", 2, "FINISH_TURNING", "精车刀 T02", "CARBIDE", 0.0, 100.0, 4000, 300.0, "LIGHT", 15),
            ToolConfig("srv-t3", 3, "GROOVING", "切槽刀 T03", "CARBIDE", 3.0, 80.0, 2500, 200.0, "NEW", 0),
            ToolConfig("srv-t4", 4, "THREADING", "60°螺纹刀 T04", "HSS", 0.0, 80.0, 2000, 100.0, "LIGHT", 28),
            ToolConfig("srv-t5", 5, "DRILLING", "φ10 钻头 T05", "HSS", 10.0, 150.0, 1500, 150.0, "MODERATE", 120)
        )
        tools.forEach { t ->
            c.prepareStatement(
                """
                INSERT OR REPLACE INTO tools(
                    id, tenant_id, toolNumber, toolType, name, material, nominalDiameterMm, lengthMm,
                    maxRpm, maxFeedMmPerMin, wearStatus, usageCount, notes
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, t.id); ps.setString(2, DEFAULT_TENANT); ps.setInt(3, t.toolNumber)
                ps.setString(4, t.toolType); ps.setString(5, t.name); ps.setString(6, t.material)
                ps.setDouble(7, t.nominalDiameterMm); ps.setDouble(8, t.lengthMm); ps.setInt(9, t.maxRpm)
                ps.setDouble(10, t.maxFeedMmPerMin); ps.setString(11, t.wearStatus); ps.setInt(12, t.usageCount)
                ps.setString(13, t.notes)
                ps.executeUpdate()
            }
        }
    }

    private fun seedMaterials(c: Connection) {
        val materials = listOf(
            MaterialConfig("srv-m1", "45# 钢", "GB/T 711", 7.85, "HB229", 150.0, 0.2, 3.0, true),
            MaterialConfig("srv-m2", "40Cr 合金钢", "GB/T 3077", 7.9, "HB217", 120.0, 0.18, 2.5, true),
            MaterialConfig("srv-m3", "HT200 铸铁", "GB/T 9439", 7.2, "HB170", 100.0, 0.25, 2.0, true),
            MaterialConfig("srv-m4", "6061 铝合金", "GB/T 3191", 2.7, "HB95", 400.0, 0.3, 5.0, false)
        )
        materials.forEach { m ->
            c.prepareStatement(
                """
                INSERT OR REPLACE INTO materials(
                    id, tenant_id, name, code, density, hardness, recommendedCuttingSpeedMs,
                    recommendedFeedPerRev, recommendedDepthMm, enabled, notes
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, m.id); ps.setString(2, DEFAULT_TENANT); ps.setString(3, m.name)
                ps.setString(4, m.code); ps.setDouble(5, m.density); ps.setString(6, m.hardness)
                ps.setDouble(7, m.recommendedCuttingSpeedMs); ps.setDouble(8, m.recommendedFeedPerRev)
                ps.setDouble(9, m.recommendedDepthMm); ps.setInt(10, if (m.enabled) 1 else 0)
                ps.setString(11, m.notes)
                ps.executeUpdate()
            }
        }
    }

    private fun seedParts(c: Connection) {
        val parts = listOf(
            PartConfig("srv-p1", "主轴接套", "srv-m1", "CNC 自动车削首件"),
            PartConfig("srv-p2", "液压阀芯", "srv-m2", "精密车削"),
            PartConfig("srv-p3", "轴承套", "srv-m3", "批量生产")
        )
        parts.forEach { p ->
            c.prepareStatement("INSERT OR REPLACE INTO parts(id, tenant_id, name, materialId, notes) VALUES(?,?,?,?,?)").use { ps ->
                ps.setString(1, p.id); ps.setString(2, DEFAULT_TENANT); ps.setString(3, p.name)
                ps.setString(4, p.materialId); ps.setString(5, p.notes)
                ps.executeUpdate()
            }
        }
        val blanks = listOf(
            BlankConfig("srv-b1", "srv-p1", 80.0, 120.0, "srv-m1", "CHUCK"),
            BlankConfig("srv-b2", "srv-p2", 40.0, 80.0, "srv-m2", "COLLET")
        )
        blanks.forEach { b ->
            c.prepareStatement(
                "INSERT OR REPLACE INTO blanks(id, tenant_id, partId, diameterMm, lengthMm, materialId, clampType) VALUES(?,?,?,?,?,?,?)"
            ).use { ps ->
                ps.setString(1, b.id); ps.setString(2, DEFAULT_TENANT); ps.setString(3, b.partId)
                ps.setDouble(4, b.diameterMm); ps.setDouble(5, b.lengthMm); ps.setString(6, b.materialId)
                ps.setString(7, b.clampType)
                ps.executeUpdate()
            }
        }
    }

    private fun seedPlans(c: Connection) {
        val plans = listOf(
            ProcessPlanConfig(
                id = "srv-pp1", name = "主轴接套 标准车削", partId = "srv-p1",
                stockDiameterMm = 100.0, stockLengthMm = 130.0, description = "粗车 + 精车 + 切槽",
                steps = listOf(
                    ProcessStepConfig("srv-ps1", "srv-pp1", 0, "ROUGH_TURNING", "srv-t1", 150.0, 1200, 0.2, 5.0, 1.0),
                    ProcessStepConfig("srv-ps2", "srv-pp1", 1, "FINISH_TURNING", "srv-t2", 180.0, 1500, 0.1, 1.0, 0.0),
                    ProcessStepConfig("srv-ps3", "srv-pp1", 2, "GROOVING", "srv-t3", 100.0, 800, 0.15, 3.0, 0.0)
                )
            ),
            ProcessPlanConfig(
                id = "srv-pp2", name = "液压阀芯 精车", partId = "srv-p2",
                stockDiameterMm = 50.0, stockLengthMm = 90.0, description = "精车为主",
                steps = listOf(
                    ProcessStepConfig("srv-ps4", "srv-pp2", 0, "ROUGH_TURNING", "srv-t1", 120.0, 1000, 0.18, 3.0, 0.5),
                    ProcessStepConfig("srv-ps5", "srv-pp2", 1, "FINISH_TURNING", "srv-t2", 200.0, 1800, 0.08, 0.5, 0.0)
                )
            )
        )
        plans.forEach { p ->
            c.prepareStatement(
                "INSERT OR REPLACE INTO process_plans(id, tenant_id, name, partId, stockDiameterMm, stockLengthMm, description) VALUES(?,?,?,?,?,?,?)"
            ).use { ps ->
                ps.setString(1, p.id); ps.setString(2, DEFAULT_TENANT); ps.setString(3, p.name)
                ps.setString(4, p.partId); ps.setDouble(5, p.stockDiameterMm); ps.setDouble(6, p.stockLengthMm)
                ps.setString(7, p.description)
                ps.executeUpdate()
            }
            p.steps.forEach { s ->
                c.prepareStatement(
                    """
                    INSERT OR REPLACE INTO process_steps(
                        id, tenant_id, planId, orderIndex, type, toolId, cuttingSpeedMmPerMin, spindleRpm,
                        feedMmPerRev, depthOfCutMm, finishAllowanceMm, approachDistanceMm,
                        retractDistanceMm, safetyDistanceMm, enabled, notes
                    ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, s.id); ps.setString(2, DEFAULT_TENANT); ps.setString(3, s.planId)
                    ps.setInt(4, s.orderIndex); ps.setString(5, s.type); ps.setString(6, s.toolId)
                    ps.setDouble(7, s.cuttingSpeedMmPerMin); ps.setInt(8, s.spindleRpm)
                    ps.setDouble(9, s.feedMmPerRev); ps.setDouble(10, s.depthOfCutMm)
                    ps.setDouble(11, s.finishAllowanceMm); ps.setDouble(12, s.approachDistanceMm)
                    ps.setDouble(13, s.retractDistanceMm); ps.setDouble(14, s.safetyDistanceMm)
                    ps.setInt(15, if (s.enabled) 1 else 0); ps.setString(16, s.notes)
                    ps.executeUpdate()
                }
            }
        }
    }

    private fun seedDrawings(c: Connection) {
        val dxf = """
            0
            SECTION
            2
            ENTITIES
            0
            LWPOLYLINE
            8
            OUTLINE
            90
            4
            70
            1
            10
            20.0
            20
            10.0
            10
            80.0
            20
            10.0
            10
            80.0
            20
            50.0
            10
            20.0
            20
            50.0
            0
            ENDSEC
            0
            EOF
        """.trimIndent()
        val drawings = listOf(
            DrawingConfig("srv-d1", "主轴接套.dxf", 1, 5, "SERVER", dxf),
            DrawingConfig("srv-d2", "液压阀芯.dxf", 1, 4, "SERVER", dxf)
        )
        drawings.forEach { d ->
            c.prepareStatement("INSERT OR REPLACE INTO drawings(id, tenant_id, filename, layers, entities, source, dxfContent) VALUES(?,?,?,?,?,?,?)").use { ps ->
                ps.setString(1, d.id); ps.setString(2, DEFAULT_TENANT); ps.setString(3, d.filename)
                ps.setInt(4, d.layers); ps.setInt(5, d.entities); ps.setString(6, d.source)
                ps.setString(7, d.dxfContent)
                ps.executeUpdate()
            }
        }
        c.prepareStatement(
            "INSERT OR REPLACE INTO drawing_corrections(drawingId, tenant_id, scale, offsetX, offsetY, rotationDeg, mirrorX, mirrorY, unit) VALUES(?,?,?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, "srv-d1"); ps.setString(2, DEFAULT_TENANT); ps.setDouble(3, 1.0)
            ps.setDouble(4, 0.0); ps.setDouble(5, 0.0); ps.setDouble(6, 0.0); ps.setInt(7, 0)
            ps.setInt(8, 0); ps.setString(9, "MM")
            ps.executeUpdate()
        }
    }

    private fun seedTasks(c: Connection) {
        val now = System.currentTimeMillis()
        val tasks = listOf(
            TaskRecord("srv-h1", "主轴接套", "srv-pp1", "SUCCESS", 312.5, "操作员A", now - 7_200_000, now - 6_887_500),
            TaskRecord("srv-h2", "液压阀芯", "srv-pp2", "FAILED", 120.3, "操作员B", now - 172_800_000, now - 172_679_700)
        )
        tasks.forEach { t ->
            c.prepareStatement(
                "INSERT OR REPLACE INTO task_history(id, tenant_id, partName, planId, status, durationSec, operator, startedAt, finishedAt) VALUES(?,?,?,?,?,?,?,?,?)"
            ).use { ps ->
                ps.setString(1, t.id); ps.setString(2, DEFAULT_TENANT); ps.setString(3, t.partName)
                ps.setString(4, t.planId); ps.setString(5, t.status); ps.setDouble(6, t.durationSec)
                ps.setString(7, t.operator); ps.setLong(8, t.startedAt); ps.setLong(9, t.finishedAt)
                ps.executeUpdate()
            }
        }
    }

    // ===== 操作日志 =====

    /** 批量插入设备上传的操作日志。 */
    fun insertLogs(deviceId: String, logs: List<OperationLogDto>, tenantId: String = DEFAULT_TENANT) = withConn { c ->
        if (logs.isEmpty()) return@withConn
        c.prepareStatement(
            "INSERT INTO operation_logs(tenant_id, device_id, timestamp, level, category, message, task_id, details) VALUES(?,?,?,?,?,?,?,?)"
        ).use { ps ->
            logs.forEach { log ->
                ps.setString(1, tenantId)
                ps.setString(2, deviceId)
                ps.setLong(3, log.timestamp)
                ps.setString(4, log.level)
                ps.setString(5, log.category)
                ps.setString(6, log.message)
                if (log.taskId != null) ps.setString(7, log.taskId) else ps.setNull(7, java.sql.Types.VARCHAR)
                if (log.details != null) ps.setString(8, log.details) else ps.setNull(8, java.sql.Types.VARCHAR)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    /** 查询操作日志；可选按 deviceId 过滤，按 timestamp DESC 返回。 */
    fun listLogs(deviceId: String? = null, limit: Int = 500, tenantId: String = DEFAULT_TENANT): List<OperationLogDto> = withConn { c ->
        val sql = if (deviceId.isNullOrBlank()) {
            "SELECT timestamp, level, category, message, task_id, details FROM operation_logs WHERE tenant_id = ? ORDER BY timestamp DESC LIMIT ?"
        } else {
            "SELECT timestamp, level, category, message, task_id, details FROM operation_logs WHERE tenant_id = ? AND device_id = ? ORDER BY timestamp DESC LIMIT ?"
        }
        c.prepareStatement(sql).use { ps ->
            ps.setString(1, tenantId)
            if (!deviceId.isNullOrBlank()) ps.setString(2, deviceId)
            ps.setInt(if (deviceId.isNullOrBlank()) 2 else 3, limit)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            OperationLogDto(
                                timestamp = rs.getLong("timestamp"),
                                level = rs.getString("level"),
                                category = rs.getString("category"),
                                message = rs.getString("message"),
                                taskId = rs.getString("task_id"),
                                details = rs.getString("details")
                            )
                        )
                    }
                }
            }
        }
    }

    /** 清理指定设备（或全部）早于 olderThanMs 的日志，返回删除行数。 */
    fun deleteLogsOlderThan(deviceId: String?, olderThanMs: Long, tenantId: String = DEFAULT_TENANT): Int = withConn { c ->
        val sql = if (deviceId.isNullOrBlank()) {
            "DELETE FROM operation_logs WHERE tenant_id = ? AND timestamp < ?"
        } else {
            "DELETE FROM operation_logs WHERE tenant_id = ? AND device_id = ? AND timestamp < ?"
        }
        c.prepareStatement(sql).use { ps ->
            ps.setString(1, tenantId)
            if (!deviceId.isNullOrBlank()) ps.setString(2, deviceId)
            ps.setLong(if (deviceId.isNullOrBlank()) 2 else 3, olderThanMs)
            ps.executeUpdate()
        }
    }
}
