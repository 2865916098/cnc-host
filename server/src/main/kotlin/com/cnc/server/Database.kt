package com.cnc.server

import kotlinx.serialization.json.Json
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * SQLite 数据访问层。
 *
 * 使用 JDBC 直接访问，避免引入 ORM 带来的额外构建复杂度。
 * 所有配置写操作都会递增 config_version，客户端据此做增量同步判断。
 */
object Db {

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
                    CREATE TABLE IF NOT EXISTS devices(
                        id TEXT PRIMARY KEY,
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
                        username TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }
        seedIfEmpty()
        // 旧库迁移：为 devices 表补充 hardware_id / app_version 列（已存在则忽略）
        withConn { c ->
            c.createStatement().use { st ->
                runCatching { st.executeUpdate("ALTER TABLE devices ADD COLUMN hardware_id TEXT NOT NULL DEFAULT ''") }
                runCatching { st.executeUpdate("ALTER TABLE devices ADD COLUMN app_version TEXT NOT NULL DEFAULT ''") }
                // 同一台设备（按硬件标识）只能注册一次（必须在列迁移之后创建）
                st.executeUpdate(
                    "CREATE UNIQUE INDEX IF NOT EXISTS idx_devices_hardware_id ON devices(hardware_id) WHERE hardware_id != ''"
                )
            }
        }
        // 管理员账号/密码：首次启动写默认值，已存在则保留后台设置
        withConn { c ->
            if (c.getMeta("admin_username") == null) c.putMeta("admin_username", DEFAULT_ADMIN_USERNAME)
            val pwd = c.getMeta("admin_password")
            // 旧版本默认密码 admin123 自动迁移为新默认 123456；用户改过的密码保留
            if (pwd == null || pwd == LEGACY_DEFAULT_ADMIN_PASSWORD) c.putMeta("admin_password", DEFAULT_ADMIN_PASSWORD)
        }
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

