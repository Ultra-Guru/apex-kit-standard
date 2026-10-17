package com.apex.standardkit.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KitSubAgentPolicy 子代理编排策略测试：深度限制 / 模型引用与覆写裁决 /
 * 续接语义 / 入场白与结果文案。
 */
class KitSubAgentPolicyTest {

    // ═══════════════════════ 深度限制 ═══════════════════════

    @Test
    fun `depth counts ancestors and caps runaway chains`() {
        val lineage = mapOf(
            "child" to "parent",
            "parent" to "grandparent",
            "grandparent" to null as String?
        )
        assertEquals(2, KitSubAgentDepthRule.depthOf("child") { lineage[it] })
        assertEquals(1, KitSubAgentDepthRule.depthOf("parent") { lineage[it] })
        assertEquals(0, KitSubAgentDepthRule.depthOf("grandparent") { lineage[it] })
        assertEquals(0, KitSubAgentDepthRule.depthOf("unknown") { lineage[it] })

        // 默认上限 1：child 已有深度 2 → 拒绝；parent 深度 1 → 拒绝；grandparent → 放行
        assertNull(KitSubAgentDepthRule.admission("grandparent", 1) { lineage[it] })
        assertTrue(KitSubAgentDepthRule.admission("parent", 1) { lineage[it] }!!.contains("depth limit"))
        assertTrue(KitSubAgentDepthRule.admission("child", 1) { lineage[it] }!!.contains("depth limit"))
        // 调高上限 → 放行
        assertNull(KitSubAgentDepthRule.admission("child", 3) { lineage[it] })
    }

    @Test
    fun `cyclic lineage is defensively cut off`() {
        val cycle = mapOf(
            "a" to "b",
            "b" to "a"
        )
        assertEquals(2, KitSubAgentDepthRule.depthOf("a") { cycle[it] })
    }

    // ═══════════════════════ 模型引用 ═══════════════════════

    @Test
    fun `model refs parse three-segment forms`() {
        val plain = KitSubAgentModelRef.parse("anthropic/claude-4")!!
        assertEquals("anthropic", plain.providerId)
        assertEquals("claude-4", plain.modelId)
        assertNull(plain.variantId)
        assertEquals("anthropic/claude-4", plain.toString())

        val variant = KitSubAgentModelRef.parse("openai/gpt#thinking")!!
        assertEquals("thinking", variant.variantId)
        assertEquals("openai/gpt#thinking", variant.toString())

        assertNull(KitSubAgentModelRef.parse(""))
        assertNull(KitSubAgentModelRef.parse("/model"))
        assertNull(KitSubAgentModelRef.parse("provider/"))
        assertNull(KitSubAgentModelRef.parse("a/b/c"))
        assertNull(KitSubAgentModelRef.parse("a/b#c#d"))
        assertNull(KitSubAgentModelRef.parse("a/b#"))
        assertNull(KitSubAgentModelRef.parse("a b/c"))
    }

    @Test
    fun `override resolution validates against catalog`() {
        val catalog: (String, String) -> KitSubAgentCatalogModel? = { provider, model ->
            if (provider == "anthropic" && model == "claude-4") {
                KitSubAgentCatalogModel("anthropic", "claude-4", variants = listOf("fast", "deep"))
            } else {
                null
            }
        }
        // 未覆写
        assertTrue(
            KitSubAgentModelOverride.resolve(null, catalog) is
                KitSubAgentModelOverride.NotOverridden
        )
        // 合法覆写（含变体）
        val ok = KitSubAgentModelOverride.resolve("anthropic/claude-4#deep", catalog)
        assertTrue(ok is KitSubAgentModelOverride.Overridden)
        assertEquals("deep", (ok as KitSubAgentModelOverride.Overridden).ref.variantId)
        // 非法形态
        val malformed = KitSubAgentModelOverride.resolve("not a ref", catalog)
            as KitSubAgentModelOverride.NotOverridden
        assertTrue(malformed.reason!!.contains("Invalid model"))
        // 未知模型
        val unknown = KitSubAgentModelOverride.resolve("ghost/model", catalog)
            as KitSubAgentModelOverride.NotOverridden
        assertTrue(unknown.reason!!.contains("not available"))
        // 未知变体（列出可用）
        val badVariant = KitSubAgentModelOverride.resolve("anthropic/claude-4#nope", catalog)
            as KitSubAgentModelOverride.NotOverridden
        assertTrue(badVariant.reason!!.contains("fast, deep"))
    }

    // ═══════════════════════ 续接语义 ═══════════════════════

    private val catalog = KitSubAgentCatalogPort { agentId ->
        when (agentId) {
            "explore" -> KitSubAgentAgentSummary(
                "explore", "Explorer", KitSubAgentAgentMode.SUBAGENT,
                defaultModelRef = "anthropic/claude-4#fast"
            )
            "reviewer" -> KitSubAgentAgentSummary(
                "reviewer", "Reviewer", KitSubAgentAgentMode.SUBAGENT,
                defaultModelRef = null
            )
            "general" -> KitSubAgentAgentSummary(
                "general", "General", KitSubAgentAgentMode.SUBAGENT,
                defaultModelRef = "openai/gpt"
            )
            "primary" -> KitSubAgentAgentSummary(
                "primary", "Primary", KitSubAgentAgentMode.PRIMARY,
                defaultModelRef = null
            )
            else -> null
        }
    }

    private class FakeLineage(
        private val sessions: Map<String, KitSubAgentLineageInfo>,
        private val current: String
    ) : KitSubAgentSessionLineage {
        override fun find(sessionId: String): KitSubAgentLineageInfo? = sessions[sessionId]
        override fun currentSessionId(): String = current
    }

