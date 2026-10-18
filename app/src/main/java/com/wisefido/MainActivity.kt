/**
 * File: MainActivity.kt
 * Package: com.wisefido
 *
 * 目录：
 * 1. 属性定义
 * 2. Activity生命周期
 * 3. UI初始化和配置
 * 4. 历史记录管理
 * 5. 扫描
 * 6. 配网操作
 * 7. 设备状态查询
 * 8. 配置验证和保存
 * 9. 结果处理
 */

package com.wisefido

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.common.BleDeviceManager
import com.common.DeviceHistory
import com.common.DeviceInfo
import com.common.FilterType
import com.common.Productor
import com.common.ServerConfig
import com.common.WifiConfig
import com.bleconfig.sleepace.SleepaceBleManager
import com.espressif.espblufi.RadarBleManager
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import androidx.camera.core.Camera
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import com.google.gson.Gson
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import com.sleepace.sdk.constant.StatusCode
import com.sleepace.sdk.domain.BleDevice
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 100
        private const val CAMERA_PERMISSION_REQUEST_CODE = 300
        private const val QR_MATCH_STAGE1_TIMEOUT = 3000L
        private const val QR_MATCH_TOTAL_TIMEOUT = 10000L
        // 定义所需权限：Android 12+ 用新的蓝牙权限，以下版本 BLE 扫描依赖定位权限
        private val REQUIRED_PERMISSIONS =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.CHANGE_WIFI_STATE
                )
            else
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.CHANGE_WIFI_STATE
                )
    }

    // region 属性定义
    private lateinit var etServerAddress: TextInputEditText
    private lateinit var etServerPort: TextInputEditText
    private lateinit var deviceServerRules: DeviceServerRules
    private lateinit var etWifiSsid: TextInputEditText
    private lateinit var etWifiPassword: TextInputEditText
    private lateinit var btnPair: MaterialButton
    private lateinit var btnQuery: MaterialButton
    private lateinit var tvRecentServer: MaterialTextView
    private lateinit var tvRecentWifi: MaterialTextView
    private lateinit var rvServerHistory: RecyclerView
    private lateinit var rvWifiHistory: RecyclerView
    private lateinit var serverHistoryAdapter: ServerHistoryAdapter
    private lateinit var wifiHistoryAdapter: WifiHistoryAdapter
    private var isServerHistoryExpanded = false
    private var isWifiHistoryExpanded = false
    private lateinit var tvDeviceId: TextView
    private lateinit var tvDeviceUid: TextView
    private lateinit var tvDeviceRssi: TextView
    private lateinit var btnSearch: ImageButton
    private lateinit var btnQrScan: ImageButton
    private lateinit var layoutDeviceInfo: View
    private lateinit var configScan: ConfigStorage
    private var lastScannedBleDevice: BleDevice? = null
    private var selectedDevice: DeviceInfo? = null
    private var selectedQrCode: String? = null  // QR 匹配选中时记录原始 QR 码，用于 Device 栏显示
    private lateinit var tvStatusOutput: TextView


    // Activity Result API
