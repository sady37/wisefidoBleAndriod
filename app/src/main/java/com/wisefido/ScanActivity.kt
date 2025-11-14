package com.wisefido

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.app.Dialog
import android.bluetooth.le.ScanResult


//common
import com.common.DeviceInfo
import com.common.Productor
import com.common.FilterType
import com.common.DeviceHistory
import com.common.DefaultConfig
import com.common.BleUtils.toBleResult
import com.common.BleDeviceManager

import com.espressif.espblufi.RadarBleManager
import com.bleconfig.sleepace.SleepaceBleManager
import com.sleepace.sdk.domain.BleDevice
import com.sleepace.sdk.constant.StatusCode



class ConfigDialog(context: Context) : Dialog(context) {
    private lateinit var etRadarName: EditText
    private lateinit var radioFilterType: RadioGroup

    // 配置更改监听器
    private var onConfigChangedListener: ((String, FilterType) -> Unit)? = null

    init {
        // 设置对话框布局
        setContentView(R.layout.popup_configure)

        // 绑定视图组件
        etRadarName = findViewById(R.id.et_radar_name)
        radioFilterType = findViewById(R.id.radio_filter_type)

        // 确认按钮点击事件
        findViewById<Button>(R.id.btn_confirm).setOnClickListener {
            // 获取用户输入的雷达设备名称
            val radarName = etRadarName.text.toString()

            // 获取用户选择的过滤器类型
            val filterType = when (radioFilterType.checkedRadioButtonId) {
                R.id.radio_device_name -> FilterType.DEVICE_NAME
                R.id.radio_mac -> FilterType.MAC
                R.id.radio_uuid -> FilterType.UUID
                else -> FilterType.DEVICE_NAME
            }

            // 触发配置更改监听器
            onConfigChangedListener?.invoke(radarName, filterType)

            // 关闭对话框
            dismiss()
        }

        // 取消按钮点击事件
        findViewById<Button>(R.id.btn_cancel).setOnClickListener {
            // 关闭对话框
            dismiss()
        }
    }

    /**
     * 设置雷达设备名称
     */
    fun setRadarDeviceName(name: String) {
        etRadarName.setText(name)
    }

    /**
     * 设置过滤器类型
     */
    fun setFilterType(filterType: FilterType) {
        when (filterType) {
            FilterType.DEVICE_NAME -> radioFilterType.check(R.id.radio_device_name)
            FilterType.MAC -> radioFilterType.check(R.id.radio_mac)
            FilterType.UUID -> radioFilterType.check(R.id.radio_uuid)
        }
    }

    /**
     * 设置配置更改监听器
     */
    fun setOnConfigChangedListener(listener: (String, FilterType) -> Unit) {
        this.onConfigChangedListener = listener
    }
}

class ScanActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "ScanActivity"
        private const val PERMISSION_REQUEST_CODE = 100
        private const val REQUEST_SCAN_SLEEPACE = 200   // B厂扫描
        private const val REQUEST_SCAN_RADAR = 201   // A厂扫描
        const val EXTRA_DEVICE = "extra_device"
        const val EXTRA_DEVICE_TYPE = "device_type"
        const val EXTRA_DEVICE_INFO = "extra_device_info"  // 添加这一行
        private const val SCAN_TIMEOUT = 10500L  // 与 RadarBleManager+500 的超时时间
    }

    // 视图组件
    private lateinit var radioGroup: RadioGroup
    private lateinit var radioRadar: RadioButton
    private lateinit var radioSleepace: RadioButton
    private lateinit var radioFilter:  RadioButton
    private lateinit var inputFilter: EditText
    private lateinit var rvDevices: RecyclerView
    private lateinit var btnBack: ImageButton
    private lateinit var btnScan: Button
    private lateinit var btnConfig: ImageButton
    private lateinit var filterLabel: TextView

    // 数据存储
    private lateinit var configScan: ConfigStorage

    // 设备列表
    private val deviceList = mutableListOf<DeviceInfo>()

    // 当前扫描的厂家模块
    private var currentScanModule: Productor = Productor.radarQL

    // 扫描状态
    private var isScanning = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val scanTimeoutRunnable = Runnable {
        //Log.d(TAG, "Scan timeout in Activity")
        updateScanButtonState(false)
    }


    // 配置参数
    private var currentFilterType: FilterType = DefaultConfig.DEFAULT_FILTER_TYPE
    private var currentFilterPrefix = DefaultConfig.DEFAULT_FILTER_PREFIX

    // 启用蓝牙的新 API
    private val enableBluetoothRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Log.i(TAG, "Bluetooth enabled")
            recreate()
        } else {
            Log.w(TAG, "Bluetooth enable request denied")
            Toast.makeText(this, getString(R.string.bluetooth_permission_required), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        // 初始化配置存储和视图
        configScan = ConfigStorage(this)
        initViews()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_SCAN_SLEEPACE -> {
                if (resultCode == RESULT_OK && data != null) {
                    // 更新扫描按钮状态
                    updateScanButtonState(true)

                    val bleDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        data.getSerializableExtra("extra_device", BleDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        data.getSerializableExtra("extra_device") as? BleDevice
                    }
                    if (bleDevice != null) {
                        val device = DeviceInfo(
                            productorName = Productor.sleepBoardHS,
                            deviceName = bleDevice.deviceName ?: "",
                            deviceId = bleDevice.deviceName ?: "",
                            macAddress = bleDevice.address,
                            rssi = 0,
                        )

                        // 添加到设备列表
                        if (!deviceList.any { it.macAddress == device.macAddress }) {
                            deviceList.add(device)
                            rvDevices.adapter?.notifyItemInserted(deviceList.size - 1)
                        }
                    }
                } else {
                    Log.w(TAG, "SleepBoard scan canceled or failed")
                    updateScanButtonState(false)
                }
            }

            PERMISSION_REQUEST_CODE -> {
                // 已有的权限处理保持不变
            }
        }
    }


    /**
     * 初始化视图组件
     */
    private fun initViews() {
        //Log.d(TAG, "Initializing views")

        // 绑定视图组件
        radioGroup = findViewById(R.id.radio_device_type)
        radioRadar = findViewById(R.id.radio_radar)
        radioSleepace = findViewById(R.id.radio_sleepace)
        radioFilter = findViewById(R.id.radio_filter)
        inputFilter = findViewById(R.id.input_filter)
        rvDevices = findViewById(R.id.rv_devices)
        btnBack = findViewById(R.id.btn_back)
        btnScan = findViewById(R.id.btn_scan)
        btnConfig = findViewById(R.id.btn_config)
        filterLabel = findViewById(R.id.filter_label)
        currentFilterPrefix = configScan.getRadarDeviceName()

        // 设置返回按钮的点击事件
        btnBack.setOnClickListener {
            stopScan()
            finish()
        }

        // 设置扫描按钮的点击事件
        btnScan.setOnClickListener {
            if (isScanning) {
                stopScan()
            } else {
                //Log.d(TAG, "Start scan clicked, current module: $currentScanModule")

                currentFilterPrefix = when (currentScanModule) {
                    Productor.radarQL -> configScan.getRadarDeviceName()
                    Productor.sleepBoardHS -> ""
                    Productor.espBle -> inputFilter.text.toString()
                }
                //Log.d(TAG, "Filter prefix set to: $currentFilterPrefix")

                currentFilterType = if (currentScanModule == Productor.espBle) {
                    configScan.getFilterType()
                } else {
                    FilterType.DEVICE_NAME
                }

                startScan()
            }
        }


        // 设置配置按钮的点击事件
        btnConfig.setOnClickListener {
            showConfigDialog()
        }

        // 初始化 RecyclerView
        val deviceAdapter = DeviceAdapter(deviceList, configScan.getDeviceHistories()) { device ->
            Log.i(TAG, "Device selected}")


            val intent = Intent().apply {
                putExtra(EXTRA_DEVICE_INFO, device)
            }

            setResult(RESULT_OK, intent)
            stopScan()
            // 添加短暂延迟以确保扫描完全停止
            Handler(Looper.getMainLooper()).postDelayed({
                finish()
            }, 200)  // 200ms延迟
        }

        rvDevices.layoutManager = LinearLayoutManager(this)
        rvDevices.adapter = deviceAdapter

        // 设置 RadioGroup 的监听器
        // RadioGroup 监听器只记录当前选择的模块
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            currentScanModule = when (checkedId) {
                R.id.radio_radar -> Productor.radarQL
                R.id.radio_sleepace -> Productor.sleepBoardHS
                R.id.radio_filter -> Productor.espBle
                else -> Productor.radarQL
            }
            // 根据当前选择的模块更新 filter_label 和 inputFilter
            updateFilterUI(currentScanModule)
        }
        // 初始化默认值 - 放在监听器设置之后
        currentScanModule = Productor.radarQL
        // 初始化 filter_label 和 inputFilter 的状态
        updateFilterUI(currentScanModule)

    }

    private fun updateFilterUI(module: Productor) {
        when (module) {
            Productor.radarQL, Productor.sleepBoardHS -> {
                filterLabel.text = "Filter Invalid"
                inputFilter.hint = ""
                inputFilter.isEnabled = false
            }
            Productor.espBle -> {
                // 根据保存的 filterType 设置相应的标签和提示
                currentFilterType = configScan.getFilterType()
                when (currentFilterType) {
                    FilterType.DEVICE_NAME -> {
                        filterLabel.text = "Filter by: Device Name"
                        inputFilter.hint = "TSBLU,..."
                    }
                    FilterType.MAC -> {
                        filterLabel.text = "Filter by: MAC"
                        inputFilter.hint = "XX:XX:XX:XX:XX:XX"
                    }
                    FilterType.UUID -> {
                        filterLabel.text = "Filter by: UUID"
                        inputFilter.hint = "XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX"
                    }
                }
                inputFilter.isEnabled = true
            }
        }
    }

    // 添加格式化工具方法
    private fun formatMacAddress(input: String): String {
        val hex = input.replace(Regex("[^0-9A-Fa-f]"), "")
            .take(12)
            .uppercase()
        return hex.chunked(2).joinToString(":")
    }

    private fun formatUUID(input: String): String {
        val hex = input.replace(Regex("[^0-9A-Fa-f]"), "")
            .take(32)
            .uppercase()
        return if (hex.length == 32) {
            "${hex.substring(0,8)}-${hex.substring(8,12)}-" +
                    "${hex.substring(12,16)}-${hex.substring(16,20)}-" +
                    "${hex.substring(20)}"
        } else hex
    }

    /**
     * 更新扫描按钮状态
     */
    private fun updateScanButtonState(scanning: Boolean) {
        isScanning = scanning
        btnScan.setText(if (scanning) R.string.stop else R.string.scan)
    }

    /**
     * 启动扫描
     */
    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (isScanning) {
            stopScan()
            return
        }

        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            // 开始新的扫描时才清空列表
            deviceList.clear()
            rvDevices.adapter?.notifyDataSetChanged()

            // 更新状态为扫描中
            updateScanButtonState(true)

            // 设置超时
            mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT)

            // 开始扫描
            when (currentScanModule) {
                Productor.sleepBoardHS -> startSleepaceScan()
                else -> startRadarScan()  // radar和filter都用这个
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN), PERMISSION_REQUEST_CODE)
        }
    }

    /**
     * 启动 Radar 扫描
     */
    @SuppressLint("MissingPermission")
    private fun startRadarScan() {
        val radarManager = com.espressif.espblufi.RadarBleManager.getInstance(this)

        //Log.d(TAG, "Starting radar scan with filter: $currentFilterPrefix, type: $currentFilterType")

        radarManager.setScanCallback { deviceInfo ->
            runOnUiThread {
                if (!deviceList.any { it.macAddress == deviceInfo.macAddress }) {
                    val adjustedDevice = if (currentScanModule != Productor.radarQL) {
                        deviceInfo.copy(productorName = currentScanModule)
                    } else {
                        deviceInfo
                    }
                    deviceList.add(adjustedDevice)
                    rvDevices.adapter?.notifyItemInserted(deviceList.size - 1)
                }
            }
        }

        radarManager.startScan(currentFilterPrefix, currentFilterType)
    }

    /**
     * 启动 Sleepace 扫描
     */
    @SuppressLint("MissingPermission")
    private fun startSleepaceScan() {
        val sleepaceManager = SleepaceBleManager.getInstance(this)
        sleepaceManager.startScan { result ->
            runOnUiThread {
                // 检查 result 是否为 null
                if (result == null) {
                    Log.e(TAG, "Scan result is null")
                    return@runOnUiThread
                }

                // 检查操作状态
                if (result.status != StatusCode.SUCCESS) {
                    Log.e(TAG, "Scan failed with status: ${result.status}")
                    return@runOnUiThread
                }

                // 检查设备信息是否为 null
                // 检查设备信息是否为 DeviceInfo
                val deviceInfo = result.result as? DeviceInfo
                if (deviceInfo == null) {
                    Log.e(TAG, "Scan result data is not DeviceInfo")
                    return@runOnUiThread
                }

                // 添加到设备列表
                if (!deviceList.any { it.macAddress == deviceInfo.macAddress }) {
                    deviceList.add(deviceInfo)
                    rvDevices.adapter?.notifyItemInserted(deviceList.size - 1)
                }
            }
        }
    }

    /**
     * 停止扫描
     */
    private fun stopScan() {
        //Log.d(TAG, "Stopping current scan")

        // 移除超时回调
        mainHandler.removeCallbacks(scanTimeoutRunnable)

        updateScanButtonState(false)

        when (currentScanModule) {
            Productor.radarQL, Productor.espBle -> RadarBleManager.getInstance(this).stopScan()
            Productor.sleepBoardHS -> SleepaceBleManager.getInstance(this).stopScan()
        }
    }

    /**
     * 显示配置对话框
     */
    private fun showConfigDialog() {
        ConfigDialog(this).apply {
            // 设置当前配置
            setRadarDeviceName(configScan.getRadarDeviceName())
            setFilterType(currentFilterType)

            // 设置配置更改监听器
            setOnConfigChangedListener { newName, newType ->
                currentFilterPrefix = newName
                currentFilterType = newType

                // === 新增：更新filterLabel和输入框提示 ===
                when (newType) {
                    FilterType.DEVICE_NAME -> {
                        filterLabel.text = "Filter by: Device Name"
                        inputFilter.hint = "TSBLU,..."  // 简化的设备名格式示例
                    }
                    FilterType.MAC -> {
                        filterLabel.text = "Filter by: MAC"
                        inputFilter.hint = "XX:XX:XX:XX:XX:XX"  // MAC地址格式示例
                    }
                    FilterType.UUID -> {
                        filterLabel.text = "Filter by: UUID"
                        inputFilter.hint = "XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX"  // UUID格式示例
                    }
                }

                // 保存配置到 SharedPreferences
                configScan.saveRadarDeviceName(newName)
                configScan.saveFilterType(newType)

                // 如果正在扫描，则停止并重新启动扫描
                if (isScanning) {
                    stopScan()
                    startScan()
                }
            }

            // 显示对话框
            show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 权限已授予，重新启动扫描
                startScan()
            } else {
                // 权限被拒绝，提示用户
                Toast.makeText(this, "Bluetooth scan permission is required", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        stopScan()
    }

    /**
     * 设备列表适配器
     */
    private class DeviceAdapter(
        private val deviceList: List<DeviceInfo>,
        private val configuredDevices: List<DeviceHistory>,
        private val onDeviceClick: (DeviceInfo) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_device, parent, false)
            return DeviceViewHolder(view)
        }

        override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
            val device = deviceList[position]
            holder.bind(device, configuredDevices)
            holder.itemView.setOnClickListener { onDeviceClick(device) }
        }

        override fun getItemCount(): Int = deviceList.size

        class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvDeviceId: TextView = itemView.findViewById(R.id.tv_device_id)
            private val tvMacConfigTime: TextView = itemView.findViewById(R.id.tv_mac_config_time)
            private val tvRssi: TextView = itemView.findViewById(R.id.tv_rssi)

            fun bind(device: DeviceInfo, configuredDevices: List<DeviceHistory>) {
                tvDeviceId.text = device.deviceId

                val configTime = configuredDevices.find { it.macAddress == device.macAddress }?.configTime
                val macConfigTime = if (configTime != null) {
                    "${device.macAddress} / ${formatConfigTime(configTime)}"
                } else {
                    device.macAddress
                }
                tvMacConfigTime.text = macConfigTime
                tvRssi.text = itemView.context.getString(R.string.format_rssi, device.rssi)
            }

            private fun formatConfigTime(configTime: Long): String {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                return dateFormat.format(Date(configTime))
            }
        }
    }
}