    /** 一次读取完整配置。 */
    fun snapshot(): ConfigSnapshot = withConn { c ->
        ConfigSnapshot(
            version = c.getMeta("config_version")?.toLongOrNull() ?: 1L,
            device = readDevice(c),
            tools = readTools(c),
            materials = readMaterials(c),
            parts = readParts(c),
            blanks = readBlanks(c),
            plans = readPlans(c),
            drawings = readDrawings(c),
            drawingCorrections = readCorrections(c)
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

    private fun readTools(c: Connection): List<ToolConfig> =
        c.prepareStatement("SELECT * FROM tools ORDER BY toolNumber").use { ps ->
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

    fun upsertTool(tool: ToolConfig): ToolConfig = withConn { c ->
        val id = tool.id.ifBlank { "tool-${UUID.randomUUID().toString().take(8)}" }
        val saved = tool.copy(id = id)
        c.tx {
            c.prepareStatement(
                """
                INSERT OR REPLACE INTO tools(
                    id, toolNumber, toolType, name, material, nominalDiameterMm, lengthMm,
                    maxRpm, maxFeedMmPerMin, wearStatus, usageCount, notes
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, id)
                ps.setInt(2, saved.toolNumber)
                ps.setString(3, saved.toolType)
                ps.setString(4, saved.name)
                ps.setString(5, saved.material)
                ps.setDouble(6, saved.nominalDiameterMm)
                ps.setDouble(7, saved.lengthMm)
                ps.setInt(8, saved.maxRpm)
                ps.setDouble(9, saved.maxFeedMmPerMin)
                ps.setString(10, saved.wearStatus)
                ps.setInt(11, saved.usageCount)
                ps.setString(12, saved.notes)
                ps.executeUpdate()
            }
            c.bumpVersion()
        }
        saved
    }

    fun deleteTool(id: String) = deleteById("tools", id)

    // ===== 材料 =====

    private fun readMaterials(c: Connection): List<MaterialConfig> =
        c.prepareStatement("SELECT * FROM materials ORDER BY name").use { ps ->
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

    fun upsertMaterial(material: MaterialConfig): MaterialConfig = withConn { c ->
        val id = material.id.ifBlank { "mat-${UUID.randomUUID().toString().take(8)}" }
        val saved = material.copy(id = id)
        c.tx {
            c.prepareStatement(
                """
                INSERT OR REPLACE INTO materials(
                    id, name, code, density, hardness, recommendedCuttingSpeedMs,
                    recommendedFeedPerRev, recommendedDepthMm, enabled, notes
                ) VALUES(?,?,?,?,?,?,?,?,?,?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, id)
                ps.setString(2, saved.name)
                ps.setString(3, saved.code)
                ps.setDouble(4, saved.density)
                ps.setString(5, saved.hardness)
                ps.setDouble(6, saved.recommendedCuttingSpeedMs)
                ps.setDouble(7, saved.recommendedFeedPerRev)
                ps.setDouble(8, saved.recommendedDepthMm)
                ps.setInt(9, if (saved.enabled) 1 else 0)
                ps.setString(10, saved.notes)
                ps.executeUpdate()
            }
            c.bumpVersion()
        }
        saved
    }

    fun deleteMaterial(id: String) = deleteById("materials", id)

    // ===== 零件 / 毛坯 =====

    private fun readParts(c: Connection): List<PartConfig> =
        c.prepareStatement("SELECT * FROM parts ORDER BY name").use { ps ->
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

    fun upsertPart(part: PartConfig): PartConfig = withConn { c ->
        val id = part.id.ifBlank { "part-${UUID.randomUUID().toString().take(8)}" }
        val saved = part.copy(id = id)
        c.tx {
            c.prepareStatement(
                "INSERT OR REPLACE INTO parts(id, name, materialId, notes) VALUES(?,?,?,?)"
            ).use { ps ->
                ps.setString(1, id)
                ps.setString(2, saved.name)
                ps.setString(3, saved.materialId)
                ps.setString(4, saved.notes)
                ps.executeUpdate()
            }
            c.bumpVersion()
        }
        saved
    }

    fun deletePart(id: String) = deleteById("parts", id)

    private fun readBlanks(c: Connection): List<BlankConfig> =
        c.prepareStatement("SELECT * FROM blanks").use { ps ->
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

    fun upsertBlank(blank: BlankConfig): BlankConfig = withConn { c ->
        val id = blank.id.ifBlank { "blank-${UUID.randomUUID().toString().take(8)}" }
        val saved = blank.copy(id = id)
        c.tx {
            c.prepareStatement(
                """
                INSERT OR REPLACE INTO blanks(id, partId, diameterMm, lengthMm, materialId, clampType)
                VALUES(?,?,?,?,?,?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, id)
                ps.setString(2, saved.partId)
                ps.setDouble(3, saved.diameterMm)
                ps.setDouble(4, saved.lengthMm)
                ps.setString(5, saved.materialId)
                ps.setString(6, saved.clampType)
                ps.executeUpdate()
            }
            c.bumpVersion()
        }
        saved
    }

    fun deleteBlank(id: String) = deleteById("blanks", id)

    // ===== 工艺方案 =====

    private fun readPlans(c: Connection): List<ProcessPlanConfig> {
        val steps = c.prepareStatement(
            "SELECT * FROM process_steps ORDER BY planId, orderIndex"
        ).use { ps ->
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

        return c.prepareStatement("SELECT * FROM process_plans ORDER BY name").use { ps ->
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

    fun upsertPlan(plan: ProcessPlanConfig): ProcessPlanConfig = withConn { c ->
        val id = plan.id.ifBlank { "plan-${UUID.randomUUID().toString().take(8)}" }
        val saved = plan.copy(id = id)
        c.tx {
            c.prepareStatement(
                """
                INSERT OR REPLACE INTO process_plans(id, name, partId, stockDiameterMm, stockLengthMm, description)
                VALUES(?,?,?,?,?,?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, id)
                ps.setString(2, saved.name)
                ps.setString(3, saved.partId)
                ps.setDouble(4, saved.stockDiameterMm)
                ps.setDouble(5, saved.stockLengthMm)
                ps.setString(6, saved.description)
                ps.executeUpdate()
            }
            c.prepareStatement("DELETE FROM process_steps WHERE planId = ?").use { ps ->
                ps.setString(1, id)
                ps.executeUpdate()
            }
            saved.steps.forEachIndexed { index, step ->
                val stepId = step.id.ifBlank { "$id-step-$index" }
                c.prepareStatement(
                    """
                    INSERT OR REPLACE INTO process_steps(
                        id, planId, orderIndex, type, toolId, cuttingSpeedMmPerMin, spindleRpm,
                        feedMmPerRev, depthOfCutMm, finishAllowanceMm, approachDistanceMm,
                        retractDistanceMm, safetyDistanceMm, enabled, notes
                    ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, stepId)
                    ps.setString(2, id)
                    ps.setInt(3, step.orderIndex.takeIf { it > 0 } ?: index)
                    ps.setString(4, step.type)
                    ps.setString(5, step.toolId)
                    ps.setDouble(6, step.cuttingSpeedMmPerMin)
                    ps.setInt(7, step.spindleRpm)
                    ps.setDouble(8, step.feedMmPerRev)
                    ps.setDouble(9, step.depthOfCutMm)
                    ps.setDouble(10, step.finishAllowanceMm)
                    ps.setDouble(11, step.approachDistanceMm)
                    ps.setDouble(12, step.retractDistanceMm)
                    ps.setDouble(13, step.safetyDistanceMm)
                    ps.setInt(14, if (step.enabled) 1 else 0)
                    ps.setString(15, step.notes)
                    ps.executeUpdate()
                }
            }
            c.bumpVersion()
        }
        saved
    }

    fun deletePlan(id: String) = withConn { c ->
        c.tx {
            c.prepareStatement("DELETE FROM process_steps WHERE planId = ?").use { ps ->
                ps.setString(1, id); ps.executeUpdate()
            }
            c.prepareStatement("DELETE FROM process_plans WHERE id = ?").use { ps ->
                ps.setString(1, id); ps.executeUpdate()
            }
            c.bumpVersion()
        }
    }

    // ===== 图纸 =====

    private fun readDrawings(c: Connection): List<DrawingConfig> =
        c.prepareStatement("SELECT * FROM drawings ORDER BY filename").use { ps ->
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

    fun upsertDrawing(drawing: DrawingConfig): DrawingConfig = withConn { c ->
        val id = drawing.id.ifBlank { "drawing-${UUID.randomUUID().toString().take(8)}" }
        val saved = drawing.copy(id = id, source = drawing.source.ifBlank { "SERVER" })
        c.tx {
            c.prepareStatement(
                """
                INSERT OR REPLACE INTO drawings(id, filename, layers, entities, source, dxfContent)
                VALUES(?,?,?,?,?,?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, id)
                ps.setString(2, saved.filename)
                ps.setInt(3, saved.layers)
                ps.setInt(4, saved.entities)
                ps.setString(5, saved.source)
                ps.setString(6, saved.dxfContent)
                ps.executeUpdate()
            }
            c.bumpVersion()
        }
        saved
    }

    fun deleteDrawing(id: String) = withConn { c ->
        c.tx {
            c.prepareStatement("DELETE FROM drawings WHERE id = ?").use { ps ->
                ps.setString(1, id); ps.executeUpdate()
            }
            c.prepareStatement("DELETE FROM drawing_corrections WHERE drawingId = ?").use { ps ->
                ps.setString(1, id); ps.executeUpdate()
            }
            c.bumpVersion()
        }
    }

    private fun readCorrections(c: Connection): List<DrawingCorrectionConfig> =
        c.prepareStatement("SELECT * FROM drawing_corrections").use { ps ->
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

    fun upsertCorrection(correction: DrawingCorrectionConfig): DrawingCorrectionConfig = withConn { c ->
        c.tx {
            c.prepareStatement(
                """
                INSERT OR REPLACE INTO drawing_corrections(
                    drawingId, scale, offsetX, offsetY, rotationDeg, mirrorX, mirrorY, unit
                ) VALUES(?,?,?,?,?,?,?,?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, correction.drawingId)
                ps.setDouble(2, correction.scale)
                ps.setDouble(3, correction.offsetX)
                ps.setDouble(4, correction.offsetY)
                ps.setDouble(5, correction.rotationDeg)
                ps.setInt(6, if (correction.mirrorX) 1 else 0)
                ps.setInt(7, if (correction.mirrorY) 1 else 0)
                ps.setString(8, correction.unit)
                ps.executeUpdate()
            }
            c.bumpVersion()
        }
        correction
    }

    // ===== 任务记录 =====

    fun tasks(limit: Int = 200): List<TaskRecord> = withConn { c ->
        c.prepareStatement("SELECT * FROM task_history ORDER BY finishedAt DESC LIMIT ?").use { ps ->
            ps.setInt(1, limit)
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

    fun upsertTask(task: TaskRecord): TaskRecord = withConn { c ->
        val id = task.id.ifBlank { "task-${UUID.randomUUID().toString().take(8)}" }
        val saved = task.copy(id = id)
        c.prepareStatement(
            """
            INSERT OR REPLACE INTO task_history(
                id, partName, planId, status, durationSec, operator, startedAt, finishedAt
            ) VALUES(?,?,?,?,?,?,?,?)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, id)
            ps.setString(2, saved.partName)
            ps.setString(3, saved.planId)
            ps.setString(4, saved.status)
            ps.setDouble(5, saved.durationSec)
            ps.setString(6, saved.operator)
            ps.setLong(7, saved.startedAt)
            ps.setLong(8, saved.finishedAt)
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

    fun saveStatus(status: DeviceStatus) = withConn { c ->
        c.putMeta("last_status", json.encodeToString(DeviceStatus.serializer(), status))
        // 同步更新对应设备的在线时间
        c.heartbeatOnly(status.deviceId)
    }

    // ===== 设备注册 / 权限 / 管理员密码 =====

    private fun decodePermissions(raw: String): ClientPermissions =
        runCatching { json.decodeFromString<ClientPermissions>(raw) }.getOrDefault(ClientPermissions())

    /** 按硬件标识查设备（用于「同一台设备只能注册一次」判定）。 */
    fun findByHardwareId(hardwareId: String): DeviceInfo? = withConn { c ->
        if (hardwareId.isBlank()) return@withConn null
        var id: String? = null
        c.prepareStatement("SELECT id FROM devices WHERE hardware_id = ?").use { ps ->
            ps.setString(1, hardwareId)
            ps.executeQuery().use { rs -> if (rs.next()) id = rs.getString(1) }
        }
        id?.let { getDevice(it) }
    }

    /** 注册一台新设备。调用前需先确认 hardwareId 未被注册（路由层负责 409）。 */
    fun registerDevice(req: RegisterRequest): DeviceInfo = withConn { c ->
        val id = req.deviceId.ifBlank { "dev-${UUID.randomUUID().toString().take(8)}" }
        val now = System.currentTimeMillis()
        val existingPerms = c.getDeviceRaw(id)?.let { decodePermissions(it) } ?: ClientPermissions()
        c.prepareStatement(
            """
            INSERT OR REPLACE INTO devices(
                id, unit_name, contact_name, phone, server_address,
                registered_at, last_seen, online, permissions, hardware_id, app_version
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, id)
            ps.setString(2, req.unitName)
            ps.setString(3, req.contactName)
            ps.setString(4, req.phone)
            ps.setString(5, req.serverAddress)
            ps.setLong(6, now)
            ps.setLong(7, now)
            ps.setInt(8, 1)
            ps.setString(9, json.encodeToString(ClientPermissions.serializer(), existingPerms))
            ps.setString(10, req.hardwareId)
            ps.setString(11, req.appVersion)
            ps.executeUpdate()
        }
        DeviceInfo(
            id = id, unitName = req.unitName, contactName = req.contactName,
            phone = req.phone, serverAddress = req.serverAddress,
            registeredAt = now, lastSeen = now, online = true,
            permissions = existingPerms
        )
    }

    /** 后台修改设备信息（单位/联系人/电话）。 */
    fun updateDeviceInfo(id: String, req: DeviceUpdateRequest): Boolean = withConn { c ->
        c.prepareStatement(
            "UPDATE devices SET unit_name = ?, contact_name = ?, phone = ? WHERE id = ?"
        ).use { ps ->
            ps.setString(1, req.unitName)
            ps.setString(2, req.contactName)
            ps.setString(3, req.phone)
            ps.setString(4, id)
            ps.executeUpdate() > 0
        }
    }

    /** 注销设备：删除后该设备才能重新注册。 */
    fun deleteDevice(id: String): Boolean = withConn { c ->
        c.prepareStatement("DELETE FROM devices WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeUpdate() > 0
        }
    }

    fun getDevice(id: String): DeviceInfo? = withConn { c ->
        val permsRaw = c.getDeviceRaw(id) ?: return@withConn null
        var info: DeviceInfo? = null
        c.prepareStatement(
            "SELECT unit_name, contact_name, phone, server_address, registered_at, last_seen, online FROM devices WHERE id = ?"
        ).use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    info = DeviceInfo(
                        id = id,
                        unitName = rs.getString("unit_name"),
                        contactName = rs.getString("contact_name"),
                        phone = rs.getString("phone"),
                        serverAddress = rs.getString("server_address"),
                        registeredAt = rs.getLong("registered_at"),
                        lastSeen = rs.getLong("last_seen"),
                        online = isOnline(rs.getLong("last_seen")),
                        permissions = decodePermissions(permsRaw)
                    )
                }
            }
        }
        info
    }

    fun listDevices(): List<DeviceListItem> = withConn { c ->
        c.prepareStatement(
            "SELECT id, unit_name, contact_name, phone, server_address, registered_at, last_seen, online, app_version, permissions FROM devices ORDER BY registered_at DESC"
        ).use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        DeviceListItem(
                            id = rs.getString("id"),
                            unitName = rs.getString("unit_name"),
                            contactName = rs.getString("contact_name"),
                            phone = rs.getString("phone"),
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

    /** 在线判定：心跳周期 30 秒，90 秒内无心跳视为离线（按读取时实时计算）。 */
    private fun isOnline(lastSeen: Long): Boolean =
        lastSeen > 0 && System.currentTimeMillis() - lastSeen < 90_000L

    fun updateDevicePermissions(id: String, permissions: ClientPermissions): Boolean = withConn { c ->
        val changed = c.prepareStatement("UPDATE devices SET permissions = ? WHERE id = ?").use { ps ->
            ps.setString(1, json.encodeToString(ClientPermissions.serializer(), permissions))
            ps.setString(2, id)
            ps.executeUpdate()
        }
        changed > 0
    }

    /** 心跳：刷新在线时间，并顺带更新客户端版本号。 */
    fun heartbeat(id: String, appVersion: String = ""): DeviceInfo? = withConn { c ->
        val now = System.currentTimeMillis()
        if (appVersion.isNotBlank()) {
            c.prepareStatement("UPDATE devices SET last_seen = ?, online = 1, app_version = ? WHERE id = ?").use { ps ->
                ps.setLong(1, now); ps.setString(2, appVersion); ps.setString(3, id); ps.executeUpdate()
            }
        } else {
            c.prepareStatement("UPDATE devices SET last_seen = ?, online = 1 WHERE id = ?").use { ps ->
                ps.setLong(1, now); ps.setString(2, id); ps.executeUpdate()
            }
        }
        getDevice(id)
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

    private fun Connection.heartbeatOnly(id: String) {
        if (id.isBlank()) return
        val now = System.currentTimeMillis()
        prepareStatement("UPDATE devices SET last_seen = ?, online = 1 WHERE id = ?").use { ps ->
            ps.setLong(1, now); ps.setString(2, id); ps.executeUpdate()
        }
    }

    private fun Connection.getDeviceRaw(id: String): String? =
        prepareStatement("SELECT permissions FROM devices WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
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

    /** 校验后台登录账号密码。 */
    fun verifyAdminAccount(username: String, password: String): Boolean = withConn { c ->
        val u = c.getMeta("admin_username") ?: DEFAULT_ADMIN_USERNAME
        val p = c.getMeta("admin_password") ?: DEFAULT_ADMIN_PASSWORD
        u == username && p == password
    }

    const val DEFAULT_ADMIN_USERNAME = "admin"
    const val DEFAULT_ADMIN_PASSWORD = "123456"
    private const val LEGACY_DEFAULT_ADMIN_PASSWORD = "admin123"

    // ===== 管理后台登录会话 =====

    /** 会话有效期：7 天。 */
    const val SESSION_TTL_MS = 7L * 24 * 3600 * 1000

    /** 登录成功创建会话，返回 token。 */
    fun createSession(username: String): String = withConn { c ->
        // 顺手清理过期会话
        c.prepareStatement("DELETE FROM admin_sessions WHERE expires_at < ?").use { ps ->
            ps.setLong(1, System.currentTimeMillis()); ps.executeUpdate()
        }
        val token = "sess-" + UUID.randomUUID().toString().replace("-", "")
        val now = System.currentTimeMillis()
        c.prepareStatement(
            "INSERT INTO admin_sessions(token, username, created_at, expires_at) VALUES(?,?,?,?)"
        ).use { ps ->
            ps.setString(1, token)
            ps.setString(2, username)
            ps.setLong(3, now)
            ps.setLong(4, now + SESSION_TTL_MS)
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

    // ===== 通用 =====

    private fun deleteById(table: String, id: String) = withConn { c ->
        c.tx {
            c.prepareStatement("DELETE FROM $table WHERE id = ?").use { ps ->
                ps.setString(1, id); ps.executeUpdate()
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
                INSERT OR REPLACE INTO tools VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, t.id); ps.setInt(2, t.toolNumber); ps.setString(3, t.toolType)
                ps.setString(4, t.name); ps.setString(5, t.material); ps.setDouble(6, t.nominalDiameterMm)
                ps.setDouble(7, t.lengthMm); ps.setInt(8, t.maxRpm); ps.setDouble(9, t.maxFeedMmPerMin)
                ps.setString(10, t.wearStatus); ps.setInt(11, t.usageCount); ps.setString(12, t.notes)
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
            c.prepareStatement("INSERT OR REPLACE INTO materials VALUES(?,?,?,?,?,?,?,?,?,?)").use { ps ->
                ps.setString(1, m.id); ps.setString(2, m.name); ps.setString(3, m.code)
                ps.setDouble(4, m.density); ps.setString(5, m.hardness)
                ps.setDouble(6, m.recommendedCuttingSpeedMs); ps.setDouble(7, m.recommendedFeedPerRev)
                ps.setDouble(8, m.recommendedDepthMm); ps.setInt(9, if (m.enabled) 1 else 0)
                ps.setString(10, m.notes)
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
            c.prepareStatement("INSERT OR REPLACE INTO parts VALUES(?,?,?,?)").use { ps ->
                ps.setString(1, p.id); ps.setString(2, p.name)
                ps.setString(3, p.materialId); ps.setString(4, p.notes)
                ps.executeUpdate()
            }
        }
        val blanks = listOf(
            BlankConfig("srv-b1", "srv-p1", 80.0, 120.0, "srv-m1", "CHUCK"),
            BlankConfig("srv-b2", "srv-p2", 40.0, 80.0, "srv-m2", "COLLET")
        )
        blanks.forEach { b ->
            c.prepareStatement("INSERT OR REPLACE INTO blanks VALUES(?,?,?,?,?,?)").use { ps ->
                ps.setString(1, b.id); ps.setString(2, b.partId); ps.setDouble(3, b.diameterMm)
                ps.setDouble(4, b.lengthMm); ps.setString(5, b.materialId); ps.setString(6, b.clampType)
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
                "INSERT OR REPLACE INTO process_plans VALUES(?,?,?,?,?,?)"
            ).use { ps ->
                ps.setString(1, p.id); ps.setString(2, p.name); ps.setString(3, p.partId)
                ps.setDouble(4, p.stockDiameterMm); ps.setDouble(5, p.stockLengthMm)
                ps.setString(6, p.description)
                ps.executeUpdate()
            }
            p.steps.forEach { s ->
                c.prepareStatement(
                    """
                    INSERT OR REPLACE INTO process_steps(
                        id, planId, orderIndex, type, toolId, cuttingSpeedMmPerMin, spindleRpm,
                        feedMmPerRev, depthOfCutMm, finishAllowanceMm, approachDistanceMm,
                        retractDistanceMm, safetyDistanceMm, enabled, notes
                    ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """.trimIndent()
                ).use { ps ->
                    ps.setString(1, s.id); ps.setString(2, s.planId); ps.setInt(3, s.orderIndex)
                    ps.setString(4, s.type); ps.setString(5, s.toolId)
                    ps.setDouble(6, s.cuttingSpeedMmPerMin); ps.setInt(7, s.spindleRpm)
                    ps.setDouble(8, s.feedMmPerRev); ps.setDouble(9, s.depthOfCutMm)
                    ps.setDouble(10, s.finishAllowanceMm); ps.setDouble(11, s.approachDistanceMm)
                    ps.setDouble(12, s.retractDistanceMm); ps.setDouble(13, s.safetyDistanceMm)
                    ps.setInt(14, if (s.enabled) 1 else 0); ps.setString(15, s.notes)
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
            c.prepareStatement("INSERT OR REPLACE INTO drawings VALUES(?,?,?,?,?,?)").use { ps ->
                ps.setString(1, d.id); ps.setString(2, d.filename); ps.setInt(3, d.layers)
                ps.setInt(4, d.entities); ps.setString(5, d.source); ps.setString(6, d.dxfContent)
                ps.executeUpdate()
            }
        }
        c.prepareStatement(
            "INSERT OR REPLACE INTO drawing_corrections VALUES(?,?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, "srv-d1"); ps.setDouble(2, 1.0); ps.setDouble(3, 0.0)
            ps.setDouble(4, 0.0); ps.setDouble(5, 0.0); ps.setInt(6, 0); ps.setInt(7, 0)
            ps.setString(8, "MM")
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
            c.prepareStatement("INSERT OR REPLACE INTO task_history VALUES(?,?,?,?,?,?,?,?)").use { ps ->
                ps.setString(1, t.id); ps.setString(2, t.partName); ps.setString(3, t.planId)
                ps.setString(4, t.status); ps.setDouble(5, t.durationSec); ps.setString(6, t.operator)
                ps.setLong(7, t.startedAt); ps.setLong(8, t.finishedAt)
                ps.executeUpdate()
            }
        }
    }
}
