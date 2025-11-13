package com.bleconfig.sleepace

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.sleepace.sdk.constant.StatusCode
import com.sleepace.sdk.domain.BleDevice
import com.sleepace.sdk.interfs.IResultCallback
import com.sleepace.sdk.manager.CallbackData
import com.sleepace.sdk.wificonfig.WiFiConfigHelper

import com.common.DeviceInfo
import com.common.BleDeviceManager



class SleepaceBleManager private constructor(context: Context) {
    private val TAG = "SleepaceBleManager"

    companion object {
        const val SCAN_TIMEOUT = 10000L // 10秒超时

        @Volatile
        private var instance: SleepaceBleManager? = null

        @JvmStatic
        fun getInstance(context: Context): SleepaceBleManager =
            instance ?: synchronized(this) {
                instance ?: SleepaceBleManager(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val wifiConfigHelper = WiFiConfigHelper.getInstance(appContext)
    private var scanCallback: IResultCallback<Any>? = null
    private var searchBleDevice: SearchBleDevice? = null
    private var isConfiguring = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // 扫描超时任务
    private val scanTimeoutRunnable = Runnable {
        Log.d(TAG, "Scan timeout after ${SCAN_TIMEOUT}ms")
        scanCallback?.let { callback ->
            val data = CallbackData<Any>()
            data.status = StatusCode.TIMEOUT
            callback.onResultCallback(data)
        }
        stopScan()
    }

    /**
     * 开始扫描设备
     */
    fun startScan(callback: IResultCallback<Any>) {
        Log.d(TAG, "Start scan")

        if (searchBleDevice != null) {
            Log.d(TAG, "Previous scan exists, stopping it")
            stopScan()
        }

        scanCallback = callback
        try {
            searchBleDevice = SearchBleDevice(appContext).also { search ->
                Log.d(TAG, "SearchBleDevice instance created")

                search.setOnDeviceFoundListener(object : SearchBleDevice.OnDeviceFoundListener {
                    override fun onDeviceFound(device: DeviceInfo) {
                        /*Log.d(TAG, "search.setOnDeviceFoundListener - Device found: " +
                                "productorName=${device.productorName}, " +
                                "deviceName=##device##, " +
                                "deviceId=${device.deviceId}, " +
                                "macAddress=${device.macAddress}, " +
                                "rssi=${device.rssi}")
                        */

                        val data = CallbackData<Any>()
                        data.status = StatusCode.SUCCESS
                        data.result = device
                        callback.onResultCallback(data)
                        Log.d(TAG, "Device returned to callback")
                    }
                })

                search.startScan()
                Log.d(TAG, "Search started")

                mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT)
                Log.d(TAG, "Scan timeout scheduled for ${SCAN_TIMEOUT}ms")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Start scan failed: ${e.message}")
            handleError(callback, e)
            mainHandler.removeCallbacks(scanTimeoutRunnable)
            Log.d(TAG, "Scan timeout callback removed due to error")
        }
    }

    /**
     * 停止扫描
     */
    fun stopScan() {
        Log.d(TAG, "Stop scan")
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        searchBleDevice?.stopScan()
        searchBleDevice = null
        scanCallback = null
    }

    /**
     * 开始配网
     */
    fun startConfig(
        device: BleDevice?,
        serverIP: String,
        serverPort: Int,
        ssidRaw: ByteArray,
        password: String,
        callback: IResultCallback<Any>
    ) {
        /*Log.d(TAG, "Start config for device: " +
                "name=${device?.deviceName}, " +
                "type=${device?.deviceType?.type}, " +
                "address=${device?.address}, " +
                "IP=$serverIP, " +
                "Port=$serverPort, " +
                "SSID=${String(ssidRaw)}")
        */
        if (isConfiguring) {
            Log.d(TAG, "Config already in progress, ignoring request")
            return
        }

        if (device?.deviceType?.type == null) {
            Log.e(TAG, "Invalid device type for device")
            handleError(callback, IllegalArgumentException("Invalid device type"))
            return
        }

        isConfiguring = true
        try {
            /*Log.d(TAG, "Starting WiFi config with parameters: " +
                    "deviceType=${device.deviceType.type}, " +
                    "address=${device.address}, " +
                    "serverIP=$serverIP, " +
                    "serverPort=$serverPort")
            */
            wifiConfigHelper.bleWiFiConfig(
                device.deviceType.type,
                device.address,
                serverIP,
                serverPort,
                ssidRaw,
                password
            ) { result ->
                isConfiguring = false
                val success = result.status == StatusCode.SUCCESS
                Log.d(TAG, "Config completed - " +
                        "Status: ${if (success) "SUCCESS" else "FAILED"}, " +
                        //"Device: ##device##, " +
                        "Result code: ${result.status}")
                callback.onResultCallback(result)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Config error for device ##device##", e)
            isConfiguring = false
            handleError(callback, e)
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        Log.d(TAG, "Release resources")
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        stopScan()
        instance = null
    }

    /**
     * 错误处理
     */
    private fun handleError(callback: IResultCallback<Any>, e: Exception) {
        Log.e(TAG, "Error: ${e.message}")
        val data = CallbackData<Any>()
        data.status = StatusCode.FAIL
        data.result = e.message ?: "Unknown error"
        callback.onResultCallback(data)
    }
}