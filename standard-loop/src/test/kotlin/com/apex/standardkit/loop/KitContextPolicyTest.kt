package com.apex.standardkit.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KitContextPolicy 上下文策略子系统测试：压力分级 / 预算规划 /
 * 收缩阶梯 / 用量台账与成本 / 估算口径。
 */
class KitContextPolicyTest {

    // ═══════════════════════ 压力分级 ═══════════════════════

    @Test
    fun `pressure grades by ratio`() {
        assertEquals(KitContextPressure.NORMAL, KitContextPressure.of(0.5f, 0.92f))
        assertEquals(KitContextPressure.ELEVATED, KitContextPressure.of(0.75f, 0.92f))
        assertEquals(KitContextPressure.CRITICAL, KitContextPressure.of(0.91f, 0.92f))
        assertEquals(KitContextPressure.OVERFLOW, KitContextPressure.of(0.95f, 0.92f))
        assertEquals(KitContextPressure.OVERFLOW, KitContextPressure.of(1.0f, 0.92f))
    }

    // ═══════════════════════ 窗口策略 ═══════════════════════

    @Test
    fun `unknown window falls back to conservative default`() {
        val policy = KitContextWindowPolicy(contextWindow = null)
        assertEquals(KitContextWindowPolicy.FALLBACK_WINDOW, policy.effectiveWindow)
    }

    @Test
    fun `history budget subtracts reserves`() {
        val policy = KitContextWindowPolicy(
            contextWindow = 100_000,
            outputReserveTokens = 4_096,
            safetyMarginRatio = 0.05f
        )
        assertEquals(100_000, policy.effectiveWindow)
        assertEquals((100_000 * 0.95f).toInt() - 4_096, policy.historyBudget)
    }

    // ═══════════════════════ 预算规划 ═══════════════════════

    @Test
    fun `planner returns ok when budget is sufficient`() {
        val policy = KitContextWindowPolicy(contextWindow = 100_000)
        val decision = KitTokenBudgetPlanner.decide(
            policy = policy,
            systemPromptTokens = 4_000,
            toolDefinitionsTokens = 2_000,
            historyTokens = 10_000
        )
        assertTrue(decision is KitBudgetDecision.Ok)
    }

    @Test
    fun `planner demands compaction when history exceeds budget`() {
        val policy = KitContextWindowPolicy(contextWindow = 20_000, outputReserveTokens = 2_000)
        val decision = KitTokenBudgetPlanner.decide(
            policy = policy,
            systemPromptTokens = 1_000,
            toolDefinitionsTokens = 1_000,
            historyTokens = 18_000
        )
        assertTrue(decision is KitBudgetDecision.Compact)
        val compact = decision as KitBudgetDecision.Compact
        assertTrue(compact.deficitTokens > 0)
    }

    @Test
    fun `planner flags structural overflow when fixed costs exceed window`() {
        val policy = KitContextWindowPolicy(contextWindow = 10_000)
        val decision = KitTokenBudgetPlanner.decide(
            policy = policy,
            systemPromptTokens = 8_000,
            toolDefinitionsTokens = 3_000,
            historyTokens = 0
        )
        assertTrue(decision is KitBudgetDecision.Overflow)
        assertTrue((decision as KitBudgetDecision.Overflow).deficitTokens > 0)
    }

    @Test
    fun `planner pressure composes all components`() {
        val policy = KitContextWindowPolicy(contextWindow = 10_000)
        assertEquals(
            KitContextPressure.NORMAL,
            KitTokenBudgetPlanner.pressure(policy, 1_000, 500, 1_000)
        )
        assertEquals(
            KitContextPressure.OVERFLOW,
            KitTokenBudgetPlanner.pressure(policy, 4_000, 2_000, 4_000)
        )
    }

    // ═══════════════════════ 收缩阶梯 ═══════════════════════

