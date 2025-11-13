package com.common

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.util.Log

/**
 * 蓝牙设备对象管理器
 * 用于存储和检索原始蓝牙设备对象，解决设备对象无法序列化的问题
 */
object BleDeviceManager {
    private const val TAG = "BleDeviceManager"

    // 使用MAC地址作为键存储设备对象
    private val deviceMap = mutableMapOf<String, Any>()

    /**
     * 保存设备对象
     * @param macAddress 设备MAC地址
     * @param device 设备对象，可以是BluetoothDevice, ScanResult, BleDevice等
     */
    fun saveDevice(macAddress: String, device: Any) {
        deviceMap[macAddress] = device
        Log.d(TAG, "Saved device with MAC")
    }

    /**
     * 获取设备对象
     * @param macAddress 设备MAC地址
     * @return 设备对象，需要进行类型转换
     */
    fun getDevice(macAddress: String): Any? {
        return deviceMap[macAddress]
    }

    /**
     * 获取BluetoothDevice设备
     * @param macAddress 设备MAC地址
     * @return BluetoothDevice对象，如果不存在或类型不匹配则返回null
     */
    fun getBluetoothDevice(macAddress: String): BluetoothDevice? {
        val device = deviceMap[macAddress]
        return when (device) {
            is BluetoothDevice -> device
            is ScanResult -> device.device
            else -> null
        }
    }

    /**
     * 获取ScanResult对象
     * @param macAddress 设备MAC地址
     * @return ScanResult对象，如果不存在或类型不匹配则返回null
     */
    fun getScanResult(macAddress: String): ScanResult? {
        val device = deviceMap[macAddress]
        return if (device is ScanResult) device else null
    }

    /**
     * 获取指定类型的设备对象
     * @param macAddress 设备MAC地址
     * @return 指定类型的设备对象，如果不存在或类型不匹配则返回null
     */
    fun <T> getDeviceAs(macAddress: String, clazz: Class<T>): T? {
        val device = deviceMap[macAddress]
        return if (clazz.isInstance(device)) clazz.cast(device) else null
    }

    /**
     * 移除设备对象
     * @param macAddress 设备MAC地址
     */
    fun removeDevice(macAddress: String) {
        deviceMap.remove(macAddress)
        Log.d(TAG, "Removed device with MAC")
    }

    /**
     * 清除所有设备对象
     */
    fun clearDevices() {
        deviceMap.clear()
        Log.d(TAG, "Cleared all devices")
    }

    /**
     * 获取当前保存的设备数量
     */
    fun getDeviceCount(): Int {
        return deviceMap.size
    }

    /**
     * 检查设备是否存在
     */
    fun containsDevice(macAddress: String): Boolean {
        return deviceMap.containsKey(macAddress)
    }
}