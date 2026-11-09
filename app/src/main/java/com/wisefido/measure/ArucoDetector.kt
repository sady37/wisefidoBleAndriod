package com.wisefido.measure

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 包装盒底贴 ArUco 检测（移植自 owlCare / WisefidoBle iOS 的 ArucoDetector.swift，算法一致）：
 * OpenCV DICT_4X4_50 标准图案，只认 #0 = 120 mm 盒贴 v2、#7 = 110 mm 旧盒贴。
 * 黑方块 = 6×6 网格，最外圈全黑边框 + 内部 4×4 数据位。
 * 流程：灰度图找黑色方形连通块 → 单应拉正 → 最大黑色连通块抠出真正的码 → 6×6 网格采样解码 → 亚像素角点。
 * 输入直接用相机 YUV 的 Y 平面（本身就是灰度），输出该图像的像素坐标四角（y 向下）。
 */

/** 8 bit 灰度图（行优先，rowStride ≥ width） */
class GrayFrame(val pixels: ByteArray, val width: Int, val height: Int, val rowStride: Int) {
    fun at(x: Int, y: Int): Int = pixels[y * rowStride + x].toInt() and 0xFF
}

data class PointD(val x: Double, val y: Double)

data class ArucoDetection(
    /** 图像像素坐标（y 向下），顺序：画面中看到的 左上、右上、右下、左下 */
    val corners: List<PointD>,
    /** 码相对标准朝向的顺时针旋转（0-3），仅诊断用 */
    val rotation: Int,
    /** 0 = 12 cm 盒贴 v2；7 = 11 cm 旧盒贴 */
    val markerId: Int,
) {
    val pixelSize: Double
        get() {
            var s = 0.0
            for (i in 0 until 4) {
                val a = corners[i]; val b = corners[(i + 1) % 4]
                s += hypot(b.x - a.x, b.y - a.y)
            }
            return s / 4
        }
}

class ArucoDetector {

    companion object {
        /** 实物边长（米）：0 = 盒底贴 v2，120 mm；7 = 旧盒底贴，110 mm */
        fun markerSize(id: Int): Double = if (id == 7) 0.110 else 0.120

        /** 盒子竖放（贴纸那面朝人）时码心离地（米）：v2 设计 80 mm；旧 #7 实量 83 mm */
        fun centerAboveFloor(id: Int): Double = if (id == 7) 0.083 else 0.080

        /** 内部 4×4 期望图案（行优先，1 = 黑） */
        private val basePatterns = mapOf(
            0 to intArrayOf(0, 1, 0, 0, 1, 0, 1, 0, 1, 1, 0, 0, 1, 1, 0, 1),
            7 to intArrayOf(0, 0, 1, 1, 1, 0, 1, 1, 0, 0, 0, 0, 1, 1, 0, 1),
        )

        private class Expected(val id: Int, val rot: Int, val bits: IntArray)

        /** (id, 旋转) → 期望图案 */
        private val expected: List<Expected> = buildList {
            for ((id, base) in basePatterns) {
                var cur = base
                for (r in 0 until 4) {
                    add(Expected(id, r, cur))
                    // 顺时针旋转 90°：new[r][c] = old[3-c][r]
                    val next = IntArray(16)
                    for (rr in 0 until 4) for (cc in 0 until 4) next[rr * 4 + cc] = cur[(3 - cc) * 4 + rr]
                    cur = next
                }
            }
        }
    }

    /** 角点亚像素精修开关（正常一直开） */
    var subpixel = true

    fun detect(gray: GrayFrame): ArucoDetection? {
        for (quad in findBlackSquares(gray)) {
            val r = decode(quad, gray) ?: continue
            var corners = r.third
            if (subpixel) {
                repeat(2) { refineCorners(corners, gray)?.let { corners = it } }
            }
            return ArucoDetection(corners, r.second, r.first)
        }
        return null
    }

    // region 黑色方形连通块

