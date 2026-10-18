package com.wisefido

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 设备前缀 -> 默认服务器映射；每条规则为 name + prefix + server + port + tls(Y/N)。
 * 规则条数不限（新增硬件只需改在线 JSON），按顺序匹配前缀，先匹配先用。
 * 是否 TLS 只由规则里的 tls 决定，不再由前端开关控制。
 * 优先级：本地自定义规则 > 在线模板（含本地前缀修改）> 内置默认值
 */
data class DeviceServerRule(
    val name: String,
    val prefix: String,
    val server: String,
    val port: Int,
    val tls: Boolean
) {
    fun displayServer(): String = "$server:$port  TLS=${if (tls) "Y" else "N"}"
}

class DeviceServerRules(context: Context) {

    companion object {
        private const val TAG = "DeviceServerRules"

        // 在线模板地址：官网 www 优先，pilot 备用（同一台服务器，仅防域名/站点调整）
        private val REMOTE_URLS = listOf(
            "https://www.wisefido.com/device_config.json",
            "https://pilot.wisefido.com/device_config.json"
        )

        // SharedPreferences 键（v4：任意条数规则）
        private const val KEY_REMOTE_CACHE = "deviceServerRemoteTemplateV4"
        private const val KEY_USER_PREFIXES = "deviceServerUserPrefixesV4"  // {name: prefix}
        private const val KEY_USER_CUSTOM = "deviceServerUserCustomV4"      // 本地自定义规则

        const val CUSTOM_NAME = "Custom"

        val BUILT_IN = listOf(
            DeviceServerRule("Radar", "TSBLU", "app.wisefido.com", 8443, true),
            DeviceServerRule("Sleepad", "BM87", "app.wisefido.com", 29010, false)
        )

        fun isValidPort(port: Int?): Boolean = port != null && port in 1..65535

        // region 解析

        /** tls 支持 true/false 和 "Y"/"N"；非法返回 null */
        private fun parseTls(value: Any?): Boolean? = when (value) {
            null, JSONObject.NULL -> false
            is Boolean -> value
            is String -> when (value.trim().uppercase()) {
                "Y", "YES", "TRUE", "1" -> true
                "N", "NO", "FALSE", "0", "" -> false
                else -> null
            }
            is Number -> value.toInt() != 0
            else -> null
        }

        /** 端口支持数字或字符串；非法返回 null */
        private fun parsePort(value: Any?): Int? = when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }?.takeIf { isValidPort(it) }

        /** 校验单条规则：name/prefix/server/port 必填，非法返回 null；未知字段忽略，便于以后扩展 */
        internal fun parseRule(obj: JSONObject): DeviceServerRule? {
            val name = (obj.opt("name") as? String)?.trim().orEmpty()
            val prefix = (obj.opt("prefix") as? String)?.trim().orEmpty()
            val server = (obj.opt("server") as? String)?.trim()?.lowercase().orEmpty()
            val port = parsePort(obj.opt("port")) ?: return null
            val tls = parseTls(obj.opt("tls")) ?: return null
            if (name.isEmpty() || prefix.isEmpty() || server.isEmpty()) return null
            return DeviceServerRule(name, prefix, server, port, tls)
        }

        /** 解析整份模板；任一条非法或 name 重复则整体视为无效，保留上次缓存 */
        internal fun parseTemplate(text: String): List<DeviceServerRule>? = runCatching {
            val array: JSONArray = JSONObject(text).getJSONArray("rules")
            val rules = (0 until array.length()).map { parseRule(array.getJSONObject(it)) ?: return null }
            if (rules.isEmpty() || rules.map { it.name.lowercase() }.toSet().size != rules.size) return null
            rules
        }.getOrNull()

