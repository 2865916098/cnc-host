package com.cnc.model.common

/**
 * 所有受版本控制的数据对象的基类。
 *
 * 每个加工任务必须绑定图纸、几何、工艺、刀路、刀具、设备和 IO 流程的版本。
 * 依赖参数改变后，旧的刀路校验和审核结果必须失效。
 */
interface VersionedEntity {
    val id: String
    val version: Int
    val createdAt: Long
    val updatedAt: Long
}
