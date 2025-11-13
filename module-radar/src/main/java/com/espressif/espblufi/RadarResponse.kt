package com.espressif.espblufi

/**
 * 雷达设备的响应数据类
 */
data class RadarResponse(
    val command: RadarCommand,    // 对应的命令
    val status: Status,           // 响应状态
    val data: ByteArray           // 响应数据
) {
    enum class Status(val code: Int) {
        SUCCESS(0x00),           // 成功
        INVALID_PARAM(0x01),     // 参数无效
        NOT_SUPPORTED(0x02),     // 不支持的命令
        BUSY(0x03),              // 设备忙
        ERROR(0xFF);             // 其他错误

        companion object {
            fun fromCode(code: Int) = values().find { it.code == code } ?: ERROR
        }
    }

    companion object {
        /**
         * 从字节数组解析响应数据
         */
        fun fromBytes(bytes: ByteArray): RadarResponse {
            require(bytes.size >= 4) { "Invalid response data length" }

            // 检查起始帧
            require(bytes[0] == 0xAA.toByte()) { "Invalid start frame" }

            // 解析命令码
            val command = RadarCommand.fromCode(bytes[1].toInt())
                ?: throw IllegalArgumentException("Unknown command code: ${bytes[1]}")

            // 解析数据长度
            val length = bytes[2].toInt()
            require(bytes.size == length + 4) { "Data length mismatch" }

            // 验证校验和
            val checksum = bytes.last()
            require(verifyChecksum(bytes)) { "Checksum verification failed" }

            // 解析状态码
            val status = Status.fromCode(bytes[3].toInt())

            // 提取数据部分
            val data = if (length > 0) {
                bytes.copyOfRange(4, bytes.size - 1)
            } else {
                ByteArray(0)
            }

            return RadarResponse(command, status, data)
        }

        private fun verifyChecksum(bytes: ByteArray): Boolean {
            var sum = 0
            for (i in 0 until bytes.size - 1) {
                sum += bytes[i]
            }
            return (sum and 0xFF).toByte() == bytes.last()
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RadarResponse

        if (command != other.command) return false
        if (status != other.status) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = command.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}