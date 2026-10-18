package com.wisefido

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceServerRulesTest {

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
}
