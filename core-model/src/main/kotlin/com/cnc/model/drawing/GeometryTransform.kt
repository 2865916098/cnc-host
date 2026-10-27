package com.cnc.model.drawing

/**
 * 二维几何变换（图纸尺寸校正）。
 *
 * 变换顺序固定为：缩放 → 镜像 → 旋转 → 平移。
 * 该顺序必须保持稳定，CAM 与校正预览都依赖同一实现，避免结果不一致。
 */
data class Transform2D(
    val scale: Double = 1.0,
    val offsetX: Double = 0.0,
    val offsetY: Double = 0.0,
    val rotationDeg: Double = 0.0,
    val mirrorX: Boolean = false,
    val mirrorY: Boolean = false
) {
    init {
        require(scale > 0.0) { "缩放系数必须大于 0" }
    }

    /** 是否发生镜像（单轴或双轴）。 */
    val mirrored: Boolean get() = mirrorX || mirrorY

    /** 是否为奇数次镜像（决定圆弧方向、凸度符号是否翻转）。 */
    private val oddMirror: Boolean get() = mirrorX.xor(mirrorY)

    /** 变换一个点。 */
    fun applyTo(point: Point2D): Point2D {
        var x = point.x * scale
        var y = point.y * scale
        if (mirrorX) x = -x
        if (mirrorY) y = -y
        val rotated = Point2D(x, y).rotate(rotationDeg)
        return Point2D(rotated.x + offsetX, rotated.y + offsetY)
    }

    /** 变换一个角度（度），用于圆弧起止角。 */
    fun applyToAngle(angleDeg: Double): Double {
        var a = angleDeg
        if (mirrorX) a = 180.0 - a
        if (mirrorY) a = -a
        a += rotationDeg
        return normalizeAngleDeg(a)
    }

    /** 变换半径（取缩放绝对值）。 */
    fun applyToRadius(radius: Double): Double = radius * kotlin.math.abs(scale)

    /** 圆弧扫向是否反转（奇数次镜像会反转）。 */
    fun flipsSweep(originalSweepSign: Double): Double =
        if (oddMirror) -originalSweepSign else originalSweepSign

    /** 凸度符号是否翻转（奇数次镜像会翻转）。 */
    fun flipsBulge(bulge: Double): Double = if (oddMirror) -bulge else bulge

    companion object {
        /** 恒等变换。 */
        val IDENTITY = Transform2D()

        /** 是否为单位变换（无任何校正）。 */
        fun isIdentity(t: Transform2D): Boolean =
            t.scale == 1.0 && t.offsetX == 0.0 && t.offsetY == 0.0 &&
                t.rotationDeg == 0.0 && !t.mirrorX && !t.mirrorY
    }
}

/** 对几何模型应用变换。 */
object GeometryTransform {

    /** 变换整个模型；实体 id / layer 保持不变，仅几何坐标变化。 */
    fun apply(model: GeometryModel, transform: Transform2D): GeometryModel {
        if (Transform2D.isIdentity(transform)) return model
        return model.copy(
            version = model.version + 1,
            updatedAt = System.currentTimeMillis(),
            entities = model.entities.map { transformEntity(it, transform) }
        )
    }

    private fun transformEntity(entity: GeometryEntity, t: Transform2D): GeometryEntity =
        when (entity) {
            is LineEntity -> entity.copy(
                start = t.applyTo(entity.start),
                end = t.applyTo(entity.end)
            )

            is CircleEntity -> entity.copy(
                center = t.applyTo(entity.center),
                radius = t.applyToRadius(entity.radius)
            )

            is ArcEntity -> entity.copy(
                center = t.applyTo(entity.center),
                radius = t.applyToRadius(entity.radius),
                startAngleDeg = t.applyToAngle(entity.startAngleDeg),
                endAngleDeg = t.applyToAngle(entity.endAngleDeg),
                sweepSign = t.flipsSweep(entity.sweepSign)
            )

            is LwPolylineEntity -> entity.copy(
                vertices = entity.vertices.map { v ->
                    PolylineVertex(
                        point = t.applyTo(v.point),
                        bulge = t.flipsBulge(v.bulge)
                    )
                }
            )

            is UnsupportedEntity -> entity
        }
}