    private fun findBlackSquares(gray: GrayFrame): List<List<PointD>> {
        // 降采样到短边 ≤ 768
        val scaleDown = max(1, min(gray.width, gray.height) / 768)
        val sw = gray.width / scaleDown
        val sh = gray.height / scaleDown
        val small = IntArray(sw * sh)
        for (y in 0 until sh) for (x in 0 until sw) small[y * sw + x] = gray.at(x * scaleDown, y * scaleDown)

        // 阈值：暗部（5 分位）与中间亮度（60 分位）的中点
        val hist = IntArray(256)
        for (v in small) hist[v]++
        fun percentile(q: Double): Int {
            val target = (sw * sh * q).toInt()
            var acc = 0
            for (i in 0 until 256) { acc += hist[i]; if (acc > target) return i }
            return 255
        }
        val lo = percentile(0.05)
        val hi = percentile(0.60)
        if (hi - lo <= 40) return emptyList()
        val thr = lo + (hi - lo) / 2

        // 暗像素连通块（4 连通 flood fill）
        val visited = BooleanArray(sw * sh)
        val quads = ArrayList<List<PointD>>()
        val stack = IntArray(sw * sh)
        for (start in 0 until sw * sh) {
            if (visited[start]) continue
            visited[start] = true
            if (small[start] >= thr) continue
            var sp = 0
            stack[sp++] = start
            var n = 0
            var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = 0; var maxY = 0
            while (sp > 0) {
                val idx = stack[--sp]
                val x = idx % sw; val y = idx / sw
                n++
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                if (x > 0) { val m = idx - 1; if (!visited[m]) { visited[m] = true; if (small[m] < thr) stack[sp++] = m } }
                if (x < sw - 1) { val m = idx + 1; if (!visited[m]) { visited[m] = true; if (small[m] < thr) stack[sp++] = m } }
                if (y > 0) { val m = idx - sw; if (!visited[m]) { visited[m] = true; if (small[m] < thr) stack[sp++] = m } }
                if (y < sh - 1) { val m = idx + sw; if (!visited[m]) { visited[m] = true; if (small[m] < thr) stack[sp++] = m } }
            }
            val quad = squareQuad(n, minX, minY, maxX, maxY, scaleDown) ?: continue
            quads.add(quad)
            if (quads.size >= 8) return quads
        }
        return quads
    }

    /** 连通块是否像码：足够大、bbox 接近正方形（透视可到 ~2.5:1）、填充率兼容空心框 */
    private fun squareQuad(n: Int, minX: Int, minY: Int, maxX: Int, maxY: Int, scaleDown: Int): List<PointD>? {
        if (n < 180) return null
        val w = maxX - minX + 1
        val h = maxY - minY + 1
        val aspect = w.toDouble() / h
        if (aspect <= 0.35 || aspect >= 2.9) return null
        val fill = n.toDouble() / (w * h)
        if (fill <= 0.3 || fill >= 1.0) return null
        val k = scaleDown.toDouble()
        return listOf(
            PointD(minX * k, minY * k), PointD((maxX + 1) * k, minY * k),
            PointD((maxX + 1) * k, (maxY + 1) * k), PointD(minX * k, (maxY + 1) * k),
        )
    }

    // endregion

    // region 解码