    @Test
    fun `new spawn gets admission prompt`() {
        val verdict = KitSubAgentContinuation.decide(
            KitSubAgentSpawnRequest(
                agentId = "explore",
                description = "scan deps",
                prompt = "Find all deprecated APIs."
            ),
            FakeLineage(emptyMap(), "s1"),
            catalog
        )
        assertTrue(verdict is KitSubAgentContinuation.Verdict.Create)
        val create = verdict as KitSubAgentContinuation.Verdict.Create
        assertTrue(create.admissionPrompt.startsWith("You are a subagent spawned by another session."))
        assertTrue(create.admissionPrompt.endsWith("Find all deprecated APIs."))
    }

    @Test
    fun `continuation validates parentage and agent mode`() {
        val sessions = mapOf(
            "child1" to KitSubAgentLineageInfo("child1", "s1", "explore", "anthropic/claude-4"),
            "foreign" to KitSubAgentLineageInfo("foreign", "other", "explore", null)
        )
        val lineage = FakeLineage(sessions, "s1")

        // 正常续接
        val ok = KitSubAgentContinuation.decide(
            KitSubAgentSpawnRequest("explore", "d", "p", sessionId = "child1"),
            lineage,
            catalog
        ) as KitSubAgentContinuation.Verdict.Continue
        assertEquals("child1", ok.sessionId)
        assertFalse(ok.switchAgent)
        assertNull(ok.effectiveModelRef)

        // 劫持别人的子会话 → 拒绝
        val hijack = KitSubAgentContinuation.decide(
            KitSubAgentSpawnRequest("explore", "d", "p", sessionId = "foreign"),
            lineage,
            catalog
        ) as KitSubAgentContinuation.Verdict.Rejected
        assertTrue(hijack.reason.contains("not a child"))

        // 目标不存在 → 拒绝
        val missing = KitSubAgentContinuation.decide(
            KitSubAgentSpawnRequest("explore", "d", "p", sessionId = "ghost"),
            lineage,
            catalog
        ) as KitSubAgentContinuation.Verdict.Rejected
        assertTrue(missing.reason.contains("not found"))

        // primary 画像 → 拒绝
        val primary = KitSubAgentContinuation.decide(
            KitSubAgentSpawnRequest("primary", "d", "p"),
            lineage,
            catalog
        ) as KitSubAgentContinuation.Verdict.Rejected
        assertTrue(primary.reason.contains("cannot run as a subagent"))

        // 未知画像 → 拒绝
        val unknown = KitSubAgentContinuation.decide(
            KitSubAgentSpawnRequest("ghost", "d", "p"),
            lineage,
            catalog
        ) as KitSubAgentContinuation.Verdict.Rejected
        assertTrue(unknown.reason.contains("Unknown agent"))
    }

    @Test
    fun `switching agent picks new agent default model`() {
        val sessions = mapOf(
            "child1" to KitSubAgentLineageInfo("child1", "s1", "explore", null)
        )
        val lineage = FakeLineage(sessions, "s1")
        // explore → general：切换后取 general 的默认模型
        val switched = KitSubAgentContinuation.decide(
            KitSubAgentSpawnRequest("general", "d", "p", sessionId = "child1"),
            lineage,
            catalog
        ) as KitSubAgentContinuation.Verdict.Continue
        assertTrue(switched.switchAgent)
        assertEquals("openai/gpt", switched.effectiveModelRef)
        // 显式覆写永远赢
        val overridden = KitSubAgentContinuation.decide(
            KitSubAgentSpawnRequest("general", "d", "p", modelRef = "anthropic/claude-4", sessionId = "child1"),
            lineage,
            catalog
        ) as KitSubAgentContinuation.Verdict.Continue
        assertEquals("anthropic/claude-4", overridden.effectiveModelRef)
    }

    // ═══════════════════════ 入场白与文案 ═══════════════════════

    @Test
    fun `admission texts follow agent-facing conventions`() {
        val admission = KitSubAgentAdmission.admissionPrompt("do the thing")
        assertTrue(admission.startsWith("You are a subagent spawned by another session."))
        assertTrue(admission.endsWith("do the thing"))

        val background = KitSubAgentAdmission.backgroundNotice("s42")
        assertTrue(background.contains("sessionID: s42"))
        assertTrue(background.contains("DO NOT sleep"))

        val completed = KitSubAgentAdmission.completedResult("s42", "found 3 issues")
        assertTrue(completed.startsWith("<subagent sessionID=\"s42\" state=\"completed\">"))
        assertTrue(completed.contains("found 3 issues"))
        assertTrue(completed.trimEnd().endsWith("</subagent>"))

        val failed = KitSubAgentAdmission.failedResult("s42", "budget exhausted")
        assertTrue(failed.contains("Subagent failed (sessionID: s42)"))
        assertTrue(failed.contains("budget exhausted"))
        assertTrue(KitSubAgentAdmission.failedResult("s42", null).contains("unknown error"))
        assertTrue(KitSubAgentAdmission.cancelledResult("s42").contains("cancelled"))
    }

    // ═══════════════════════ 请求校验 ═══════════════════════

    @Test
    fun `spawn request validates required fields`() {
        KitSubAgentSpawnRequest("explore", "d", "p") // 合法
        try {
            KitSubAgentSpawnRequest("", "d", "p")
            org.junit.Assert.fail("expected rejection for empty agentId")
        } catch (_: IllegalArgumentException) {
        }
        try {
            KitSubAgentSpawnRequest("explore", "", "p")
            org.junit.Assert.fail("expected rejection for empty description")
        } catch (_: IllegalArgumentException) {
        }
        try {
            KitSubAgentSpawnRequest("explore", "d", "")
            org.junit.Assert.fail("expected rejection for empty prompt")
        } catch (_: IllegalArgumentException) {
        }
    }
}
