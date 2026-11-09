package com.wisefido.measure

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Measure：用手机测单轴角度（与 iOS AngleMeasureViewController 同一套算法与界面；
 * 风格参考 phyphox「Inclination」：深灰底、橙色强调、顶部分页、大号数值卡 + 实时曲线；代码自写）。
 *
 *   Pitch（俯仰）：绕屏幕横轴（X）转，屏幕上沿抬起为 +
 *   Roll （横滚）：绕屏幕竖轴（Y）转，屏幕右沿压低为 +
 *   Yaw  （偏航）：绕竖直轴（Z）在水平面内转，俯视逆时针为 +
 *
 * 每次只测一种，不做欧拉角换算：Pitch / Roll 由重力在「屏幕坐标」平面上的投影求角；
 * Yaw 取相对起点姿态的旋转在竖直轴上的分量（swing-twist），只用陀螺 + 加速度计（GAME_ROTATION_VECTOR，不用磁力计）。
 * 手机平放、屏幕朝上，按 ▶ 以当前姿态为零点。Pitch / Roll 支持横屏：按 ▶ 时锁定当前屏幕方向。
 */
class AngleMeasureActivity : AppCompatActivity(), SensorEventListener {

    private enum class Mode(val title: String, val axis: String, val hint: String) {
        PITCH("Pitch", "X axis", "Rotation around the screen's X axis: tilt forward / backward. Top edge up = +."),
        ROLL("Roll", "Y axis", "Rotation around the screen's Y axis: tilt left / right. Right edge down = +."),
        YAW("Yaw", "Z axis", "Rotation around the vertical Z axis: turn the phone flat on the table. Counter-clockwise (seen from above) = +."),
    }

    private object Palette {
        val background = Color.rgb(64, 64, 64)       // #404040
        val bar = Color.rgb(33, 33, 33)
        val card = Color.rgb(48, 48, 48)
        val accent = Color.rgb(255, 126, 34)         // #ff7e22
        const val text = Color.WHITE
        val secondary = Color.rgb(184, 184, 184)
        val warning = Color.rgb(255, 204, 77)
    }

    private lateinit var sensors: SensorManager
    private var mode = Mode.PITCH
    private var running = false
    private var needsZero = false
    /** 测量中锁定的屏幕旋转（Surface.ROTATION_*）；决定 Pitch/Roll 的屏幕坐标轴 */
    private var lockedRotation = Surface.ROTATION_0
    private var zeroAngle = 0.0
    private var zeroQuat = doubleArrayOf(1.0, 0.0, 0.0, 0.0)   // w x y z
    private var latestQuat: DoubleArray? = null
    private var filtered = 0.0
    private var startTime = 0L
    private var lastLabelUpdate = 0L
    private var offAxisWarning = false

