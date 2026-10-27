package com.cnc.device.simulator

import com.cnc.model.logging.LogCategory
import kotlinx.coroutines.delay

/**
 * 自动化流程步骤。
 *
 * 对应规格第七章的推荐流程。
 */
enum class AutomationStep {
    STANDBY,
    LOAD_MATERIAL,
    CONFIRM_LOAD,
    CLAMP,
    CONFIRM_CLAMP,
    SPINDLE_PREPARE,
    ROUGH_TURN,
    FINISH_TURN,
    RETRACT,
    STOP_SPINDLE,
    UNCLAMP,
    CONFIRM_UNCLAMP,
    RECORD_RESULT,
    COMPLETED,
    FAULT
}

/** 流程执行结果。 */
enum class FlowResult {
    SUCCESS,
    FAILED,
    ABORTED,
    TIMEOUT
}

/**
 * 自动化流程模拟器。
 *
 * 模拟送料、夹紧、切削、退刀、松料等完整流程。
 * 每个步骤使用真实反馈信号判断动作结果，不依靠固定延时。
 *
 * 流程：待机 → 送料 → 送料反馈确认 → 夹紧 → 夹紧反馈确认 →
 *       主轴准备 → 粗车 → 精车 → 退刀 → 停主轴 → 松料 →
 *       松料反馈确认 → 结果记录 → 返回待机
 */
