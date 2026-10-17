package com.apex.standardkit.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * KitJson 迷你 JSON 编解码器测试：六型解析 / 容错 / 序列化 / 访问器。
 */
class KitJsonTest {

    // ═══════════════════════ 解析 ═══════════════════════

    @Test
    fun `parses primitive scalars`() {
        assertEquals("text", (KitJson.parse("\"text\"") as KitJson.KitJsonValue.Str).value)
        assertEquals(true, (KitJson.parse("true") as KitJson.KitJsonValue.Bool).value)
        assertEquals(false, (KitJson.parse("false") as KitJson.KitJsonValue.Bool).value)
        assertTrue(KitJson.parse("null") is KitJson.KitJsonValue.Null)
        assertEquals(42.0, (KitJson.parse("42") as KitJson.KitJsonValue.Num).value, 0.0)
        assertEquals(-3.5, (KitJson.parse("-3.5") as KitJson.KitJsonValue.Num).value, 0.0)
        assertEquals(1.5e3, (KitJson.parse("1.5e3") as KitJson.KitJsonValue.Num).value, 0.0)
    }

    @Test
    fun `parses nested objects and arrays`() {
        val doc = KitJson.parse(
            """
            {"name":"index","count":3,"nested":{"list":[1,"two",false]},"empty":{}}
            """.trimIndent()
        ) as KitJson.KitJsonValue.Obj
        assertEquals("index", doc.fields["name"].let { (it as KitJson.KitJsonValue.Str).value })
        val nested = doc.fields["nested"] as KitJson.KitJsonValue.Obj
        val list = nested.fields["list"] as KitJson.KitJsonValue.Arr
        assertEquals(3, list.items.size)
        assertEquals(1.0, (list.items[0] as KitJson.KitJsonValue.Num).value, 0.0)
        assertEquals("two", (list.items[1] as KitJson.KitJsonValue.Str).value)
        assertTrue(list.items[2] is KitJson.KitJsonValue.Bool)
        assertTrue(doc.fields["empty"] is KitJson.KitJsonValue.Obj)
    }

    @Test
    fun `parses escapes including unicode`() {
        val parsed = KitJson.parse("\"a\\nb\\tc\\\\d\\\"e\\u4e2d\\u6587\"") as KitJson.KitJsonValue.Str
        assertEquals("a\nb\tc\\d\"e中文", parsed.value)
    }

    @Test
    fun `rejects malformed documents`() {
        assertNull(KitJson.parse(""))
        assertNull(KitJson.parse("{"))
        assertNull(KitJson.parse("[1,]"))
        assertNull(KitJson.parse("{\"a\":}"))
        assertNull(KitJson.parse("truex"))
        assertNull(KitJson.parse("\"unterminated"))
        assertNull(KitJson.parse("{\"a\":1} trailing"))
        assertNull(KitJson.parse("1."))
        assertNull(KitJson.parse("-"))
    }

    @Test
    fun `allows surrounding whitespace`() {
        assertEquals(7.0, (KitJson.parse("  7  ") as KitJson.KitJsonValue.Num).value, 0.0)
        val obj = KitJson.parse("\n{\"a\" : 1}\n") as KitJson.KitJsonValue.Obj
        assertEquals(1.0, (obj.fields["a"] as KitJson.KitJsonValue.Num).value, 0.0)
    }

    // ═══════════════════════ 访问器 ═══════════════════════

    @Test
    fun `accessor chain navigates paths safely`() {
        val root = KitJson.parse("""{"skills":[{"name":"alpha","files":["SKILL.md"]},{"name":"beta"}]}""")
        assertEquals("alpha", root["skills"][0]["name"].text)
        assertEquals("beta", root["skills"][1]["name"].text)
        assertNull(root["skills"][5])
        assertNull(root["skills"][0]["name"][0])
        assertNull(root["missing"]["deeper"])
        assertEquals(listOf("SKILL.md"), root["skills"][0]["files"].textList)
        assertEquals(emptyList<String>(), root["skills"][1]["files"].textList)
    }

    @Test
    fun `typed views are null-safe`() {
        val root = KitJson.parse("""{"s":"x","n":5,"b":true,"a":[1]}""")
        assertEquals("x", root["s"].text)
        assertEquals(5.0, root["n"].number!!, 0.0)
        assertEquals(true, root["b"].boolean)
        assertNull(root["s"].number)
        assertNull(root["n"].text)
        assertTrue(root["a"].array != null)
        assertTrue(root["a"].obj == null)
        assertTrue(root.rootIsObject())
    }

    private fun KitJson.KitJsonValue?.rootIsObject(): Boolean = this?.obj != null

    // ═══════════════════════ 序列化 ═══════════════════════

    @Test
    fun `writes compact json round-tripping scalars`() {
        val value = KitJson.obj(
            "name" to KitJson.str("pdf-writer"),
            "count" to KitJson.num(3.0),
            "flag" to KitJson.bool(false),
            "list" to KitJson.arr(listOf(KitJson.str("a"), KitJson.KitJsonValue.Null))
        )
        val written = KitJson.write(value)
        val reparsed = KitJson.parse(written) as KitJson.KitJsonValue.Obj
        assertEquals("pdf-writer", (reparsed.fields["name"] as KitJson.KitJsonValue.Str).value)
        assertEquals(3.0, (reparsed.fields["count"] as KitJson.KitJsonValue.Num).value, 0.0)
        val list = reparsed.fields["list"] as KitJson.KitJsonValue.Arr
        assertEquals(2, list.items.size)
        assertTrue(list.items[1] is KitJson.KitJsonValue.Null)
    }

    @Test
    fun `escapes control characters on write`() {
        val written = KitJson.write(KitJson.str("line1\nline2\u000Cff\ttab"))
        assertTrue(written.contains("\\n"))
        assertTrue(written.contains("\\f"))
        assertTrue(written.contains("\\t"))
        val back = KitJson.parse(written) as KitJson.KitJsonValue.Str
        assertEquals("line1\nline2\u000Cff\ttab", back.value)
    }

    // ═══════════════════════ 形态探测 ═══════════════════════

    @Test
    fun `looksStructured detects json-shaped text`() {
        assertTrue(KitJson.looksStructured("{ \"a\": 1 }"))
        assertTrue(KitJson.looksStructured("  [1,2,3]"))
        assertFalse(KitJson.looksStructured("plain text"))
        assertFalse(KitJson.looksStructured(""))
    }
}
