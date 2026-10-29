package com.cnc.server

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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 任务状态机 + revision 失效链测试（P2-③ / R8）。
 */
class TaskStateMachineTest {

    private fun freshDb(): String =
        java.nio.file.Files.createTempFile("cnc-task-state-", ".db").toFile().absolutePath

    // ---------- 状态常量与迁移校验（纯函数） ----------

    @Test
    fun `all six states are valid`() {
        listOf("PENDING_REVIEW", "READY", "RUNNING", "SUCCESS", "FAILED", "ABORTED")
            .forEach { assertTrue(isValidTaskState(it), "$it should be valid") }
    }

    @Test
    fun `invalid state rejected`() {
        assertTrue(!isValidTaskState("UNKNOWN"))
        assertTrue(!isValidTaskState(""))
        assertTrue(!isValidTaskState("success")) // 大小写敏感
    }

    @Test
    fun `legal transitions accepted`() {
        assertTrue(isValidTaskTransition("PENDING_REVIEW", "READY"))
        assertTrue(isValidTaskTransition("PENDING_REVIEW", "RUNNING"))
        assertTrue(isValidTaskTransition("READY", "RUNNING"))
        assertTrue(isValidTaskTransition("READY", "PENDING_REVIEW"))
        assertTrue(isValidTaskTransition("RUNNING", "SUCCESS"))
        assertTrue(isValidTaskTransition("RUNNING", "FAILED"))
        assertTrue(isValidTaskTransition("RUNNING", "ABORTED"))
    }

    @Test
    fun `same-state transition allowed for idempotent retry`() {
        assertTrue(isValidTaskTransition("PENDING_REVIEW", "PENDING_REVIEW"))
        assertTrue(isValidTaskTransition("SUCCESS", "SUCCESS"))
    }

    @Test
    fun `terminal states cannot transition`() {
        assertTrue(!isValidTaskTransition("SUCCESS", "READY"))
        assertTrue(!isValidTaskTransition("FAILED", "RUNNING"))
        assertTrue(!isValidTaskTransition("ABORTED", "PENDING_REVIEW"))
    }

    @Test
    fun `illegal non-terminal transitions rejected`() {
        assertTrue(!isValidTaskTransition("PENDING_REVIEW", "SUCCESS")) // 跳过 READY/RUNNING
        assertTrue(!isValidTaskTransition("READY", "FAILED"))           // 必须先 RUNNING
        assertTrue(!isValidTaskTransition("RUNNING", "READY"))          // 不允许回退
    }

    // ---------- Database 层 ----------

    @Test
    fun `upsertTask rejects invalid state`() {
        Db.init(freshDb())
        assertFailsWith<IllegalArgumentException> {
            Db.upsertTask(TaskRecord(partName = "x", state = "BOGUS"))
        }
    }

    @Test
    fun `upsertTask rejects illegal transition on existing record`() {
        Db.init(freshDb())
        val t = Db.upsertTask(TaskRecord(partName = "x", planId = "p1", state = "READY"))
        // READY → SUCCESS 非法（必须先 RUNNING）
        assertFailsWith<IllegalArgumentException> {
            Db.upsertTask(TaskRecord(id = t.id, partName = "x", planId = "p1", state = "SUCCESS"))
        }
    }

    @Test
    fun `upsertTask accepts full lifecycle`() {
        Db.init(freshDb())
        val t1 = Db.upsertTask(TaskRecord(partName = "x", planId = "p1", state = "PENDING_REVIEW"))
        val t2 = Db.upsertTask(TaskRecord(id = t1.id, partName = "x", planId = "p1", state = "READY"))
        val t3 = Db.upsertTask(TaskRecord(id = t1.id, partName = "x", planId = "p1", state = "RUNNING"))
        val t4 = Db.upsertTask(TaskRecord(id = t1.id, partName = "x", planId = "p1", state = "SUCCESS"))
        assertEquals("SUCCESS", t4.state)
        // 同态幂等
        val t5 = Db.upsertTask(TaskRecord(id = t1.id, partName = "x", planId = "p1", state = "SUCCESS"))
        assertEquals("SUCCESS", t5.state)
        // 终态禁止迁移
        assertFailsWith<IllegalArgumentException> {
            Db.upsertTask(TaskRecord(id = t1.id, partName = "x", planId = "p1", state = "READY"))
        }
    }

    @Test
    fun `upsertTask syncs status and state`() {
        Db.init(freshDb())
        val t = Db.upsertTask(TaskRecord(partName = "x", planId = "p1", state = "READY"))
        assertEquals("READY", t.state)
        assertEquals("READY", t.status) // status 与 state 保持一致
    }

    @Test
    fun `upsertTask falls back to status when state blank`() {
        Db.init(freshDb())
        // 模拟旧客户端只写 status 不写 state（state 为空串）
        val t = Db.upsertTask(TaskRecord(partName = "x", planId = "p1", status = "FAILED", state = ""))
        assertEquals("FAILED", t.state)
        assertEquals("FAILED", t.status)
    }

