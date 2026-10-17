package com.apex.standardkit.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KitAgentCatalog 目录完整性测试：
 * 角色分工 / 预算序 / 只读硬约束 / 档位映射 / 思考档位推理努力映射。
 */
class KitAgentsTest {

    @Test
    fun `catalog has six definitions covering all kinds`() {
        assertEquals(6, KitAgentCatalog.ALL.size)
        KitAgentKind.entries.forEach { kind ->
            assertNotNull("missing definition for ${kind.key}", KitAgentCatalog.definitionOf(kind))
        }
    }

    @Test
    fun `primary vs subagent split`() {
        assertEquals(
            listOf(KitAgentKind.BUILD, KitAgentKind.PLAN, KitAgentKind.GENERAL),
            KitAgentCatalog.PRIMARY_KINDS
        )
        assertEquals(
            listOf(KitAgentKind.EXPLORE, KitAgentKind.RESEARCH, KitAgentKind.REVIEWER),
            KitAgentCatalog.SUBAGENT_KINDS
        )
    }

    @Test
    fun `plan and explore are readonly profiles`() {
        assertTrue(KitAgentCatalog.PLAN.isReadOnly)
        assertTrue(KitAgentCatalog.EXPLORE.isReadOnly)
        assertTrue(KitAgentCatalog.RESEARCH.isReadOnly)
        assertTrue("评审员只读", KitAgentCatalog.REVIEWER.isReadOnly)
        assertFalse(KitAgentCatalog.BUILD.isReadOnly)
        assertFalse(KitAgentCatalog.GENERAL.isReadOnly)
    }

    @Test
    fun `only primary profiles include synthetic task tool`() {
        KitAgentCatalog.ALL.forEach { def ->
            assertEquals(
                "task 合成工具只给主代理（防子代理递归派发）: ${def.kind.key}",
                def.kind.role == KitAgentRole.PRIMARY,
                def.includeSyntheticTools
            )
        }
    }

    @Test
    fun `plan profile tool surface excludes write tools`() {
        val allow = KitAgentCatalog.PLAN.toolAllowlist
        assertFalse(allow.any { it.isEmpty() })
        assertTrue(allow.contains("code_read"))
        assertTrue(allow.any { it.startsWith("code_git_") })
    }

    @Test
    fun `turn budgets are ordered sanely`() {
        val build = KitAgentCatalog.BUILD.defaultMaxTurns
        val plan = KitAgentCatalog.PLAN.defaultMaxTurns
        val explore = KitAgentCatalog.EXPLORE.defaultMaxTurns
        assertTrue("build 应有最大回合预算", build > plan)
        assertTrue("主代理预算 > 子代理预算", build > explore)
        assertTrue(plan > 4)
        assertTrue(explore >= 8)
    }

    @Test
    fun `primaryFor maps execution modes`() {
        assertEquals(KitAgentKind.BUILD, KitAgentCatalog.primaryFor("build").kind)
        assertEquals(KitAgentKind.PLAN, KitAgentCatalog.primaryFor("PLAN").kind)
        assertEquals(KitAgentKind.GENERAL, KitAgentCatalog.primaryFor("spec").kind)
    }

    @Test
    fun `kind fromKey parses and rejects garbage`() {
        assertEquals(KitAgentKind.EXPLORE, KitAgentKind.fromKey("explore"))
        assertEquals(KitAgentKind.RESEARCH, KitAgentKind.fromKey(" RESEARCH "))
        assertEquals(null, KitAgentKind.fromKey("copilot"))
        assertEquals(null, KitAgentKind.fromKey(null))
        assertEquals(null, KitAgentKind.fromKey(""))
    }

    @Test
    fun `task subagent types all parse via kind catalog`() {
        listOf("explore", "research", "general", "reviewer").forEach { key ->
            assertNotNull("task subagent_type '$key' 必须能解析为画像", KitAgentKind.fromKey(key))
        }
        assertEquals(KitAgentKind.GENERAL, KitAgentKind.fromKey("general"))
    }

    @Test
    fun `thinking levels map to reasoning effort`() {
        assertEquals(KitReasoningEffort.LOW, KitThinkingLevel.NONE.reasoningEffort)
        assertEquals(KitReasoningEffort.LOW, KitThinkingLevel.LIGHT.reasoningEffort)
        assertEquals(null, KitThinkingLevel.STANDARD.reasoningEffort)
        assertEquals(KitReasoningEffort.HIGH, KitThinkingLevel.DEEP.reasoningEffort)
        assertEquals(KitReasoningEffort.HIGH, KitThinkingLevel.APEXCODE.reasoningEffort)
        assertEquals(null, KitThinkingLevel.AUTO.reasoningEffort)
    }

    @Test
    fun `thinking level parses from persistence name`() {
        assertEquals(KitThinkingLevel.STANDARD, KitThinkingLevel.fromName("standard"))
        assertEquals(KitThinkingLevel.APEXCODE, KitThinkingLevel.fromName(" apexcode "))
        assertEquals(null, KitThinkingLevel.fromName("bogus"))
        assertEquals(null, KitThinkingLevel.fromName(null))
    }
}