    /** 候选四角 → 拉正 → 黑色区域真实四角 → 6×6 网格解码。返回 (id, rot, 精修四角) */
    private fun decode(corners: List<PointD>, gray: GrayFrame): Triple<Int, Int, List<PointD>>? {
        val src = listOf(PointD(0.0, 0.0), PointD(6.0, 0.0), PointD(6.0, 6.0), PointD(0.0, 6.0))
        val h1 = PlanarPose.homography(src, corners) ?: return null

        // 拉正到 240×240（40 px/格）
        val w = 240
        val patch = IntArray(w * w)
        for (py in 0 until w) for (px in 0 until w) {
            val p = mapH(h1, px.toDouble() / w * 6, py.toDouble() / w * 6)
            patch[py * w + px] = sample(gray, p.x, p.y)
        }
        val lo = patch.min()
        val hi = patch.max()
        if (hi - lo <= 40) return null
        val thr = (lo + hi) / 2

        // 黑色方形的四个真实角：暗像素沿两条对角线方向的极值点
        var tlX = 0; var tlY = 0; var tlS = Int.MAX_VALUE
        var trX = 0; var trY = 0; var trS = Int.MIN_VALUE
        var brX = 0; var brY = 0; var brS = Int.MIN_VALUE
        var blX = 0; var blY = 0; var blS = Int.MIN_VALUE
        for (y in 0 until w) for (x in 0 until w) {
            if (patch[y * w + x] >= thr) continue
            val xy = x + y; val xy2 = x - y
            if (xy < tlS) { tlX = x; tlY = y; tlS = xy }
            if (xy2 > trS) { trX = x; trY = y; trS = xy2 }
            if (xy > brS) { brX = x; brY = y; brS = xy }
            if (-xy2 > blS) { blX = x; blY = y; blS = -xy2 }
        }
        if ((brX - tlX) <= w * 0.2 || (brY - tlY) <= w * 0.2) return null

        val dstG = listOf(
            PointD(tlX.toDouble(), tlY.toDouble()), PointD(trX.toDouble(), trY.toDouble()),
            PointD(brX.toDouble(), brY.toDouble()), PointD(blX.toDouble(), blY.toDouble()),
        )
        val h2 = PlanarPose.homography(src, dstG) ?: return null
        val means = DoubleArray(36)
        for (r in 0 until 6) for (c in 0 until 6) {
            var sum = 0; var cnt = 0
            for (iy in 0 until 5) for (ix in 0 until 5) {
                val p = mapH(h2, c + 0.3 + 0.1 * ix, r + 0.3 + 0.1 * iy)
                val x = p.x.toInt(); val y = p.y.toInt()
                if (x < 0 || y < 0 || x >= w || y >= w) continue
                sum += patch[y * w + x]; cnt++
            }
            means[r * 6 + c] = sum.toDouble() / max(cnt, 1)
        }
        val l2 = means.min()
        val h2v = means.max()
        if (h2v - l2 <= 30) return null
        val thr2 = (l2 + h2v) / 2
        val bits = IntArray(36) { if (means[it] < thr2) 1 else 0 }

        // 外圈必须全黑，允许 2 格误差
        var borderErrors = 0
        for (i in 0 until 6) { if (bits[i] != 1) borderErrors++; if (bits[30 + i] != 1) borderErrors++ }
        for (i in 1 until 5) { if (bits[i * 6] != 1) borderErrors++; if (bits[i * 6 + 5] != 1) borderErrors++ }
        if (borderErrors > 2) return null

        // 内部 4×4 与各 ID 的四个旋转比对，允许 1 位误差
        val inner = IntArray(16) { bits[(it / 4 + 1) * 6 + (it % 4 + 1)] }
        for (e in expected) {
            var mismatch = 0
            for (i in 0 until 16) if (inner[i] != e.bits[i]) mismatch++
            if (mismatch <= 1) {
                fun toImage(px: Int, py: Int) = mapH(h1, px.toDouble() / w * 6, py.toDouble() / w * 6)
                return Triple(e.id, e.rot, listOf(toImage(tlX, tlY), toImage(trX, trY), toImage(brX, brY), toImage(blX, blY)))
            }
        }
        return null
    }

    private fun mapH(h: DoubleArray, u: Double, v: Double): PointD {
        val x = h[0] * u + h[1] * v + h[2]
        val y = h[3] * u + h[4] * v + h[5]
        val z = h[6] * u + h[7] * v + h[8]
        return PointD(x / z, y / z)
    }

    // endregion

    // region 亚像素角点