    // ---------- Revision 失效链 ----------

    @Test
    fun `invalidateTasksForPlans resets non-terminal tasks to PENDING_REVIEW`() {
        Db.init(freshDb())
        // 三个任务分别处于 PENDING_REVIEW / RUNNING / SUCCESS
        Db.upsertTask(TaskRecord(id = "t-p", partName = "a", planId = "plan-1", state = "PENDING_REVIEW"))
        Db.upsertTask(TaskRecord(id = "t-run", partName = "b", planId = "plan-1", state = "READY"))
        Db.upsertTask(TaskRecord(id = "t-ok", partName = "c", planId = "plan-1", state = "SUCCESS"))
        // 另一个 plan 的任务不受影响
        Db.upsertTask(TaskRecord(id = "t-other", partName = "d", planId = "plan-2", state = "READY"))

        val count = Db.invalidateTasksForPlans(listOf("plan-1"))
        // PENDING_REVIEW 和 READY 被重置（PENDING_REVIEW 同态也算更新），SUCCESS 不动
        assertTrue(count >= 1)

        val tasks = Db.tasks(tenantId = Db.DEFAULT_TENANT).associateBy { it.id }
        assertEquals("PENDING_REVIEW", tasks["t-p"]?.state)
        assertEquals("PENDING_REVIEW", tasks["t-run"]?.state)
        assertEquals("SUCCESS", tasks["t-ok"]?.state)      // 终态不受影响
        assertEquals("READY", tasks["t-other"]?.state)     // 其它 plan 不受影响
    }

    @Test
    fun `upsertTool invalidates tasks referencing that tool via plan steps`() {
        Db.init(freshDb())
        // 创建刀具 → 工艺方案（含该刀具的 step）→ 任务（state=READY）
        val tool = Db.upsertTool(ToolConfig(name = "车刀A"))
        Db.upsertMaterial(MaterialConfig(name = "45#钢", code = "45"))
        val part = Db.upsertPart(PartConfig(name = "轴", materialId = "mat-x"))
        Db.upsertPlan(ProcessPlanConfig(
            id = "plan-1", name = "轴工艺", partId = part.id,
            steps = listOf(ProcessStepConfig(id = "s1", planId = "plan-1", toolId = tool.id))
        ))
        Db.upsertTask(TaskRecord(id = "task-1", partName = "轴", planId = "plan-1", state = "READY"))

        // 修改刀具（磨损状态变化）
        Db.upsertTool(ToolConfig(id = tool.id, name = "车刀A", wearStatus = "WORN"))

        val task = Db.tasks(tenantId = Db.DEFAULT_TENANT).first { it.id == "task-1" }
        assertEquals("PENDING_REVIEW", task.state)
    }

    @Test
    fun `upsertPlan invalidates tasks referencing that plan`() {
        Db.init(freshDb())
        Db.upsertTask(TaskRecord(id = "task-1", partName = "x", planId = "plan-1", state = "RUNNING"))
        Db.upsertTask(TaskRecord(id = "task-2", partName = "x", planId = "plan-1", state = "SUCCESS"))

        Db.upsertPlan(ProcessPlanConfig(id = "plan-1", name = "新方案"))

        val tasks = Db.tasks(tenantId = Db.DEFAULT_TENANT).associateBy { it.id }
        assertEquals("PENDING_REVIEW", tasks["task-1"]?.state)
        assertEquals("SUCCESS", tasks["task-2"]?.state) // 终态不受影响
    }

    @Test
    fun `upsertDrawing invalidates tasks using that drawing as planId`() {
        Db.init(freshDb())
        // 客户端简易模式：planId = drawingId
        Db.upsertTask(TaskRecord(id = "task-1", partName = "x", planId = "drawing-1", state = "READY"))
        Db.upsertDrawing(DrawingConfig(id = "drawing-1", filename = "a.dxf"))

        val task = Db.tasks(tenantId = Db.DEFAULT_TENANT).first { it.id == "task-1" }
        assertEquals("PENDING_REVIEW", task.state)
    }

    // ---------- HTTP 路由层 ----------

    @Test
    fun `POST tasks returns 500 with message on illegal transition`() = testApplication {
        Db.init(freshDb())
        application { cncServerModule("root-token") }
        Db.upsertTask(TaskRecord(id = "task-1", partName = "x", planId = "p1", state = "SUCCESS"))

        val res = client.post("/api/v1/tasks") {
            header(HttpHeaders.Authorization, "Bearer root-token")
            contentType(ContentType.Application.Json)
            setBody("""{"id":"task-1","partName":"x","planId":"p1","state":"READY"}""")
        }
        // 终态 SUCCESS → READY 非法，服务端返回 500 + 错误信息
        assertEquals(HttpStatusCode.InternalServerError, res.status)
        assertTrue(res.bodyAsText().contains("非法任务状态迁移"))
    }
}
