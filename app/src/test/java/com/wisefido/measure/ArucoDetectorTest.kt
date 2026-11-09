package com.wisefido.measure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 合成图验证（无真机时守住移植正确性）：已知相机内参 + 码位姿，透视投影渲染 DICT_4X4_50 #0，
 * 检测 → id、角点、PlanarPose 距离、竖直约束码心都应与真值吻合。
 */
class ArucoDetectorTest {

    // #0 的 6×6（1 = 黑）：外框全黑 + 内部 4×4
    private val inner = arrayOf("0100", "1010", "1100", "1101")
    private fun cellBlack(r: Int, c: Int): Boolean =
        if (r == 0 || r == 5 || c == 0 || c == 5) true else inner[r - 1][c - 1] == '1'

    private val k = Intrinsics(1400.0, 1400.0, 960.0, 540.0)
    private val w = 1920
    private val h = 1080

    /** OpenCV 相机系（y 下、z 前）：码平面点 (u,v,0) → 相机点 */
    private class Cam(val yaw: Double, val pitch: Double, val t: V3) {
        fun toCam(u: Double, v: Double): V3 {
            // 码面先绕竖直轴 yaw，再整体绕 x 轴 pitch
            val x0 = u * cos(yaw); val z0 = u * sin(yaw); val y0 = v
            val y1 = y0 * cos(pitch) - z0 * sin(pitch)
            val z1 = y0 * sin(pitch) + z0 * cos(pitch)
            return V3(x0, y1, z1) + t
        }
    }

    private fun render(cam: Cam, size: Double): GrayFrame {
        // 背景：左暗右亮的渐变（真实画面有明暗层次；纯色背景时 5 分位 = 背景，检测按设计直接放弃）
        val px = ByteArray(w * h) { (40 + 190 * (it % w) / w).toByte() }
        // 码外加 1 格白边（贴纸）
        val q = size / 6
        val pts = listOf(-q to -q, size + q to -q, size + q to size + q, -q to size + q)
            .map { (u, v) -> project(cam.toCam(u, v)) }
        val hInv = PlanarPose.homography(pts, listOf(PointD(-q, -q), PointD(size + q, -q), PointD(size + q, size + q), PointD(-q, size + q)))!!
        for (y in 0 until h) for (x in 0 until w) {
            val zz = hInv[6] * x + hInv[7] * y + hInv[8]
            val u = (hInv[0] * x + hInv[1] * y + hInv[2]) / zz
            val v = (hInv[3] * x + hInv[4] * y + hInv[5]) / zz
            if (u < -q || v < -q || u >= size + q || v >= size + q) continue
            val black = u >= 0 && v >= 0 && u < size && v < size && cellBlack((v / q).toInt(), (u / q).toInt())
            px[y * w + x] = (if (black) 25 else 235).toByte()
        }
        return GrayFrame(px, w, h, w)
    }

    private fun project(p: V3) = PointD(k.fx * p.x / p.z + k.cx, k.fy * p.y / p.z + k.cy)

    @Test
    fun detectsMarkerAndSolvesDistance() {
        val size = ArucoDetector.markerSize(0)
        val cam = Cam(yaw = 0.35, pitch = -0.25, t = V3(-0.05, 0.10, 1.6))
        val det = ArucoDetector().detect(render(cam, size))
        assertNotNull("marker not detected", det)
        det!!
        assertEquals(0, det.markerId)
        // 角点：检测顺序为画面中 TL TR BR BL，与真值逐点比对（< 1.5 px）
        val truth = listOf(0.0 to 0.0, size to 0.0, size to size, 0.0 to size).map { (u, v) -> project(cam.toCam(u, v)) }
        for (p in det.corners) {
            val best = truth.minOf { dist(it, p) }
            assertTrue("corner off by $best px", best < 1.5)
        }
        val pose = PlanarPose.solve(det.corners, k, size)!!
        val trueCenter = cam.toCam(size / 2, size / 2)
        assertEquals(trueCenter.length, pose.distance, trueCenter.length * 0.04)   // 单应位姿：小码斜视 2-3% 误差（iOS 同），测高用竖直约束精修
        // OpenGL 相机系下 y、z 取反
        assertEquals(trueCenter.x, pose.centerInCamera.x, 0.05)
        assertEquals(-trueCenter.y, pose.centerInCamera.y, 0.05)
        assertEquals(-trueCenter.z, pose.centerInCamera.z, 0.07)
    }

    @Test
    fun uprightRefineFindsCenterForVerticalMarker() {
        // 竖直码（pitch = 0）、相机水平朝前：竖直约束解应回到真码心
        val size = ArucoDetector.markerSize(0)
        val cam = Cam(yaw = 0.4, pitch = 0.0, t = V3(0.1, 0.25, 2.2))
        val det = ArucoDetector().detect(render(cam, size))!!
        val pose = PlanarPose.solve(det.corners, k, size)!!
        val identity = RigidTransform(floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f))
        val c = PlanarPose.uprightCenterWorld(det.corners, k, identity, size, pose)
        assertNotNull("upright refine failed", c)
        val t = cam.toCam(size / 2, size / 2)
        val truth = V3(t.x, -t.y, -t.z)
        assertTrue("upright center off: $c vs $truth", (c!! - truth).length < 0.01)
        assertTrue(abs(c.y - truth.y) < 0.005)
    }
}