    /**
     * 沿黑框四条外边逐点找明暗过渡（法向剖面中点，插值到 0.25 px），总最小二乘拟合直线，
     * 剔除离群点再拟一次，相邻两边求交得角点。返回 null = 不可靠，保留原角点。
     */
    private fun refineCorners(c: List<PointD>, gray: GrayFrame): List<PointD>? {
        if (c.size != 4) return null
        val cx = c.sumOf { it.x } / 4
        val cy = c.sumOf { it.y } / 4
        val lines = ArrayList<DoubleArray>()   // [px, py, dx, dy]
        for (i in 0 until 4) {
            val a = c[i]; val b = c[(i + 1) % 4]
            val len = hypot(b.x - a.x, b.y - a.y)
            if (len <= 24) return null
            val dx = (b.x - a.x) / len; val dy = (b.y - a.y) / len
            var nx = -dy; var ny = dx
            if (nx * ((a.x + b.x) / 2 - cx) + ny * ((a.y + b.y) / 2 - cy) < 0) { nx = -nx; ny = -ny }
            val reach = max(2.5, min(len / 6 * 0.45, 14.0))
            val step = 0.25
            val m = (2 * reach / step).toInt() + 1
            val samples = max(10, (len / 2).toInt())
            val pts = ArrayList<PointD>()
            val prof = DoubleArray(m)
            for (k in 0 until samples) {
                val t = 0.12 + 0.76 * k / (samples - 1)
                val bx = a.x + (b.x - a.x) * t; val by = a.y + (b.y - a.y) * t
                for (j in 0 until m) {
                    val s = -reach + j * step
                    prof[j] = sampleD(gray, bx + nx * s, by + ny * s)
                }
                val q4 = max(2, m / 4)
                var dark = 0.0; var light = 0.0
                for (j in 0 until q4) { dark += prof[j]; light += prof[m - 1 - j] }
                dark /= q4; light /= q4
                if (light - dark <= 25) continue
                val thr = (dark + light) / 2
                var best: Double? = null
                for (j in 1 until m) {
                    if (prof[j - 1] < thr && prof[j] >= thr) {
                        val f = (thr - prof[j - 1]) / (prof[j] - prof[j - 1])
                        val s = -reach + (j - 1 + f) * step
                        if (best == null || abs(s) < abs(best)) best = s
                    }
                }
                best?.let { pts.add(PointD(bx + nx * it, by + ny * it)) }
            }
            if (pts.size < max(6, samples / 3)) return null
            var line = fitLine(pts) ?: return null
            val res = pts.map { abs((it.x - line[0]) * -line[3] + (it.y - line[1]) * line[2]) }
            val med = res.sorted()[res.size / 2]
            val keep = pts.filterIndexed { idx, _ -> res[idx] <= max(0.75, 3 * med) }
            if (keep.size >= 6) fitLine(keep)?.let { line = it }
            lines.add(line)
        }
        val out = ArrayList<PointD>()
        for (i in 0 until 4) {
            val x = intersect(lines[(i + 3) % 4], lines[i]) ?: return null
            val side = hypot(c[(i + 1) % 4].x - c[i].x, c[(i + 1) % 4].y - c[i].y)
            if (hypot(x.x - c[i].x, x.y - c[i].y) >= max(3.0, side * 0.08)) return null
            out.add(x)
        }
        return out
    }

    private fun fitLine(pts: List<PointD>): DoubleArray? {
        if (pts.size < 2) return null
        val mx = pts.sumOf { it.x } / pts.size
        val my = pts.sumOf { it.y } / pts.size
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        for (p in pts) { val vx = p.x - mx; val vy = p.y - my; sxx += vx * vx; sxy += vx * vy; syy += vy * vy }
        val theta = 0.5 * atan2(2 * sxy, sxx - syy)
        return doubleArrayOf(mx, my, cos(theta), sin(theta))
    }

    private fun intersect(l1: DoubleArray, l2: DoubleArray): PointD? {
        val det = l1[2] * l2[3] - l1[3] * l2[2]
        if (abs(det) <= 1e-6) return null
        val wx = l2[0] - l1[0]; val wy = l2[1] - l1[1]
        val t = (wx * l2[3] - wy * l2[2]) / det
        return PointD(l1[0] + l1[2] * t, l1[1] + l1[3] * t)
    }

    /** 双线性灰度采样，越界返回白 */
    private fun sampleD(g: GrayFrame, x: Double, y: Double): Double {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        if (x0 < 0 || y0 < 0 || x0 + 1 >= g.width || y0 + 1 >= g.height) return 255.0
        val fx = x - x0; val fy = y - y0
        val top = g.at(x0, y0) * (1 - fx) + g.at(x0 + 1, y0) * fx
        val bot = g.at(x0, y0 + 1) * (1 - fx) + g.at(x0 + 1, y0 + 1) * fx
        return top * (1 - fy) + bot * fy
    }

    private fun sample(g: GrayFrame, x: Double, y: Double): Int = sampleD(g, x, y).toInt()

    // endregion
}

/** 二维距离小工具 */
internal fun dist(a: PointD, b: PointD) = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))
