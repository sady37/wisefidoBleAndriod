package com.bleconfig.sleepace

import android.util.Log

/**
 * BLE广播数据解析工具类
 */
object BleAdvUtil {
    private const val TAG = "BleAdvUtil"
    private const val MAX_PACKET_LENGTH = 62

    fun getDeviceName(record: ByteArray): String? {
        if (record.isEmpty()) {
            return null
        }

        var data: ByteArray? = null
        var index = 0

        // 解析广播数据
        while (index < (record.size - 1)) {
            val len = record[index].toInt() and 0xFF
            val tp = record[index + 1].toInt() and 0xFF

            // 长度检查
            if (index + len + 1 > MAX_PACKET_LENGTH) {
                break
            } else if (len == 0) {
                break
            }

            // 找到需要的数据段
            if (0xFF == tp) {
                data = ByteArray(len - 1)
                for (i in 0 until len - 1) {
                    data[i] = record[index + 2 + i]
                }
                break
            }
            index += (len + 1)
        }

        // 返回结果日志
        return data?.let {
            String(it).also { name ->
                //Log.d(TAG, "Decoded device name: '$name'")
            }
        }
    }
}