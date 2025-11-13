package com.espressif.espblufi

/**
 * 雷达设备自定义指令定义及工具方法
 *
 * 功能/指令编号参考设备协议：
 * 1  ：设置调度服务器地址
 * 2  ：设置调度服务器端口
 * 8  ：重启设备
 * 10 ：获取运行状态
 * 12 ：获取设备 UID
 * 15 ：设置静态 IP
 * 16 ：设置静态掩码
 * 17 ：设置网关 IP
 * 62 ：获取 Wi-Fi 状态
 * 65 ：获取设备 MAC
 */
enum class RadarCommand(val code: Int) {
    SET_SERVER_ADDRESS(1),
    SET_SERVER_PORT(2),
    RESTART_DEVICE(8),
    GET_RUNNING_STATUS(10),
    GET_DEVICE_UID(12),
    SET_STATIC_IP(15),
    SET_STATIC_MASK(16),
    SET_GATEWAY_IP(17),
    GET_WIFI_STATUS(62),
    GET_DEVICE_MAC(65);

    /**
     * 构建请求字符串，格式为 "code:payload" 或 "code:"
     */
    fun buildRequest(payload: String? = null): String {
        val trimmed = payload?.trim().orEmpty()
        return if (trimmed.isEmpty()) {
            "$code:"
        } else {
            "$code:$trimmed"
        }
    }

    companion object {
        fun fromCode(code: Int): RadarCommand? = values().find { it.code == code }

        /**
         * 根据响应字符串解析指令及数据
         * @param response 设备返回的原始字符串，如 "10:Running"、"1:0"
         */
        fun parseResponse(response: String?): RadarCommandResponse {
                if (response.isNullOrBlank()) {
                    return RadarCommandResponse(null, null, null, raw = response)
                }

                val parts = response.split(":", limit = 2)
                val commandCode = parts.firstOrNull()?.toIntOrNull()
                val payload = if (parts.size > 1) parts[1] else ""
                val command = values().find { it.code == commandCode }

                if (command == GET_RUNNING_STATUS) {
                    return RadarCommandResponse(command, true, response, raw = response)
                }

                val success = when (command) {
                    SET_SERVER_ADDRESS,
                    SET_SERVER_PORT,
                    RESTART_DEVICE,
                    SET_STATIC_IP,
                    SET_STATIC_MASK,
                    SET_GATEWAY_IP,
                    GET_WIFI_STATUS,
                    GET_DEVICE_MAC -> when (payload.firstOrNull()) {
                        '0' -> true
                        '1' -> false
                        else -> null
                    }

                    GET_DEVICE_UID -> if (payload.isNotEmpty()) true else null

                    else -> null
                }

                return RadarCommandResponse(command, success, payload, raw = response)
        }
    }
}

/**
 * 设备指令响应结构
 * @param command 对应的指令枚举，无法识别则为 null
 * @param success 成功标记，若无法判断则为 null
 * @param payload 解析后的载荷数据
 * @param raw     原始响应字符串
 */
data class RadarCommandResponse(
    val command: RadarCommand?,
    val success: Boolean?,
    val payload: String?,
    val raw: String?
)