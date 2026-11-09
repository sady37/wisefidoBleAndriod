package com.wisefido.measure

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.UnavailableException
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 安装高度测量（移植自 owlCare / WisefidoBle iOS HeightMeasureViewController，ARKit → ARCore）：
 *   1. 扫盒底 ArUco 进入本页，ARCore 全程跑着提供内参 + 重力方向 + 手机位姿（世界坐标）；
 *   2. 拍「码」的照片 → 检测码（亚像素角点 + 码面竖直约束）→ 地面基准 floorY；
 *   3. 雷达在同一张里就直接点；不在就按 Radar photo 把手机朝上补拍一张再点；
 *   4. 回到码那张照片，沿虚线点雷达正下方的地面点（拖动有放大镜）→
 *      「雷达射线 × 过地面点的竖线」求交 → 高度 = 交点.y - floorY。
 * 页面锁竖屏；ARCore CPU 图像为传感器横屏方向，显示时按传感器朝向转成竖屏。
 */
class HeightMeasureActivity : AppCompatActivity(), GLSurfaceView.Renderer, FrozenShotView.Listener {

    companion object {
        const val EXTRA_HEIGHT = "height_m"
        private const val TAG = "HeightMeasure"
        private const val CAMERA_REQ = 401

        fun intent(ctx: Context) = Intent(ctx, HeightMeasureActivity::class.java)
    }

    private sealed class State {
        object Aim : State()
        object Reviewing : State()
        data class Done(val height: Double) : State()
        val allowsAim get() = this !is Aim
    }

    /** 一张照片：拍照瞬间的相机位姿/内参 + 竖屏图（缓冲为横屏） */
    private class Shot(
        val transform: RigidTransform,
        val intrinsics: Intrinsics,
        val bufW: Int, val bufH: Int,
        val clockwise: Boolean,          // 缓冲顺时针转 90° 成竖屏（传感器朝向 90°）；否则逆时针（270°）
        val image: Bitmap,
        val gray: GrayFrame,
    ) {
        fun imageToBuffer(p: PointF) =
            if (clockwise) PointD(p.y.toDouble(), bufH - p.x.toDouble()) else PointD(bufW - p.y.toDouble(), p.x.toDouble())
        fun bufferToImage(p: PointD) =
            if (clockwise) PointF((bufH - p.y).toFloat(), p.x.toFloat()) else PointF(p.y.toFloat(), (bufW - p.x).toFloat())
    }

    private class Ray(val origin: V3, val dir: V3)

    // region 视图

    private lateinit var glView: GLSurfaceView
    private lateinit var frozen: FrozenShotView
    private lateinit var statusLabel: TextView
    private lateinit var resultLabel: TextView
    private lateinit var debugLabel: TextView
    private lateinit var snapButton: View
    private lateinit var useButton: Button
    private lateinit var retryButton: Button
    private lateinit var reaimButton: Button
    private lateinit var radarPhotoButton: Button

    // endregion

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val renderer = ArBackgroundRenderer()
    private var session: Session? = null
    private var installRequested = false
    private var sensorClockwise = true
    private var cameraTextureSet = false
    @Volatile private var snapRequested = false
    private var viewportChanged = false
    private var viewportW = 0
    private var viewportH = 0

    private var state: State = State.Aim
        set(v) { field = v; updateUI() }

    private var markerShot: Shot? = null
    private var radarShot: Shot? = null
    private var shown: Shot? = null
    private var shownIsMarker = false
    private var markerQuad: List<PointD>? = null
    private var markerWorld: V3? = null
    private var floorY: Double? = null

