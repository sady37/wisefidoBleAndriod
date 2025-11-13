package com.wisefido

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken


import com.common.FilterType
import com.common.DeviceHistory
import com.common.ServerConfig
import com.common.WifiConfig
import com.common.DefaultConfig
import android.util.Log

// 文件顶部必须包含以下导入
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties



class ConfigStorage(context: Context) {
    private val prefs = context.getSharedPreferences("ble_config", Context.MODE_PRIVATE)
    private val gson = Gson()

    // ==================================================================
    // 新增的密码加密工具类（直接嵌入在 ConfigStorage 内部，无需额外文件）
    // ==================================================================
    private object WifiPasswordCrypto {
        private const val AES_ALGORITHM = "AES/GCM/NoPadding"
        private const val KEY_ALIAS = "WiseFido_Wifi_Key"

        // 获取或生成 AES 密钥（通过 AndroidKeyStore 安全存储）
        private fun getKey(): SecretKey {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            return if (keyStore.containsAlias(KEY_ALIAS)) {
                (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
            } else {
                KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    "AndroidKeyStore"
                ).apply {
                    init(
                        KeyGenParameterSpec.Builder(
                            KEY_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                        ).apply {
                            setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            setKeySize(256)
                        }.build()
                    )
                }.generateKey()
            }
        }

        // 加密密码
        fun encrypt(plainPassword: String): String {
            return try {
                val cipher = Cipher.getInstance(AES_ALGORITHM)
                cipher.init(Cipher.ENCRYPT_MODE, getKey())
                val iv = cipher.iv
                val encryptedBytes = cipher.doFinal(plainPassword.toByteArray())
                Base64.encodeToString(encryptedBytes + iv, Base64.DEFAULT)
            } catch (e: Exception) {
                ""
            }
        }

        // 解密密码
        fun decrypt(encryptedPassword: String): String {
            return try {
                val decodedBytes = Base64.decode(encryptedPassword, Base64.DEFAULT)
                val iv = decodedBytes.copyOfRange(decodedBytes.size - 12, decodedBytes.size)
                val cipher = Cipher.getInstance(AES_ALGORITHM)
                val spec = GCMParameterSpec(128, iv)
                cipher.init(Cipher.DECRYPT_MODE, getKey(), spec)
                val decryptedBytes = cipher.doFinal(decodedBytes.copyOfRange(0, decodedBytes.size - 12))
                String(decryptedBytes)
            } catch (e: Exception) {
                // 兼容旧版本或明文存储的历史数据
                encryptedPassword
            }
        }
    }

    // 保存服务器配置
    fun saveServerConfig(serverConfig: ServerConfig) {
        //Log.d("ConfigStorage", "Saving server config: ${serverConfig.serverAddress}:${serverConfig.port}")

        val servers = getServerConfigs().toMutableList()

        // 移除相同配置
        servers.removeAll { it.serverAddress == serverConfig.serverAddress && it.port == serverConfig.port }

        // 添加到开头
        servers.add(0, serverConfig)

        // 保持最多 5 条记录
        if (servers.size > 5) {
            servers.subList(5, servers.size).clear()
        }

        // 保存到 SharedPreferences
        prefs.edit().apply {
            putString(KEY_SERVER_CONFIGS, gson.toJson(servers))
            apply()
        }

    }

    // 获取服务器配置
    fun getServerConfigs(): List<ServerConfig> {
        val json = prefs.getString(KEY_SERVER_CONFIGS, "[]") ?: "[]"
        val type = object : TypeToken<List<ServerConfig>>() {}.type

        return gson.fromJson(json, type)
    }

    // 保存 Wi-Fi 配置
    fun saveWifiConfig(wifiConfig: WifiConfig) {
        // 1. 创建加密后的配置对象（关键一步）
        val encryptedPassword = WifiPasswordCrypto.encrypt(wifiConfig.password)
        val encryptedConfig = wifiConfig.copy(
            password = if (encryptedPassword.isEmpty() && wifiConfig.password.isNotEmpty()) {
                wifiConfig.password
            } else {
                encryptedPassword
            }
        )

        val existing = getWifiConfigs()
            .filter { it.ssid != wifiConfig.ssid }
            .take(4) // 新记录占据第一个，其余最多保留 4 条

        val encryptedExisting = existing.map {
            val encPwd = WifiPasswordCrypto.encrypt(it.password)
            it.copy(
                password = if (encPwd.isEmpty() && it.password.isNotEmpty()) it.password else encPwd
            )
        }

        val finalList = mutableListOf<WifiConfig>()
        finalList.add(encryptedConfig)
        finalList.addAll(encryptedExisting)

        // 保存到 SharedPreferences
        prefs.edit().apply {
            putString(KEY_WIFI_CONFIGS, gson.toJson(finalList))
            apply()
        }
    }