    private lateinit var playButton: ImageButton
    private val tabLabels = ArrayList<TextView>()
    private val tabUnderlines = ArrayList<View>()
    private lateinit var hintLabel: TextView
    private lateinit var warningLabel: TextView
    private lateinit var nameLabel: TextView
    private lateinit var valueLabel: TextView
    private lateinit var zeroLabel: TextView
    private lateinit var graphTitle: TextView
    private lateinit var dial: AngleDialView
    private lateinit var graph: AngleGraphView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensors = getSystemService(SensorManager::class.java)
        mode = Mode.entries[savedInstanceState?.getInt("mode") ?: 0]
        window.statusBarColor = Palette.bar
        buildViews()
        selectMode(mode)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("mode", mode.ordinal)
    }

    override fun onPause() {
        super.onPause()
        stop()
    }

    // region 布局

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun px(v: Int) = dp(v.toFloat()).toInt()

    private fun buildViews() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.background)
        }
        setContentView(root)

        // 顶栏：关闭 · 标题 · ▶
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.bar)
        }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(8), 0, px(8), 0)
        }
        bar.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(52)))
        val close = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Palette.text)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "Close"
            setOnClickListener { finish() }
        }
        row.addView(close, LinearLayout.LayoutParams(px(44), px(44)))
        row.addView(TextView(this).apply {
            text = "Measure angle"; setTextColor(Palette.text); textSize = 19f
            setPadding(px(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        playButton = ImageButton(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Palette.accent)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setOnClickListener { if (running) stop() else start() }
        }
        row.addView(playButton, LinearLayout.LayoutParams(px(48), px(48)))

        // 分页：PITCH | ROLL | YAW，选中项橙色下划线
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(44)))
        for (m in Mode.entries) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setOnClickListener { selectMode(m) }
            }
            val label = TextView(this).apply {
                text = m.title.uppercase(); textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            }
            cell.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            val underline = View(this).apply { setBackgroundColor(Palette.accent) }
            cell.addView(underline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(3)))
            tabs.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            tabLabels.add(label); tabUnderlines.add(underline)
        }

        // 内容：竖屏上下排，横屏左右排（左：说明 + 数值；右：曲线）
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val content = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(px(10), px(10), px(10), px(10))
        }
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        hintLabel = TextView(this).apply { setTextColor(Palette.secondary); textSize = 14f }
        warningLabel = TextView(this).apply {
            setTextColor(Palette.warning); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            visibility = View.GONE; setPadding(0, px(6), 0, 0)
        }
        val hintCard = card(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(hintLabel); addView(warningLabel)
        })

        nameLabel = TextView(this).apply { setTextColor(Palette.text); textSize = 17f }
        zeroLabel = TextView(this).apply { setTextColor(Palette.secondary); textSize = 12f; typeface = Typeface.MONOSPACE }
        valueLabel = TextView(this).apply {
            setTextColor(Palette.text); textSize = if (landscape) 44f else 52f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.END; maxLines = 1
        }
        val nameCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(nameLabel); addView(zeroLabel)
        }
        val valueRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(nameCol, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(valueLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        dial = AngleDialView(this)
        val valueBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(valueRow)
            if (!landscape) addView(dial, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(170)).apply { topMargin = px(8) })
        }
        val valueCard = card(valueBox)

        graphTitle = TextView(this).apply { setTextColor(Palette.text); textSize = 15f }
        graph = AngleGraphView(this)
        val graphBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(graphTitle)
            addView(graph, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = px(6) })
        }
        val graphCard = card(graphBox)

        if (landscape) {
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            left.addView(hintCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            left.addView(valueCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(10) })
            content.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.45f))
            content.addView(graphCard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.55f).apply { leftMargin = px(10) })
        } else {
            content.addView(hintCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            content.addView(valueCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(10) })
            content.addView(graphCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = px(10) })
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            bar.setPadding(b.left, b.top, b.right, 0)
            content.setPadding(px(10) + b.left, px(10), px(10) + b.right, px(10) + b.bottom)
            insets
        }
    }

    private fun card(child: View): View {
        val f = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(Palette.card); cornerRadius = dp(6f) }
            setPadding(px(14), px(12), px(14), px(12))
        }
        f.addView(child, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return f
    }

    // endregion

    // region 交互

    private fun selectMode(m: Mode) {
        stop()
        mode = m
        for ((i, label) in tabLabels.withIndex()) {
            label.setTextColor(if (i == m.ordinal) Palette.text else Palette.secondary)
            tabUnderlines[i].visibility = if (i == m.ordinal) View.VISIBLE else View.INVISIBLE
        }
        nameLabel.text = "${m.title} (${m.axis})"
        graphTitle.text = "${m.title} (°) over time"
        hintLabel.text = "Lay the phone flat, screen up, then tap ▶ to set zero.\n" + m.hint +
            if (m == Mode.YAW) "" else "\nLandscape works too: turn the phone first — the screen orientation locks when you tap ▶."
        warningLabel.visibility = View.GONE
        valueLabel.text = "—"
        zeroLabel.text = " "
        dial.angle = null
        graph.clear()
        updatePlayButton()
    }

    private fun updatePlayButton() {
        playButton.setImageResource(if (running) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        playButton.contentDescription = if (running) "Stop" else "Start"
    }

    /** ▶：锁定当前屏幕方向，下一帧作为零点 */
    private fun start() {
        val gravity = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val rot = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        if (gravity == null || (mode == Mode.YAW && rot == null)) {
            warningLabel.text = "Motion sensors aren't available on this device."
            warningLabel.visibility = View.VISIBLE
            return
        }
        @Suppress("DEPRECATION")
        lockedRotation = windowManager.defaultDisplay.rotation
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        graph.clear()
        running = true
        needsZero = true
        latestQuat = null
        offAxisWarning = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updatePlayButton()
        hintLabel.text = mode.hint
        sensors.registerListener(this, gravity, SensorManager.SENSOR_DELAY_GAME)
        rot?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    /** ❚❚：停在最后读数，解锁屏幕方向 */
    private fun stop() {
        if (!running) return
        sensors.unregisterListener(this)
        running = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updatePlayButton()
    }

    // endregion

    // region 计算

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                val q = FloatArray(4)
                SensorManager.getQuaternionFromVector(q, e.values)   // w x y z，设备 → 世界
                latestQuat = doubleArrayOf(q[0].toDouble(), q[1].toDouble(), q[2].toDouble(), q[3].toDouble())
            }
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> handleGravity(e)
        }
    }

    private fun deg(r: Double) = r * 180 / Math.PI

    private fun wrap(a: Double): Double {
        var x = a % 360
        if (x > 180) x -= 360
        if (x <= -180) x += 360
        return x
    }

    private fun handleGravity(e: SensorEvent) {
        // Android 重力传感器平放朝上读 z = +9.8（支撑力方向），取反成「重力指向」并归一化
        val n = sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble())
        if (n < 1e-3) return
        val gx = -e.values[0] / n; val gy = -e.values[1] / n; val gz = -e.values[2] / n
        // 屏幕坐标：sx 指屏幕右、sy 指屏幕上
        val (sx, sy) = when (lockedRotation) {
            Surface.ROTATION_90 -> Pair(-gy, gx)     // 机身顶部朝左
            Surface.ROTATION_270 -> Pair(gy, -gx)    // 机身顶部朝右
            Surface.ROTATION_180 -> Pair(-gx, -gy)
            else -> Pair(gx, gy)
        }

        val raw: Double
        val offAxis: Double
        when (mode) {
            Mode.PITCH -> { raw = deg(atan2(-sy, -gz)); offAxis = deg(asin(sx.coerceIn(-1.0, 1.0))) }
            Mode.ROLL -> { raw = deg(atan2(sx, -gz)); offAxis = deg(asin(sy.coerceIn(-1.0, 1.0))) }
            Mode.YAW -> {
                val q = latestQuat ?: return
                if (needsZero) zeroQuat = q
                // rel = q · q0⁻¹（世界系下起点以来的转动），取绕竖直 z 轴的分量
                val rel = quatMul(q, quatConj(zeroQuat))
                var w = rel[0]; var z = rel[3]
                if (w < 0) { w = -w; z = -z }
                raw = deg(2 * atan2(z, w))
                offAxis = deg(acos((-gz).coerceIn(-1.0, 1.0)))
            }
        }

        val now = e.timestamp
        if (needsZero) {
            needsZero = false
            zeroAngle = if (mode == Mode.YAW) 0.0 else raw
            filtered = 0.0
            startTime = now
            val tilt = deg(acos((-gz).coerceIn(-1.0, 1.0)))
            zeroLabel.text = if (mode == Mode.YAW) "zero: start heading" else String.format("zero: %.1f°", zeroAngle)
            if (tilt > 10) {
                warningLabel.text = String.format("Not flat at start (tilted %.0f°). Lay the phone flat, screen up, and tap ▶ again.", tilt)
                warningLabel.visibility = View.VISIBLE
            } else {
                warningLabel.visibility = View.GONE
            }
        } else if (abs(offAxis) > 20 && (warningLabel.visibility != View.VISIBLE || offAxisWarning)) {
            offAxisWarning = true
            warningLabel.text = if (mode == Mode.YAW)
                String.format("Keep the phone flat (tilted %.0f°) — yaw is measured around the vertical axis.", abs(offAxis))
            else
                String.format("The other axis is tilted %.0f° — keep it level for an accurate %s.", abs(offAxis), mode.title.lowercase())
            warningLabel.visibility = View.VISIBLE
        } else if (offAxisWarning && abs(offAxis) <= 15) {
            offAxisWarning = false
            warningLabel.visibility = View.GONE
        }

        // 低通平滑（~0.1 s），跨 ±180° 按最短差值走
        val value = wrap(raw - zeroAngle)
        filtered = wrap(filtered + 0.2 * wrap(value - filtered))

        graph.append((now - startTime) / 1e9, filtered)
        val wall = SystemClock.uptimeMillis()
        if (wall - lastLabelUpdate >= 100) {
            lastLabelUpdate = wall
            valueLabel.text = String.format("%.1f °", filtered)
            dial.angle = filtered
        }
    }

    private fun quatMul(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3],
        a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2],
        a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1],
        a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0],
    )

    private fun quatConj(q: DoubleArray) = doubleArrayOf(q[0], -q[1], -q[2], -q[3])

    // endregion
}