class AutomationFlowSimulator(
    private val device: SimulatedDeviceController,
    private val config: DeviceSimulatorConfig = device.config
) {
    var currentStep: AutomationStep = AutomationStep.STANDBY
        private set

    var result: FlowResult? = null
        private set

    var lastError: String? = null
        private set

    /**
     * 执行完整自动化流程。
     *
     * @param taskId 任务 ID（用于日志关联）
     * @return 流程执行结果
     */
    suspend fun execute(taskId: String? = null): FlowResult {
        result = null
        lastError = null

        return try {
            runLoadMaterial(taskId)
            runConfirmLoad(taskId)
            runClamp(taskId)
            runConfirmClamp(taskId)
            runSpindlePrepare(taskId)
            runRoughTurn(taskId)
            runFinishTurn(taskId)
            runRetract(taskId)
            runStopSpindle(taskId)
            runUnclamp(taskId)
            runConfirmUnclamp(taskId)
            runRecordResult(taskId)

            currentStep = AutomationStep.COMPLETED
            result = FlowResult.SUCCESS
            FlowResult.SUCCESS
        } catch (e: FlowException) {
            // 保留失败发生时的步骤，不覆盖为 FAULT
            result = FlowResult.FAILED
            lastError = e.message
            device.logger.error(LogCategory.TASK, "流程失败于 $currentStep: ${e.message}", taskId)
            FlowResult.FAILED
        }
    }

    private suspend fun runLoadMaterial(taskId: String?) {
        currentStep = AutomationStep.LOAD_MATERIAL
        device.logger.info(LogCategory.TASK, "步骤: 送料", taskId)
        // 送料电磁阀 ON
        device.setOutputs(mapOf(2 to true))
    }

    private suspend fun runConfirmLoad(taskId: String?) {
        currentStep = AutomationStep.CONFIRM_LOAD
        device.logger.info(LogCategory.TASK, "步骤: 送料反馈确认", taskId)
        // 等待送料到位反馈（输入 2）
        val ok = waitForInput(2, true, config.feedActionMs * 3)
        if (!ok) throw FlowException("送料超时：未收到送料到位反馈")
        device.logger.info(LogCategory.TASK, "送料到位确认", taskId)
    }

    private suspend fun runClamp(taskId: String?) {
        currentStep = AutomationStep.CLAMP
        device.logger.info(LogCategory.TASK, "步骤: 夹紧", taskId)
        device.setOutputs(mapOf(1 to true))
    }

    private suspend fun runConfirmClamp(taskId: String?) {
        currentStep = AutomationStep.CONFIRM_CLAMP
        device.logger.info(LogCategory.TASK, "步骤: 夹紧反馈确认", taskId)
        val ok = waitForInput(1, true, config.clampActionMs * 3)
        if (!ok) throw FlowException("夹紧超时：未收到夹紧反馈")
        device.logger.info(LogCategory.TASK, "夹紧确认", taskId)
    }

    private suspend fun runSpindlePrepare(taskId: String?) {
        currentStep = AutomationStep.SPINDLE_PREPARE
        device.logger.info(LogCategory.TASK, "步骤: 主轴准备", taskId)
        // 主轴正转 1500 rpm
        val cmd = com.cnc.device.api.DeviceCommand.setSpindle("spindle-prep", taskId, 1500, true)
        val result = device.sendMotionCommand(cmd)
        if (result.isFailure) throw FlowException("主轴启动失败: ${result.exceptionOrNull()?.message}")
    }

    private suspend fun runRoughTurn(taskId: String?) {
        currentStep = AutomationStep.ROUGH_TURN
        device.logger.info(LogCategory.TASK, "步骤: 粗车", taskId)
        // 模拟粗车：快速定位到切削起点，然后直线进给
        val rapid = com.cnc.device.api.DeviceCommand.rapidMove(
            "rough-rapid", taskId, com.cnc.model.drawing.Point2D(30.0, 5.0)
        )
        if (device.sendMotionCommand(rapid).isFailure) throw FlowException("粗车定位失败")

        val feed = com.cnc.device.api.DeviceCommand.linearFeed(
            "rough-feed", taskId, com.cnc.model.drawing.Point2D(30.0, 80.0), 200.0
        )
        if (device.sendMotionCommand(feed).isFailure) throw FlowException("粗车进给失败")
    }

    private suspend fun runFinishTurn(taskId: String?) {
        currentStep = AutomationStep.FINISH_TURN
        device.logger.info(LogCategory.TASK, "步骤: 精车", taskId)
        val rapid = com.cnc.device.api.DeviceCommand.rapidMove(
            "finish-rapid", taskId, com.cnc.model.drawing.Point2D(25.0, 5.0)
        )
        if (device.sendMotionCommand(rapid).isFailure) throw FlowException("精车定位失败")

        val feed = com.cnc.device.api.DeviceCommand.linearFeed(
            "finish-feed", taskId, com.cnc.model.drawing.Point2D(25.0, 80.0), 100.0
        )
        if (device.sendMotionCommand(feed).isFailure) throw FlowException("精车进给失败")
    }

    private suspend fun runRetract(taskId: String?) {
        currentStep = AutomationStep.RETRACT
        device.logger.info(LogCategory.TASK, "步骤: 退刀", taskId)
        val retract = com.cnc.device.api.DeviceCommand.rapidMove(
            "retract", taskId, com.cnc.model.drawing.Point2D(50.0, 100.0)
        )
        if (device.sendMotionCommand(retract).isFailure) throw FlowException("退刀失败")
    }

    private suspend fun runStopSpindle(taskId: String?) {
        currentStep = AutomationStep.STOP_SPINDLE
        device.logger.info(LogCategory.TASK, "步骤: 停止主轴", taskId)
        device.requestStop()
    }

    private suspend fun runUnclamp(taskId: String?) {
        currentStep = AutomationStep.UNCLAMP
        device.logger.info(LogCategory.TASK, "步骤: 松料", taskId)
        device.setOutputs(mapOf(1 to false))
    }

    private suspend fun runConfirmUnclamp(taskId: String?) {
        currentStep = AutomationStep.CONFIRM_UNCLAMP
        device.logger.info(LogCategory.TASK, "步骤: 松料反馈确认", taskId)
        val ok = waitForInput(1, false, config.unclampActionMs * 3)
        if (!ok) throw FlowException("松料超时：未收到松料反馈")
        device.logger.info(LogCategory.TASK, "松料确认", taskId)
    }

    private suspend fun runRecordResult(taskId: String?) {
        currentStep = AutomationStep.RECORD_RESULT
        device.logger.info(LogCategory.TASK, "步骤: 加工结果记录", taskId)
        // 记录结果（模拟）
    }

    /**
     * 等待输入点达到期望值。
     *
     * 使用反馈信号判断动作结果，不依靠固定延时。
     */
    private suspend fun waitForInput(point: Int, expected: Boolean, timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val inputs = device.readInputs()
            if (inputs[point] == expected) return true
            delay(20)
        }
        return false
    }

    /** 复位流程到待机状态。 */
    fun reset() {
        currentStep = AutomationStep.STANDBY
        result = null
        lastError = null
    }

    private class FlowException(message: String) : Exception(message)
}
