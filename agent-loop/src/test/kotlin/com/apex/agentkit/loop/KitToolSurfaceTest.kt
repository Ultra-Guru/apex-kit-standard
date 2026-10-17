package com.apex.agentkit.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KitToolSurface 工具面编排测试：别名归一 / 相近候选 / 白名单过滤 /
 * MAX_TOOLS 截断 / 合成工具 / 指引文案。
 */
class KitToolSurfaceTest {

    private fun defs(vararg names: String): List<KitToolDefinition> =
        names.map { KitToolDefinition(name = it, description = "d", parameters = "{}") }

    @Test
    fun `alias resolution maps common names to registry ids`() {
        assertEquals("code_read", KitToolSurface.resolveRegistryId("read"))
        assertEquals("code_read", KitToolSurface.resolveRegistryId("Read-File"))
        assertEquals("code_write", KitToolSurface.resolveRegistryId("write_file"))
        assertEquals("code_edit", KitToolSurface.resolveRegistryId("multiedit"))
        assertEquals("shell_execute", KitToolSurface.resolveRegistryId("bash"))
        assertEquals("shell_execute", KitToolSurface.resolveRegistryId("Shell"))
        assertEquals("code_grep", KitToolSurface.resolveRegistryId("grep_search"))
        assertEquals("code_glob", KitToolSurface.resolveRegistryId("ls"))
        assertEquals("code_todo", KitToolSurface.resolveRegistryId("todowrite"))
        assertEquals("task", KitToolSurface.resolveRegistryId("subagent"))
        // 未知名原样返回（MCP/skill 动态 id）
        assertEquals("mcp__github__x", KitToolSurface.resolveRegistryId("mcp__github__x"))
    }

    @Test
    fun `suggest tool ids finds near candidates`() {
        val suggestions = KitToolSurface.suggestToolIds(
            "code_reads",
            listOf("code_read", "code_write", "code_grep", "shell_execute")
        )
        assertTrue(suggestions.contains("code_read"))
        assertTrue(KitToolSurface.suggestToolIds("zzzzz", listOf("code_read")).isEmpty())
    }

    @Test
    fun `wildcard allowlist exposes everything`() {
        val plan = KitToolSurface.buildToolPlan(
            KitAgentCatalog.BUILD,
            defs("code_read", "code_write", "shell_execute", "mcp__github__x")
        )
        // 全量 + task 合成工具
        assertTrue(plan.definitions.any { it.name == "task" })
        assertTrue(plan.providerNameToId.containsKey("code_read"))
    }

    @Test
    fun `readonly allowlist filters write tools`() {
        val plan = KitToolSurface.buildToolPlan(
            KitAgentCatalog.PLAN,
            defs("code_read", "code_write", "shell_execute", "code_git_status", "code_todo")
        )
        val registryIds = plan.providerNameToId.values
        assertTrue(registryIds.contains("code_read"))
        assertTrue(registryIds.contains("code_git_status"))
        assertTrue(!registryIds.contains("code_write"))
        assertTrue(!registryIds.contains("shell_execute"))
    }

    @Test
    fun `max tools budget truncates with priority order`() {
        val many = (1..60).map { "code_tool_$it" }
        val plan = KitToolSurface.buildToolPlan(KitAgentCatalog.BUILD, defs(*many.toTypedArray()))
        // 60 > 48 → 截断（+ task 不占预算）
        assertTrue(plan.definitions.size <= KitToolSurface.MAX_TOOLS + 1)
    }

    @Test
    fun `forced tools narrow the surface`() {
        val plan = KitToolSurface.buildToolPlan(
            KitAgentCatalog.BUILD,
            defs("code_read", "code_write", "shell_execute"),
            forcedToolIds = setOf("code_read")
        )
        val registryIds = plan.providerNameToId.values
        assertEquals(setOf("code_read"), registryIds.toSet())
    }

    @Test
    fun `synthetic task tool excluded for subagents`() {
        val plan = KitToolSurface.buildToolPlan(
            KitAgentCatalog.EXPLORE,
            defs("code_read"),
            includeSyntheticTools = false
        )
        assertTrue(plan.definitions.none { it.name == "task" })
    }

    @Test
    fun `tool guide mentions mcp naming with instructions`() {
        val guide = KitToolSurface.buildToolGuide(
            KitAgentCatalog.BUILD,
            listOf("code_read", "mcp__github__create_issue", "task"),
            listOf(KitMcpServerInstructions("github", "gh guide", "mcp__github__"))
        )
        assertTrue(guide.contains("mcp__<server>__<tool>"))
        assertTrue(guide.contains("github"))
        assertTrue(guide.contains("aliases are auto-resolved"))
    }

    @Test
    fun `tool guide for readonly profile declares absence`() {
        val guide = KitToolSurface.buildToolGuide(
            KitAgentCatalog.PLAN,
            listOf("code_read", "code_grep"),
            emptyList()
        )
        assertTrue(guide.contains("READ-ONLY agent"))
        assertTrue(!guide.contains("`task` dispatches"))
    }

    @Test
    fun `empty surface guide answers directly`() {
        val guide = KitToolSurface.buildToolGuide(
            KitAgentCatalog.BUILD, emptyList(), emptyList()
        )
        assertTrue(guide.contains("No tools are available"))
    }
}
