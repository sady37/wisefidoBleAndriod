package com.wisefido.measure

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** 三维向量（double） */
data class V3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = V3(x * s, y * s, z * s)
    operator fun div(s: Double) = V3(x / s, y / s, z / s)
    operator fun unaryMinus() = V3(-x, -y, -z)
    infix fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val length get() = sqrt(this dot this)
    fun normalized() = this / max(length, 1e-12)
}

/** 相机内参（像素）：对应 ARCore Camera.getImageIntrinsics()（CPU 图像） */
data class Intrinsics(val fx: Double, val fy: Double, val cx: Double, val cy: Double)

/**
 * 刚体变换（相机 → 世界）：列主序 4×4（ARCore Pose.toMatrix 输出），
 * 相机系 = OpenGL 约定（x 右、y 上、-z 朝前，"右/上"相对 CPU 图像读出方向）。世界系 y 向上（重力对齐）。
 */
class RigidTransform(val m: FloatArray) {
    fun apply(p: V3) = V3(
        m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12],
        m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13],
        m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14],
    )
    fun rotate(v: V3) = V3(
        m[0] * v.x + m[4] * v.y + m[8] * v.z,
        m[1] * v.x + m[5] * v.y + m[9] * v.z,
        m[2] * v.x + m[6] * v.y + m[10] * v.z,
    )
    /** 世界 → 相机 */
    fun inverseApply(p: V3): V3 {
        val d = V3(p.x - m[12], p.y - m[13], p.z - m[14])
        return V3(
            m[0] * d.x + m[1] * d.y + m[2] * d.z,
            m[4] * d.x + m[5] * d.y + m[6] * d.z,
            m[8] * d.x + m[9] * d.y + m[10] * d.z,
        )
    }
    val origin get() = V3(m[12].toDouble(), m[13].toDouble(), m[14].toDouble())
}

/**
 * 平面标记位姿解算（移植自 PlanarPose.swift）：ArUco 四角 + 实际边长 + 内参 → 码相对相机的位姿。
 * 结果在 OpenGL 相机系（x 右、y 上、z 朝后）。
 */