private val ACCENT = Color.rgb(255, 126, 34)

/** 刻度盘：半圆量角器，橙色弧 + 指针，正值偏右；超出 ±90° 指针压到底 */
private class AngleDialView(context: Context) : View(context) {
    var angle: Double? = null
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = context.resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val r = min(width / 2f, height.toFloat()) - 14 * d
        if (r <= 10) return
        val cx = width / 2f
        val cy = height - 6 * d
        fun pt(deg: Double, radius: Float): Pair<Float, Float> {
            val a = Math.toRadians(deg - 90)
            return Pair(cx + radius * cos(a).toFloat(), cy + radius * sin(a).toFloat())
        }
        val grey = Color.rgb(140, 140, 140)
        paint.style = Paint.Style.STROKE; paint.color = grey; paint.strokeWidth = 1.5f * d
        canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), 180f, 180f, false, paint)
        paint.textSize = 10 * d
        for (deg in -90..90 step 10) {
            val major = deg % 30 == 0
            val (x1, y1) = pt(deg.toDouble(), r)
            val (x2, y2) = pt(deg.toDouble(), r - (if (major) 10 else 5) * d)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = (if (major) 1.5f else 1f) * d
            canvas.drawLine(x1, y1, x2, y2, paint)
            if (major) {
                val (lx, ly) = pt(deg.toDouble(), r - 20 * d)
                paint.style = Paint.Style.FILL
                val s = deg.toString()
                canvas.drawText(s, lx - paint.measureText(s) / 2, ly + paint.textSize / 3, paint)
            }
        }
        val a = angle ?: return
        val shown = a.coerceIn(-90.0, 90.0)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 6 * d
        paint.color = Color.argb(153, 255, 126, 34)
        canvas.drawArc(RectF(cx - r + 2 * d, cy - r + 2 * d, cx + r - 2 * d, cy + r - 2 * d), -90f, shown.toFloat(), false, paint)
        val (nx, ny) = pt(shown, r - 4 * d)
        paint.color = ACCENT; paint.strokeWidth = 3 * d; paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(cx, cy, nx, ny, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, 5 * d, paint)
        paint.strokeCap = Paint.Cap.BUTT
    }
}

