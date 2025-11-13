/**
 * File: RadarBleManager.kt
 * Path: module-radar/src/main/java/com/espressif/espblufi/RadarBleManager.kt
 *
 * A厂(Radar)蓝牙管理类，封装 BlufiClient 实现
 * */
package com.espressif.espblufi

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.net.MacAddress
import android.net.wifi.WifiSsid
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

//common data type
import com.common.DeviceInfo
import com.common.Productor
import com.common.FilterType
import com.common.DeviceHistory
import com.common.ServerConfig
import com.common.WifiConfig
import com.common.DefaultConfig
import com.common.NearbyWifiNetwork
import android.os.Build

import com.espressif.espblufi.constants.BlufiConstants
import com.espressif.espblufi.params.BlufiConfigureParams
import com.espressif.espblufi.response.BlufiStatusResponse
import com.espressif.espblufi.response.BlufiVersionResponse
import com.espressif.espblufi.response.BlufiScanResult
import com.google.gson.Gson
import java.util.Locale
import com.common.BleDeviceManager


/**
 * A厂雷达设备蓝牙管理类
 * - 扫描: 使用系统原生扫描，支持多种过滤方式
 * - 通信: 封装 BlufiClient，负责连接和配网
 */
@SuppressLint("MissingPermission")
class RadarBleManager private constructor(private val context: Context) {