class PlanarPose private constructor(
    /** marker → camera：r1 r2 r3 为旋转列，t 为码平面原点（TL 角） */
    val r1: V3, val r2: V3, val r3: V3, val t: V3,
    /** 码几何中心在相机系的位置（米） */
    val centerInCamera: V3,
) {
    val distance get() = centerInCamera.length

    fun markerToCamera(u: Double, v: Double): V3 = t + r1 * u + r2 * v

    companion object {
        /** corners: TL TR BR BL（y 向下像素）。失败返回 null */
        fun solve(corners: List<PointD>, k: Intrinsics, markerSize: Double): PlanarPose? {
            if (corners.size != 4) return null
            val s = markerSize
            val plane = listOf(PointD(0.0, 0.0), PointD(s, 0.0), PointD(s, s), PointD(0.0, s))
            val h = homography(plane, corners) ?: return null
            // G = K⁻¹ H
            fun kinv(x: Double, y: Double, z: Double) = V3((x - k.cx * z) / k.fx, (y - k.cy * z) / k.fy, z)
            var g1 = kinv(h[0], h[3], h[6])
            var g2 = kinv(h[1], h[4], h[7])
            var g3 = kinv(h[2], h[5], h[8])
            var lambda = 2.0 / (g1.length + g2.length)
            // 单应分解是 OpenCV 相机系（y 下、z 前）：码在前方 ⇒ t.z > 0
            if (lambda * g3.z < 0) lambda = -lambda
            g1 *= lambda; g2 *= lambda; g3 *= lambda
            val r1cv = g1.normalized()
            val r2cv = (g2 - r1cv * (g2 dot r1cv)).normalized()
            val r3cv = r1cv cross r2cv
            // OpenCV(y下,z前) → OpenGL(y上,z后)
            fun gl(v: V3) = V3(v.x, -v.y, -v.z)
            val r1 = gl(r1cv); val r2 = gl(r2cv); val r3 = gl(r3cv); val t = gl(g3)
            val center = t + (r1 + r2) * (s / 2)
            return PlanarPose(r1, r2, r3, t, center)
        }

        /**
         * 竖直码约束精修：盒子立在地上 → 码面竖直、上下边水平。只解 码心(3) + 水平朝向(1)，
         * 对 8 个角点坐标做重投影最小二乘（LM）。返回码心世界坐标；码面不竖直/对不上返回 null。
         */
        fun uprightCenterWorld(
            corners: List<PointD>, k: Intrinsics, camToWorld: RigidTransform,
            markerSize: Double, initial: PlanarPose,
        ): V3? {
            if (corners.size != 4) return null
            val s = markerSize
            val h = s / 2
            val plane = listOf(PointD(0.0, 0.0), PointD(s, 0.0), PointD(s, s), PointD(0.0, s))
            val p0 = plane.map { camToWorld.apply(initial.markerToCamera(it.x, it.y)) }
            val c0 = (p0[0] + p0[1] + p0[2] + p0[3]) / 4.0
            val nrm = ((p0[1] - p0[0]) cross (p0[3] - p0[0])).normalized()
            if (abs(nrm.y) >= 0.5) return null
            var e = V3(-nrm.z, 0.0, nrm.x).normalized()
            val ab = p0.map { p ->
                val d = p - c0
                Pair(if ((d dot e) >= 0) 1.0 else -1.0, if (d.y >= 0) 1.0 else -1.0)
            }
            if (ab.toSet().size != 4) return null

            fun residuals(q: DoubleArray): DoubleArray? {
                e = V3(cos(q[3]), 0.0, sin(q[3]))
                val r = DoubleArray(8)
                for (i in 0 until 4) {
                    val w = V3(q[0], q[1], q[2]) + e * (ab[i].first * h) + V3(0.0, ab[i].second * h, 0.0)
                    val c = camToWorld.inverseApply(w)
                    if (c.z >= -0.05) return null
                    r[2 * i] = k.fx * c.x / -c.z + k.cx - corners[i].x
                    r[2 * i + 1] = k.cy - k.fy * c.y / -c.z - corners[i].y
                }
                return r
            }

            var q = doubleArrayOf(c0.x, c0.y, c0.z, atan2(e.z, e.x))
            var r = residuals(q) ?: return null
            var lambda = 1e-3
            repeat(30) {
                val jac = Array(8) { DoubleArray(4) }
                for (j in 0 until 4) {
                    val qp = q.copyOf()
                    val dq = if (j < 3) 1e-5 else 1e-6
                    qp[j] += dq
                    val rp = residuals(qp) ?: return null
                    for (i in 0 until 8) jac[i][j] = (rp[i] - r[i]) / dq
                }
                val a = Array(4) { DoubleArray(4) }
                val g = DoubleArray(4)
                for (x in 0 until 4) {
                    for (y in 0 until 4) {
                        var acc = 0.0
                        for (i in 0 until 8) acc += jac[i][x] * jac[i][y]
                        a[x][y] = acc
                    }
                    a[x][x] *= 1 + lambda
                    var acc = 0.0
                    for (i in 0 until 8) acc += jac[i][x] * r[i]
                    g[x] = -acc
                }
                val d = gaussianSolve(a, g) ?: return null
                val qn = DoubleArray(4) { q[it] + d[it] }
                val cost = r.sumOf { it * it }
                val rn = residuals(qn)
                if (rn != null && rn.sumOf { it * it } < cost) {
                    q = qn; r = rn; lambda = max(1e-6, lambda / 3)
                    if (maxOf(abs(d[0]), abs(d[1]), abs(d[2])) < 1e-6) return@repeat
                } else {
                    lambda *= 10
                }
            }
            val rms = sqrt(r.sumOf { it * it } / 8)
            if (rms >= 3) return null
            return V3(q[0], q[1], q[2])
        }

        /** 4 点单应（DLT）。返回行主序 3×3（h[8] = 1） */
        fun homography(src: List<PointD>, dst: List<PointD>): DoubleArray? {
            if (src.size != 4 || dst.size != 4) return null
            val a = Array(8) { DoubleArray(8) }
            val b = DoubleArray(8)
            for (i in 0 until 4) {
                val bx = src[i].x; val by = src[i].y
                val x = dst[i].x; val y = dst[i].y
                a[2 * i] = doubleArrayOf(bx, by, 1.0, 0.0, 0.0, 0.0, -x * bx, -x * by)
                b[2 * i] = x
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, bx, by, 1.0, -y * bx, -y * by)
                b[2 * i + 1] = y
            }
            val h = gaussianSolve(a, b) ?: return null
            return doubleArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0)
        }

        fun gaussianSolve(aIn: Array<DoubleArray>, bIn: DoubleArray): DoubleArray? {
            val n = bIn.size
            val m = Array(n) { aIn[it].copyOf() }
            val r = bIn.copyOf()
            for (col in 0 until n) {
                var pivot = col
                for (row in col until n) if (abs(m[row][col]) > abs(m[pivot][col])) pivot = row
                if (abs(m[pivot][col]) < 1e-12) return null
                val tm = m[col]; m[col] = m[pivot]; m[pivot] = tm
                val tr = r[col]; r[col] = r[pivot]; r[pivot] = tr
                val d = m[col][col]
                for (j in col until n) m[col][j] /= d
                r[col] /= d
                for (row in 0 until n) {
                    if (row == col) continue
                    val f = m[row][col]
                    if (f == 0.0) continue
                    for (j in col until n) m[row][j] -= f * m[col][j]
                    r[row] -= f * r[col]
                }
            }
            return r
        }
    }
}