        // endregion
    }

    private val prefs = context.getSharedPreferences("ble_config", Context.MODE_PRIVATE)

    // region 规则读取

    /** 不含本地修改的默认规则（在线模板缓存，没有则内置） */
    fun defaultRules(): List<DeviceServerRule> =
        prefs.getString(KEY_REMOTE_CACHE, null)?.let { parseTemplate(it) } ?: BUILT_IN

    /** 在线/内置规则，叠加本地前缀修改 */
    fun currentRules(): List<DeviceServerRule> {
        val userPrefixes = userPrefixes()
        return defaultRules().map { rule ->
            userPrefixes.optString(rule.name).takeIf { it.isNotEmpty() }?.let { rule.copy(prefix = it) } ?: rule
        }
    }

    /** 本地自定义规则（优先级最高），未设置返回 null */
    fun customRule(): DeviceServerRule? =
        prefs.getString(KEY_USER_CUSTOM, null)?.let { runCatching { parseRule(JSONObject(it)) }.getOrNull() }

    /**
     * 按 deviceName / deviceId / QR 码前缀匹配规则：本地自定义优先；
     * 同一设备可配多组（如 Radar 的 MQTT / MQTTS），有 TLS=Y 的优先，否则取模板中第一条
     */
    fun ruleForDevice(vararg candidates: String?): DeviceServerRule? {
        fun matches(rule: DeviceServerRule) = candidates.any { it != null && it.startsWith(rule.prefix, ignoreCase = true) }
        customRule()?.takeIf { matches(it) }?.let { return it }
        val matched = currentRules().filter { matches(it) }
        return matched.firstOrNull { it.tls } ?: matched.firstOrNull()
    }

    /** 端口是否 TLS：按规则的 tls 判断，先匹配 server+port，再只匹配端口；没有对应规则按 TLS=N */
    fun isTlsPort(server: String, port: Int): Boolean {
        val rules = listOfNotNull(customRule()) + currentRules()
        rules.firstOrNull { it.server.equals(server.trim(), ignoreCase = true) && it.port == port }?.let { return it.tls }
        return rules.firstOrNull { it.port == port }?.tls ?: false
    }

    // endregion

    // region 本地修改

    private fun userPrefixes(): JSONObject =
        prefs.getString(KEY_USER_PREFIXES, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    /** 保存某条规则的本地前缀；为空或与默认值相同则清除覆盖 */
    fun saveUserPrefix(ruleName: String, prefix: String) {
        val map = userPrefixes()
        val trimmed = prefix.trim()
        val defaultPrefix = defaultRules().firstOrNull { it.name == ruleName }?.prefix.orEmpty()
        if (trimmed.isEmpty() || trimmed.equals(defaultPrefix, ignoreCase = true)) map.remove(ruleName) else map.put(ruleName, trimmed)
        prefs.edit().putString(KEY_USER_PREFIXES, map.toString()).apply()
    }

    /** 保存本地自定义规则；前缀/服务器为空或端口非法则清除 */
    fun saveCustomRule(prefix: String, server: String, port: Int?, tls: Boolean) {
        val rule = if (isValidPort(port)) parseRule(
            JSONObject().put("name", CUSTOM_NAME).put("prefix", prefix).put("server", server).put("port", port).put("tls", tls)
        ) else null
        if (rule == null) {
            prefs.edit().remove(KEY_USER_CUSTOM).apply()
            return
        }
        val json = JSONObject().put("name", rule.name).put("prefix", rule.prefix).put("server", rule.server)
            .put("port", rule.port).put("tls", rule.tls)
        prefs.edit().putString(KEY_USER_CUSTOM, json.toString()).apply()
    }

    fun clearUserOverrides() {
        prefs.edit().remove(KEY_USER_PREFIXES).remove(KEY_USER_CUSTOM).apply()
    }

    // endregion

    // region 在线更新

    /** 从 www.wisefido.com（失败则 pilot.wisefido.com）拉取在线模板，校验通过后缓存；回调在主线程 */
    fun refreshFromRemote(completion: ((Boolean) -> Unit)? = null) {
        Thread {
            var updated = false
            for (url in REMOTE_URLS) {
                val text = runCatching {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000
                        readTimeout = 8000
                        useCaches = false
                        setRequestProperty("Cache-Control", "no-cache")
                    }
                    try {
                        if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
                    } finally {
                        conn.disconnect()
                    }
                }.getOrNull()
                // pilot 站是 SPA，未知路径也返回 200 + HTML，必须以 JSON 解析结果为准
                if (text != null && parseTemplate(text) != null) {
                    prefs.edit().putString(KEY_REMOTE_CACHE, text).apply()
                    Log.d(TAG, "Remote rules updated from $url")
                    updated = true
                    break
                }
                Log.w(TAG, "Remote rules invalid from $url")
            }
            completion?.let { cb -> Handler(Looper.getMainLooper()).post { cb(updated) } }
        }.start()
    }

    // endregion
}
