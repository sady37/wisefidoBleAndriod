package com.bleconfig.sleepace

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.sleepace.sdk.domain.BleDevice
import com.sleepace.sdk.interfs.IBleScanCallback
import com.sleepace.sdk.manager.DeviceType
import com.sleepace.sdk.manager.ble.BleHelper
import java.util.regex.Pattern
import android.annotation.SuppressLint

//common data type
import com.common.DeviceInfo
import com.common.Productor
import com.common.BleDeviceManager



class SearchBleDevice(private val context: Context) {
    private val TAG = "SearchBleDevice"
    private val bleHelper: BleHelper = BleHelper.getInstance(context)
    private var onDeviceFoundListener: OnDeviceFoundListener? = null

    interface OnDeviceFoundListener {
        fun onDeviceFound(device: DeviceInfo)
    }

    fun setOnDeviceFoundListener(listener: OnDeviceFoundListener) {
        this.onDeviceFoundListener = listener
    }


    @SuppressLint("MissingPermission")
    fun startScan() {
        if (bleHelper.isBluetoothOpen) {
            bleHelper.scanBleDevice(scanCallback)
        }
    }

    fun stopScan() {
        bleHelper.stopScan()
    }

    private val scanCallback = object : IBleScanCallback {
        override fun onStartScan() {
            Log.d(TAG, "Scan started")

        }

        @SuppressLint("MissingPermission")
        override fun onLeScan(device: BluetoothDevice, rssi: Int, scanRecord: ByteArray) {
            var modelName = device.name
            if (modelName != null) {
                modelName = modelName.trim()
            }

            val deviceName = BleAdvUtil.getDeviceName(scanRecord)?.trim()
            val deviceType = getDeviceTypeByName(deviceName)
            //Log.d(TAG, "onLeScan -deviceType: $deviceType, deviceName: $deviceName, rssi: $rssi, address: ${device.address}")

            if (checkDeviceName(deviceName)) {
                // 创建 BleDevice 对象
                val bleDevice = BleDevice().apply {
                    this.modelName = modelName
                    this.address = device.address
                    this.deviceName = deviceName
                    this.deviceId = deviceName
                    this.deviceType = getDeviceTypeByName(deviceName)
                    this.versionCode = getVersionCode()
                }

                // 保存原始设备到 BleDeviceManager
                BleDeviceManager.saveDevice(device.address, bleDevice)

                // 创建DeviceInfo
                val deviceInfo = DeviceInfo(
                    productorName = Productor.sleepBoardHS,
                    deviceName = deviceType?.toString()?.replace("DEVICE_TYPE_", "") ?: "",
                    deviceId = deviceName ?: "",
                    macAddress = device.address,
                    rssi = rssi
                )

                // 直接调用监听器，返回 DeviceInfo
                onDeviceFoundListener?.onDeviceFound(deviceInfo)
            }
        }

        override fun onStopScan() {
            Log.d(TAG, "Scan stopped")
        }
    }

    private fun checkDeviceName(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p = Pattern.compile("[0-9a-zA-Z-]+")
        val m = p.matcher(deviceName)
        return m.matches()
    }

    private fun checkRestOnZ300(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p = Pattern.compile("^(Z3)[0-9a-zA-Z-]{11}$")
        val m = p.matcher(deviceName)
        return m.matches()
    }

    private fun checkZ400TWP3(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p = Pattern.compile("^(ZTW3)[0-9a-zA-Z]{9}$")
        val m = p.matcher(deviceName)
        return m.matches()
    }

    private fun checkBG001A(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p1 = Pattern.compile("^(GW001)[0-9a-zA-Z-]{8}$")
        val m1 = p1.matcher(deviceName)
        val p2 = Pattern.compile("^(BG01A)[0-9a-zA-Z-]{8}$")
        val m2 = p2.matcher(deviceName)
        return m1.matches() || m2.matches()
    }

    private fun checkBG002(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p1 = Pattern.compile("^(BG02)[0-9a-zA-Z-]{9}$")
        val m1 = p1.matcher(deviceName)
        return m1.matches()
    }

    private fun checkSN913E(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p = Pattern.compile("^(SN91E)[0-9a-zA-Z-]{8}$")
        val m = p.matcher(deviceName)
        return m.matches()
    }

    private fun checkM600(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p = Pattern.compile("^(M6)[0-9a-zA-Z-]{11}$")
        val m = p.matcher(deviceName)
        return m.matches()
    }

    private fun checkM800(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p1 = Pattern.compile("^(M8)[0-9a-zA-Z-]{11}$")
        val m1 = p1.matcher(deviceName)
        return m1.matches()
    }