// 扫描结果处理需要修改为：
    @SuppressLint("MissingPermission")
    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Log.d(TAG, "Scan result received with RESULT_OK")
            result.data?.let { intent ->
                val deviceInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getSerializableExtra(ScanActivity.EXTRA_DEVICE_INFO, DeviceInfo::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getSerializableExtra(ScanActivity.EXTRA_DEVICE_INFO) as? DeviceInfo
                }

                Log.d(TAG, "Received DeviceInfo}")
                selectedQrCode = null  // BLE 手动选择没有 QR 码
                deviceInfo?.let { onDeviceSelected(it) }
            }
        } else {
            Log.d(TAG, "Scan result received with result code: ${result.resultCode}")
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateDeviceDisplay(device: DeviceInfo?) {
        if (device == null) {
            tvDeviceId.text = getString(R.string.hint_scan_device)
            tvDeviceId.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            tvDeviceUid.text = ""
            tvDeviceRssi.text = ""
            updateActionButtonsState()
            return
        }

        // 更新设备信息显示：Ble_id | QR/uid | RSSI
        layoutDeviceInfo.visibility = View.VISIBLE
        tvDeviceId.text = device.deviceId
        tvDeviceId.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        tvDeviceUid.text = selectedQrCode ?: device.macAddress
        tvDeviceRssi.text = device.rssi.toString() + "dBm"
        updateActionButtonsState()
    }
    private val configLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val success = result.data?.getBooleanExtra("config_success", false) ?: false
            handleConfigResult(success)
        }
    }
    // endregion

    // region Activity生命周期
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        configScan = ConfigStorage(this)
        deviceServerRules = DeviceServerRules(this)
        initViews()
        setupHistoryViews()
        loadRecentConfigs()

        // 拉取在线设备服务器模板（失败则用缓存/内置默认）
        deviceServerRules.refreshFromRemote()

        RadarBleManager.getInstance(this).setErrorCallback { _, message ->
            appendStatusLine("Radar error: $message")
        }

    }
    // endregion

    // region UI初始化
    private fun initViews() {
        etServerAddress = findViewById(R.id.et_server_address)
        etServerPort = findViewById(R.id.et_server_port)
        etWifiSsid = findViewById(R.id.et_wifi_ssid)
        etWifiPassword = findViewById(R.id.et_wifi_password)
        btnPair = findViewById(R.id.btn_pair)
        btnQuery = findViewById(R.id.btn_query)
        tvRecentServer = findViewById(R.id.tv_recent_server)
        tvRecentWifi = findViewById(R.id.tv_recent_wifi)
        tvDeviceId = findViewById(R.id.tv_device_id)
        tvDeviceUid = findViewById(R.id.tv_device_uid)
        tvDeviceRssi = findViewById(R.id.tv_device_rssi)  // 添加 RSSI TextView
        layoutDeviceInfo = findViewById(R.id.layout_device_info)
        btnSearch = findViewById(R.id.btn_ble_scan)
        btnQrScan = findViewById(R.id.btn_qr_scan)
        tvStatusOutput = findViewById(R.id.tv_status_output)
        rvServerHistory = findViewById(R.id.rv_server_history)
        rvWifiHistory = findViewById(R.id.rv_wifi_history)

        serverHistoryAdapter = ServerHistoryAdapter(
            onSelect = { server ->
                etServerAddress.setText(server.serverAddress)
                etServerPort.setText("${server.protocol}${server.port}")
                collapseServerHistory()
            },
            onDelete = { server ->
                configScan.removeServerConfig(server)
                showMessage(getString(R.string.toast_history_deleted))
                loadRecentConfigs()
            }
        )
        rvServerHistory.layoutManager = LinearLayoutManager(this)
        rvServerHistory.adapter = serverHistoryAdapter
        attachServerHistorySwipe()

        wifiHistoryAdapter = WifiHistoryAdapter(
            onSelect = { wifi ->
                etWifiSsid.setText(wifi.ssid)
                etWifiPassword.setText(wifi.password)
                collapseWifiHistory()
            },
            onDelete = { wifi ->
                configScan.removeWifiConfig(wifi.ssid)
                showMessage(getString(R.string.toast_history_deleted))
                loadRecentConfigs()
            }
        )
        rvWifiHistory.layoutManager = LinearLayoutManager(this)
        rvWifiHistory.adapter = wifiHistoryAdapter


        // 添加输入过滤器，去除空格
        val noSpaceFilter = InputFilter { source, start, end, dest, dstart, dend ->
            source.toString().trim { it <= ' ' }
        }

        // 添加SSID长度限制过滤器（最大32字节）
        val ssidLengthFilter = InputFilter.LengthFilter(32)

        // 应用到所有输入框
        etServerAddress.filters = arrayOf(noSpaceFilter)
        etServerPort.filters = arrayOf(noSpaceFilter)
        etWifiSsid.filters = arrayOf(ssidLengthFilter) // 只限制长度，允许空格
        // 输入完（失去焦点）自动去掉 SSID 首尾空格，中间空格保留
        etWifiSsid.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) trimSsidField() }
        etWifiPassword.filters = arrayOf() // 不应用任何过滤器，允许所有字符包括空格

        // 初始隐藏设备信息区域
        //layoutDeviceInfo.visibility = View.GONE
        updateDeviceDisplay(null)  // 显示空状态而不是隐藏
        updateActionButtonsState()
        btnSearch.setOnClickListener {
            clearStatus()
            startScanActivity()
        }

        // 扫码入口：首页小窗扫码并自动匹配选中设备
        btnQrScan.setOnClickListener {
            if (qrMatching) {
                cancelQrMatch()
                return@setOnClickListener
            }
            // 新动作开始：清空状态栏，断开当前 GATT 连接
            clearStatus()
            disconnectAllGatt()
            // Android 9 等旧版本 BLE 扫描依赖定位权限，扫码匹配前一并检查
            val needed = (REQUIRED_PERMISSIONS + Manifest.permission.CAMERA)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (needed.isEmpty()) {
                showQrScanDialog()
            } else {
                requestPermissions(needed.toTypedArray(), CAMERA_PERMISSION_REQUEST_CODE)
            }
        }

        // 配对按钮点击事件
        btnPair.setOnClickListener {
            handlePairClick()

        }

        btnQuery.setOnClickListener {
            handleStatusClick()
        }

        tvRecentServer.setOnClickListener { toggleServerHistory() }
        tvRecentWifi.setOnClickListener { toggleWifiHistory() }

        // 设置历史记录标题的时钟图标
        val clockDrawable = ContextCompat.getDrawable(this, R.drawable.ic_history_24)
        clockDrawable?.setTint(ContextCompat.getColor(this, R.color.text_secondary))
        tvRecentServer.setCompoundDrawablesWithIntrinsicBounds(clockDrawable, null, null, null)
        tvRecentWifi.setCompoundDrawablesWithIntrinsicBounds(clockDrawable, null, null, null)

        // 加载最近配置
        loadRecentConfigs()

    }

    private fun setupHistoryViews() {
        val clockDrawable = ContextCompat.getDrawable(this, R.drawable.ic_history_24)
        clockDrawable?.setTint(ContextCompat.getColor(this, R.color.text_secondary))
        tvRecentServer.setCompoundDrawablesWithIntrinsicBounds(clockDrawable, null, null, null)
        tvRecentWifi.setCompoundDrawablesWithIntrinsicBounds(clockDrawable, null, null, null)
    }
    // endregion

    // region 历史记录管理
    private fun attachServerHistorySwipe() {
        val callback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                val target = serverHistoryAdapter.getItem(position)
                if (target != null) {
                    configScan.removeServerConfig(target)
                    showMessage(getString(R.string.toast_history_deleted))
                }
                loadRecentConfigs()
                collapseServerHistory()
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(rvServerHistory)
    }

    private fun loadRecentConfigs() {
        val recentServers = configScan.getServerConfigs()
        serverHistoryAdapter.submitList(recentServers)
        if (recentServers.isNotEmpty()) {
            tvRecentServer.text = getString(R.string.recent_servers_count, recentServers.size.coerceAtMost(5))
            tvRecentServer.isEnabled = true
            tvRecentServer.alpha = 1f
            setHistoryEntryColor(tvRecentServer, true)
            rvServerHistory.visibility = if (isServerHistoryExpanded) View.VISIBLE else View.GONE
        } else {
            tvRecentServer.text = getString(R.string.no_recent_servers)
            tvRecentServer.isEnabled = false
            tvRecentServer.alpha = 0.6f
            setHistoryEntryColor(tvRecentServer, false)
            collapseServerHistory()
        }

        val recentWifis = configScan.getWifiConfigs()
        wifiHistoryAdapter.submitList(recentWifis)
        if (recentWifis.isNotEmpty()) {
            tvRecentWifi.text = getString(R.string.recent_networks_count, recentWifis.size.coerceAtMost(5))
            tvRecentWifi.isEnabled = true
            tvRecentWifi.alpha = 1f
            setHistoryEntryColor(tvRecentWifi, true)
            rvWifiHistory.visibility = if (isWifiHistoryExpanded) View.VISIBLE else View.GONE
        } else {
            tvRecentWifi.text = getString(R.string.no_recent_networks)
            tvRecentWifi.isEnabled = false
            tvRecentWifi.alpha = 0.6f
            setHistoryEntryColor(tvRecentWifi, false)
            collapseWifiHistory()
        }
    }

    /** 有记录时用主题蓝（文字 + 时钟图标），无记录时保持灰色 */
    private fun setHistoryEntryColor(view: MaterialTextView, hasRecords: Boolean) {
        val color = ContextCompat.getColor(this, if (hasRecords) R.color.primary else R.color.text_secondary)
        view.setTextColor(color)
        // 每个入口用独立的图标实例，避免共用 Drawable 时互相改色
        val icon = ContextCompat.getDrawable(this, R.drawable.ic_history_24)?.mutate()?.apply { setTint(color) }
        view.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
    }

    private fun toggleServerHistory() {
        if (serverHistoryAdapter.itemCount == 0) return
        val newExpanded = !isServerHistoryExpanded
        if (newExpanded) {
            collapseWifiHistory()
        }
        isServerHistoryExpanded = newExpanded
        rvServerHistory.visibility = if (newExpanded) View.VISIBLE else View.GONE
    }

    private fun toggleWifiHistory() {
        if (wifiHistoryAdapter.itemCount == 0) return
        val newExpanded = !isWifiHistoryExpanded
        if (newExpanded) {
            collapseServerHistory()
        }
        isWifiHistoryExpanded = newExpanded
        rvWifiHistory.visibility = if (newExpanded) View.VISIBLE else View.GONE
    }

    private fun collapseServerHistory() {
        if (isServerHistoryExpanded || rvServerHistory.visibility != View.GONE) {
            isServerHistoryExpanded = false
            rvServerHistory.visibility = View.GONE
        }
    }

    private fun collapseWifiHistory() {
        if (isWifiHistoryExpanded || rvWifiHistory.visibility != View.GONE) {
            isWifiHistoryExpanded = false
            rvWifiHistory.visibility = View.GONE
        }
    }

    private inner class ServerHistoryAdapter(
        private val onSelect: (ServerConfig) -> Unit,
        private val onDelete: (ServerConfig) -> Unit
    ) : RecyclerView.Adapter<ServerHistoryAdapter.ServerHistoryViewHolder>() {

        private val items = mutableListOf<ServerConfig>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerHistoryViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history_server, parent, false)
            return ServerHistoryViewHolder(view)
        }

        override fun onBindViewHolder(holder: ServerHistoryViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        fun submitList(data: List<ServerConfig>) {
            items.clear()
            items.addAll(data.take(5))
            notifyDataSetChanged()
        }

        fun getItem(position: Int): ServerConfig? = items.getOrNull(position)

        inner class ServerHistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val deleteButton: TextView = itemView.findViewById(R.id.btn_delete_server)
            private val summaryView: TextView = itemView.findViewById(R.id.tv_server_summary)

            fun bind(server: ServerConfig) {
                val protocol = server.protocol.uppercase(Locale.getDefault())
                val summary = "${server.serverAddress} / $protocol ${server.port}"
                summaryView.text = summary
                itemView.setOnClickListener { onSelect(server) }
                deleteButton.setOnClickListener { onDelete(server) }
            }
        }
    }

    private inner class WifiHistoryAdapter(
        private val onSelect: (WifiConfig) -> Unit,
        private val onDelete: (WifiConfig) -> Unit
    ) : RecyclerView.Adapter<WifiHistoryAdapter.WifiHistoryViewHolder>() {

        private val items = mutableListOf<WifiConfig>()
        private var revealedPosition: Int? = null
        private val revealHandler = Handler(Looper.getMainLooper())
        private var pendingResetRunnable: Runnable? = null

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WifiHistoryViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history_wifi, parent, false)
            return WifiHistoryViewHolder(view)
        }

        override fun onBindViewHolder(holder: WifiHistoryViewHolder, position: Int) {
            holder.bind(items[position], position == revealedPosition)
        }

        override fun getItemCount(): Int = items.size

        fun submitList(data: List<WifiConfig>) {
            items.clear()
            items.addAll(data.take(5))
            resetRevealState()
            notifyDataSetChanged()
        }

        fun getItem(position: Int): WifiConfig? = items.getOrNull(position)

        inner class WifiHistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val summaryView: TextView = itemView.findViewById(R.id.tv_wifi_summary)
            private val deleteButton: TextView = itemView.findViewById(R.id.btn_delete)
            private val revealButton: ImageButton = itemView.findViewById(R.id.btn_reveal)

            fun bind(wifi: WifiConfig, showPassword: Boolean) {
                summaryView.text = buildSummaryText(wifi, showPassword)
                itemView.setOnClickListener { onSelect(wifi) }
                deleteButton.setOnClickListener { onDelete(wifi) }
                revealButton.setOnClickListener {
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        revealPasswordTemporarily(position)
                    }
                }
                revealButton.isEnabled = !showPassword
                revealButton.alpha = if (showPassword) 0.4f else 1f
            }
        }

        private fun buildSummaryText(wifi: WifiConfig, isRevealed: Boolean): String {
            val passwordDisplay = when {
                wifi.password.isEmpty() -> getString(R.string.history_wifi_password_empty)
                isRevealed -> wifi.password
                else -> "*****"
            }
            return "${wifi.ssid} / $passwordDisplay"
        }

        private fun revealPasswordTemporarily(position: Int) {
            val previous = revealedPosition
            if (previous == position) return

            previous?.let { notifyItemChanged(it) }
            revealedPosition = position
            notifyItemChanged(position)

            pendingResetRunnable?.let { revealHandler.removeCallbacks(it) }
            val runnable = Runnable {
                if (revealedPosition == position) {
                    revealedPosition = null
                    notifyItemChanged(position)
                }
            }
            pendingResetRunnable = runnable
            revealHandler.postDelayed(runnable, 3000L)
        }

        private fun resetRevealState() {
            revealedPosition = null
            pendingResetRunnable?.let { revealHandler.removeCallbacks(it) }
            pendingResetRunnable = null
        }
    }
    // endregion

    // region 扫描
    private fun startScanActivity() {
        if (REQUIRED_PERMISSIONS.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE)
            return
        }

        saveCurrentConfig()
        // 进入扫描前断开残留的 GATT 连接：设备被连接时停止广播，会扫不到
        disconnectAllGatt()
        scanLauncher.launch(Intent(this, ScanActivity::class.java))
    }

    /**
     * 选中设备的统一入口（ScanActivity 返回 / QR 匹配命中共用）
     */
    private fun onDeviceSelected(device: DeviceInfo) {
        selectedDevice = device
        updateDeviceDisplay(device)
        applyDefaultServerForDevice(device)
    }

    /**
     * 按设备前缀（Config 页 Default Device Server）自动填写服务器地址和端口
     */
    private fun applyDefaultServerForDevice(device: DeviceInfo) {
        val rule = deviceServerRules.ruleForDevice(device.deviceName, device.deviceId, selectedQrCode) ?: return
        etServerAddress.setText(rule.server)
        etServerPort.setText(rule.port.toString())
        appendStatusLine("Server set by prefix ${rule.prefix}: ${rule.displayServer()}")
    }

    /** 当前地址+端口是否走 TLS（由 Default Device Server 规则决定）；端口无效时按明文处理 */
    private fun currentPortIsTls(): Boolean {
        val port = parseProtocolAndPort(etServerPort.text.toString().trim())?.second ?: return false
        return deviceServerRules.isTlsPort(etServerAddress.text.toString(), port)
    }

    /**
     * 根据是否有选中设备，统一更新 Query / Pair 按钮可用状态
     */
    private fun updateActionButtonsState() {
        val hasDevice = selectedDevice != null
        btnQuery.isEnabled = hasDevice
        btnPair.isEnabled = hasDevice
    }

    // region 首页扫码匹配
    private var qrMatching = false
    private var qrMatchCode = ""
    private var qrOriginalCode = ""
    private val qrSeenDevices = mutableSetOf<String>()
    private val qrHandler = Handler(Looper.getMainLooper())
    private val qrStage1Runnable = Runnable { startQrStage2() }
    private val qrTimeoutRunnable = Runnable { onQrMatchTimeout() }

    /**
     * 弹出靠上的小扫码窗口（高约屏幕 40%）：CameraX 预览 + ML Kit 内置模型识别二维码
     */
    private fun showQrScanDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_qr_scan, null)
        val container = view.findViewById<android.widget.FrameLayout>(R.id.barcode_container)
        container.layoutParams.height = (resources.displayMetrics.heightPixels * 0.4).toInt()

        val previewView = PreviewView(this)
        container.addView(previewView)

        val dialog = Dialog(this)
        dialog.setContentView(view)
        dialog.window?.let { w ->
            w.setGravity(Gravity.TOP)
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val analysisExecutor = Executors.newSingleThreadExecutor()
        var camera: Camera? = null
        // 码太小时，ML Kit 能检测到位置但解不出内容，此时由它建议放大倍数（自动变焦）
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .setZoomSuggestionOptions(
                    ZoomSuggestionOptions.Builder { zoomRatio ->
                        camera?.cameraControl?.setZoomRatio(zoomRatio)
                        true
                    }.setMaxSupportedZoomRatio(4f).build()
                ).build()
        )
        val handled = AtomicBoolean(false)
        var cameraProvider: ProcessCameraProvider? = null

        view.findViewById<View>(R.id.btn_qr_close).setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            cameraProvider?.unbindAll()
            scanner.close()
            analysisExecutor.shutdown()
        }

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (!dialog.isShowing) return@addListener
            val provider = providerFuture.get()
            cameraProvider = provider
            // 分析默认只有 640x480，小二维码识别不出来；提高到 1080p
            val hiRes = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                ).build()
            val preview = Preview.Builder().setResolutionSelector(hiRes).build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(hiRes)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage == null || handled.get()) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                scanner.process(input)
                    .addOnSuccessListener { codes ->
                        val text = codes.firstNotNullOfOrNull { it.rawValue?.trim()?.takeIf { v -> v.isNotEmpty() } }
                        if (text != null && handled.compareAndSet(false, true)) {
                            dialog.dismiss()
                            startQrMatch(text)
                        }
                    }
                    .addOnCompleteListener { imageProxy.close() }
            }
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                val cam = camera!!
                // 设备码只有约 1cm，起始放大 2 倍，ML Kit 仍会按需继续建议变焦
                cam.cameraControl.setZoomRatio(2f)
                // 对画面中心每 2 秒重新对焦一次；点击预览可手动对焦
                val focusHandler = Handler(Looper.getMainLooper())
                fun focusAt(x: Float, y: Float) {
                    val point = previewView.meteringPointFactory.createPoint(x, y)
                    cam.cameraControl.startFocusAndMetering(
                        FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                            .setAutoCancelDuration(2, java.util.concurrent.TimeUnit.SECONDS).build()
                    )
                }
                val focusLoop = object : Runnable {
                    override fun run() {
                        if (!dialog.isShowing) return
                        focusAt(previewView.width / 2f, previewView.height / 2f)
                        focusHandler.postDelayed(this, 2000)
                    }
                }
                previewView.setOnTouchListener { v, ev ->
                    if (ev.action == android.view.MotionEvent.ACTION_UP) { focusAt(ev.x, ev.y); v.performClick() }
                    true
                }
                focusHandler.post(focusLoop)
            } catch (e: Exception) {
                Log.e(TAG, "QR camera bind failed", e)
                dialog.dismiss()
                showMessage(getString(R.string.qr_scan_failed))
            }
        }, ContextCompat.getMainExecutor(this))

        dialog.show()
        appendStatusLine(getString(R.string.scan_qr_title))
    }

    /**
     * 扫码成功后开始匹配设备：BM 开头走 Sleepace 直接匹配 QR 码；
     * 否则按 ESP 雷达处理，先用 "TSBLU"+后 6 位匹配，3 秒未命中再用完整 QR 码兜底
     */
    @SuppressLint("MissingPermission")
    private fun startQrMatch(code: String) {
        if (code.isEmpty()) return
        stopQrMatch()
        qrMatching = true
        qrOriginalCode = code
        qrMatchCode = if (code.startsWith("BM", ignoreCase = true)) code
        else "TSBLU" + code.takeLast(6).uppercase()
        qrSeenDevices.clear()
        appendStatusLine("QR code: $code, matching device: $qrMatchCode")
        // 匹配前断开残留的 GATT 连接，让设备恢复广播
        disconnectAllGatt()
        startQrStage1()
        qrHandler.postDelayed(qrStage1Runnable, QR_MATCH_STAGE1_TIMEOUT)
        qrHandler.postDelayed(qrTimeoutRunnable, QR_MATCH_TOTAL_TIMEOUT)
        // 不再弹进度框：匹配过程只在状态栏显示，再点一次扫码图标可取消
        appendStatusLine("Matching... tap the QR icon again to cancel")
    }

    @SuppressLint("MissingPermission")
    private fun startQrStage1() {
        if (qrMatchCode.startsWith("BM", ignoreCase = true)) {
            // Sleepace 设备
            SleepaceBleManager.getInstance(this).startScan { result ->
                runOnUiThread {
                    if (!qrMatching || result?.status != StatusCode.SUCCESS) return@runOnUiThread
                    (result.result as? DeviceInfo)?.let { dev ->
                        if (qrDeviceMatches(dev, qrMatchCode)) onQrMatchHit(dev)
                    }
                }
            }
        } else {
            // ESP 雷达设备：空前缀扫全部，回调里逐个比对
            val radar = RadarBleManager.getInstance(this)
            radar.setScanCallback { dev ->
                runOnUiThread {
                    if (!qrMatching) return@runOnUiThread
                    reportQrScannedDevice(dev)
                    if (qrDeviceMatches(dev, qrMatchCode)) onQrMatchHit(dev)
                }
            }
            radar.startScan("", FilterType.DEVICE_NAME)
        }
    }

    /**
     * 匹配过程中把扫到的设备实时打到状态栏，方便确认设备是否在广播
     */
    private fun reportQrScannedDevice(dev: DeviceInfo) {
        if (qrSeenDevices.add(dev.macAddress)) {
            appendStatusLine("Scanned: ${dev.deviceName} (${dev.macAddress})")
        }
    }

    /**
     * 3 秒未命中：ESP 雷达改用完整 QR 码兜底匹配；Sleepace 无兜底，继续原扫描
     */
    @SuppressLint("MissingPermission")
    private fun startQrStage2() {
        if (!qrMatching) return
        if (qrOriginalCode.startsWith("BM", ignoreCase = true)) return
        appendStatusLine("Trying fallback device id: $qrOriginalCode")
        qrMatchCode = qrOriginalCode
        stopQrScans()
        val radar = RadarBleManager.getInstance(this)
        radar.setScanCallback { dev ->
            runOnUiThread {
                if (!qrMatching) return@runOnUiThread
                reportQrScannedDevice(dev)
                if (qrDeviceMatches(dev, qrMatchCode)) onQrMatchHit(dev)
            }
        }
        radar.startScan("", FilterType.DEVICE_NAME)
    }

    private fun qrDeviceMatches(dev: DeviceInfo, code: String): Boolean {
        val c = code.trim()
        fun String?.eqCode() = this?.equals(c, ignoreCase = true) == true
        fun String.stripColon() = replace(":", "")
        if (dev.deviceId.eqCode() || dev.deviceName.eqCode() || dev.macAddress.eqCode() ||
                dev.macAddress.stripColon().equals(c.stripColon(), ignoreCase = true)) return true
        // 设备广播名为 "TSBLU"+MAC 后 6 位；QR 码是完整 MAC/序列号时做后缀匹配
        val suffix6 = c.stripColon().takeLast(6)
        if (suffix6.length == 6) {
            val tsblu = "TSBLU$suffix6"
            if (dev.deviceName.equals(tsblu, ignoreCase = true) ||
                    dev.deviceId.equals(tsblu, ignoreCase = true)) return true
            if (dev.macAddress.stripColon().endsWith(suffix6, ignoreCase = true)) return true
        }
        return false
    }

    private fun onQrMatchHit(device: DeviceInfo) {
        stopQrMatch()
        appendStatusLine("QR matched device: ${device.deviceId}")
        selectedQrCode = qrOriginalCode
        onDeviceSelected(device)
        Toast.makeText(this, getString(R.string.qr_device_selected, device.deviceId), Toast.LENGTH_SHORT).show()
    }

    private fun onQrMatchTimeout() {
        if (!qrMatching) return
        val code = qrOriginalCode
        stopQrMatch()
        appendStatusLine(getString(R.string.device_not_found, code))
        Toast.makeText(this, getString(R.string.device_not_found, code), Toast.LENGTH_LONG).show()
    }

    private fun stopQrScans() {
        RadarBleManager.getInstance(this).stopScan()
        SleepaceBleManager.getInstance(this).stopScan()
    }

    private fun stopQrMatch() {
        qrMatching = false
        qrHandler.removeCallbacks(qrStage1Runnable)
        qrHandler.removeCallbacks(qrTimeoutRunnable)
        stopQrScans()
    }

    private fun cancelQrMatch() {
        stopQrMatch()
        appendStatusLine("QR match canceled")
    }
    // endregion

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    // 所有权限都获取成功，启动扫描
                    startScanActivity()
                } else {
                    showMessage(getString(R.string.permissions_required))
                }
            }
            CAMERA_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    showQrScanDialog()
                } else {
                    Toast.makeText(this, getString(R.string.camera_permission_required), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // endregion

    // region配网操作

    private fun handlePairClick() {
        hideKeyboard()
        clearStatus()
        // 首先确保没有扫描在进行
        RadarBleManager.getInstance(this).stopScan()
        SleepaceBleManager.getInstance(this).stopScan()
        // 验证设备选择
        if (selectedDevice == null) {
            showMessage(getString(R.string.toast_select_device_first))
            return
        }

        // 先显示实际使用的服务器和 TLS 判断（TLS 由 Default Device Server 规则决定，首页无开关）
        val port = parseProtocolAndPort(etServerPort.text.toString().trim())?.second
        if (port != null) {
            appendStatusLine("Server: ${etServerAddress.text.toString().trim()}:$port  TLS=${if (currentPortIsTls()) "Y" else "N"}")
        }

        // 根据设备类型进行不同的验证和配网
        when (selectedDevice?.productorName) {
            Productor.radarQL, Productor.espBle -> {
                val wifiConfig = getCurrentWifiConfig()
                val serverConfig = getCurrentServerConfig()

                if (wifiConfig == null && serverConfig == null) {
                    appendStatusLine("Please enter at least a valid Wi-Fi or server configuration")
                    return
                }

                configureRadarDevice(wifiConfig, serverConfig)
            }

            Productor.sleepBoardHS -> {
                // B厂设备需要同时配置WiFi和服务器
                val serverConfig = getCurrentServerConfig()
                val wifiConfig = getCurrentWifiConfig()

                if (serverConfig == null || wifiConfig == null) {
                    showMessage("Both valid WiFi and server configuration are required")
                    return
                }

                // 两者都有效，开始配网
                startSleepConfig()
            }

            else -> showMessage(getString(R.string.toast_unknown_device_type))
        }
    }

    private fun configureRadarDevice(wifiConfig: WifiConfig?, serverConfig: ServerConfig?) {
        val device = selectedDevice ?: return
        val radarManager = RadarBleManager.getInstance(this)
        appendStatusLine("Configuring device...")

        radarManager.configureDevice(
            deviceInfo = device,
            wifiConfig = wifiConfig,
            serverConfig = serverConfig,
            statusCallback = { message -> appendStatusLine(message) }
        ) { result ->
            runOnUiThread {
                result["message"]?.let { appendStatusLine(it) }
                result["error"]?.let { appendStatusLine("Configuration failed: $it") }

                val success = result["success"]?.toBoolean() ?: false
                if (success) {
                    wifiConfig?.let { configScan.saveWifiConfig(it) }
                    serverConfig?.let { configScan.saveServerConfig(it) }
                    handleConfigResult(true)
                }
            }
        }
    }

    /** 每个动作（QR / BLE 扫描、Query、Pair）开始时清空状态栏，只保留本次动作的输出 */
    private fun clearStatus() {
        runOnUiThread { tvStatusOutput.text = "" }
    }

    /** 收起键盘并清除输入焦点（焦点离开 SSID 时会顺带去掉首尾空格） */
    private fun hideKeyboard() {
        trimSsidField()
        currentFocus?.let { view ->
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(view.windowToken, 0)
            view.clearFocus()
        }
    }

    private fun trimSsidField() {
        val text = etWifiSsid.text?.toString() ?: return
        val trimmed = text.trim()
        if (trimmed != text) etWifiSsid.setText(trimmed)
    }

    /** 断开雷达与 Sleepace 当前的 GATT 连接 */
    private fun disconnectAllGatt() {
        RadarBleManager.getInstance(this).disconnect()
        SleepaceBleManager.getInstance(this).disconnect()
    }

    private fun appendStatusLine(message: String) {
        runOnUiThread {
            val current = tvStatusOutput.text.toString()
            tvStatusOutput.text = if (current.isEmpty()) message else "$current\n$message"
        }
    }

    /**
     * B厂(Sleepace)配网实现
     */
    @SuppressLint("MissingPermission")
    private fun startSleepConfig() {
        // 获取设备信息
        val deviceInfo = selectedDevice ?: return
        // 从 BleDeviceManager 获取设备对象
        val bleDevice = BleDeviceManager.getDeviceAs(deviceInfo.macAddress, com.sleepace.sdk.domain.BleDevice::class.java)
        if (bleDevice == null) {
            showMessage(getString(R.string.toast_invalid_device))
            return
        }

        // 验证并创建配置对象
        val serverConfig = getCurrentServerConfig()
        val wifiConfig = getCurrentWifiConfig()


        // 检查是否是UDP端口
        val portStr = etServerPort.text.toString().lowercase()
        if (portStr.startsWith("udp")) {
            showMessage("Invalid port, need tcp port")
            return
        }

        // 检查server and wifi 项都存在配置
        if (serverConfig == null || wifiConfig == null) {
            showMessage(getString(R.string.toast_config_required))
            return
        }

        val sleepaceManager = SleepaceBleManager.getInstance(this)

        // 显示配置进度
        showMessage("Configuring...")

        try {
            sleepaceManager.startConfig(
                bleDevice,
                etServerAddress.text.toString(),
                etServerPort.text.toString().replace("tcp", "").replace("TCP", "").toInt(),
                currentPortIsTls(),
                etWifiSsid.text.toString().trim().toByteArray(),
                etWifiPassword.text.toString()
            ) { callbackData ->
                runOnUiThread {
                    when (callbackData.status) {
                        StatusCode.SUCCESS -> {
                            hideMessage()
                            // 与雷达一致：配网成功后记入 Recent WiFi / Recent Servers
                            configScan.saveWifiConfig(wifiConfig)
                            configScan.saveServerConfig(serverConfig)
                            // 处理配置结果
                            handleConfigResult(true)
                            if (callbackData.result is DeviceInfo) {
                                val deviceInfo = callbackData.result as DeviceInfo
                                Log.d(TAG, "WiFi configuration successful}")
                            }
                            showMessage("""
                                WiFi configuration successful.
                                  <-SleepBoard WiFi Light->
                                SleepBoard WiFi Light:
                                Solid Red->wifi connect fail
                                Flashing red-> Wifi connect success,Server Connect Fail
                                Solid Green-> wifi connect Success,Server connect Success
                            """.trimIndent())
                        }
                        StatusCode.TIMEOUT -> {
                            hideMessage()
                            handleConfigResult(false)
                            showMessage("WiFi configuration timeout")
                        }
                        StatusCode.DISCONNECT -> {
                            showMessage("Device disconnected, retrying...")
                            handleConfigResult(false)
                        }
                        StatusCode.PARAMETER_ERROR -> {
                            hideMessage()
                            handleConfigResult(false)
                            showMessage("Invalid WiFi configuration parameters")
                        }
                        else -> {
                            hideMessage()
                            handleConfigResult(false)
                            showMessage("""
                                WiFi configuration fail.
                                  <-SleepBoard WiFi Light->
                                Solid Red->wifi connect fail
                                Flashing red-> Wifi connect success,Server Connect Fail
                                Solid Green-> wifi connect Success,Server connect Success
                            """.trimIndent())
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Config error", e)
            hideMessage()
            showMessage(getString(R.string.toast_config_exception))
        }
    }

    // endregion

    // region 设备状态查询
    private fun handleStatusClick() {
        hideKeyboard()
        clearStatus()
        if (selectedDevice == null) {
            showMessage(getString(R.string.toast_select_device_first))
            return
        }

        when (selectedDevice?.productorName) {
            Productor.radarQL -> queryRadarStatus()  // 内部会输出 "Querying device status..."
            Productor.sleepBoardHS -> {
                showMessage("Querying device status...")
                querySleepaceStatus()
            }
            Productor.espBle -> {
                showMessage("Querying ESP nearby Wi-Fi...")
                queryEspStatus()
            }
            else -> showMessage(getString(R.string.toast_unknown_device_type))
        }
    }


    //@SuppressLint("MissingPermission")
    private fun queryRadarStatus() {
        val deviceInfo = selectedDevice ?: return
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        showMessage("Querying device status...")

        val radarManager = RadarBleManager.getInstance(this)

        radarManager.queryDeviceStatus(deviceInfo) { status ->
            runOnUiThread {
                val latestDeviceInfo = radarManager.getLastQueryDeviceInfo()
                val displayDevice = latestDeviceInfo ?: deviceInfo
                selectedDevice = displayDevice

                val info = StringBuilder()

                val macAddress = status["macAddress"] ?: displayDevice.macAddress
                val deviceIdDisplay = status["deviceId"] ?: displayDevice.deviceId

                info.append("deviceName:${displayDevice.deviceName}\n")
                info.append("deviceId:$deviceIdDisplay\n")
                info.append("macAddress:$macAddress\n")
                info.append("BLErssi:${displayDevice.rssi}dBm\n")

                status["uid"]?.let { uid -> info.append("uid:$uid\n") }

                val wifiMode = status["customWifiMode"] ?: status["wifiOpMode"] ?: ""
                val wifiConnected = status["customWifiConnected"]?.toBoolean() ?: status["staConnected"]?.toBoolean()
                val wifiSsid = status["customWifiSSID"] ?: status["staSSID"] ?: ""
                val wifiSignal = status["customWifiRssi"] ?: status["staRssi"] ?: "-255"

                info.append("wifiMode:${if (wifiMode.isNotEmpty()) wifiMode else "Unknown"}\n")

                val wifiSignalDisplay =
                    if (wifiSignal == "-255") "-255,not signal" else wifiSignal
                info.append("wifiSsid:$wifiSsid   wifiRssi:$wifiSignalDisplay\n")

                status["runningStatus"]?.takeIf { it.isNotBlank() }?.let { runStatus ->
                    info.append("radarRunStatus:\n")
                    runStatus.split("\r\n", "\n").forEach { line ->
                        info.append("  ")
                        info.append(line)
                        info.append('\n')
                    }
                    info.append('\n')
                } ?: info.append("radarRunStatus:\n")

                latestDeviceInfo?.nearbyWiFiNetworks?.let { networks ->
                    info.append("nearbyWiFi:\n")
                    networks.forEachIndexed { index, network ->
                        val ssid = network.ssid.ifBlank { "(hidden SSID)" }
                        val rssiValue = network.rssi?.toString() ?: ""
                        info.append("  ${index + 1}. $ssid (RSSI:$rssiValue dBm)\n")
                    }
                } ?: info.append("nearbyWiFi:\n")

                val version = status["version"] ?: ""
                if (version.isNotEmpty()) {
                    info.append("version:$version\n")
                }

                info.append("lastUpdateTime:${dateFormat.format(Date())}\n")

                if (status.containsKey("error")) {
                    info.append("error:${status["error"]}\n")
                }

                tvStatusOutput.text = info.toString()
                hideMessage()
                showMessage(if (!status.containsKey("error")) "Query completed" else "Query failed")
            }
        }
    }


    private fun queryEspStatus() {
        val deviceInfo = selectedDevice ?: return
        val radarManager = RadarBleManager.getInstance(this)
        
        radarManager.scanNearbyWiFiForDevice(deviceInfo) { updatedDevice, success ->
            runOnUiThread {
                if (!success) {
                    showMessage("Failed to scan ESP nearby Wi-Fi")
                    return@runOnUiThread
                }
                
                selectedDevice = updatedDevice
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val info = StringBuilder().apply {
                    append("deviceName:${updatedDevice.deviceName}\n")
                    append("deviceId:${updatedDevice.deviceId}\n")
                    append("macAddress:${updatedDevice.macAddress}\n")
                    append("BLErssi:${updatedDevice.rssi}dBm\n")
                    
                    updatedDevice.nearbyWiFiNetworks?.let { networks ->
                        append("nearbyWiFi:\n")
                        networks.forEachIndexed { index, network ->
                            val ssid = network.ssid.ifBlank { "(hidden SSID)" }
                            val rssiValue = network.rssi?.toString() ?: ""
                            append("  ${index + 1}. $ssid (RSSI:$rssiValue dBm)\n")
                        }
                    } ?: append("nearbyWiFi:\n")
                    
                    append("lastUpdateTime:${dateFormat.format(Date())}\n")
                }.toString()
                
                tvStatusOutput.text = info
                hideMessage()
                showMessage("Query completed")
            }
        }
    }

    /**
     * Sleepace 查询：厂家 SDK 只能读 WiFi 设置（SSID / 密码 / DHCP 或静态 IP），读不到服务器地址和 TLS
     */
    @SuppressLint("MissingPermission")
    private fun querySleepaceStatus() {
        val deviceInfo = selectedDevice ?: return
        val bleDevice = BleDeviceManager.getDeviceAs(deviceInfo.macAddress, com.sleepace.sdk.domain.BleDevice::class.java)
        if (bleDevice == null) {
            showMessage(getString(R.string.toast_invalid_device))
            return
        }
        SleepaceBleManager.getInstance(this).queryWifiConfig(bleDevice) { cd ->
            runOnUiThread {
                val info = StringBuilder()
                    .append("deviceName:${deviceInfo.deviceName}\n")
                    .append("macAddress:${deviceInfo.macAddress}\n")
                    .append("BLErssi:${deviceInfo.rssi}dBm\n")
                val setting = cd.result as? com.sleepace.sdk.wificonfig.WifiSetting
                if (cd.status == StatusCode.SUCCESS && setting != null) {
                    info.append("wifiSsid:${setting.ssid.orEmpty()}\n")
                        .append("wifiPwd:${setting.password.orEmpty()}\n")
                    if (setting.ipType == "0") {
                        info.append("ipType:DHCP\n")
                    } else {
                        info.append("ipType:Static ip:${setting.staticIp} mask:${setting.staticCode} gw:${setting.staticGateway}\n")
                    }
                    info.append("(server/TLS not readable via Sleepace SDK)")
                } else {
                    info.append("Query failed, status:${cd.status} ${cd.result ?: ""}")
                }
                appendStatusLine(info.toString())
            }
        }
    }

    // endregion

    // region 配置验证和保存
    /**
     * 解析协议和端口
     * @param input 用户输入的端口字符串，可能包含协议前缀
     * @return 返回协议和端口的Pair，如果解析失败则返回null
     */
    private fun parseProtocolAndPort(input: String): Pair<String, Int>? {
        val regex = Regex("^(tcp|udp)?(\\d+)$", RegexOption.IGNORE_CASE)
        val matchResult = regex.find(input)

        return if (matchResult != null) {
            val protocol = matchResult.groupValues[1].lowercase().ifEmpty { "tcp" }
            val port = matchResult.groupValues[2].toIntOrNull()

            if (port != null && port in 1..65535) {
                Pair(protocol, port)
            } else {
                null
            }
        } else {
            null
        }
    }

    /**
     * 验证服务器配置
     * @param address 服务器地址
     * @param portInput 端口输入字符串
     * @return 如果验证通过，返回ServerConfig对象；否则返回null
     */
    private fun validateAndCreateServerConfig(address: String, portInput: String): ServerConfig? {
        if (address.isEmpty()) return null

        val protocolAndPort = parseProtocolAndPort(portInput)
        return protocolAndPort?.let { (protocol, port) ->
            if (port > 0) {
                ServerConfig(address, port, protocol)
            } else null
        }
    }

    /**
     * 验证WiFi配置
     * @param ssid WiFi名称
     * @param password WiFi密码
     * @return 如果验证通过，返回WifiConfig对象；否则返回null
     */
    private fun validateAndCreateWifiConfig(ssid: String, password: String): WifiConfig? {
        return if (ssid.isNotEmpty()) {
            WifiConfig(ssid, password)
        } else null
    }

    /**
     * 保存当前配置
     */
    private fun saveCurrentConfig() {
        getCurrentServerConfig()?.let { configScan.saveServerConfig(it) }
        getCurrentWifiConfig()?.let { configScan.saveWifiConfig(it) }
    }

    /**
     * 获取当前UI上的服务器配置
     */
    private fun getCurrentServerConfig(): ServerConfig? {
        val address = etServerAddress.text.toString().trim()
        val portInput = etServerPort.text.toString().trim()
        return validateAndCreateServerConfig(address, portInput)
    }

    /**
     * 获取当前UI上的WiFi配置
     */
    private fun getCurrentWifiConfig(): WifiConfig? {
        val ssid = etWifiSsid.text.toString().trim()
        val password = etWifiPassword.text.toString()
        return validateAndCreateWifiConfig(ssid, password)
    }

    /**
     * 获取指定的服务器配置
     * 可在startRadarConfig/startSleepConfig中直接调用
     */
    private fun getServerConfig(address: String, portInput: String): ServerConfig? {
        return validateAndCreateServerConfig(address, portInput)
    }

    /**
     * 获取指定的WiFi配置
     * 可在startRadarConfig/startSleepConfig中直接调用
     */
    private fun getWifiConfig(ssid: String, password: String): WifiConfig? {
        return validateAndCreateWifiConfig(ssid, password)
    }

    // endregion

    // region 结果处理
    @SuppressLint("MissingPermission")
    private fun handleConfigResult(success: Boolean) {
        if (success) {
            // 获取设备的 MAC 地址和名称
            val deviceMac = selectedDevice?.macAddress ?: return
            val deviceName = selectedDevice?.deviceName ?: ""


            // 获取当前配置
            val serverConfig = getCurrentServerConfig()
            val wifiConfig = getCurrentWifiConfig()

            if (serverConfig != null || wifiConfig != null) {
                // 创建或更新设备历史记录
                val deviceHistory = DeviceHistory(
                    deviceName = deviceName,
                    macAddress = deviceMac,
                    rssi = selectedDevice?.rssi ?: -255,
                    serverConfig = serverConfig,
                    wifiSsid = wifiConfig?.ssid ?: ""
                )

                // 保存设备历史记录
                configScan.saveDeviceHistory(deviceHistory)
            }

            // 刷新UI显示
            loadRecentConfigs()

            appendStatusLine("Configuration record updated")
        }
    }

    private fun showMessage(message: String) {
        runOnUiThread {
            // 将消息追加到 tvStatusOutput 文本框中
            val currentText = tvStatusOutput.text.toString()
            val newText = if (currentText.isEmpty()) {
                message
            } else {
                "$currentText\n$message"
            }
            tvStatusOutput.text = newText
        }
    }

    private fun hideMessage() {
    }

    // endregion




      fun testServerConfigLogic() {
        val configStorage = ConfigStorage(this)

        // 清除所有现有配置
        configStorage.clearServerConfigs()
        //Log.d("ConfigTest", "已清除所有服务器配置")

        // 验证清除是否成功
        var configs = configStorage.getServerConfigs()
        //Log.d("ConfigTest", "清除后配置数量: ${configs.size}")

        // 添加5个测试配置
        for (i in 1..5) {
            val config = ServerConfig(
                serverAddress = "server$i.example.com",
                port = 1000 + i,
                protocol = "tcp",
                timestamp = System.currentTimeMillis() + i
            )
            configStorage.saveServerConfig(config)

            // 检查添加后的状态
            configs = configStorage.getServerConfigs()
            //Log.d("ConfigTest", "添加#${i}后，配置数量: ${configs.size}")
            //Log.d("ConfigTest", "当前配置列表: ${configs.map { "${it.serverAddress}:${it.port}" }}")
        }

        // 添加第6个配置，验证是否删除最后一个
        val extraConfig = ServerConfig(
            serverAddress = "extraserver.example.com",
            port = 2000,
            protocol = "tcp",
            timestamp = System.currentTimeMillis()
        )
        configStorage.saveServerConfig(extraConfig)

        // 检查是否正确删除了最后一个
        configs = configStorage.getServerConfigs()
        //Log.d("ConfigTest", "添加第6个后，配置数量: ${configs.size}")
        //Log.d("ConfigTest", "最终配置列表: ${configs.map { "${it.serverAddress}:${it.port}" }}")

        // 验证第一个是否是刚添加的
        if (configs.isNotEmpty() && configs[0].serverAddress == extraConfig.serverAddress) {
            //Log.d("ConfigTest", "测试通过：最新添加的配置在列表首位")
        } else {
            Log.e("ConfigTest", "测试失败：最新添加的配置不在列表首位")
        }

        // 验证列表大小
        if (configs.size <= 5) {
            //Log.d("ConfigTest", "测试通过：配置列表不超过5个")
        } else {
            //Log.e("ConfigTest", "测试失败：配置列表超过5个")
        }
    }

}