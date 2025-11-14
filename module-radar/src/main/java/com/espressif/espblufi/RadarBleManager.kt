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
import com.espressif.espblufi.BlufiCallback.STATUS_SUCCESS
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
        private const val QUERY_TIMEOUT = 20000L  // 查询超时时间 20秒
        private const val GATT_WRITE_TIMEOUT = 10000L //连接超时间10秒
        private const val CONFIG_TOTAL_TIMEOUT = 20000L
        private const val PREHEAT_TIMEOUT = 5000L
        private const val WIFI_CONFIG_TIMEOUT = 5000L
        private const val SERVER_STEP_TIMEOUT = 8000L
        private const val DEVICERESTART_DELAYTIME = 5000L
        private const val RESTART_FINALIZE_DELAY = 3000L
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
    private var isGattConnected: Boolean = false
    private var activeBlufiCallback: BlufiCallback? = null
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


    //region 配网、配服务器
    // -------------- 5. 配网和服务器配置函数 --------------

    private enum class ConfigStage { IDLE, PREHEAT, WIFI, SERVER, COMPLETE }
    private enum class ServerPhase { NONE, ADDRESS, PORT, EXTRA3, RESTART }

    private data class ConfigContext(
        val deviceInfo: DeviceInfo,
        val wifiConfig: WifiConfig?,
        val serverConfig: ServerConfig?,
        val statusCallback: ((String) -> Unit)?,
        val resultCallback: (Map<String, String>) -> Unit
    ) {
        val result: MutableMap<String, String> = mutableMapOf()
        var stage: ConfigStage = ConfigStage.IDLE
        var serverPhase: ServerPhase = ServerPhase.NONE
        var configTimeout: Runnable? = null
        var stepTimeout: Runnable? = null
        var restartFinalizeTask: Runnable? = null
    }

    private var currentConfig: ConfigContext? = null

    fun configureDevice(
        deviceInfo: DeviceInfo,
        wifiConfig: WifiConfig?,
        serverConfig: ServerConfig?,
        statusCallback: ((String) -> Unit)? = null,
        callback: (Map<String, String>) -> Unit
    ) {
        if (wifiConfig == null && serverConfig == null) {
            callback.invoke(mapOf("success" to "false", "error" to "No configuration parameters provided"))
            return
        }
        if (currentConfig != null) {
            callback.invoke(mapOf("success" to "false", "error" to "Another configuration is already in progress"))
            return
        }

        val targetDevice = resolveBluetoothDevice(deviceInfo.macAddress) ?: run {
            callback.invoke(mapOf("success" to "false", "error" to "Invalid device address"))
            return
        }

        val context = ConfigContext(
            deviceInfo = deviceInfo,
            wifiConfig = wifiConfig,
            serverConfig = serverConfig,
            statusCallback = statusCallback,
            resultCallback = callback
        )
        currentConfig = context

        postConfigStatus(context, "Starting configuration for ${deviceInfo.deviceName.ifBlank { deviceInfo.macAddress }}")
        startConfigTimeout(context, CONFIG_TOTAL_TIMEOUT, "configuration timeout")

        val reuseConnection = isGattConnected && blufiClient != null

        val configCallback = createConfigCallback(context)

        if (reuseConnection) {
            Log.d(TAG, "Reusing existing connection for configuration")
            postConfigStatus(context, "Reusing existing connection")
            activeBlufiCallback = configCallback
            blufiClient?.setBlufiCallback(configCallback)
            beginConfiguration(context)
        } else {
            postConfigStatus(context, "Connecting to ${deviceInfo.deviceName.ifBlank { deviceInfo.macAddress }}")
            connect(targetDevice, configCallback)
        }
    }

    private fun createConfigCallback(context: ConfigContext): BlufiCallback {
        return object : BlufiCallback() {
            override fun onGattPrepared(
                client: BlufiClient,
                gatt: BluetoothGatt,
                service: BluetoothGattService?,
                writeChar: BluetoothGattCharacteristic?,
                notifyChar: BluetoothGattCharacteristic?
            ) {
                if (currentConfig !== context) return
                if (service == null || writeChar == null || notifyChar == null) {
                    failConfiguration(context, "Service discovery failed")
                    return
                }
                postConfigStatus(context, "Negotiating security...")
                try {
                    client.negotiateSecurity()
                } catch (e: Exception) {
                    failConfiguration(context, "Failed to negotiate security: ${e.message}")
                }
            }

            override fun onNegotiateSecurityResult(client: BlufiClient, status: Int) {
                if (currentConfig !== context || context.stage == ConfigStage.COMPLETE) return
                if (status == STATUS_SUCCESS) {
                    postConfigStatus(context, "Security negotiation successful")
                    startPreheat(context, client)
                } else {
                    failConfiguration(context, "Security negotiation failed: $status")
                }
            }

            override fun onPostConfigureParams(client: BlufiClient, status: Int) {
                if (currentConfig !== context || context.stage != ConfigStage.WIFI) return
                handleWifiConfigureResult(context, status, client)
            }

            override fun onReceiveCustomData(client: BlufiClient, status: Int, data: ByteArray) {
                if (currentConfig !== context || context.stage == ConfigStage.COMPLETE) return
                val responseStr = runCatching { String(data) }.getOrNull()
                val parsed = RadarCommand.parseResponse(responseStr)
                when (context.stage) {
                    ConfigStage.PREHEAT -> handlePreheatResponse(context, parsed, client)
                    ConfigStage.SERVER -> handleServerResponse(context, parsed, responseStr, client)
                    else -> Unit
                }
            }

            override fun onError(client: BlufiClient, errCode: Int) {
                if (currentConfig !== context || context.stage == ConfigStage.COMPLETE) return
                failConfiguration(context, "Communication error: $errCode")
            }
        }
    }

    private fun beginConfiguration(context: ConfigContext) {
        if (currentConfig !== context) return
        postConfigStatus(context, "Negotiating security...")
        try {
            blufiClient?.negotiateSecurity()
        } catch (e: Exception) {
            failConfiguration(context, "Failed to negotiate security: ${e.message}")
        }
    }

    private fun startPreheat(context: ConfigContext, client: BlufiClient) {
        if (currentConfig !== context) return
        context.stage = ConfigStage.PREHEAT
        startStepTimeout(context, PREHEAT_TIMEOUT, "Preheat timed out")
        val request = RadarCommand.GET_DEVICE_UID.buildRequest()
        postConfigStatus(context, "Sending UID query (12:)")
        try {
            client.postCustomData(request.toByteArray())
        } catch (e: Exception) {
            failConfiguration(context, "Preheat command failed: ${e.message}")
        }
    }

    private fun handlePreheatResponse(
        context: ConfigContext,
        response: RadarCommandResponse,
        client: BlufiClient
    ) {
        if (response.command != RadarCommand.GET_DEVICE_UID) return
        clearStepTimeout(context)
        if (response.success == false) {
            failConfiguration(context, "Preheat failed")
            return
        }
        val uid = response.payload?.trim()?.takeIf { it.isNotEmpty() }
        if (uid != null) {
            context.result["uid"] = uid
            postConfigStatus(context, "Preheat success, UID:$uid")
        } else {
            postConfigStatus(context, "Preheat success, UID unavailable")
        }
        when {
            context.wifiConfig != null -> startWifiConfiguration(context, client)
            context.serverConfig != null -> startServerConfiguration(context, client)
            else -> finishConfiguration(context, true)
        }
    }

    private fun startWifiConfiguration(context: ConfigContext, client: BlufiClient) {
        val wifiConfig = context.wifiConfig ?: run {
            startServerConfiguration(context, client)
            return
        }
        context.stage = ConfigStage.WIFI
        startStepTimeout(context, WIFI_CONFIG_TIMEOUT, "Wi-Fi configuration timed out")
        postConfigStatus(context, "Configuring Wi-Fi SSID: ${wifiConfig.ssid}")
        val params = BlufiConfigureParams().apply {
            opMode = 1
            staSSIDBytes = wifiConfig.ssid.toByteArray()
            staPassword = wifiConfig.password
        }
        try {
            client.configure(params)
        } catch (e: Exception) {
            clearStepTimeout(context)
            failConfiguration(context, "Wi-Fi configuration send failure: ${e.message}")
        }
    }

    private fun handleWifiConfigureResult(context: ConfigContext, status: Int, client: BlufiClient) {
        clearStepTimeout(context)
        if (status == STATUS_SUCCESS) {
            context.result["wifiConfigured"] = "true"
            if (context.serverConfig != null) {
                postConfigStatus(context, "WiFi configuration successful, proceeding to server configuration")
                startServerConfiguration(context, client)
            } else {
                postConfigStatus(context, "WiFi configuration successful")
                context.result["message"] = "WiFi configuration successful"
                finishConfiguration(context, true)
            }
        } else {
            context.result["wifiConfigured"] = "false"
            failConfiguration(context, "Wi-Fi configuration failed: $status")
        }
    }

    private fun startServerConfiguration(context: ConfigContext, client: BlufiClient) {
        val serverConfig = context.serverConfig ?: run {
            finishConfiguration(context, true)
            return
        }
        context.stage = ConfigStage.SERVER
        context.serverPhase = ServerPhase.ADDRESS
        postConfigStatus(context, "Configuring server ${serverConfig.serverAddress}:${serverConfig.port}")
        sendNextServerCommand(context, client)
    }

    private fun sendNextServerCommand(context: ConfigContext, client: BlufiClient) {
        val serverConfig = context.serverConfig ?: return
        when (context.serverPhase) {
            ServerPhase.ADDRESS -> {
                startStepTimeout(context, SERVER_STEP_TIMEOUT, "Server address acknowledgement timed out")
                val command = RadarCommand.SET_SERVER_ADDRESS.buildRequest(serverConfig.serverAddress)
                Log.d(TAG, "Sending server address command: $command")
                client.postCustomData(command.toByteArray())
            }
            ServerPhase.PORT -> {
                startStepTimeout(context, SERVER_STEP_TIMEOUT, "Server port acknowledgement timed out")
                val command = RadarCommand.SET_SERVER_PORT.buildRequest(serverConfig.port.toString())
                Log.d(TAG, "Sending server port command: $command")
                client.postCustomData(command.toByteArray())
            }
            ServerPhase.EXTRA3 -> {
                startStepTimeout(context, SERVER_STEP_TIMEOUT, "Server auxiliary command timed out")
                Log.d(TAG, "Sending server auxiliary command: 3:0")
                client.postCustomData("3:0".toByteArray())
            }
            ServerPhase.RESTART -> {
                postConfigStatus(context, "Sending restart command (8:)")
                Log.d(TAG, "Sending restart command: 8:")
                client.postCustomData(RadarCommand.RESTART_DEVICE.buildRequest().toByteArray())
            }
            else -> Unit
        }
    }

    private fun handleServerResponse(
        context: ConfigContext,
        response: RadarCommandResponse,
        raw: String?,
        client: BlufiClient
    ) {
        when (context.serverPhase) {
            ServerPhase.ADDRESS -> {
                if (response.command == RadarCommand.SET_SERVER_ADDRESS && response.success == true) {
                    clearStepTimeout(context)
                    context.result["serverAddressSuccess"] = "true"
                    context.serverPhase = ServerPhase.PORT
                    sendNextServerCommand(context, client)
                } else {
                    failConfiguration(context, "Server address configuration failed")
                }
            }
            ServerPhase.PORT -> {
                if (response.command == RadarCommand.SET_SERVER_PORT && response.success == true) {
                    clearStepTimeout(context)
                    context.result["serverPortSuccess"] = "true"
                    postConfigStatus(context, "Server configuration successful")
                    context.result["message"] = "Server configuration successful"
                    context.serverPhase = ServerPhase.EXTRA3
                    sendNextServerCommand(context, client)
                } else {
                    failConfiguration(context, "Server port configuration failed")
                }
            }
            ServerPhase.EXTRA3 -> {
                val success = raw?.trim()?.endsWith("3:0") == true || response.payload?.startsWith("0") == true
                if (success) {
                    clearStepTimeout(context)
                    context.serverPhase = ServerPhase.RESTART
                    scheduleRestartFinalize(context)
                    sendNextServerCommand(context, client)
                } else {
                    failConfiguration(context, "Server auxiliary command failed")
                }
            }
            ServerPhase.RESTART -> {
                if (response.command == RadarCommand.RESTART_DEVICE && response.success == true) {
                    clearStepTimeout(context)
                    context.restartFinalizeTask?.let { mainHandler.removeCallbacks(it) }
                    context.restartFinalizeTask = null
                    context.result["deviceRestarted"] = "true"
                    val previous = context.result["message"]?.takeIf { it.isNotBlank() } ?: "Server configuration successful"
                    val message = "$previous\nwait 10 seconds for restart"
                    context.result["message"] = message
                    postConfigStatus(context, "wait 10 seconds for restart")
                    finishConfiguration(context, true)
                } else {
                    failConfiguration(context, "Device restart failed")
                }
            }
            else -> Unit
        }
    }

    private fun postConfigStatus(context: ConfigContext, message: String) {
        context.statusCallback?.let { cb ->
            mainHandler.post { cb(message) }
        }
    }

    private fun startConfigTimeout(context: ConfigContext, timeoutMs: Long, reason: String) {
        context.configTimeout?.let { mainHandler.removeCallbacks(it) }
        val timeout = Runnable {
            if (currentConfig === context) {
                failConfiguration(context, reason)
            }
        }
        context.configTimeout = timeout
        mainHandler.postDelayed(timeout, timeoutMs)
    }

    private fun startStepTimeout(context: ConfigContext, timeoutMs: Long, reason: String) {
        context.stepTimeout?.let { mainHandler.removeCallbacks(it) }
        val timeout = Runnable {
            if (currentConfig === context) {
                failConfiguration(context, reason)
            }
        }
        context.stepTimeout = timeout
        mainHandler.postDelayed(timeout, timeoutMs)
    }

    private fun scheduleRestartFinalize(context: ConfigContext) {
        context.restartFinalizeTask?.let { mainHandler.removeCallbacks(it) }
        val task = Runnable {
            if (currentConfig === context && context.stage == ConfigStage.SERVER && context.serverPhase == ServerPhase.RESTART) {
                context.result["deviceRestarted"] = "true"
                val previous = context.result["message"]?.takeIf { it.isNotBlank() } ?: "Server configuration successful"
                val message = "$previous\nwait 10 seconds for restart"
                context.result["message"] = message
                postConfigStatus(context, "wait 10 seconds for restart")
                finishConfiguration(context, true)
            }
        }
        context.restartFinalizeTask = task
        mainHandler.postDelayed(task, RESTART_FINALIZE_DELAY)
    }

    private fun clearConfigTimeout(context: ConfigContext) {
        context.configTimeout?.let { mainHandler.removeCallbacks(it) }
        context.configTimeout = null
    }

    private fun clearStepTimeout(context: ConfigContext) {
        context.stepTimeout?.let { mainHandler.removeCallbacks(it) }
        context.stepTimeout = null
    }

    private fun finishConfiguration(context: ConfigContext, success: Boolean) {
        if (context.stage == ConfigStage.COMPLETE) return
        clearStepTimeout(context)
        clearConfigTimeout(context)
        context.restartFinalizeTask?.let { mainHandler.removeCallbacks(it) }
        context.restartFinalizeTask = null
        context.stage = ConfigStage.COMPLETE
        if (success) {
            context.result["success"] = "true"
            if (context.result["message"].isNullOrEmpty()) {
                context.result["message"] = "Configuration successful"
            }
        } else {
            context.result.putIfAbsent("success", "false")
        }
        val copy = context.result.toMap()
        mainHandler.post { context.resultCallback(copy) }
        currentConfig = null
    }

    private fun failConfiguration(context: ConfigContext, error: String) {
        if (context.stage == ConfigStage.COMPLETE) return
        context.restartFinalizeTask?.let { mainHandler.removeCallbacks(it) }
        context.restartFinalizeTask = null
        context.result["success"] = "false"
        context.result["error"] = error
        postConfigStatus(context, error)
        finishConfiguration(context, false)
    }

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
    fun connect(deviceMacAddress: String, blufiCallback: BlufiCallback? = null) {
        Log.d(TAG, "connect() requested for $deviceMacAddress, callback=$blufiCallback")
        val device = resolveBluetoothDevice(deviceMacAddress)

        if (device != null) {
            Log.d(TAG, "Device resolved for $deviceMacAddress, starting connection process")
            isConnecting = true
            currentDeviceMac = deviceMacAddress
            connect(device, blufiCallback)
        } else {
            //Log.e(TAG, "Failed to get device with MAC: $deviceMacAddress")
            notifyErrorCallback?.invoke(ErrorType.CONNECTION_TIMEOUT, "Invalid device address")
            // 处理错误
        }
    }

    private fun connect(device: BluetoothDevice, blufiCallback: BlufiCallback? = null) {
        Log.d(TAG, "Preparing new connection to device: ${device.address}")
        if (blufiClient != null) {
            Log.d(TAG, "Existing client detected, disconnecting before new connection")
        }
        disconnect(clearCallback = false)

        BlufiClient(context, device).also { client ->
            blufiClient = client
            Log.d(TAG, "BlufiClient created for ${device.address}, assigning callbacks")

            // 设置 GATT 回调
            client.setGattCallback(createGattCallback())
            val callbackToUse = blufiCallback ?: createBlufiCallback()
            activeBlufiCallback = callbackToUse
            client.setBlufiCallback(callbackToUse)

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

    private fun handleGattFailure(status: Int): Boolean {
        currentConfig?.let { context ->
            failConfiguration(context, "Connection failed: $status")
            return false
        }

        if (!isRetryEnabled) {
            notifyErrorCallback?.invoke(ErrorType.CONNECTION_TIMEOUT, "Connection failed: $status")
            return false
        }
        val mac = currentDeviceMac ?: run {
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
        val callbackSnapshot = activeBlufiCallback
        stopKeepAlive()
        mainHandler.postDelayed({
            disconnect(clearCallback = false)
            if (callbackSnapshot != null) {
                connect(mac, callbackSnapshot)
            } else {
                connect(mac)
            }
        }, RECONNECT_DELAY)
        return true
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
    fun disconnect(clearCallback: Boolean = true) {
        if (blufiClient != null) {
            Log.d(TAG, "disconnect() invoked, clearCallback=$clearCallback, activeCallback=$activeBlufiCallback")
        }
        isConnecting = false

        // 移除所有挂起的回调以防止内存泄漏
        mainHandler.removeCallbacksAndMessages(null)

        // 直接同步执行关闭操作，不使用延迟,否则在connect开始调用，会中断Rigster
        try {
            blufiClient?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing BluetoothGatt", e)
        }
        if (blufiClient != null) {
            Log.d(TAG, "disconnect() completed, client cleared")
        }
        blufiClient = null
        configureCallback = null
        if (clearCallback) {
            activeBlufiCallback = null
        }
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
                            isGattConnected = true
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
                            isGattConnected = false
                            // 连接断开时停止保活
                            stopKeepAlive()
                            disconnect()
                        }
                    }
                } else if (status == 8 && newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.e(TAG, "Connection failed with status 8 (timeout)")
                    isGattConnected = false
                    if (!handleGattFailure(status)) {
                        stopKeepAlive()
                        disconnect()
                    }
                } else if (status == 133 && newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.e(TAG, "Connection failed with status 133 (GATT_ERROR)")
                    isGattConnected = false
                    if (!handleGattFailure(status)) {
                        stopKeepAlive()
                        disconnect()
                    }
                } else {
                    // 其他连接失败情况
                    //Log.e(TAG, "Connection failed with status: $status")
                    stopKeepAlive()
                    isGattConnected = false
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
            //disconnect()
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
        connect(device, object : BlufiCallback() {
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
                    if (wifiNetworks.isNotEmpty()) {
                        val currentWifiSsid = when {
                            statusMap["customWifiSSID"].isNullOrBlank().not() -> statusMap["customWifiSSID"]!!
                            statusMap["staSSID"].isNullOrBlank().not() -> statusMap["staSSID"]!!
                            else -> ""
                        }
                        val currentRssiValue = statusMap["customWifiRssi"]?.toIntOrNull() ?: -255
                        if (currentRssiValue <= -255 && currentWifiSsid.isNotEmpty()) {
                            val matched = wifiNetworks.firstOrNull { it.ssid == currentWifiSsid }
                            matched?.rssi?.let { rssi ->
                                statusMap["customWifiRssi"] = rssi.toString()
                                Log.d(TAG, "Updated Wi-Fi RSSI from scan: ssid=${matched.ssid}, rssi=$rssi")
                            }
                        }
                    }
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
            //disconnect()
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
        connect(device, object : BlufiCallback() {
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
}