    private fun checkBM8701(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p1 = Pattern.compile("^(BM)[0-9a-zA-Z-]{11}$")
        val m1 = p1.matcher(deviceName)
        return m1.matches()
    }

    private fun checkBM8701_2(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p1 = Pattern.compile("^(BM872)[0-9a-zA-Z-]{8}$")
        val m1 = p1.matcher(deviceName)
        return m1.matches()
    }

    private fun checkM8701W(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p1 = Pattern.compile("^(M871W)[0-9a-zA-Z-]{8}$")
        val m1 = p1.matcher(deviceName)
        return m1.matches()
    }

    private fun checkEW201W(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p = Pattern.compile("^(EW1W)[0-9a-zA-Z-]{9}$")
        val m = p.matcher(deviceName)
        return m.matches()
    }

    private fun checkEW202W(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p = Pattern.compile("^(EW22W)[0-9a-zA-Z-]{8}$")
        val m = p.matcher(deviceName)
        return m.matches()
    }

    private fun checkNoxSAW(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p = Pattern.compile("^(SA11)[0-9a-zA-Z-]{9}$")
        val m = p.matcher(deviceName)
        return m.matches()
    }

    private fun checkFH601W(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p = Pattern.compile("^(FH61W)[0-9a-zA-Z-]{8}$")
        val m = p.matcher(deviceName)
        return m.matches()
    }

    private fun checkNox2W(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p = Pattern.compile("^(SN22)[0-9a-zA-Z-]{9}$")
        val m = p.matcher(deviceName)
        return m.matches()
    }

    private fun checkSM100(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p = Pattern.compile("^(SM100)[0-9a-zA-Z]{8}$")
        val m = p.matcher(deviceName)
        return m.matches()
    }

    private fun checkSM200(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p = Pattern.compile("^(SM200)[0-9a-zA-Z]{8}$")
        val m = p.matcher(deviceName)
        return m.matches()
    }

    private fun checkSM300(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p = Pattern.compile("^(SM300)[0-9a-zA-Z]{8}$")
        val m = p.matcher(deviceName)
        return m.matches()
    }

    private fun checkSDC10(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p = Pattern.compile("^(SDC10)[0-9a-zA-Z]{8}$")
        val m = p.matcher(deviceName)
        return m.matches()
    }

    private fun checkM901L(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val p = Pattern.compile("^(M901L)[0-9a-zA-Z]{8}$")
        val m = p.matcher(deviceName)
        return m.matches()
    }

    private fun getDeviceTypeByName(deviceName: String?): DeviceType? {
        return when {
            checkRestOnZ300(deviceName) -> DeviceType.DEVICE_TYPE_Z3
            checkEW201W(deviceName) -> DeviceType.DEVICE_TYPE_EW201W
            checkEW202W(deviceName) -> DeviceType.DEVICE_TYPE_EW202W
            checkNoxSAW(deviceName) -> DeviceType.DEVICE_TYPE_NOX_SAW
            checkM600(deviceName) -> DeviceType.DEVICE_TYPE_M600
            checkM800(deviceName) -> DeviceType.DEVICE_TYPE_M800
            checkBM8701_2(deviceName) -> DeviceType.DEVICE_TYPE_BM8701_2
            checkM8701W(deviceName) -> DeviceType.DEVICE_TYPE_M8701W
            checkBM8701(deviceName) -> DeviceType.DEVICE_TYPE_BM8701
            checkBG001A(deviceName) -> DeviceType.DEVICE_TYPE_BG001A
            checkBG002(deviceName) -> DeviceType.DEVICE_TYPE_BG002
            checkSN913E(deviceName) -> DeviceType.DEVICE_TYPE_SN913E
            checkFH601W(deviceName) -> DeviceType.DEVICE_TYPE_FH601W
            checkNox2W(deviceName) -> DeviceType.DEVICE_TYPE_NOX_2W
            checkZ400TWP3(deviceName) -> DeviceType.DEVICE_TYPE_Z400TWP_3
            checkSM100(deviceName) -> DeviceType.DEVICE_TYPE_SM100
            checkSM200(deviceName) -> DeviceType.DEVICE_TYPE_SM200
            checkSM300(deviceName) -> DeviceType.DEVICE_TYPE_SM300
            checkSDC10(deviceName) -> DeviceType.DEVICE_TYPE_SDC100
            checkM901L(deviceName) -> DeviceType.DEVICE_TYPE_M901L
            else -> {
                Log.d(TAG, "Device type not recognized for device")
                null
            }
        }
    }

    // Device name check methods (e.g., checkRestOnZ300, checkEW201W, etc.)
    // These methods are already defined in your original code.
}