/** 曲线：phyphox 式图框（深底、灰网格、橙色曲线），最近 30 s，纵轴自动缩放 */
private class AngleGraphView(context: Context) : View(context) {
    private val ts = ArrayList<Double>()
    private val vs = ArrayList<Double>()
    private val span = 30.0
    private var pending = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = context.resources.displayMetrics.density

    init {
        background = GradientDrawable().apply { setColor(Color.rgb(26, 26, 26)); cornerRadius = 3 * d }
    }

    fun clear() { ts.clear(); vs.clear(); invalidate() }

    fun append(t: Double, v: Double) {
        ts.add(t); vs.add(v)
        val cut = ts.last() - span
        var i = 0
        while (i < ts.size && ts[i] < cut) i++
        if (i > 0) { ts.subList(0, i).clear(); vs.subList(0, i).clear() }
        if (++pending >= 3) { pending = 0; invalidate() }
    }

    private fun niceStep(range: Double, ticks: Double): Double {
        val raw = range / ticks
        val mag = 10.0.pow(floor(log10(raw)))
        for (m in doubleArrayOf(1.0, 2.0, 5.0, 10.0)) if (raw <= m * mag) return m * mag
        return 10 * mag
    }

    override fun onDraw(canvas: Canvas) {
        val left = 40 * d; val top = 8 * d; val right = width - 8 * d; val bottom = height - 20 * d
        if (right - left < 10 || bottom - top < 10) return
        val pw = right - left; val ph = bottom - top
        val tMax = max(span, ts.lastOrNull() ?: 0.0)
        val tMin = tMax - span
        var lo = vs.minOrNull() ?: -1.0
        var hi = vs.maxOrNull() ?: 1.0
        if (hi - lo < 2) { val m = (hi + lo) / 2; lo = m - 1; hi = m + 1 }
        val pad = (hi - lo) * 0.1; lo -= pad; hi += pad
        fun x(t: Double) = left + ((t - tMin) / span).toFloat() * pw
        fun y(v: Double) = bottom - ((v - lo) / (hi - lo)).toFloat() * ph

        paint.textSize = 10 * d
        val grid = Color.rgb(77, 77, 77)
        val label = Color.rgb(153, 153, 153)
        val step = niceStep(hi - lo, 5.0)
        var v = ceil(lo / step) * step
        while (v <= hi) {
            paint.style = Paint.Style.STROKE; paint.color = grid; paint.strokeWidth = (if (v == 0.0) 1.2f else 0.5f) * d
            canvas.drawLine(left, y(v), right, y(v), paint)
            paint.style = Paint.Style.FILL; paint.color = label
            val s = if (step < 1) String.format("%.1f", v) else String.format("%.0f", v)
            canvas.drawText(s, left - paint.measureText(s) - 4 * d, y(v) + paint.textSize / 3, paint)
            v += step
        }
        var t = ceil(tMin / 5) * 5
        while (t <= tMax) {
            paint.style = Paint.Style.STROKE; paint.color = grid; paint.strokeWidth = 0.5f * d
            canvas.drawLine(x(t), top, x(t), bottom, paint)
            paint.style = Paint.Style.FILL; paint.color = label
            val s = String.format("%.0f", t)
            canvas.drawText(s, x(t) - paint.measureText(s) / 2, bottom + 13 * d, paint)
            t += 5
        }
        val unit = "t (s)"
        canvas.drawText(unit, right - paint.measureText(unit), bottom + 13 * d, paint)

        if (ts.size < 2) return
        val path = Path()
        path.moveTo(x(ts[0]), y(vs[0]))
        for (i in 1 until ts.size) path.lineTo(x(ts[i]), y(vs[i]))
        paint.style = Paint.Style.STROKE; paint.color = ACCENT; paint.strokeWidth = 2 * d
        paint.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(path, paint)
    }
}
