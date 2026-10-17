package com.apex.agentkit.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具名 provider 清洗测试（KitToolNameSanitizer）：
 * 非法字符 / 长度钳制 / 冲突消解 / 双向路由表。
 */
class KitToolSurfaceSanitizeTest {

    @Test
    fun `dotted names become underscores`() {
        assertEquals("terminal_exec", KitToolNameSanitizer.sanitize("terminal.exec"))
        assertEquals("terminal_linux_bootstrap", KitToolNameSanitizer.sanitize("terminal.linux.bootstrap"))
    }

    @Test
    fun `unicode names become provider-safe`() {
        val sanitized = KitToolNameSanitizer.sanitize("mcp__天气服务__查天气")
        assertTrue(Regex("^[a-zA-Z0-9_-]{1,64}$").matches(sanitized))
        assertTrue(sanitized.isNotEmpty())
    }

    @Test
    fun `length clamped to 64`() {
        val long = "a".repeat(100)
        assertEquals(64, KitToolNameSanitizer.sanitize(long).length)
    }

    @Test
    fun `mapping is injective with deterministic disambiguation`() {
        val mapping = KitToolNameSanitizer.buildMapping(
            listOf("terminal.exec", "terminal_exec", "terminal__exec")
        )
        val values = mapping.values.toList()
        assertEquals(values.size, values.toSet().size) // 无冲突
        // 长 id 优先保名
        assertEquals("terminal_exec", mapping["terminal.exec"])
        assertTrue(mapping.values.any { it.startsWith("terminal_") })
    }

    @Test
    fun `empty and garbage ids get fallback names`() {
        assertEquals("tool", KitToolNameSanitizer.sanitize("..."))
        assertEquals("tool", KitToolNameSanitizer.sanitize(""))
        assertEquals("t", KitToolNameSanitizer.sanitize("t"))
    }

    @Test
    fun `build tool plan emits provider legal names only`() {
        val plan = KitToolSurface.buildToolPlan(
            KitAgentCatalog.BUILD,
            listOf(
                "terminal.exec", "mcp__中文__工具", "code_read",
                "a".repeat(80)
            ).map { KitToolDefinition(name = it, description = "d", parameters = "{}") }
        )
        val legal = Regex("^[a-zA-Z0-9_-]{1,64}$")
        plan.definitions.forEach {
            assertTrue("not provider-safe: ${it.name}", legal.matches(it.name))
        }
        // 反查表路由回注册表 id
        assertEquals("terminal.exec", plan.providerNameToId["terminal_exec"])
        assertEquals("code_read", plan.providerNameToId["code_read"])
    }
}