    @Test
    fun `shrink ladder walks retention levels then exhausts`() {
        assertEquals(0.7f, KitOverflowShrinkLadder.next(1.0f)!!)
        assertEquals(0.5f, KitOverflowShrinkLadder.next(0.7f)!!)
        assertEquals(0.35f, KitOverflowShrinkLadder.next(0.5f)!!)
        assertNull(KitOverflowShrinkLadder.next(0.35f))
        assertTrue(KitOverflowShrinkLadder.exhausted(0.35f))
        assertFalse(KitOverflowShrinkLadder.exhausted(0.5f))
        // 未在阶梯上的值归一到不小于它的最小阶梯级
        assertEquals(0.5f, KitOverflowShrinkLadder.next(0.65f)!!)
        assertEquals(0.35f, KitOverflowShrinkLadder.next(0.4f)!!)
        assertEquals(4, KitOverflowShrinkLadder.LADDER.size)
        assertEquals(3, KitOverflowShrinkLadder.MAX_RETRIES)
    }

    @Test
    fun `target tokens apply retention`() {
        assertEquals(7_000, KitOverflowShrinkLadder.targetTokens(10_000, 0.7f))
        assertEquals(3_500, KitOverflowShrinkLadder.targetTokens(10_000, 0.35f))
        assertEquals(0, KitOverflowShrinkLadder.targetTokens(0, 0.7f))
    }

    // ═══════════════════════ 用量台账 ═══════════════════════

    @Test
    fun `ledger accumulates turns and totals`() {
        val ledger = KitUsageLedger()
        ledger.recordTurn(1, 1_000, 500)
        ledger.recordTurn(2, 2_000, 300, cacheReadTokens = 800)
        ledger.recordTurn(3, 500, 200)
        val totals = ledger.totals()
        assertEquals(3, totals.turns)
        assertEquals(3_500L, totals.promptTokens)
        assertEquals(1_000L, totals.completionTokens)
        assertEquals(800L, totals.cacheReadTokens)
        assertEquals(4_500L, totals.totalTokens)
        assertEquals(3, ledger.turnHistory().size)
        assertEquals(1, ledger.turnHistory()[0].turn)
        assertEquals(0L, totals.costUsdMicros) // 未配置费率 → 0
    }

    @Test
    fun `ledger prices input output and cache separately`() {
        val rates = KitCostRates(
            inputCostPerMillion = 3.0,      // $3 / 1M tokens
            outputCostPerMillion = 15.0,    // $15 / 1M tokens
            cacheReadCostPerMillion = 0.3   // $0.3 / 1M tokens
        )
        val ledger = KitUsageLedger(rates)
        // 1M input + 0.2M output + 1M cache = 3 + 3 + 0.3 = $6.3 → 6_300_000 µ$
        ledger.recordTurn(1, 1_000_000, 200_000, cacheReadTokens = 1_000_000)
        assertEquals(6_300_000L, ledger.totals().costUsdMicros)
        // 累计继续翻倍
        ledger.recordTurn(2, 1_000_000, 200_000, cacheReadTokens = 1_000_000)
        assertEquals(12_600_000L, ledger.totals().costUsdMicros)
    }

    // ═══════════════════════ 估算口径 ═══════════════════════

    @Test
    fun `token estimates handle ascii and cjk mixtures`() {
        assertEquals(0, KitTokenEstimates.estimate(""))
        // 8 个 ASCII ≈ 2 tokens
        assertEquals(2, KitTokenEstimates.estimate("abcdefgh"))
        // 4 个 CJK ≈ 2 tokens（1.5 字符/token 向上取整口径）
        assertEquals(2, KitTokenEstimates.estimate("中文文本"))
        // 混合：4 ASCII (1) + 3 CJK (2)
        assertTrue(KitTokenEstimates.estimate("abcd中文本") >= 3)
    }

    @Test
    fun `tool surface estimation grows with definitions`() {
        val one = listOf(
            KitToolDefinition("code_read", "Reads a file", """{"type":"object"}""")
        )
        val three = one + listOf(
            KitToolDefinition("code_write", "Writes a file", """{"type":"object"}"""),
            KitToolDefinition("code_edit", "Edits a file", """{"type":"object"}""")
        )
        val oneCost = KitTokenEstimates.estimateToolSurface(one)
        val threeCost = KitTokenEstimates.estimateToolSurface(three)
        assertTrue(threeCost > oneCost)
        assertTrue(oneCost > KitTokenEstimates.SURFACE_OVERHEAD)
    }
}
