package com.cnc.host.persistence

/**
 * 首次启动种子数据（写一次，之后不覆盖）。
 *
 * 所有种子 id 显式写死，方便升级时 detect existing。
 */
object SeedData {

    // ===== 零件 =====
    val parts = listOf(
        PartEntity(id = "p1", name = "主轴接套", materialId = "m1", notes = "CNC 自动车削首件"),
        PartEntity(id = "p2", name = "液压阀芯", materialId = "m2", notes = "精密车削"),
        PartEntity(id = "p3", name = "轴承套", materialId = "m3", notes = "批量生产")
    )

    // ===== 毛坯 =====
    val blanks = listOf(
        BlankEntity(id = "b1", partId = "p1", diameterMm = 80.0, lengthMm = 120.0, materialId = "m1", clampType = "CHUCK"),
        BlankEntity(id = "b2", partId = "p2", diameterMm = 40.0, lengthMm = 80.0, materialId = "m2", clampType = "COLLET")
    )

    // ===== 刀具 =====
    val tools = listOf(
        ToolEntity("t1", 1, "TURNING", "外圆车刀 T01", "CARBIDE", 0.0, 120.0, 3000, 500.0, "NEW", 0),
        ToolEntity("t2", 2, "FINISH_TURNING", "精车刀 T02", "CARBIDE", 0.0, 100.0, 4000, 300.0, "LIGHT", 15),
        ToolEntity("t3", 3, "GROOVING", "切槽刀 T03", "CARBIDE", 3.0, 80.0, 2500, 200.0, "NEW", 0),
        ToolEntity("t4", 4, "THREADING", "60°螺纹刀 T04", "HSS", 0.0, 80.0, 2000, 100.0, "LIGHT", 28),
        ToolEntity("t5", 5, "DRILLING", "φ10 钻头 T05", "HSS", 10.0, 150.0, 1500, 150.0, "MODERATE", 120)
    )

    // ===== 材料 =====
    val materials = listOf(
        MaterialEntity("m1", "45# 钢", "GB/T 711", 7.85, "HB229", 150.0, 0.2, 3.0, enabled = true),
        MaterialEntity("m2", "40Cr 合金钢", "GB/T 3077", 7.9, "HB217", 120.0, 0.18, 2.5, enabled = true),
        MaterialEntity("m3", "HT200 铸铁", "GB/T 9439", 7.2, "HB170", 100.0, 0.25, 2.0, enabled = true),
        MaterialEntity("m4", "6061 铝合金", "GB/T 3191", 2.7, "HB95", 400.0, 0.3, 5.0, enabled = false)
    )

    // ===== 工艺方案 + 步骤 =====
    val processPlans = listOf(
        ProcessPlanEntity("pp-1", "主轴接套 标准车削", "p1", 100.0, 130.0),
        ProcessPlanEntity("pp-2", "液压阀芯 精车", "p2", 50.0, 90.0)
    )

    val processSteps = listOf(
        ProcessStepEntity("ps-1", "pp-1", 0, "ROUGH_TURNING", "t1", 150.0, 1200, 0.2, 5.0, 1.0),
        ProcessStepEntity("ps-2", "pp-1", 1, "FINISH_TURNING", "t2", 180.0, 1500, 0.1, 1.0, 0.0),
        ProcessStepEntity("ps-3", "pp-1", 2, "GROOVING", "t3", 100.0, 800, 0.15, 3.0, 0.0),
        ProcessStepEntity("ps-4", "pp-2", 0, "ROUGH_TURNING", "t1", 120.0, 1000, 0.18, 3.0, 0.5),
        ProcessStepEntity("ps-5", "pp-2", 1, "FINISH_TURNING", "t2", 200.0, 1800, 0.08, 0.5, 0.0)
    )

    // ===== 图纸索引 =====
    val drawings = listOf(
        DrawingEntity("d1", "sample-out.dxf", layers = 3, entities = 5, source = "EMBEDDED", planId = "pp-1"),
        DrawingEntity("d2", "spindle-housing.dxf", layers = 4, entities = 12, source = "EMBEDDED", planId = "pp-2")
    )

    // ===== 任务历史（首次启动时自动写几条） =====
    val taskHistory = listOf(
        TaskHistoryEntity("h1", "主轴接套", "pp-1", "SUCCESS", 312.5, "操作员A",
            System.currentTimeMillis() - 7200000, System.currentTimeMillis() - 6887500),
        TaskHistoryEntity("h2", "液压阀芯", "pp-2", "FAILED", 120.3, "操作员B",
            System.currentTimeMillis() - 172800000, System.currentTimeMillis() - 172679700)
    )
}
