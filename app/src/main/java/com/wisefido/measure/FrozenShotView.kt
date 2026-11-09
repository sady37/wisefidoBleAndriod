package com.wisefido.measure

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * 冻结照片 + 覆盖层（对应 iOS frozenView 上的 CAShapeLayer 们）：
 * 竖屏照片 aspectFit 显示；绿框（码）、红虚线（雷达正下方参考线）、红钉（雷达）、瞄准镜、放大镜。
 * 坐标：view 点 ↔ 竖屏图像像素，由 Activity 再换算到相机缓冲。
 */
class FrozenShotView(context: Context) : View(context) {

    interface Listener {
        fun onTap(p: PointF)
        /** 拖动中：aim = 瞄准点（触点上方） */
        fun onDrag(aim: PointF)
        fun onDragEnd(aim: PointF)
    }

    var listener: Listener? = null
    var bitmap: Bitmap? = null
        set(v) { field = v; invalidate() }

    var quad: List<PointF>? = null
        set(v) { field = v; invalidate() }
    /** 参考线：地面点、其正上方点（view 坐标），绘制时向两端延长 */
    var guide: Pair<PointF, PointF>? = null
        set(v) { field = v; invalidate() }
    var pin: PointF? = null
        set(v) { field = v; invalidate() }
    var reticle: PointF? = null
        set(v) { field = v; invalidate() }
    var reticleDragging = false
    var stageColor = Color.RED
    var loupeAt: PointF? = null
        set(v) { field = v; invalidate() }

    private val dp = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    init { setBackgroundColor(Color.BLACK) }

    /** aspectFit 缩放与偏移 */
    private fun fit(): Triple<Float, Float, Float>? {
        val b = bitmap ?: return null
        if (width == 0 || height == 0) return null
        val s = min(width.toFloat() / b.width, height.toFloat() / b.height)
        return Triple(s, (width - b.width * s) / 2, (height - b.height * s) / 2)
    }

    fun viewToImage(p: PointF): PointF? {
        val b = bitmap ?: return null
        val (s, ox, oy) = fit() ?: return null
        val ix = (p.x - ox) / s; val iy = (p.y - oy) / s
        if (ix < 0 || iy < 0 || ix >= b.width || iy >= b.height) return null
        return PointF(ix, iy)
    }

    fun imageToView(p: PointF): PointF? {
        val (s, ox, oy) = fit() ?: return null
        return PointF(ox + p.x * s, oy + p.y * s)
    }

    fun clearOverlays() {
        guide = null; pin = null; reticle = null; loupeAt = null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val aim = PointF(e.x, e.y - 110 * dp)   // 瞄准点在触点上方，不挡视线
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; dragging = false }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(e.x - downX, e.y - downY) > 12 * dp) dragging = true
                if (dragging) listener?.onDrag(aim)
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) listener?.onDragEnd(aim) else { performClick(); listener?.onTap(PointF(e.x, e.y)) }
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> { if (dragging) listener?.onDragEnd(aim); dragging = false }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bitmap ?: return
        val (s, ox, oy) = fit() ?: return
        canvas.drawBitmap(b, null, RectF(ox, oy, ox + b.width * s, oy + b.height * s), bmpPaint)

        quad?.let { q ->
            if (q.size == 4) {
                val path = Path().apply { moveTo(q[0].x, q[0].y); for (p in q.drop(1)) lineTo(p.x, p.y); close() }
                paint.reset(); paint.isAntiAlias = true
                paint.style = Paint.Style.FILL; paint.color = Color.argb(26, 52, 199, 89)
                canvas.drawPath(path, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 3 * dp; paint.color = Color.rgb(52, 199, 89)
                canvas.drawPath(path, paint)
            }
        }
        guide?.let { (vb, vu) ->
            val len = maxOf(1f, hypot(vu.x - vb.x, vu.y - vb.y))
            val ux = (vu.x - vb.x) / len; val uy = (vu.y - vb.y) / len
            paint.reset(); paint.isAntiAlias = true
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f * dp
            paint.color = Color.argb(204, 255, 59, 48)
            paint.pathEffect = DashPathEffect(floatArrayOf(6 * dp, 5 * dp), 0f)
            canvas.drawLine(vb.x - ux * 120 * dp, vb.y - uy * 120 * dp, vb.x + ux * 3000 * dp, vb.y + uy * 3000 * dp, paint)
            paint.pathEffect = null
        }
        pin?.let {
            paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.FILL; paint.color = Color.rgb(255, 59, 48)
            canvas.drawCircle(it.x, it.y, 5 * dp, paint)
        }
        reticle?.let { p ->
            paint.reset(); paint.isAntiAlias = true
            if (reticleDragging) {
                paint.style = Paint.Style.FILL; paint.color = withAlpha(stageColor, 102)
                canvas.drawCircle(p.x, p.y, 9 * dp, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 1 * dp; paint.color = withAlpha(stageColor, 178)
                canvas.drawCircle(p.x, p.y, 9 * dp, paint)
            } else {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f * dp; paint.color = stageColor
                canvas.drawCircle(p.x, p.y, 30 * dp, paint)
                val c = 8 * dp
                canvas.drawLine(p.x - c, p.y, p.x + c, p.y, paint)
                canvas.drawLine(p.x, p.y - c, p.x, p.y + c, paint)
            }
        }
        loupeAt?.let { p -> drawLoupe(canvas, b, s, ox, oy, p) }
    }

    /** 放大镜：左上角圆形，3 倍放大瞄准点周围 */
    private fun drawLoupe(canvas: Canvas, b: Bitmap, s: Float, ox: Float, oy: Float, p: PointF) {
        val size = 140 * dp
        val left = 16 * dp
        val top = 120 * dp
        val ix = (p.x - ox) / s; val iy = (p.y - oy) / s
        val span = size / (s * 3)
        val src = Rect((ix - span / 2).toInt(), (iy - span / 2).toInt(), (ix + span / 2).toInt(), (iy + span / 2).toInt())
        val dst = RectF(left, top, left + size, top + size)
        canvas.save()
        val clip = Path().apply { addCircle(dst.centerX(), dst.centerY(), size / 2, Path.Direction.CW) }
        canvas.clipPath(clip)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(b, src, dst, bmpPaint)
        canvas.restore()
        paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3 * dp; paint.color = Color.WHITE
        canvas.drawCircle(dst.centerX(), dst.centerY(), size / 2, paint)
        paint.strokeWidth = 1.5f * dp; paint.color = stageColor
        val c = 12 * dp
        canvas.drawLine(dst.centerX() - c, dst.centerY(), dst.centerX() + c, dst.centerY(), paint)
        canvas.drawLine(dst.centerX(), dst.centerY() - c, dst.centerX(), dst.centerY() + c, paint)
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
}
