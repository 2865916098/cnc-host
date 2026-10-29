package com.cnc.host.sync

import android.content.Context
import androidx.room.withTransaction
import com.cnc.host.data.DeviceRepository
import com.cnc.host.persistence.BlankEntity
import com.cnc.host.persistence.CncDatabase
import com.cnc.host.persistence.DrawingCorrectionEntity
import com.cnc.host.persistence.DrawingEntity
import com.cnc.host.persistence.MaterialEntity
import com.cnc.host.persistence.PartEntity
import com.cnc.host.persistence.ProcessPlanEntity
import com.cnc.host.persistence.ProcessStepEntity
import com.cnc.host.persistence.SyncMetaEntity
import com.cnc.host.persistence.ToolEntity
import com.google.gson.Gson

/**
 * 把服务端配置写入本地 Room 缓存。
 *
 * 策略：服务端为权威源。
 * - 刀具/材料/零件/毛坯/工艺：整表替换
 * - 图纸：只替换 source = SERVER 的记录，保留本地导入图纸
 * - 校正：先删除服务端图纸的旧校正，再写入新校正
 */
object ConfigSyncer {

    suspend fun apply(context: Context, config: ConfigDto): Result<Unit> = runCatching {
        val db = CncDatabase.getInstance(context)
        val now = System.currentTimeMillis()

        db.withTransaction {
            db.toolDao().clearAll()
            db.toolDao().insertAll(
                config.tools.orEmpty().map { t ->
                    ToolEntity(
                        id = t.id, toolNumber = t.toolNumber, toolType = t.toolType,
                        name = t.name, material = t.material,
                        nominalDiameterMm = t.nominalDiameterMm, lengthMm = t.lengthMm,
                        maxRpm = t.maxRpm, maxFeedMmPerMin = t.maxFeedMmPerMin,
                        wearStatus = t.wearStatus, usageCount = t.usageCount,
                        notes = t.notes, createdAt = now, updatedAt = now
                    )
                }
            )

            db.materialDao().clearAll()
            db.materialDao().insertAll(
                config.materials.orEmpty().map { m ->
                    MaterialEntity(
                        id = m.id, name = m.name, code = m.code, density = m.density,
                        hardness = m.hardness,
                        recommendedCuttingSpeedMs = m.recommendedCuttingSpeedMs,
                        recommendedFeedPerRev = m.recommendedFeedPerRev,
                        recommendedDepthMm = m.recommendedDepthMm,
                        enabled = m.enabled, notes = m.notes, createdAt = now
                    )
                }
            )

            db.partDao().clearAll()
            db.partDao().insertAll(
                config.parts.orEmpty().map { p ->
                    PartEntity(
                        id = p.id, name = p.name, materialId = p.materialId,
                        notes = p.notes, createdAt = now, updatedAt = now
                    )
                }
            )

            db.blankDao().clearAll()
            db.blankDao().insert(
                config.blanks.orEmpty().map { b ->
                    BlankEntity(
                        id = b.id, partId = b.partId, diameterMm = b.diameterMm,
                        lengthMm = b.lengthMm, materialId = b.materialId,
                        clampType = b.clampType, createdAt = now
                    )
                }
            )

            db.processStepDao().clearAll()
            db.processPlanDao().clearAll()
            config.plans.orEmpty().forEach { plan ->
                db.processPlanDao().insert(
                    ProcessPlanEntity(
                        id = plan.id, name = plan.name, partId = plan.partId,
                        stockDiameterMm = plan.stockDiameterMm,
                        stockLengthMm = plan.stockLengthMm,
                        description = plan.description,
                        createdAt = now, updatedAt = now
                    )
                )
                db.processStepDao().insert(
                    plan.steps.orEmpty().map { s ->
                        ProcessStepEntity(
                            id = s.id, planId = plan.id, orderIndex = s.orderIndex,
                            type = s.type, toolId = s.toolId,
                            cuttingSpeedMmPerMin = s.cuttingSpeedMmPerMin,
                            spindleRpm = s.spindleRpm, feedMmPerRev = s.feedMmPerRev,
                            depthOfCutMm = s.depthOfCutMm,
                            finishAllowanceMm = s.finishAllowanceMm,
                            approachDistanceMm = s.approachDistanceMm,
                            retractDistanceMm = s.retractDistanceMm,
                            safetyDistanceMm = s.safetyDistanceMm,
                            enabled = s.enabled, notes = s.notes
                        )
                    }
                )
            }

            // 先清理服务端图纸的校正，再删图纸，最后写入新的图纸与校正
            db.drawingCorrectionDao().deleteForServerDrawings()
            db.drawingDao().deleteServerDrawings()
            db.drawingDao().insertAll(
                config.drawings.orEmpty().map { d ->
                    DrawingEntity(
                        id = d.id, filename = d.filename, layers = d.layers,
                        entities = d.entities, source = "SERVER",
                        dxfContent = d.dxfContent, createdAt = now
                    )
                }
            )
            config.drawingCorrections.orEmpty().forEach { c ->
                db.drawingCorrectionDao().upsert(
                    DrawingCorrectionEntity(
                        drawingId = c.drawingId, scale = c.scale,
                        offsetX = c.offsetX, offsetY = c.offsetY,
                        rotationDeg = c.rotationDeg, mirrorX = c.mirrorX,
                        mirrorY = c.mirrorY, unit = c.unit, updatedAt = now
                    )
                )
            }

            db.syncMetaDao().put(SyncMetaEntity("config_version", config.version.toString()))
            db.syncMetaDao().put(SyncMetaEntity("last_sync_at", now.toString()))
        }

        // 设备/通讯参数原样缓存，高级页面通过它展示服务端下发的值
        val gson = Gson()
        val device = config.device ?: DeviceConfigDto()
        ServerPrefs.saveDeviceJson(
            context,
            deviceJson = gson.toJson(device),
            commJson = gson.toJson(device.comm)
        )

        // 按服务端下发的驱动配置切换设备驱动（默认/异常均回退模拟器）
        DeviceRepository.shared.applyServerConfig(device)
        Unit
    }
}