    private enum class AimStage { RADAR, BASE }
    private var aimStage = AimStage.RADAR
    private var aimLocked = false
    private var radarRay: Ray? = null
    private var guideLine: Pair<PointD, PointD>? = null   // markerShot 缓冲坐标：估计地面点 → 其正上方 1 m

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildViews()
        updateUI()
    }

    // region 布局

    private val dp get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    private fun buildViews() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        val match = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        glView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(this@HeightMeasureActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setWillNotDraw(false)
        }
        root.addView(glView, match)

        frozen = FrozenShotView(this).apply { visibility = View.GONE; listener = this@HeightMeasureActivity }
        root.addView(frozen, match)

        statusLabel = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(px(12), px(8), px(12), px(8))
            background = GradientDrawable().apply { setColor(Color.argb(115, 0, 0, 0)); cornerRadius = 10 * dp }
        }
        root.addView(statusLabel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            setMargins(px(32), px(64), px(32), 0)
        })

        resultLabel = TextView(this).apply {
            setTextColor(Color.rgb(52, 199, 89)); textSize = 44f; typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(resultLabel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        snapButton = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(Color.rgb(255, 59, 48)); setStroke(px(4), Color.WHITE)
            }
            contentDescription = "Shutter"
            setOnClickListener { snap() }
        }
        root.addView(snapButton, FrameLayout.LayoutParams(px(76), px(76), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = px(48)
        })

        fun button(title: String, color: Int) = Button(this).apply {
            text = title; isAllCaps = false; setTextColor(Color.WHITE); textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { setColor(color); cornerRadius = 12 * dp }
            visibility = View.GONE
        }
        useButton = button("Use this height", Color.rgb(52, 199, 89))
        retryButton = button("Restart", Color.GRAY)
        reaimButton = button("Re-aim", Color.GRAY)
        radarPhotoButton = button("Radar not in photo? Take radar photo", Color.rgb(0, 122, 255))
        root.addView(useButton, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(50), Gravity.BOTTOM).apply {
            setMargins(px(40), 0, px(40), px(36))
        })
        root.addView(radarPhotoButton, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(50), Gravity.BOTTOM).apply {
            setMargins(px(40), 0, px(40), px(36))
        })
        val half = (resources.displayMetrics.widthPixels - px(80) - px(12)) / 2
        root.addView(retryButton, FrameLayout.LayoutParams(half, px(44), Gravity.BOTTOM or Gravity.START).apply {
            setMargins(px(40), 0, 0, px(36 + 50 + 12))
        })
        root.addView(reaimButton, FrameLayout.LayoutParams(half, px(44), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, px(40), px(36 + 50 + 12))
        })
        useButton.setOnClickListener { confirmResult() }
        retryButton.setOnClickListener { retake() }
        reaimButton.setOnClickListener { reaim() }
        radarPhotoButton.setOnClickListener { startRadarPhoto() }

        debugLabel = TextView(this).apply {
            setTextColor(Color.argb(178, 255, 255, 255)); textSize = 11f; typeface = Typeface.MONOSPACE
        }
        root.addView(debugLabel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(px(12), 0, 0, px(12))
        })

        val close = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(102, 0, 0, 0)) }
            contentDescription = "Close"
            setOnClickListener { finish() }
        }
        root.addView(close, FrameLayout.LayoutParams(px(40), px(40), Gravity.TOP or Gravity.START).apply {
            setMargins(px(16), px(16), 0, 0)
        })

        // 避开状态栏 / 手势条
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (close.layoutParams as FrameLayout.LayoutParams).topMargin = bars.top + px(8)
            (statusLabel.layoutParams as FrameLayout.LayoutParams).topMargin = bars.top + px(56)
            for (v in listOf(snapButton, useButton, radarPhotoButton, retryButton, reaimButton, debugLabel)) {
                val lp = v.layoutParams as FrameLayout.LayoutParams
                val base = when (v) {
                    snapButton -> px(36)
                    useButton, radarPhotoButton -> px(24)
                    retryButton, reaimButton -> px(24 + 50 + 12)
                    else -> px(4)
                }
                lp.bottomMargin = bars.bottom + base
            }
            root.requestLayout()
            insets
        }
    }

    // endregion

    // region ARCore 生命周期

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQ)
            return
        }
        if (session == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { installRequested = true; return }
                    else -> {}
                }
                val s = Session(this)
                configure(s)
                session = s
            } catch (e: UnavailableException) {
                Log.e(TAG, "ARCore unavailable", e)
                showStatus("This phone doesn't support ARCore (Google Play Services for AR)", autoHide = false)
                snapButton.visibility = View.GONE
                return
            } catch (e: Exception) {
                Log.e(TAG, "ARCore session failed", e)
                showStatus("Couldn't start AR: ${e.message}", autoHide = false)
                snapButton.visibility = View.GONE
                return
            }
        }
        try {
            session?.resume()
        } catch (e: Exception) {
            Log.e(TAG, "resume failed", e)
            showStatus("Camera unavailable — close other camera apps and retry", autoHide = false)
            session = null
            return
        }
        glView.onResume()
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
        session?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.close()
        session = null
        worker.shutdown()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQ && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            showStatus("Camera access is off. Allow it in Settings to measure.", autoHide = false)
            snapButton.visibility = View.GONE
        }
    }

    /** CPU 图像尽量高清（≤ 1920 宽，检测/放大镜更准），自动对焦，不找平面（只要位姿） */
    private fun configure(s: Session) {
        val filter = CameraConfigFilter(s).setFacingDirection(CameraConfig.FacingDirection.BACK)
        val configs = s.getSupportedCameraConfigs(filter)
        val best = configs.filter { it.imageSize.width <= 1920 }.maxByOrNull { it.imageSize.width * it.imageSize.height }
            ?: configs.firstOrNull()
        best?.let { s.cameraConfig = it }
        val cfg = Config(s).apply {
            focusMode = Config.FocusMode.AUTO
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            planeFindingMode = Config.PlaneFindingMode.DISABLED
            lightEstimationMode = Config.LightEstimationMode.DISABLED
        }
        s.configure(cfg)
        // 传感器朝向：90°（绝大多数后摄）= 顺时针转成竖屏；270° = 逆时针
        sensorClockwise = try {
            val mgr = getSystemService(CameraManager::class.java)
            val id = s.cameraConfig.cameraId
            (mgr.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90) != 270
        } catch (e: Exception) { true }
    }

    // endregion

    // region GL

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        renderer.create()
        cameraTextureSet = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportW = width; viewportH = height; viewportChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (!cameraTextureSet) { s.setCameraTextureName(renderer.textureId); cameraTextureSet = true }
        if (viewportChanged) { s.setDisplayGeometry(Surface.ROTATION_0, viewportW, viewportH); viewportChanged = false }
        val frame = try { s.update() } catch (e: Exception) { return }
        renderer.draw(frame)
        if (snapRequested) {
            snapRequested = false
            capture(frame)
        }
    }

    /** GL 线程：取 CPU 图像 + 位姿 + 内参，复制后交给后台转竖屏位图 */
    private fun capture(frame: Frame) {
        val cam = frame.camera
        if (cam.trackingState != TrackingState.TRACKING) {
            main.post { showStatus("Hold on — move the phone slowly for a second, then tap again") }
            return
        }
        val image = try { frame.acquireCameraImage() } catch (e: Exception) {
            main.post { showStatus("Camera frame not ready — tap again") }
            return
        }
        val w = image.width
        val h = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yStride = yPlane.rowStride
        val y = ByteArray(yStride * h).also { yPlane.buffer.position(0); yPlane.buffer.get(it, 0, min(it.size, yPlane.buffer.remaining())) }
        val uvRow = uPlane.rowStride
        val uvPix = uPlane.pixelStride
        val u = ByteArray(uPlane.buffer.remaining()).also { uPlane.buffer.get(it) }
        val v = ByteArray(vPlane.buffer.remaining()).also { vPlane.buffer.get(it) }
        image.close()

        val m = FloatArray(16)
        cam.pose.toMatrix(m, 0)
        val ii = cam.imageIntrinsics
        val f = ii.focalLength; val pp = ii.principalPoint; val dims = ii.imageDimensions
        val sx = w.toDouble() / dims[0]; val sy = h.toDouble() / dims[1]
        val intr = Intrinsics(f[0] * sx, f[1] * sy, pp[0] * sx, pp[1] * sy)
        val cw = sensorClockwise

        worker.execute {
            val bmp = toPortraitBitmap(y, yStride, u, v, uvRow, uvPix, w, h, cw)
            val shot = Shot(RigidTransform(m), intr, w, h, cw, bmp, GrayFrame(y, w, h, yStride))
            main.post { onShot(shot) }
        }
    }

    /** YUV_420_888 → 竖屏 ARGB（缓冲转 90°） */
    private fun toPortraitBitmap(
        y: ByteArray, yStride: Int, u: ByteArray, v: ByteArray, uvRow: Int, uvPix: Int,
        w: Int, h: Int, cw: Boolean,
    ): Bitmap {
        val outW = h; val outH = w
        val px = IntArray(outW * outH)
        for (by in 0 until h) {
            val yRow = by * yStride
            val cRow = (by shr 1) * uvRow
            for (bx in 0 until w) {
                val yy = (y[yRow + bx].toInt() and 0xFF)
                val ci = cRow + (bx shr 1) * uvPix
                val uu = (if (ci < u.size) u[ci].toInt() and 0xFF else 128) - 128
                val vv = (if (ci < v.size) v[ci].toInt() and 0xFF else 128) - 128
                val r = (yy + 1.402f * vv).toInt().coerceIn(0, 255)
                val g = (yy - 0.344f * uu - 0.714f * vv).toInt().coerceIn(0, 255)
                val b = (yy + 1.772f * uu).toInt().coerceIn(0, 255)
                val ox: Int; val oy: Int
                if (cw) { ox = h - 1 - by; oy = bx } else { ox = by; oy = w - 1 - bx }
                px[oy * outW + ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(px, outW, outH, Bitmap.Config.ARGB_8888)
    }

    // endregion

    // region 状态机

    private var statusGeneration = 0

    /** 提示语显示 1.5 s 后淡出；autoHide = false 常驻 */
    private fun showStatus(text: String, autoHide: Boolean = true) {
        statusGeneration++
        val gen = statusGeneration
        statusLabel.text = text
        statusLabel.animate().cancel()
        statusLabel.alpha = 1f
        if (!autoHide) return
        main.postDelayed({
            if (statusGeneration == gen) statusLabel.animate().alpha(0f).setDuration(300).start()
        }, 1500)
    }

    private fun updateUI() {
        when (val s = state) {
            State.Aim -> {
                showStatus(
                    if (markerShot == null) "Frame the box label (and the radar if you can), then tap the shutter"
                    else "Tilt up to the radar, keep it in view, tap the shutter",
                    autoHide = markerShot == null,
                )
                resultLabel.text = null
                frozen.visibility = View.GONE
                snapButton.visibility = View.VISIBLE
                useButton.visibility = View.GONE; reaimButton.visibility = View.GONE; radarPhotoButton.visibility = View.GONE
                retryButton.visibility = if (markerShot == null) View.GONE else View.VISIBLE
            }
            State.Reviewing -> {
                frozen.visibility = View.VISIBLE
                snapButton.visibility = View.GONE
                useButton.visibility = View.GONE; retryButton.visibility = View.VISIBLE
                reaimButton.visibility = if (radarRay == null) View.GONE else View.VISIBLE
                radarPhotoButton.visibility =
                    if (markerShot != null && radarRay == null && radarShot == null) View.VISIBLE else View.GONE
                resultLabel.text = null
            }
            is State.Done -> {
                showStatus("Radar installation height")
                resultLabel.text = String.format("%.2f m", s.height)
                frozen.visibility = View.VISIBLE
                snapButton.visibility = View.GONE
                useButton.visibility = View.VISIBLE; retryButton.visibility = View.VISIBLE; reaimButton.visibility = View.VISIBLE
                radarPhotoButton.visibility = View.GONE
            }
        }
    }

    private fun retake() {
        markerShot = null; radarShot = null; shown = null
        markerQuad = null; markerWorld = null; floorY = null
        aimLocked = false; aimStage = AimStage.RADAR
        radarRay = null; guideLine = null
        frozen.clearOverlays(); frozen.quad = null
        state = State.Aim
    }

    private fun startRadarPhoto() {
        frozen.clearOverlays()
        state = State.Aim
    }

    private fun show(shot: Shot, isMarker: Boolean) {
        shown = shot
        shownIsMarker = isMarker
        frozen.bitmap = shot.image
        frozen.quad = null
        if (isMarker) markerQuad?.let { q -> frozen.post { frozen.quad = q.mapNotNull { bufferToView(it) } } }
    }

    private fun confirmResult() {
        val s = state as? State.Done ?: return
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_HEIGHT, s.height))
        finish()
    }

    // endregion

    // region 拍照 → 检测 ArUco

    private fun snap() {
        snapButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        snapRequested = true
    }

    private fun onShot(shot: Shot) {
        if (markerShot != null) {
            // 补拍的雷达照片：不需要码
            radarShot = shot
            show(shot, isMarker = false)
            aimStage = AimStage.RADAR
            aimLocked = false
            state = State.Reviewing
            showStatus("Tap the center of the radar, or drag below it to fine-aim", autoHide = false)
            return
        }
        show(shot, isMarker = false)
        state = State.Reviewing
        showStatus("Reading the marker…", autoHide = false)
        worker.execute {
            val det = ArucoDetector().detect(shot.gray)
            main.post { onMarkerDetected(shot, det) }
        }
    }

    private fun onMarkerDetected(shot: Shot, det: ArucoDetection?) {
        if (shown !== shot) return
        val size = det?.let { ArucoDetector.markerSize(it.markerId) } ?: 0.0
        val pose = det?.let { PlanarPose.solve(it.corners, shot.intrinsics, size) }
        if (det == null || pose == null) {
            showStatus("Marker not readable — move closer and retake", autoHide = false)
            return
        }
        // 盒子立在地上 → 码面竖直：4 自由度约束解（失败退回单应位姿）
        val upright = PlanarPose.uprightCenterWorld(det.corners, shot.intrinsics, shot.transform, size, pose)
        val world = upright ?: shot.transform.apply(pose.centerInCamera)
        markerWorld = world
        val fy = world.y - ArucoDetector.centerAboveFloor(det.markerId)
        floorY = fy
        markerQuad = det.corners
        markerShot = shot
        show(shot, isMarker = true)
        debugLabel.text = String.format("id%d %.0fpx d:%.2f camH:%.2f %s",
            det.markerId, det.pixelSize, pose.distance, shot.transform.origin.y - fy, if (upright == null) "H" else "U")
        aimLocked = false
        state = State.Reviewing
        showStatus(
            if (det.pixelSize < 60) "Marker is small — closer is more accurate. Tap the radar, or take the radar as a 2nd photo"
            else "Tap the center of the radar — not in this photo? Take a radar photo",
            autoHide = false,
        )
    }

    // endregion

    // region 两点瞄准

    override fun onTap(p: PointF) {
        if (!state.allowsAim) return
        if (aimLocked) { unlockAim(); return }
        lockAim(p)
    }

    override fun onDrag(aim: PointF) {
        if (!state.allowsAim) return
        aimLocked = false
        frozen.reticleDragging = true
        frozen.stageColor = stageColor
        frozen.reticle = aim
        frozen.loupeAt = aim
        measure(aim, lock = false)
    }

    override fun onDragEnd(aim: PointF) {
        if (!state.allowsAim) return
        frozen.loupeAt = null
        lockAim(aim)
    }

    private val stageColor get() = if (aimStage == AimStage.RADAR) Color.rgb(255, 59, 48) else Color.rgb(0, 122, 255)

    private fun unlockAim() {
        aimLocked = false
        frozen.reticle = null
        if (state is State.Done) state = State.Reviewing
        if (aimStage == AimStage.BASE) {
            showStatus("Re-aim the floor point on the dashed line, tap to lock")
        } else {
            frozen.pin = null; frozen.guide = null
            radarRay = null; guideLine = null
            showStatus("Re-aim the radar, tap to lock")
        }
    }

    private fun reaim() {
        aimLocked = false
        aimStage = AimStage.RADAR
        radarRay = null; guideLine = null
        frozen.clearOverlays()
        radarShot?.let { show(it, false) } ?: markerShot?.let { show(it, true) }
        state = State.Reviewing
        showStatus("Tap the center of the radar, or drag below it to fine-aim")
    }

    private fun lockAim(p: PointF) {
        frozen.reticleDragging = false
        frozen.stageColor = stageColor
        frozen.reticle = p
        if (measure(p, lock = true) != null) aimLocked = true
    }

    private fun viewToBuffer(p: PointF): PointD? {
        val shot = shown ?: return null
        val img = frozen.viewToImage(p) ?: return null
        return shot.imageToBuffer(img)
    }

    private fun bufferToView(p: PointD): PointF? {
        val shot = shown ?: return null
        return frozen.imageToView(shot.bufferToImage(p))
    }

    /** 缓冲像素 → 世界射线 */
    private fun rayFor(p: PointD, shot: Shot): Ray {
        val k = shot.intrinsics
        val rayCam = V3((p.x - k.cx) / k.fx, -(p.y - k.cy) / k.fy, -1.0).normalized()
        return Ray(shot.transform.origin, shot.transform.rotate(rayCam).normalized())
    }

    /** 世界点 → 缓冲像素 */
    private fun projectToBuffer(w: V3, shot: Shot): PointD? {
        val c = shot.transform.inverseApply(w)
        if (c.z >= -0.05) return null
        val k = shot.intrinsics
        return PointD(k.fx * c.x / -c.z + k.cx, k.cy - k.fy * c.y / -c.z)
    }

    private fun nearestOnLine(p: PointD, a: PointD, b: PointD): PointD {
        val dx = b.x - a.x; val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 <= 1e-6) return a
        val t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2
        return PointD(a.x + dx * t, a.y + dy * t)
    }

    /** 当前视图点 → 按阶段推进。lock = false 只实时刷新读数，lock = true 锁定并进入下一阶段/出结果 */
    private fun measure(point: PointF, lock: Boolean): Double? {
        val fy = floorY ?: return null
        val marker = markerWorld ?: return null
        val mShot = markerShot ?: return null
        val shot = shown ?: return null
        val px = viewToBuffer(point) ?: return null

        when (aimStage) {
            AimStage.RADAR -> {
                if (!lock) return 0.0
                val ray = rayFor(px, shot)
                radarRay = ray
                // 估计雷达正下方地面点：雷达射线上与码等水平距离处（盒子就在杆底/墙脚）
                val rho0 = max(0.5, hypot(marker.x - ray.origin.x, marker.z - ray.origin.z))
                val hl = max(1e-9, hypot(ray.dir.x, ray.dir.z))
                val base0 = V3(ray.origin.x + ray.dir.x / hl * rho0, fy, ray.origin.z + ray.dir.z / hl * rho0)
                val up0 = base0 + V3(0.0, 1.0, 0.0)
                if (!shownIsMarker) show(mShot, isMarker = true)
                frozen.clearOverlays()
                val b0 = projectToBuffer(base0, mShot)
                val u0 = projectToBuffer(up0, mShot)
                guideLine = if (b0 != null && u0 != null) Pair(b0, u0) else null
                frozen.post {
                    guideLine?.let { g ->
                        val vb = bufferToView(g.first); val vu = bufferToView(g.second)
                        frozen.guide = if (vb != null && vu != null) Pair(vb, vu) else null
                    }
                    // 雷达在这张里能看到就留红钉
                    val top = ray.origin + ray.dir * (rho0 / max(1e-3, hl))
                    projectToBuffer(top, mShot)?.let { tp ->
                        bufferToView(tp)?.let { tv ->
                            if (tv.x in 0f..frozen.width.toFloat() && tv.y in 0f..frozen.height.toFloat()) frozen.pin = tv
                        }
                    }
                }
                aimStage = AimStage.BASE
                showStatus("Tap where the dashed line meets the floor — drag for a magnifier", autoHide = false)
                return 0.0
            }
            AimStage.BASE -> {
                val ballRay = radarRay ?: return null
                // 地面点吸附到参考线上
                val snapped = guideLine?.let { nearestOnLine(px, it.first, it.second) } ?: px
                val baseRay = rayFor(snapped, mShot)
                if (baseRay.dir.y >= -0.02) {
                    if (lock) showStatus("The floor point must be below the camera — aim lower", autoHide = false)
                    return null
                }
                val t = (fy - baseRay.origin.y) / baseRay.dir.y
                val base = baseRay.origin + baseRay.dir * t
                // 雷达射线 × 过地面点的竖线 → 高度
                val num = ballRay.dir.x * (base.x - ballRay.origin.x) + ballRay.dir.z * (base.z - ballRay.origin.z)
                val den = ballRay.dir.x * ballRay.dir.x + ballRay.dir.z * ballRay.dir.z
                if (den <= 1e-6) return null
                val d = num / den
                if (d <= 0.3 || d >= 15) {
                    if (lock) showStatus("Aim looks off — drag again or restart", autoHide = false)
                    return null
                }
                val height = ballRay.origin.y + ballRay.dir.y * d - fy
                if (height <= 0.3 || height >= 5.0) {
                    if (lock) showStatus(String.format("Implausible %.2f m — drag again or restart", height), autoHide = false)
                    return null
                }
                debugLabel.text = String.format("d:%.2f camH:%.2f %s", d, ballRay.origin.y - fy,
                    if (radarShot == null) "1 photo" else "2 photos")
                if (lock) state = State.Done(height) else resultLabel.text = String.format("%.2f m", height)
                return height
            }
        }
    }

    // endregion
}