    // 获取 Wi-Fi 配置
    fun getWifiConfigs(): List<WifiConfig> {
        val json = prefs.getString(KEY_WIFI_CONFIGS, "[]") ?: "[]"
        val type = object : TypeToken<List<WifiConfig>>() {}.type
        //return gson.fromJson(json, type)

        val encryptedList: List<WifiConfig> = gson.fromJson(json, type)
        // 仅解密密码字段，其他字段直接保留
        return encryptedList.map { config ->
            config.copy(
                password = WifiPasswordCrypto.decrypt(config.password)
                // ssid/serverAddress/port 等字段保持原始值
            )
        }
    }

    // 保存配网成功的设备记录
    fun saveDeviceHistory(deviceHistory: DeviceHistory) {
        val histories = getDeviceHistories().toMutableList()

        // 移除相同设备的记录
        histories.removeAll { it.macAddress == deviceHistory.macAddress }

        // 添加到开头
        histories.add(0, deviceHistory)

        // 保持最多 100 条记录
        if (histories.size > 100) {
            histories.removeAt(histories.lastIndex)
        }

        // 保存到 SharedPreferences
        prefs.edit().apply {
            putString(KEY_DEVICE_HISTORIES, gson.toJson(histories))
            apply()
        }
    }

    fun clearServerConfigs() {
        prefs.edit().remove(KEY_SERVER_CONFIGS).apply()
        Log.d("ConfigStorage", "All server configs cleared")
    }

    fun removeServerConfig(serverConfig: ServerConfig) {
        val json = prefs.getString(KEY_SERVER_CONFIGS, "[]") ?: "[]"
        val type = object : TypeToken<List<ServerConfig>>() {}.type
        val servers = gson.fromJson<List<ServerConfig>>(json, type)?.toMutableList() ?: mutableListOf()

        val removed = servers.removeAll {
            it.serverAddress == serverConfig.serverAddress &&
                    it.port == serverConfig.port &&
                    it.protocol.equals(serverConfig.protocol, ignoreCase = true)
        }

        if (removed) {
            prefs.edit().apply {
                putString(KEY_SERVER_CONFIGS, gson.toJson(servers))
                apply()
            }
        }
    }

    fun clearWifiConfigs() {
        prefs.edit().remove(KEY_WIFI_CONFIGS).apply()
        Log.d("ConfigStorage", "All server configs cleared")
    }

    fun removeWifiConfig(ssid: String) {
        val json = prefs.getString(KEY_WIFI_CONFIGS, "[]") ?: "[]"
        val type = object : TypeToken<List<WifiConfig>>() {}.type
        val wifiList = gson.fromJson<List<WifiConfig>>(json, type)?.toMutableList() ?: mutableListOf()

        val removed = wifiList.removeAll { it.ssid == ssid }

        if (removed) {
            prefs.edit().apply {
                putString(KEY_WIFI_CONFIGS, gson.toJson(wifiList))
                apply()
            }
        }
    }

    // 获取配网成功的设备记录
    fun getDeviceHistories(): List<DeviceHistory> {
        val json = prefs.getString(KEY_DEVICE_HISTORIES, "[]") ?: "[]"
        val type = object : TypeToken<List<DeviceHistory>>() {}.type
        return gson.fromJson(json, type)
    }

    // 保存雷达设备名称
    fun saveRadarDeviceName(radarDeviceName: String) {
        prefs.edit().apply {
            putString(KEY_RADAR_DEVICE_NAME, radarDeviceName)
            apply()
        }
    }

    // 获取雷达设备名称
    fun getRadarDeviceName(): String {
        return prefs.getString(KEY_RADAR_DEVICE_NAME, DefaultConfig.RADAR_DEVICE_NAME)
            ?: DefaultConfig.RADAR_DEVICE_NAME
    }

    // 保存过滤器类型
    fun saveFilterType(filterType: String) {
        prefs.edit().apply {
            putString(KEY_FILTER_TYPE, filterType)
            apply()
        }
    }

    // 获取过滤器类型
    fun getFilterType(): FilterType {
        val filterTypeName = prefs.getString(KEY_FILTER_TYPE, DefaultConfig.DEFAULT_FILTER_TYPE.name)
            ?: DefaultConfig.DEFAULT_FILTER_TYPE.name
        return try {
            FilterType.valueOf(filterTypeName)
        } catch (e: IllegalArgumentException) {
            DefaultConfig.DEFAULT_FILTER_TYPE
        }
    }

    // 保存过滤器前缀
    fun saveFilterType(filterType: FilterType) {
        prefs.edit().apply {
            putString(KEY_FILTER_TYPE, filterType.name)
            apply()
        }
    }

    // 获取过滤器前缀
    fun getFilterPrefix(): String {
        return prefs.getString(KEY_FILTER_PREFIX, DefaultConfig.DEFAULT_FILTER_PREFIX)
            ?: DefaultConfig.DEFAULT_FILTER_PREFIX
    }

    companion object {
        private const val KEY_SERVER_CONFIGS = "server_configs"
        private const val KEY_WIFI_CONFIGS = "wifi_configs"
        private const val KEY_DEVICE_HISTORIES = "device_histories"
        private const val KEY_RADAR_DEVICE_NAME = "radar_device_name"
        private const val KEY_FILTER_TYPE = "filter_type"
        private const val KEY_FILTER_PREFIX = "filter_prefix"
    }
}