package com.wisefido

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DeviceServerRulesTest {

    private fun newRules(): Pair<DeviceServerRules, android.content.SharedPreferences> {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("ble_config", Context.MODE_PRIVATE)
        return DeviceServerRules(context) to prefs
    }

    private val published = """
        {
          "version": 4,
          "rules": [
            { "name": "Radar",   "prefix": "TSBLU", "server": "app.wisefido.com", "port": 8443,  "tls": "Y" },
            { "name": "Sleepad", "prefix": "BM87",  "server": "app.wisefido.com", "port": 29010, "tls": "N" }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesPublishedTemplate() {
        val rules = DeviceServerRules.parseTemplate(published)!!
        assertEquals(
            listOf(
                DeviceServerRule("Radar", "TSBLU", "app.wisefido.com", 8443, true),
                DeviceServerRule("Sleepad", "BM87", "app.wisefido.com", 29010, false)
            ),
            rules
        )
        assertEquals("app.wisefido.com:8443  TLS=Y", rules[0].displayServer())
    }

    @Test
    fun supportsAnyNumberOfRulesAndUnknownFields() {
        val rules = DeviceServerRules.parseTemplate(
            """{"rules":[
                {"name":"Radar MQTTS","prefix":"TSBLU","server":"app.wisefido.com","port":8443,"tls":true},
                {"name":"Radar MQTT","prefix":"TSBLU","server":"App.Wisefido.com","port":"1883","tls":"N"},
                {"name":"NewDevice","prefix":"ND01","server":"x.wisefido.com","port":9000,"tls":"y","vendor":"future-field"}
            ]}"""
        )!!
        assertEquals(3, rules.size)
        assertEquals(DeviceServerRule("Radar MQTT", "TSBLU", "app.wisefido.com", 1883, false), rules[1])
        assertEquals(true, rules[2].tls)
    }

    @Test
    fun rejectsInvalidTemplates() {
        // pilot 站 SPA 回退的 HTML
        assertNull(DeviceServerRules.parseTemplate("<!doctype html><html></html>"))
        // 空列表
        assertNull(DeviceServerRules.parseTemplate("""{"rules":[]}"""))
        // 非法端口
        assertNull(DeviceServerRules.parseTemplate("""{"rules":[{"name":"A","prefix":"T","server":"h","port":99999,"tls":"Y"}]}"""))
        // 非法 tls
        assertNull(DeviceServerRules.parseTemplate("""{"rules":[{"name":"A","prefix":"T","server":"h","port":8443,"tls":"maybe"}]}"""))
        // 缺 prefix / server
        assertNull(DeviceServerRules.parseTemplate("""{"rules":[{"name":"A","prefix":"","server":"h","port":1,"tls":"N"}]}"""))
        assertNull(DeviceServerRules.parseTemplate("""{"rules":[{"name":"A","prefix":"T","port":1,"tls":"N"}]}"""))
        // name 重复
        assertNull(DeviceServerRules.parseTemplate(
            """{"rules":[{"name":"A","prefix":"T","server":"h","port":1},{"name":"a","prefix":"U","server":"h","port":2}]}"""
        ))
    }

    @Test
    fun saveUserRuleOverlaysCurrentRules() {
        val (rules, _) = newRules()
        rules.saveUserRule("Radar", "TSBLU", "x.wisefido.com", 1883, false)
        val radar = rules.currentRules().first { it.name == "Radar" }
        assertEquals(DeviceServerRule("Radar", "TSBLU", "x.wisefido.com", 1883, false), radar)
        // 未覆盖的规则不受影响
        assertEquals(DeviceServerRules.BUILT_IN[1], rules.currentRules().first { it.name == "Sleepad" })
    }

    @Test
    fun saveUserRuleStoresOnlyFieldsDifferentFromDefault() {
        val (rules, _) = newRules()
        rules.saveUserRule("Radar", "AB12", "app.wisefido.com", 8443, true)
        assertEquals(setOf("prefix"), rules.userRuleOverrides().getJSONObject("Radar").keys().asSequence().toSet())
    }

    @Test
    fun saveUserRuleRemovesEntryWhenAllFieldsMatchDefault() {
        val (rules, _) = newRules()
        rules.saveUserRule("Radar", "AB12", "x.wisefido.com", 1883, false)
        assertTrue(rules.userRuleOverrides().has("Radar"))
        rules.saveUserRule("Radar", "TSBLU", "app.wisefido.com", 8443, true)
        assertFalse(rules.userRuleOverrides().has("Radar"))
        assertEquals(DeviceServerRules.BUILT_IN, rules.currentRules())
    }

    @Test
    fun saveUserRuleIgnoresInvalidPortAndEmptyServer() {
        val (rules, _) = newRules()
        rules.saveUserRule("Radar", "TSBLU", "", 0, true)
        assertFalse(rules.userRuleOverrides().has("Radar"))
    }

    @Test
    fun clearUserOverridesClearsUserRulesKey() {
        val (rules, prefs) = newRules()
        rules.saveUserRule("Radar", "AB12", "x.wisefido.com", 1883, false)
        rules.clearUserOverrides()
        assertNull(prefs.getString("deviceServerUserRulesV5", null))
        assertEquals(DeviceServerRules.BUILT_IN, rules.currentRules())
    }

    @Test
    fun migratesLegacyUserPrefixesIntoUserRules() {
        val (rules, prefs) = newRules()
        prefs.edit().putString("deviceServerUserPrefixesV4", """{"Radar":"AB12"}""").commit()
        val radar = rules.currentRules().first { it.name == "Radar" }
        assertEquals("AB12", radar.prefix)
        // 迁移后旧 key 被删除，新 key 记录前缀覆盖
        assertNull(prefs.getString("deviceServerUserPrefixesV4", null))
        assertEquals("AB12", rules.userRuleOverrides().getJSONObject("Radar").getString("prefix"))
        // 迁移条目与默认其它字段叠加后不应改变服务器
        assertEquals(8443, radar.port)
    }

    @Test
    fun saveUserPrefixWritesToNewKey() {
        val (rules, prefs) = newRules()
        rules.saveUserPrefix("Radar", "AB12")
        assertNull(prefs.getString("deviceServerUserPrefixesV4", null))
        assertEquals("AB12", JSONObject(prefs.getString("deviceServerUserRulesV5", null)!!).getJSONObject("Radar").getString("prefix"))
        assertEquals("AB12", rules.currentRules().first { it.name == "Radar" }.prefix)
    }

    @Test
    fun parsesProtocolField() {
        // 缺省 / 非法值按 tcp
        val rules = DeviceServerRules.parseTemplate(
            """{"rules":[
                {"name":"A","prefix":"T","server":"h","port":1,"tls":"N"},
                {"name":"B","prefix":"U","server":"h","port":2,"tls":"N","protocol":"UDP"},
                {"name":"C","prefix":"V","server":"h","port":3,"tls":"N","protocol":"mqtt"}
            ]}"""
        )!!
        assertEquals("tcp", rules[0].protocol)
        assertEquals("udp", rules[1].protocol)
        assertEquals("tcp", rules[2].protocol)
    }

    @Test
    fun saveUserRuleStoresUdpProtocol() {
        val (rules, _) = newRules()
        rules.saveUserRule("Radar", "TSBLU", "app.wisefido.com", 8443, true, "udp")
        assertEquals(setOf("protocol"), rules.userRuleOverrides().getJSONObject("Radar").keys().asSequence().toSet())
        val radar = rules.currentRules().first { it.name == "Radar" }
        assertEquals("udp", radar.protocol)
        // 恢复 tcp 后覆盖被删除
        rules.saveUserRule("Radar", "TSBLU", "app.wisefido.com", 8443, true, "tcp")
        assertFalse(rules.userRuleOverrides().has("Radar"))
    }

    @Test
    fun saveCustomRuleStoresProtocol() {
        val (rules, _) = newRules()
        rules.saveCustomRule("AB12", "x.wisefido.com", 9000, false, "udp")
        assertEquals("udp", rules.customRule()!!.protocol)
        rules.saveCustomRule("AB12", "", null, false)
        assertNull(rules.customRule())
    }
}