    //region 参数定义
    // -------------- 1. 参数定义 --------------
    companion object {
        private const val TAG = "RadarBleManager"
        private const val SCAN_TIMEOUT = 10000L  // 扫描超时时间 10秒
        private const val QUERY_TIMEOUT = 25000L  // 查询超时时间 25秒
        private const val GATT_WRITE_TIMEOUT = 10000L //连接超时间10秒
        private const val CONFIGSERVER_TIMEOUT=25000L
        private const val COMMAND_DELAYTIME=1000L
        private const val DEVICERESTART_DELAYTIME=5000L
        private const val WIFI_SCAN_TIMEOUT = 8000L
        private const val CUSTOM_COMMAND_TIMEOUT = 300L
        private const val RUN_STATUS_FINALIZE_DELAY = 500L

        // 错误处理相关常量
        private const val MAX_RETRY_COUNT = 3
        private const val RECONNECT_DELAY = 1000L  // 1秒
        private const val RETRY_DELAY = 500L       // 500毫秒
        private const val MAX_ERROR_THRESHOLD = 5  // 最大错误阈值

        // 添加 keepAlive 相关常量
        private const val KEEP_ALIVE_INTERVAL = 3000L // 3秒发送一次保活信号


        @Volatile
        private var instance: RadarBleManager? = null

        fun getInstance(context: Context): RadarBleManager {
            return instance ?: synchronized(this) {
                instance ?: RadarBleManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    // 添加 keepAlive 相关成员变量
    private var isKeepAliveRunning = false
    private lateinit var keepAliveRunnable: Runnable
    private val gson: Gson = Gson()

    // Then add this initialization in the init block or constructor:
    init {
        keepAliveRunnable = Runnable {
            if (isConnecting && blufiClient != null) {
                Log.d(TAG, "Sending keep-alive signal")
                try {
                    // 发送一个轻量级命令来保持连接活跃
                    blufiClient?.postCustomData("65:".toByteArray())
                } catch (e: Exception) {
                    Log.e(TAG, "Keep-alive error", e)
                }

                // 安排下一次保活信号
                if (isKeepAliveRunning) {
                    mainHandler.postDelayed(keepAliveRunnable, KEEP_ALIVE_INTERVAL)
                }
            }
        }
    }


    /**
     * 启动 keepAlive 机制
     */
    private fun startKeepAlive() {
        if (!isKeepAliveRunning) {
            Log.d(TAG, "Starting keep-alive mechanism")
            isKeepAliveRunning = true
            mainHandler.postDelayed(keepAliveRunnable, KEEP_ALIVE_INTERVAL)
        }
    }

    /**
     * 停止 keepAlive 机制
     */
    private fun stopKeepAlive() {
        if (isKeepAliveRunning) {
            Log.d(TAG, "Stopping keep-alive mechanism")
            isKeepAliveRunning = false
            mainHandler.removeCallbacks(keepAliveRunnable)
        }
    }


    // 错误类型枚举
    enum class ErrorType {
        CONNECTION_TIMEOUT,
        SECURITY_ERROR,
        DATA_ERROR,
        UNKNOWN
    }

    // 添加错误处理相关变量
    private var notifyErrorCallback: ((ErrorType, String) -> Unit)? = null
    private var errorCount = 0
    private var isRetryEnabled = true
    private var isConnecting = false
    private var currentDeviceMac: String? = null  // 存储当前连接的设备MAC地址
    @Volatile
    private var lastQueryDeviceInfo: DeviceInfo? = null  // 最近一次查询结果

    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var blufiClient: BlufiClient? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val scanTimeoutRunnable = Runnable {
        Log.d(TAG, "Scan timeout")
        stopScan()
    }

    private var isScanning = false
    private var filterPrefix: String? = null
    private var filterType: FilterType = FilterType.DEVICE_NAME
    private var configureCallback: ((Boolean) -> Unit)? = null

    // 扫描回调
    private var scanCallback: ((DeviceInfo) -> Unit)? = null

    //endregion

    //region 基础函数及扩展函数
    // -------------- 2. 基础函数 --------------
    /**
     * 设置错误回调
     */
    fun setErrorCallback(callback: (ErrorType, String) -> Unit) {
        notifyErrorCallback = callback
    }

    /**
     * 启用/禁用自动重试
     */
    fun enableRetry(enable: Boolean) {
        isRetryEnabled = enable
    }

    /**
     * 重置错误计数
     */
    fun resetErrorCount() {
        errorCount = 0
    }


    /**
     * 设置扫描回调
     */
    fun setScanCallback(callback: (DeviceInfo) -> Unit) {
        scanCallback = callback
    }


    /**
     * 连接设备
     */
    fun connect(deviceMacAddress: String) {
        val device = resolveBluetoothDevice(deviceMacAddress)

        if (device != null) {
            Log.d(TAG, "Device found, starting connection process")
            isConnecting = true
            currentDeviceMac = deviceMacAddress
            connect(device)
        } else {
            //Log.e(TAG, "Failed to get device with MAC: $deviceMacAddress")
            notifyErrorCallback?.invoke(ErrorType.CONNECTION_TIMEOUT, "Invalid device address")
            // 处理错误
        }
    }

    private fun connect(device: BluetoothDevice) {
        //Log.d(TAG, "Starting connection to device: ${device.address}")
        disconnect()

        BlufiClient(context, device).also { client ->
            blufiClient = client

            // 设置 GATT 回调
            client.setGattCallback(createGattCallback())
            client.setBlufiCallback(createBlufiCallback())

            // 设置超时 BlufiConstants.GATT_WRITE_TIMEOUT=5000L->10000L
            //client.setGattWriteTimeout(BlufiConstants.GATT_WRITE_TIMEOUT)
            client.setGattWriteTimeout(GATT_WRITE_TIMEOUT)

            // 重置MTU和错误计数
            errorCount = 0

            // 开始连接
            //Log.d(TAG, "Initiating GATT connection to device: ${device.address}")
            client.connect()
        }
    }

    /**
     * 重新连接设备
     */
    private fun reconnect() {
        if (isConnecting) return

        val deviceMac = currentDeviceMac ?: return
        //Log.d(TAG, "Attempting reconnection to $deviceMac")

        disconnect()
        // 短暂延迟确保断开完全处理
        mainHandler.postDelayed({
            connect(deviceMac)
        }, 200)
    }

    /**
     * 重置安全状态
     */
    private fun resetSecurityState() {
        // 重置与安全协商相关的状态
        Log.d(TAG, "Resetting security state")
        // 执行任何必要的状态重置
    }



    /**
     * 获取设备版本
     */
    fun requestDeviceVersion() {
        blufiClient?.requestDeviceVersion()
    }

    /**
     * 获取设备状态
     */
    fun requestDeviceStatus() {
        blufiClient?.requestDeviceStatus()
    }

    fun getLastQueryDeviceInfo(): DeviceInfo? = lastQueryDeviceInfo

    /**
     * 配置设备
     */
    fun configure(params: BlufiConfigureParams, callback: ((Boolean) -> Unit)? = null) {
        configureCallback = callback
        blufiClient?.let { client ->
            // 先进行安全协商
            client.negotiateSecurity()
            // 发送配置
            client.configure(params)
        } ?: run {
            callback?.invoke(false)
        }
    }


    /**
     * 发送自定义数据
     */
    fun postCustomData(data: ByteArray) {
        blufiClient?.postCustomData(data)
    }


    /**
     * 断开连接
     */
    fun disconnect() {
        isConnecting = false

        // 移除所有挂起的回调以防止内存泄漏
        mainHandler.removeCallbacksAndMessages(null)

        // 直接同步执行关闭操作，不使用延迟,否则在connect开始调用，会中断Rigster
        try {
            blufiClient?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing BluetoothGatt", e)
        }
        blufiClient = null
        configureCallback = null
    }

    /**
     * 释放资源
     */
    fun release() {
        stopScan()
        disconnect()
        mainHandler.removeCallbacksAndMessages(null)
        currentDeviceMac = null
        instance = null
    }

    private fun createGattCallback(): BluetoothGattCallback {
        return object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                Log.d(TAG, "onConnectionStateChange status: $status, newState: $newState")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            Log.d(TAG, "Connected to device")
                            errorCount = 0
                            // 连接成功后立即启动保活
                            startKeepAlive()
                            // 请求高优先级连接（对所有版本）
                            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                            // 增加延迟时间，确保连接参数设置完成
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (isConnecting) { // 确保仍然在连接过程中
                                    gatt.discoverServices()
                                }
                            }, 150) // 稍微增加延迟
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            Log.d(TAG, "Disconnected from device")
                            // 连接断开时停止保活
                            stopKeepAlive()
                            disconnect()
                        }
                    }
                } else if (status == 8 && newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.e(TAG, "Connection failed with status 8 (timeout)")
                    if (!handleGattFailure(status)) {
                        stopKeepAlive()
                        disconnect()
                    }
                } else if (status == 133 && newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.e(TAG, "Connection failed with status 133 (GATT_ERROR)")
                    if (!handleGattFailure(status)) {
                        stopKeepAlive()
                        disconnect()
                    }
                } else {
                    // 其他连接失败情况
                    //Log.e(TAG, "Connection failed with status: $status")
                    stopKeepAlive()
                    disconnect()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    //Log.d(TAG, "Services discovered successfully")
                    // 在服务发现成功后也启动保活
                    startKeepAlive()
                } else {
                    //Log.e(TAG, "Service discovery failed with status: $status")
                }
            }

            // 添加 MTU 变化回调
            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    //Log.d(TAG, "MTU changed to: $mtu")
                } else {
                    //Log.e(TAG, "MTU change failed with status: $status")
                }
            }

            // 删除有问题的 onConnectionUpdated 方法
        }
    }

    private fun createBlufiCallback(): BlufiCallback {
        return object : BlufiCallback() {
            override fun onGattPrepared(
                client: BlufiClient,
                gatt: BluetoothGatt,
                service: BluetoothGattService?,
                writeChar: BluetoothGattCharacteristic?,
                notifyChar: BluetoothGattCharacteristic?
            ) {
                //Log.d(TAG, "onGattPrepared: service=${service != null}, write=${writeChar != null}, notify=${notifyChar != null}")
                if (service == null || writeChar == null || notifyChar == null) {
                    Log.e(TAG, "Discover service failed")
                    disconnect()
                    return
                }
                //Log.d(TAG, "GATT services prepared successfully")
            }

            override fun onNegotiateSecurityResult(client: BlufiClient, status: Int) {
                //Log.d(TAG, "Security negotiation result: $status")
                if (status == STATUS_SUCCESS) {
                    Log.d(TAG, "Security negotiation successful")
                } else {
                    Log.e(TAG, "Security negotiation failed with status: $status")
                }
            }

            override fun onPostConfigureParams(client: BlufiClient, status: Int) {
                Log.d(TAG, "Configure result: $status")
                configureCallback?.invoke(status == STATUS_SUCCESS)
                configureCallback = null
            }

            override fun onDeviceStatusResponse(
                client: BlufiClient,
                status: Int,
                response: BlufiStatusResponse
            ) {
                //Log.d(TAG, "Device status: $status, response: $response")
            }

            override fun onDeviceVersionResponse(
                client: BlufiClient,
                status: Int,
                response: BlufiVersionResponse
            ) {
                //Log.d(TAG, "Device version: $status, response: $response")
            }

            override fun onReceiveCustomData(client: BlufiClient, status: Int, data: ByteArray) {
                //Log.d(TAG, "Received custom data, status: $status")
            }

            override fun onError(client: BlufiClient, errCode: Int) {
                Log.e(TAG, "BluFi error: $errCode")

                // 记录错误次数
                errorCount++

                when (errCode) {
                    CODE_GATT_WRITE_TIMEOUT -> {
                        //Log.e(TAG, "GATT write operation timed out with current MTU}")
                        disconnect()
                        notifyErrorCallback?.invoke(ErrorType.CONNECTION_TIMEOUT,
                            "Connection timed out after $errorCount attempts")
                    }

                    CODE_NEG_ERR_DEV_KEY -> {
                        Log.e(TAG, "Security negotiation failed: invalid device key")
                        resetSecurityState()
                        disconnect()
                        notifyErrorCallback?.invoke(ErrorType.SECURITY_ERROR,
                            "Failed to negotiate security")
                    }

                    CODE_INVALID_NOTIFICATION, CODE_NEG_ERR_SECURITY -> {
                        Log.e(TAG, "Invalid data received or security error")
                        disconnect()
                        notifyErrorCallback?.invoke(ErrorType.DATA_ERROR,
                            "Communication error occurred")
                    }

                    else -> {
                        Log.e(TAG, "Unknown error: $errCode")
                        if (errorCount >= MAX_ERROR_THRESHOLD) {
                            disconnect()
                            notifyErrorCallback?.invoke(ErrorType.UNKNOWN,
                                "Multiple errors occurred: $errCode")
                        }
                    }
                }
            }
        }
    }

    // 添加带间隔参数的保活启动方法
    private fun startKeepAliveWithInterval(interval: Long) {
        if (isKeepAliveRunning) {
            stopKeepAlive()
        }
        //Log.d(TAG, "Starting keep-alive mechanism with interval: $interval ms")
        isKeepAliveRunning = true
        mainHandler.postDelayed(keepAliveRunnable, interval)
    }

    //endregion

    //region  扫描BleResult
    // -------------- 3. 扫描相关函数 --------------

    //region  扫描BleResult
    // -------------- 3. 扫描相关函数 --------------

    /**
     * 开始扫描
     * @param filterPrefix 过滤值，null 或空值时不过滤
     * @param filterType 过滤类型，默认为设备名称过滤
     */
    fun startScan(filterPrefix: String?, filterType: FilterType = FilterType.DEVICE_NAME) {
        if (isScanning) return
        //Log.d(TAG,"RadarBleManager startScan with filterPrefix: '$filterPrefix', filterType: $filterType")
        this.filterPrefix = filterPrefix
        this.filterType = filterType

        bluetoothAdapter?.bluetoothLeScanner?.let { scanner ->
            isScanning = true

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            try {
                scanner.startScan(null, settings, leScanCallback)
                mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT)
            } catch (e: Exception) {
                Log.e(TAG, "Start scan failed: ${e.message}")
            }
        }
    }

    /**
     * 停止扫描
     */
    fun stopScan() {
        if (!isScanning) return

        mainHandler.removeCallbacks(scanTimeoutRunnable)

        bluetoothAdapter?.bluetoothLeScanner?.let { scanner ->
            isScanning = false
            try {
                scanner.stopScan(leScanCallback)
                Log.d(TAG, "Scan stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Stop scan failed: ${e.message}")
            }
        }
    }

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // 只有在设置了过滤条件时才进行过滤
            val prefix = filterPrefix
            if (!prefix.isNullOrEmpty()) {
                when (filterType) {
                    FilterType.DEVICE_NAME -> {
                        val deviceName = result.device.name
                        if (deviceName == null || !deviceName.contains(prefix, ignoreCase = true)) {
                            return
                        }
                    }

                    FilterType.MAC -> {
                        val deviceMac = result.device.address
                        if (!deviceMac.replace(":", "")
                                .replace("-", "")
                                .contains(prefix, ignoreCase = true)
                        ) {
                            return
                        }
                    }

                    FilterType.UUID -> {
                        val scanRecord = result.scanRecord ?: return
                        val serviceUuids = scanRecord.serviceUuids ?: return
                        val matchFound = serviceUuids.any { uuid ->
                            uuid.toString().contains(prefix, ignoreCase = true)
                        }
                        if (!matchFound) {
                            return
                        }
                    }
                }
            }

            // 保存原始设备以便后续直接复用 GATT 连接
            BleDeviceManager.saveDevice(result.device.address, result.device)

            // 转换为 DeviceInfo
            val deviceInfo = DeviceInfo(
                productorName = Productor.radarQL,
                deviceName = result.device.name ?: "Unknown",
                deviceId = result.device.name ?: result.device.address,
                macAddress = result.device.address,
                rssi = result.rssi,
           )

            mainHandler.post {
                scanCallback?.invoke(deviceInfo)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error: $errorCode")
            isScanning = false
        }
    }
    //endregion

    //region  查询RadarQL设备状态
    // -------------- 4. 连接和查询相关函数 --------------
    /**
     * 查询RadarQL设备状态
     * 依次查询：UID -> WiFi Status -> Server Status
     */
    fun queryDeviceStatus(
        deviceInfo: DeviceInfo,
        callback: ((Map<String, String>) -> Unit)? = null
    ) {
        Log.d(TAG, "Start query device status")

        val device = resolveBluetoothDevice(deviceInfo.macAddress) ?: run {
            callback?.invoke(mapOf("error" to "Invalid device address"))
            return
        }

        val statusMap = mutableMapOf<String, String>()
        var isQueryComplete = false
        var hasWifiStatus = false
        var hasUID = false
        var requestedStatusAfterUid = false
        var wifiScanRequested = false
        var wifiScanCompleted = false
        var wifiScanTimeoutRunnable: Runnable? = null
        var updatedDeviceInfo = deviceInfo
        var lastCommandWasRunStatus = false
        var runStatusBuffer: StringBuilder? = null
        var runStatusStartTime = 0L
        var runStatusFinalizeTask: Runnable? = null

        val customCommandQueue = ArrayDeque<RadarCommand>()
        var customCommandsInitialized = false
        var currentCustomCommand: RadarCommand? = null
        var customCommandTimeout: Runnable? = null
        var queryTimeoutRunnable: Runnable? = null

        fun clearCustomTimeout() {
            customCommandTimeout?.let { mainHandler.removeCallbacks(it) }
            customCommandTimeout = null
        }

        fun cancelWifiScanTimeout() {
            wifiScanTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            wifiScanTimeoutRunnable = null
        }

        fun cancelRunStatusFinalize() {
            runStatusFinalizeTask?.let { mainHandler.removeCallbacks(it) }
            runStatusFinalizeTask = null
        }

        fun complete(extraStatus: Map<String, String> = emptyMap()) {
            if (isQueryComplete) return
            isQueryComplete = true
            clearCustomTimeout()
            queryTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            cancelWifiScanTimeout()
            cancelRunStatusFinalize()
            runStatusBuffer = null
            runStatusStartTime = 0L
            lastCommandWasRunStatus = false
            if (!statusMap.containsKey("nearbyWiFiNetworks")) {
                statusMap["nearbyWiFiNetworks"] = gson.toJson(updatedDeviceInfo.nearbyWiFiNetworks)
            }
            if (extraStatus.isNotEmpty()) {
                statusMap.putAll(extraStatus)
            }
            lastQueryDeviceInfo = updatedDeviceInfo
            callback?.invoke(statusMap)
            disconnect()
        }

        fun startWifiScan(client: BlufiClient) {
            if (wifiScanRequested || isQueryComplete) return
            wifiScanRequested = true
            wifiScanCompleted = false
            Log.d(TAG, "Starting nearby Wi-Fi scan")
            cancelWifiScanTimeout()
            wifiScanTimeoutRunnable = Runnable {
                if (!wifiScanCompleted && !isQueryComplete) {
                    Log.e(TAG, "Nearby Wi-Fi scan timeout")
                    statusMap["wifiScanError"] = "Wi-Fi scan timeout"
                    wifiScanCompleted = true
                    complete()
                }
            }
            wifiScanTimeoutRunnable?.let { mainHandler.postDelayed(it, WIFI_SCAN_TIMEOUT) }
            try {
                client.requestDeviceWifiScan()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request Wi-Fi scan", e)
                cancelWifiScanTimeout()
                statusMap["wifiScanError"] = "Wi-Fi scan request failed: ${e.message}"
                statusMap["nearbyWiFiNetworks"] = gson.toJson(emptyList<NearbyWifiNetwork>())
                wifiScanCompleted = true
                complete()
            }
        }

        fun tryFinalize(client: BlufiClient? = null) {
            if (isQueryComplete) return
            val queueIdle = customCommandQueue.isEmpty() && currentCustomCommand == null
            if (queueIdle && (hasWifiStatus || statusMap.containsKey("wifiError") || hasUID)) {
                if (!wifiScanRequested) {
                    if (client != null) {
                        startWifiScan(client)
                    } else {
                        complete()
                    }
                } else if (wifiScanCompleted) {
                    complete()
                }
            } else if (!hasWifiStatus && client != null && !requestedStatusAfterUid) {
                requestedStatusAfterUid = true
                Log.d(TAG, "WiFi status missing, requesting device status again")
                client.requestDeviceStatus()
            }
        }

        fun sendNextCustomCommand(client: BlufiClient) {
            if (isQueryComplete) return
            if (customCommandQueue.isEmpty()) {
                currentCustomCommand = null
                clearCustomTimeout()
                lastCommandWasRunStatus = false
                cancelRunStatusFinalize()
                runStatusBuffer = null
                runStatusStartTime = 0L
                tryFinalize(client)
                return
            }

            val command = customCommandQueue.removeFirst()
            currentCustomCommand = command
            lastCommandWasRunStatus = command == RadarCommand.GET_RUNNING_STATUS
            if (lastCommandWasRunStatus) {
                runStatusBuffer = StringBuilder()
                runStatusStartTime = SystemClock.elapsedRealtime()
                cancelRunStatusFinalize()
                Log.d(TAG, "Queued run status command; starting buffer and timer")
            }
            val request = command.buildRequest()
            Log.d(TAG, "Sending custom command ${command.code} during query: $request")
            client.postCustomData(request.toByteArray())
            clearCustomTimeout()

            customCommandTimeout = Runnable {
                if (!isQueryComplete) {
                    Log.e(TAG, "Custom command ${command.code} timeout")
                    when (command) {
                        RadarCommand.GET_DEVICE_MAC -> statusMap["deviceMacResponse"] = "Device MAC Query: NO RESPONSE"
                        RadarCommand.GET_DEVICE_UID -> statusMap["uid"] = "Device UID Query: NO RESPONSE"
                        RadarCommand.GET_RUNNING_STATUS -> statusMap["runningStatus"] = "Operating Status Query: NO RESPONSE"
                        else -> statusMap["error"] = "Command ${command.code} timeout"
                    }
                    lastCommandWasRunStatus = false
                    complete()
                }
            }
            val timeoutMs = CUSTOM_COMMAND_TIMEOUT
            mainHandler.postDelayed(customCommandTimeout!!, timeoutMs)
        }

        val scheduleRunStatusFinalize: (BlufiClient) -> Unit = { client ->
            runStatusFinalizeTask?.let { mainHandler.removeCallbacks(it) }
            runStatusFinalizeTask = Runnable {
                if (!lastCommandWasRunStatus) return@Runnable
                if (runStatusStartTime > 0L) {
                    val elapsed = SystemClock.elapsedRealtime() - runStatusStartTime
                    Log.d(TAG, "Run status response completed in ${elapsed}ms")
                    runStatusStartTime = 0L
                }
                runStatusBuffer?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    statusMap["runningStatus"] = it
                    Log.d(TAG, "Run status finalize with payload length=${it.length}")
                }
                runStatusBuffer = null
                lastCommandWasRunStatus = false
                clearCustomTimeout()
                currentCustomCommand = null
                runStatusFinalizeTask = null
                sendNextCustomCommand(client)
                tryFinalize(client)
            }
            Log.d(TAG, "Scheduling run status finalize in ${RUN_STATUS_FINALIZE_DELAY}ms")
            mainHandler.postDelayed(runStatusFinalizeTask!!, RUN_STATUS_FINALIZE_DELAY)
        }

        queryTimeoutRunnable = Runnable {
            if (!isQueryComplete) {
                Log.e(TAG, "Query timeout after ${QUERY_TIMEOUT / 1000} seconds")
                statusMap["error"] = "Query timeout"
                complete()
            }
        }

        Log.d(TAG, "Connecting to device")
        connect(device)

        blufiClient?.setBlufiCallback(object : BlufiCallback() {
            override fun onGattPrepared(
                client: BlufiClient,
                gatt: BluetoothGatt,
                service: BluetoothGattService?,
                writeChar: BluetoothGattCharacteristic?,
                notifyChar: BluetoothGattCharacteristic?
            ) {
                if (service == null || writeChar == null || notifyChar == null) {
                    Log.e(TAG, "Discover service failed")
                    statusMap["error"] = "Service discovery failed"
                    complete()
                    return
                }

                queryTimeoutRunnable?.let { mainHandler.postDelayed(it, QUERY_TIMEOUT) }
                Log.d(TAG, "Starting security negotiation")
                client.negotiateSecurity()
            }

            override fun onNegotiateSecurityResult(client: BlufiClient, status: Int) {
                Log.d(TAG, "Security negotiation result: $status")
                if (status == STATUS_SUCCESS) {
                    Log.d(TAG, "Requesting device status...")
                    client.requestDeviceStatus()
                } else {
                    Log.e(TAG, "Security negotiation failed with status: $status")
                    statusMap["error"] = "Security negotiation failed: $status"
                    complete()
                }
            }

            override fun onDeviceStatusResponse(
                client: BlufiClient,
                status: Int,
                response: BlufiStatusResponse
            ) {
                Log.d(TAG, "=================== DEVICE STATUS RESPONSE BEGIN ===================")
                Log.d(TAG, "Status code: $status")

                if (status == STATUS_SUCCESS) {
                    try {
                        Log.d(TAG, "----- Extracting fields from status response -----")
                        val fields = response.javaClass.declaredFields
                        for (field in fields) {
                            field.isAccessible = true
                            try {
                                val value = field.get(response)
                                Log.d(TAG, "${field.name}: $value")
                            } catch (e: Exception) {
                                Log.d(TAG, "${field.name}: [Error accessing: ${e.message}]")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error accessing fields via reflection: ${e.message}")
                    }

                    hasWifiStatus = true
                    statusMap["wifiOpMode"] = when (response.opMode) {
                        0 -> "NULL"
                        1 -> "STA"
                        2 -> "SOFTAP"
                        3 -> "STASOFTAP"
                        else -> "UNKNOWN(${response.opMode})"
                    }

                    if (response.opMode == 1 || response.opMode == 3) {
                        statusMap["staConnected"] = (response.staConnectionStatus == 0).toString()
                        statusMap["staSSID"] = response.staSSID ?: ""
                        statusMap["staBSSID"] = response.staBSSID ?: ""
                    }

                    if (response.opMode == 2 || response.opMode == 3) {
                        statusMap["apSSID"] = response.softAPSSID ?: ""
                        statusMap["apSecurity"] = response.softAPSecurity.toString()
                        statusMap["apChannel"] = response.softAPChannel.toString()
                        statusMap["apConnCount"] = response.softAPConnectionCount.toString()
                    }

                    if (!customCommandsInitialized) {
                        customCommandsInitialized = true
                        customCommandQueue.clear()
                        customCommandQueue.add(RadarCommand.GET_DEVICE_MAC)
                        customCommandQueue.add(RadarCommand.GET_DEVICE_UID)
                        customCommandQueue.add(RadarCommand.GET_WIFI_STATUS)
                        customCommandQueue.add(RadarCommand.GET_RUNNING_STATUS)
                        sendNextCustomCommand(client)
                    }
                } else {
                    Log.e(TAG, "Failed to get device status, status code: $status")
                    statusMap["wifiError"] = "Status response error: $status"
                    tryFinalize(client)
                }

                Log.d(TAG, "=================== DEVICE STATUS RESPONSE END ===================")
            }

            override fun onReceiveCustomData(client: BlufiClient, status: Int, data: ByteArray) {
                Log.d(TAG, "=================== CUSTOM DATA RESPONSE BEGIN ===================")
                Log.d(TAG, "Status code: $status")

                try {
                    val stringData = String(data)
                    Log.d(TAG, "Custom data as string: '$stringData'")
                    val parsed = RadarCommand.parseResponse(stringData)

                    when (parsed.command) {
                        RadarCommand.GET_DEVICE_MAC -> {
                            clearCustomTimeout()
                            currentCustomCommand = null
                            if (status == STATUS_SUCCESS && parsed.success != false) {
                                val raw = parsed.raw ?: stringData
                                val mac = extractMacAddress(raw, parsed.payload)
                                if (!mac.isNullOrEmpty()) {
                                    statusMap["macAddress"] = mac
                                    updatedDeviceInfo = updatedDeviceInfo.copy(macAddress = mac)
                                } else {
                                    statusMap["deviceMacResponse"] = parsed.payload ?: stringData
                                }
                            } else {
                                statusMap["deviceMacError"] = parsed.raw ?: stringData
                            }
                            sendNextCustomCommand(client)
                            tryFinalize(client)
                            return
                        }

                        RadarCommand.GET_DEVICE_UID -> {
                            clearCustomTimeout()
                            currentCustomCommand = null
                            hasUID = true
                            if (status == STATUS_SUCCESS && parsed.success != false) {
                                val payload = parsed.payload?.trim().orEmpty()
                                val uidValue = payload.split(":").lastOrNull()?.trim().orEmpty()
                                if (uidValue.isNotEmpty()) {
                                    statusMap["uid"] = uidValue
                                    Log.d(TAG, "Extracted UID: $uidValue")
                                } else {
                                    statusMap["uidError"] = parsed.raw ?: stringData
                                }
                            } else {
                                statusMap["uidError"] = parsed.raw ?: stringData
                            }
                            sendNextCustomCommand(client)
                            tryFinalize(client)
                            return
                        }

                        RadarCommand.GET_RUNNING_STATUS -> {
                            clearCustomTimeout()
                            currentCustomCommand = null
                            if (status == STATUS_SUCCESS && parsed.success != false) {
                                val chunk = (parsed.raw ?: parsed.payload ?: stringData).trim()
                                if (chunk.isNotEmpty()) {
                                    if (runStatusBuffer == null) {
                                        runStatusBuffer = StringBuilder()
                                    }
                                    if (runStatusBuffer!!.isNotEmpty()) {
                                        runStatusBuffer!!.append('\n')
                                    }
                                    runStatusBuffer!!.append(chunk)
                                    Log.d(TAG, "Run status chunk received (primary): '${chunk.take(80)}'")
                                }
                            } else {
                                statusMap["runningStatusError"] = parsed.raw ?: stringData
                            }
                            cancelRunStatusFinalize()
                            scheduleRunStatusFinalize(client)
                            return
                        }

                        RadarCommand.GET_WIFI_STATUS -> {
                            clearCustomTimeout()
                            currentCustomCommand = null
                            if (status == STATUS_SUCCESS && parsed.success != false) {
                                statusMap["customWifiStatus"] = parsed.payload ?: stringData
                                parsed.payload?.let { payload ->
                                    val wifiParts = payload.split(":")
                                    if (wifiParts.size >= 3) {
                                        statusMap["customWifiMode"] = when (wifiParts[0]) {
                                            "1" -> "STA"
                                            "2" -> "AP"
                                            "3" -> "APSTA"
                                            else -> "Unknown"
                                        }
                                        statusMap["customWifiConnected"] = (wifiParts[1] == "0").toString()
                                        statusMap["customWifiSSID"] = wifiParts.getOrNull(2) ?: ""
                                        if (wifiParts.size > 3) {
                                            statusMap["customWifiRssi"] = wifiParts[3]
                                        }
                                    }
                                }
                            } else {
                                statusMap["customWifiStatusError"] = parsed.raw ?: stringData
                            }
                            hasWifiStatus = true
                            sendNextCustomCommand(client)
                            tryFinalize(client)
                            return
                        }

                        else -> {
                            if (lastCommandWasRunStatus) {
                                val chunk = stringData.trim()
                                if (chunk.isNotEmpty()) {
                                    if (runStatusBuffer == null) {
                                        runStatusBuffer = StringBuilder()
                                    }
                                    if (runStatusBuffer!!.isNotEmpty()) {
                                        runStatusBuffer!!.append('\n')
                                    }
                                    runStatusBuffer!!.append(chunk)
                                    Log.d(TAG, "Run status chunk received (additional): '${chunk.take(80)}'")
                                }
                                cancelRunStatusFinalize()
                                clearCustomTimeout()
                                runStatusStartTime = SystemClock.elapsedRealtime()
                                scheduleRunStatusFinalize(client)
                                return
                            }

                            val parts = stringData.split(":")
                            val commandCode = parts.getOrNull(0)?.toIntOrNull()
                            when (commandCode) {
                                62 -> {
                                    Log.d(TAG, "Identified as WiFi status response (command=62)")
                                    if (parts.size >= 3) {
                                        val mode = parts[1]
                                        val connected = parts[2] == "0"
                                        statusMap["customWifiMode"] = when (mode) {
                                            "1" -> "STA"
                                            "2" -> "AP"
                                            "3" -> "APSTA"
                                            else -> "Unknown"
                                        }
                                        statusMap["customWifiConnected"] = connected.toString()
                                        if (connected && parts.size > 3) {
                                            statusMap["customWifiSSID"] = parts[3]
                                        }
                                        if (parts.size > 4) {
                                            statusMap["customWifiRssi"] = parts[4]
                                        }
                                    }
                                    hasWifiStatus = true
                                }

                                else -> {
                                    Log.w(TAG, "Unknown custom data command: $commandCode, response: $stringData")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing custom data", e)
                    statusMap["parseError"] = "Failed to parse response: ${e.message}"
                } finally {
                    Log.d(TAG, "=================== CUSTOM DATA RESPONSE END ===================")
                }
            }

            override fun onDeviceScanResult(
                client: BlufiClient,
                status: Int,
                results: MutableList<BlufiScanResult>?
            ) {
                Log.d(TAG, "Nearby Wi-Fi scan result status=$status, count=${results?.size ?: 0}")
                if (!wifiScanRequested || wifiScanCompleted || isQueryComplete) {
                    return
                }
                cancelWifiScanTimeout()
                wifiScanCompleted = true
                if (status == STATUS_SUCCESS) {
                    val wifiNetworks = results
                        ?.filter { it.type == BlufiScanResult.TYPE_WIFI }
                        ?.map {
                            NearbyWifiNetwork(
                                ssid = it.ssid ?: "",
                                rssi = it.rssi
                            )
                        }
                        ?: emptyList()
                    updatedDeviceInfo = updatedDeviceInfo.copy(nearbyWiFiNetworks = wifiNetworks)
                    statusMap["nearbyWiFiNetworks"] = gson.toJson(wifiNetworks)
                } else {
                    statusMap["wifiScanError"] = "Wi-Fi scan failed: $status"
                    statusMap["nearbyWiFiNetworks"] = gson.toJson(emptyList<NearbyWifiNetwork>())
                }
                tryFinalize(client)
            }

            override fun onError(client: BlufiClient, errCode: Int) {
                Log.e(TAG, "BluFi error: $errCode")
                statusMap["error"] = "Communication error: $errCode"
                complete()
            }
        })

        fun scheduleRunStatusFinalize(client: BlufiClient) {
            runStatusFinalizeTask?.let { mainHandler.removeCallbacks(it) }
            runStatusFinalizeTask = Runnable {
                if (!lastCommandWasRunStatus) return@Runnable
                if (runStatusStartTime > 0L) {
                    val elapsed = SystemClock.elapsedRealtime() - runStatusStartTime
                    Log.d(TAG, "Run status response completed in ${elapsed}ms")
                    runStatusStartTime = 0L
                }
                runStatusBuffer?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    statusMap["runningStatus"] = it
                }
                runStatusBuffer = null
                lastCommandWasRunStatus = false
                clearCustomTimeout()
                currentCustomCommand = null
                runStatusFinalizeTask = null
                sendNextCustomCommand(client)
                tryFinalize(client)
            }
            mainHandler.postDelayed(runStatusFinalizeTask!!, RUN_STATUS_FINALIZE_DELAY)
        }
    }

    private fun extractMacAddress(raw: String?, payload: String?): String? {
        val candidates = mutableListOf<String>()
        raw?.let { candidates.add(it) }
        payload?.let { candidates.add(it) }
        candidates.forEach { candidate ->
            candidate
                .split(":", "-", ",", " ")
                .filter { it.isNotBlank() }
        }
        candidates.forEach { candidate ->
            val normalized = candidate.trim()
            val macRegex = Regex("([0-9A-Fa-f]{2}[-:]){5}[0-9A-Fa-f]{2}")
            val match = macRegex.find(normalized)
            if (match != null) {
                return match.value.replace('-', ':').uppercase(Locale.getDefault())
            }
            val rawDigits = normalized.replace(":", "").replace("-", "").uppercase(Locale.getDefault())
            if (rawDigits.length == 12 && rawDigits.all { it in "0123456789ABCDEF" }) {
                return rawDigits.chunked(2).joinToString(":") { it }
            }
        }
        return null
    }

    private fun resolveBluetoothDevice(macAddress: String?): BluetoothDevice? {
        if (macAddress.isNullOrBlank()) return null
        return BleDeviceManager.getBluetoothDevice(macAddress)
            ?: bluetoothAdapter?.getRemoteDevice(macAddress)
    }

    fun scanNearbyWiFiForDevice(
        deviceInfo: DeviceInfo,
        callback: ((DeviceInfo, Boolean) -> Unit)? = null
    ) {
        Log.d(TAG, "Start standalone nearby Wi-Fi scan")
        val device = resolveBluetoothDevice(deviceInfo.macAddress) ?: run {
            callback?.invoke(deviceInfo, false)
            return
        }

        var updatedDeviceInfo = deviceInfo
        var operationCompleted = false
        var timeoutRunnable: Runnable? = null

        fun finish(success: Boolean) {
            if (operationCompleted) return
            operationCompleted = true
            timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            lastQueryDeviceInfo = updatedDeviceInfo
            callback?.invoke(updatedDeviceInfo, success)
            disconnect()
        }

        fun scheduleTimeout() {
            timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            timeoutRunnable = Runnable {
                Log.e(TAG, "Standalone nearby Wi-Fi scan timeout")
                finish(false)
            }
            timeoutRunnable?.let { mainHandler.postDelayed(it, WIFI_SCAN_TIMEOUT) }
        }

        Log.d(TAG, "Connecting to device for Wi-Fi scan")
        connect(device)

        blufiClient?.setBlufiCallback(object : BlufiCallback() {
            override fun onGattPrepared(
                client: BlufiClient,
                gatt: BluetoothGatt,
                service: BluetoothGattService?,
                writeChar: BluetoothGattCharacteristic?,
                notifyChar: BluetoothGattCharacteristic?
            ) {
                if (service == null || writeChar == null || notifyChar == null) {
                    Log.e(TAG, "Discover service failed during Wi-Fi scan")
                    finish(false)
                    return
                }
                try {
                    client.negotiateSecurity()
                } catch (e: Exception) {
                    Log.e(TAG, "Security negotiation error during Wi-Fi scan", e)
                    finish(false)
                }
            }

            override fun onNegotiateSecurityResult(client: BlufiClient, status: Int) {
                if (status == STATUS_SUCCESS) {
                    try {
                        client.requestDeviceWifiScan()
                        scheduleTimeout()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to request Wi-Fi scan", e)
                        finish(false)
                    }
                } else {
                    Log.e(TAG, "Security negotiation failed: $status")
                    finish(false)
                }
            }

            override fun onDeviceScanResult(
                client: BlufiClient,
                status: Int,
                results: MutableList<BlufiScanResult>?
            ) {
                timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                if (status == STATUS_SUCCESS) {
                    val wifiNetworks = results
                        ?.filter { it.type == BlufiScanResult.TYPE_WIFI }
                        ?.map {
                            NearbyWifiNetwork(
                                ssid = it.ssid ?: "",
                                rssi = it.rssi
                            )
                        }
                        ?: emptyList()
                    updatedDeviceInfo = updatedDeviceInfo.copy(nearbyWiFiNetworks = wifiNetworks)
                    lastQueryDeviceInfo = updatedDeviceInfo
                    Log.d(TAG, "Standalone Wi-Fi scan success: ${wifiNetworks.size} networks")
                    finish(true)
                } else {
                    Log.e(TAG, "Standalone Wi-Fi scan failed: $status")
                    finish(false)
                }
            }

            override fun onError(client: BlufiClient, errCode: Int) {
                Log.e(TAG, "Error during standalone Wi-Fi scan: $errCode")
                finish(false)
            }
        })
    }

    //endregion

    //region 配网、配服务器
    // -------------- 5. 配网和服务器配置函数 --------------

    /**
     * 配置设备WiFi
     *
     * @param deviceInfo 设备信息
     * @param wifiConfig WiFi配置
     * @param callback 配置结果回调
     */
    fun configureWifi(
        macAdd: String,
        _ssid: String,
        _password:String,
        callback: ((Map<String, String>) -> Unit)? = null
    ) {
        Log.d(TAG, "Start configuring device WiFi:, SSID")

        val device = resolveBluetoothDevice(macAdd) ?: run {
            callback?.invoke(mapOf("error" to "Invalid device address"))
            return
        }

        // 初始化状态信息
        val resultMap = mutableMapOf<String, String>()


        // 配置状态控制
        var isComplete = false
        var warmupRequested = false
        var hasStartedConfig = false

        // 配置超时
        val configTimeoutRunnable = Runnable {
            Log.e(TAG, "WiFi configuration timeout after 20 seconds")
            if (!isComplete) {
                isComplete = true
                resultMap["error"] = "WiFi configuration timeout"
                resultMap["success"] = "false"
                callback?.invoke(resultMap)
                disconnect()
            }
        }

        // 连接设备
        Log.d(TAG, "Connecting to device")
        connect(device)

        // 设置回调
        blufiClient?.setBlufiCallback(object : BlufiCallback() {
            override fun onGattPrepared(client: BlufiClient,
                                        gatt: BluetoothGatt,
                                        service: BluetoothGattService?,
                                        writeChar: BluetoothGattCharacteristic?,
                                        notifyChar: BluetoothGattCharacteristic?) {
                if (service == null || writeChar == null || notifyChar == null) {
                    Log.e(TAG, "Discover service failed")
                    if (!isComplete) {
                        isComplete = true
                        resultMap["error"] = "Service discovery failed"
                        resultMap["success"] = "false"
                        callback?.invoke(resultMap)
                        disconnect()
                    }
                    return
                }

                // 设置超时
                mainHandler.postDelayed(configTimeoutRunnable, 20000)

                // 开始安全协商
                client.negotiateSecurity()
            }

            override fun onNegotiateSecurityResult(client: BlufiClient, status: Int) {
                if (status == STATUS_SUCCESS) {
                    // 安全协商成功，先执行预热指令获取运行状态
                    sendWarmupCommand(client)
                } else {
                    // 安全协商失败
                    if (!isComplete) {
                        isComplete = true
                        resultMap["error"] = "Security negotiation failed: $status"
                        resultMap["success"] = "false"
                        callback?.invoke(resultMap)
                        mainHandler.removeCallbacks(configTimeoutRunnable)
                        disconnect()
                    }
                }
            }

            private fun sendWarmupCommand(client: BlufiClient) {
                if (warmupRequested || isComplete) return
                warmupRequested = true
                Log.d(TAG, "Sending warmup command (${RadarCommand.GET_DEVICE_MAC.code}) before WiFi configuration")
                client.postCustomData(RadarCommand.GET_DEVICE_MAC.buildRequest().toByteArray())
            }

            private fun startWifiConfiguration(client: BlufiClient) {
                if (isComplete || hasStartedConfig) return
                hasStartedConfig = true
                Log.d(TAG, "Warmup successful, starting WiFi configuration")
                val params = BlufiConfigureParams().apply {
                    opMode = 1  // STA模式
                    staSSIDBytes = _ssid.toByteArray()
                    staPassword = _password
                }
                client.configure(params)
            }

            override fun onPostConfigureParams(client: BlufiClient, status: Int) {
                if (!isComplete) {
                    isComplete = true

                    if (status == STATUS_SUCCESS) {
                        // WiFi配置成功
                        resultMap["wifiConfigured"] = "true"
                        resultMap["success"] = "true"
                        Log.d(TAG, "WiFi configuration successful")
                    } else {
                        // WiFi配置失败
                        resultMap["wifiConfigured"] = "false"
                        resultMap["success"] = "false"
                        resultMap["error"] = "WiFi configuration failed: $status"
                        Log.e(TAG, "WiFi configuration failed with status: $status")
                    }

                    // 添加完成时间
                    resultMap["completedAt"] = System.currentTimeMillis().toString()

                    // 返回结果
                    callback?.invoke(resultMap)

                    // 清理资源
                    mainHandler.removeCallbacks(configTimeoutRunnable)
                    disconnect()
                }
            }

            override fun onError(client: BlufiClient, errCode: Int) {
                if (!isComplete) {
                    isComplete = true
                    resultMap["error"] = "Communication error: $errCode"
                    resultMap["success"] = "false"
                    resultMap["completedAt"] = System.currentTimeMillis().toString()
                    callback?.invoke(resultMap)
                    mainHandler.removeCallbacks(configTimeoutRunnable)
                    disconnect()
                }
            }

            override fun onReceiveCustomData(client: BlufiClient, status: Int, data: ByteArray) {
                if (isComplete) return

                val responseStr = runCatching { String(data) }.getOrNull()
                val parsed = RadarCommand.parseResponse(responseStr)

                if (parsed.command == RadarCommand.GET_DEVICE_MAC) {
                    if (status == STATUS_SUCCESS && parsed.success != false) {
                        val warmupResult = parsed.payload ?: responseStr.orEmpty()
                        Log.d(TAG, "Warmup command response received before WiFi configuration: $warmupResult")
                        resultMap["warmupStatus"] = warmupResult
                        startWifiConfiguration(client)
                    } else if (!isComplete) {
                        isComplete = true
                        resultMap["error"] = "Warmup command failed during WiFi configuration"
                        resultMap["success"] = "false"
                        callback?.invoke(resultMap)
                        mainHandler.removeCallbacks(configTimeoutRunnable)
                        disconnect()
                    }
                }
            }
        })
    }

    /**
     * 配置设备服务器
     *
     * @param deviceInfo 设备信息
     * @param serverConfig 服务器配置
     * @param callback 配置结果回调
     */
    fun configureServer(
        deviceInfo: DeviceInfo,
        serverConfig: ServerConfig,
        callback: ((Map<String, String>) -> Unit)? = null
    ) {
        Log.d(TAG, "Start configuring device server")

        val device = resolveBluetoothDevice(deviceInfo.macAddress) ?: run {
            callback?.invoke(mapOf("error" to "Invalid device address"))
            return
        }

        // 初始化状态信息
        val resultMap = mutableMapOf<String, String>()
        resultMap["deviceId"] = deviceInfo.deviceId
        resultMap["macAddress"] = deviceInfo.macAddress

        // 配置状态控制
        var isComplete = false
        var addressConfigured = false
        var portConfigured = false
        var deviceRestarted = false
        var warmupRequested = false
        var serverCommandsStarted = false

        // 配置超时
        val configTimeoutRunnable = Runnable {
            Log.e(TAG, "Server configuration timeout after 25 seconds")
            if (!isComplete) {
                isComplete = true
                resultMap["error"] = "Server configuration timeout"
                resultMap["success"] = "false"
                callback?.invoke(resultMap)
                disconnect()
            }
        }

        // 连接设备
        Log.d(TAG, "Connecting to device")
        connect(device)

        // 设置回调
        blufiClient?.setBlufiCallback(object : BlufiCallback() {
            override fun onGattPrepared(client: BlufiClient,
                                        gatt: BluetoothGatt,
                                        service: BluetoothGattService?,
                                        writeChar: BluetoothGattCharacteristic?,
                                        notifyChar: BluetoothGattCharacteristic?) {
                if (service == null || writeChar == null || notifyChar == null) {
                    Log.e(TAG, "Discover service failed")
                    if (!isComplete) {
                        isComplete = true
                        resultMap["error"] = "Service discovery failed"
                        resultMap["success"] = "false"
                        callback?.invoke(resultMap)
                        disconnect()
                    }
                    return
                }

                // 设置超时
                mainHandler.postDelayed(configTimeoutRunnable, CONFIGSERVER_TIMEOUT)

                // 开始安全协商
                client.negotiateSecurity()
            }

            override fun onNegotiateSecurityResult(client: BlufiClient, status: Int) {
                if (status == STATUS_SUCCESS) {
                    // 安全协商成功，先执行预热命令
                    sendWarmupCommand(client)
                } else {
                    // 安全协商失败
                    if (!isComplete) {
                        isComplete = true
                        resultMap["error"] = "Security negotiation failed: $status"
                        resultMap["success"] = "false"
                        callback?.invoke(resultMap)
                        mainHandler.removeCallbacks(configTimeoutRunnable)
                        disconnect()
                    }
                }
            }

            private fun sendWarmupCommand(client: BlufiClient) {
                if (warmupRequested || isComplete) return
                warmupRequested = true
                Log.d(TAG, "Sending warmup command (${RadarCommand.GET_DEVICE_MAC.code}) before server configuration")
                client.postCustomData(RadarCommand.GET_DEVICE_MAC.buildRequest().toByteArray())
            }

            private fun sendServerCommands(client: BlufiClient) {
                if (serverCommandsStarted || isComplete) return
                serverCommandsStarted = true
                // 发送服务器地址
                val serverCmd = RadarCommand.SET_SERVER_ADDRESS.buildRequest(serverConfig.serverAddress)
                Log.d(TAG, "Sending server address command: $serverCmd")
                client.postCustomData(serverCmd.toByteArray())

                // 等待服务器地址响应后再发送端口 - 延迟发送端口命令
                mainHandler.postDelayed({
                    if (!isComplete) {  // 检查是否已完成
                        // 发送服务器端口
                        val portCmd = RadarCommand.SET_SERVER_PORT.buildRequest(serverConfig.port.toString())
                        Log.d(TAG, "Sending server port command: $portCmd")
                        client.postCustomData(portCmd.toByteArray())

                        // 等待端口响应后再发送额外命令
                        mainHandler.postDelayed({
                            if (!isComplete) {  // 再次检查是否已完成
                                // 发送必要的额外命令
                                sendExtraCommands(client)
                            }
                        }, COMMAND_DELAYTIME)
                    }
                }, COMMAND_DELAYTIME)
            }

            private fun sendExtraCommands(client: BlufiClient) {
                // 发送命令 3:0
                Log.d(TAG, "Sending extra command 3:0")
                client.postCustomData("3:0".toByteArray())

                // 等待响应后发送下一个命令
                mainHandler.postDelayed({
                    if (!isComplete) {  // 检查是否已完成
                        // 发送命令 8:0
                        Log.d(TAG, "Sending extra command 8:0")
                client.postCustomData("8:0".toByteArray())

                        // 等待响应后重启设备
                        mainHandler.postDelayed({
                            if (!isComplete) {  // 再次检查是否已完成
                                // 重启设备
                                sendRestartCommand(client)
                            }
                        }, COMMAND_DELAYTIME)
                    }
                }, COMMAND_DELAYTIME)
            }

            private fun sendRestartCommand(client: BlufiClient) {
                // 发送重启命令
                Log.d(TAG, "Sending restart command 8:")
                client.postCustomData(RadarCommand.RESTART_DEVICE.buildRequest().toByteArray())

                // 如果5秒后仍未收到重启响应，则认为重启已经开始但无法收到响应，认为配置完成
                mainHandler.postDelayed({
                    if (!isComplete) {
                        // 还未收到重启响应，但认为已经在重启中
                        isComplete = true

                        // 添加最终状态
                        resultMap["deviceRestarted"] = "unknown"

                        // 检查整体配置是否成功 - 至少服务器地址或端口配置成功
                        val success = addressConfigured || portConfigured
                        resultMap["success"] = success.toString()

                        // 添加完成时间
                        resultMap["completedAt"] = System.currentTimeMillis().toString()

                        // 返回结果
                        Log.d(TAG, "Configuration completed with timeout, result: $resultMap")
                        callback?.invoke(resultMap)

                        // 清理资源
                        mainHandler.removeCallbacks(configTimeoutRunnable)
                        disconnect()
                    }
                }, DEVICERESTART_DELAYTIME)
            }

            override fun onReceiveCustomData(client: BlufiClient, status: Int, data: ByteArray) {
                try {
                    val responseStr = String(data)
                    Log.d(TAG, "Received custom data: $responseStr")

                    val parsed = RadarCommand.parseResponse(responseStr)
                    val raw = parsed.raw ?: responseStr

                    when (parsed.command) {
                        RadarCommand.GET_DEVICE_MAC -> {
                            if (status == STATUS_SUCCESS && parsed.success != false) {
                                Log.d(TAG, "Warmup command response received before server configuration: ${parsed.payload ?: raw}")
                                resultMap["warmupStatus"] = parsed.payload ?: raw
                                sendServerCommands(client)
                            } else if (!isComplete) {
                                isComplete = true
                                resultMap["error"] = "Warmup command failed during server configuration"
                                resultMap["success"] = "false"
                                callback?.invoke(resultMap)
                                mainHandler.removeCallbacks(configTimeoutRunnable)
                                disconnect()
                            }
                        }

                        RadarCommand.SET_SERVER_ADDRESS -> {
                            val success = parsed.success == true
                            addressConfigured = success
                            resultMap["serverAddressSuccess"] = success.toString()
                            Log.d(TAG, "Server address configuration ${if (success) "successful" else "failed"}")
                        }

                        RadarCommand.SET_SERVER_PORT -> {
                            val success = parsed.success == true
                            portConfigured = success
                            resultMap["serverPortSuccess"] = success.toString()
                            Log.d(TAG, "Server port configuration ${if (success) "successful" else "failed"}")
                        }

                        RadarCommand.RESTART_DEVICE -> {
                            val success = parsed.success == true
                            deviceRestarted = success
                            resultMap["deviceRestarted"] = success.toString()
                            Log.d(TAG, "Device restart ${if (success) "successful" else "failed"}")

                            if (!isComplete) {
                                isComplete = true
                                val overallSuccess = addressConfigured || portConfigured
                                resultMap["success"] = overallSuccess.toString()
                                resultMap["completedAt"] = System.currentTimeMillis().toString()
                                callback?.invoke(resultMap)
                                mainHandler.removeCallbacks(configTimeoutRunnable)
                                disconnect()
                            }
                        }

                        else -> {
                            // 保留原有字符串，供上层调试
                            resultMap["unknownResponse"] = parsed.payload ?: raw
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing custom data response", e)
                    resultMap["parseError"] = e.message ?: "Unknown error"
                }
            }

            override fun onError(client: BlufiClient, errCode: Int) {
                if (!isComplete) {
                    isComplete = true
                    resultMap["error"] = "Communication error: $errCode"
                    resultMap["success"] = "false"
                    resultMap["completedAt"] = System.currentTimeMillis().toString()
                    callback?.invoke(resultMap)
                    mainHandler.removeCallbacks(configTimeoutRunnable)
                    disconnect()
                }
            }
        })
    }

//endregion

//---------------A厂自定义---------
    /**
     * 重启设备
     *
     * @param callback 结果回调
     */
    fun restartDevice(callback: ((Boolean) -> Unit)? = null) {
        // 发送重启指令 8:
        val restartCmd = "8:"
        configureCallback = callback
        postCustomData(restartCmd.toByteArray())
    }

    /**
     * 获取设备UID
     *
     * @param onResult 结果回调，传递UID字符串
     */
    fun getDeviceUID(onResult: (String?) -> Unit) {
        // 发送获取UID指令 12:
        val uidCmd = "12:"

        // 这里假设设备会通过自定义数据返回UID
        // 实际实现可能需要在BlufiCallback中处理返回数据
        postCustomData(uidCmd.toByteArray())

        // 注意：这里需要在接收到设备返回数据后回调
        // 此处仅为示例，实际实现应在onReceiveCustomData中处理
    }

    private fun handleGattFailure(status: Int): Boolean {
        if (!isRetryEnabled) {
            notifyErrorCallback?.invoke(ErrorType.CONNECTION_TIMEOUT, "Connection failed: $status")
            return false
        }
        val mac = currentDeviceMac
        if (mac.isNullOrBlank()) {
            notifyErrorCallback?.invoke(ErrorType.CONNECTION_TIMEOUT, "Connection failed: $status")
            return false
        }
        if (!isConnecting) {
            isConnecting = true
        }
        if (errorCount >= MAX_RETRY_COUNT) {
            notifyErrorCallback?.invoke(ErrorType.CONNECTION_TIMEOUT, "Connection failed: $status")
            return false
        }
        errorCount++
        stopKeepAlive()
        mainHandler.postDelayed({
            disconnect()
            connect(mac)
        }, RECONNECT_DELAY)
        return true
    }
}