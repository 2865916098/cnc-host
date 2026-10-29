package com.cnc.server

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 数据导出 / 按范围删除 API 测试（用户确认项 3：永久保留 + 导出与删除）。
 */
class DataExportTest {

    private fun freshDb(): String =
        java.nio.file.Files.createTempFile("cnc-export-", ".db").toFile().absolutePath

    @Test
    fun `export tasks returns csv with header and rows`() = testApplication {
        Db.init(freshDb())
        Db.upsertTask(TaskRecord(partName = "导出测试件", planId = "p1", status = "SUCCESS",
            durationSec = 12.5, operator = "张三", startedAt = 1_700_000_000_000L, finishedAt = 1_700_000_012_500L))
        application { cncServerModule("root-token") }

        val res = client.get("/api/v1/export/tasks") {
            header(HttpHeaders.Authorization, "Bearer root-token")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val csv = res.bodyAsText()
        assertTrue(csv.startsWith("任务ID,零件名称"))
        assertTrue(csv.contains("导出测试件"))
    }

    @Test
    fun `export devices csv includes unit name`() = testApplication {
        Db.init(freshDb())
        application { cncServerModule("root-token") }
        Db.registerDevice(RegisterRequest(unitName = "测试工厂A", contactName = "李四", phone = "13900000000"))

        val res = client.get("/api/v1/export/devices") {
            header(HttpHeaders.Authorization, "Bearer root-token")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val csv = res.bodyAsText()
        assertTrue(csv.contains("测试工厂A"))
        assertTrue(csv.contains("13900000000"))
    }

    @Test
    fun `delete tasks by range and reject unbounded delete`() = testApplication {
        Db.init(freshDb())
        application { cncServerModule("root-token") }
        Db.upsertTask(TaskRecord(partName = "旧任务", planId = "p1", status = "SUCCESS",
            durationSec = 1.0, operator = "x", startedAt = 1_600_000_000_000L, finishedAt = 1_600_000_001_000L))
        Db.upsertTask(TaskRecord(partName = "新任务", planId = "p1", status = "SUCCESS",
            durationSec = 1.0, operator = "x", startedAt = 1_800_000_000_000L, finishedAt = 1_800_000_001_000L))

        // 无范围 → 400
        val bad = client.delete("/api/v1/data/tasks") {
            header(HttpHeaders.Authorization, "Bearer root-token")
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)

        val before = Db.tasks().size
        // 删除 1700 之前的（命中旧任务，不影响新任务与种子数据中较新的记录）
        val del = client.delete("/api/v1/data/tasks?to=1700000000000") {
            header(HttpHeaders.Authorization, "Bearer root-token")
        }
        assertEquals(HttpStatusCode.OK, del.status)
        assertTrue(del.bodyAsText().contains("已删除 1"))

        // 新任务仍在，总数恰好少 1
        val after = Db.tasks()
        assertEquals(before - 1, after.size)
        assertTrue(after.any { it.partName == "新任务" })
        assertTrue(!after.any { it.partName == "旧任务" })
    }

    @Test
    fun `export and delete are tenant isolated`() = testApplication {
        Db.init(freshDb())
        application { cncServerModule("root-token") }
        Db.upsertTask(TaskRecord(partName = "租户A任务"), tenantId = "tenant-a")
        Db.upsertTask(TaskRecord(partName = "租户B任务"), tenantId = "tenant-b")

        // 租户A 导出只看到自己
        val csvA = client.get("/api/v1/export/tasks") {
            header(HttpHeaders.Authorization, "Bearer root-token")
            header("X-Tenant-Id", "tenant-a")
        }.bodyAsText()
        assertTrue(csvA.contains("租户A任务"))
        assertTrue(!csvA.contains("租户B任务"))

        // 租户B 删除自己的，不影响租户A
        client.delete("/api/v1/data/tasks?from=0") {
            header(HttpHeaders.Authorization, "Bearer root-token")
            header("X-Tenant-Id", "tenant-b")
        }
        assertEquals(1, Db.tasks(tenantId = "tenant-a").size)
        assertEquals(0, Db.tasks(tenantId = "tenant-b").size)
    }
}
