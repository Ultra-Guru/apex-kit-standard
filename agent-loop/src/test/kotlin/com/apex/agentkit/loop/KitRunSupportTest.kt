package com.apex.agentkit.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 运行支撑结构测试：工具输出头尾截断器 / 重复守卫 / 流式工具调用累加器 /
 * 默认错误分类器。
 */
class KitRunSupportTest {

    // ═══ ToolOutputTruncator ═══

    @Test
    fun `under budget output passes through unchanged`() {
        val t = ToolOutputTruncator(charBudget = 1_000)
        assertEquals("hello", t.truncate("hello"))
    }

    @Test
    fun `over char budget keeps head and tail with marker`() {
        val t = ToolOutputTruncator(charBudget = 1_000)
        val head = "H".repeat(700)
        val tail = "T".repeat(700)
        val out = t.truncate(head + tail)
        assertTrue(out.startsWith("HHHH"))
        assertTrue(out.endsWith("TTTT"))
        assertTrue(out.contains("output truncated"))
        assertTrue(out.length < head.length + tail.length)
    }

    @Test
    fun `line budget triggers truncation even under char budget`() {
        val t = ToolOutputTruncator(charBudget = 100_000, maxLines = 10)
        val manyLines = (1..500).joinToString("\n") { "line $it" }
        val out = t.truncate(manyLines)
        assertTrue(out.contains("output truncated"))
        // 头尾保留
        assertTrue(out.startsWith("line 1"))
        assertTrue(out.endsWith("line 500"))
    }

    @Test
    fun `extreme min budget still keeps a floor window`() {
        val t = ToolOutputTruncator(charBudget = 10)
        val out = t.truncate("x".repeat(10_000))
        // 保底保留窗（256）+ 截断说明行
        assertTrue(out.contains("output truncated"))
        assertTrue(out.length >= 256)
    }

    // ═══ RepeatGuard ═══

    @Test
    fun `repeat guard warns at third and forces final at fourth`() {
        val g = RepeatGuard()
        g.record("tool|args")
        assertNull(g.warningFor())
        g.record("tool|args")
        assertNull(g.warningFor())
        g.record("tool|args")
        assertTrue(g.warningFor()?.contains("3 times") == true)
        assertFalse(g.shouldForceFinal())
        g.record("tool|args")
        assertTrue(g.shouldForceFinal())
    }

    @Test
    fun `repeat guard counts per fingerprint`() {
        val g = RepeatGuard()
        repeat(4) { g.record("a|x") }
        repeat(2) { g.record("b|y") }
        assertTrue(g.shouldForceFinal())
        // 不同指纹独立计数
        val g2 = RepeatGuard()
        repeat(3) {
            g2.record("a|${it % 2}") // 两个指纹各 2 次
        }
        assertFalse(g2.shouldForceFinal())
    }

    // ═══ KitToolCallAccumulator ═══

    @Test
    fun `streamed fragments accumulate name and arguments`() {
        val acc = KitToolCallAccumulator("call_1", "code_read")
        acc.append(null, """{"pa""")
        acc.append("code_read", """th":"a"}""")
        val built = acc.build()
        assertEquals("call_1", built.id)
        assertEquals("code_read", built.name)
        assertEquals("""{"path":"a"}""", built.arguments)
    }

    // ═══ 默认错误分类器 ═══

    @Test
    fun `default classifier recognizes overflow transient and rejection`() {
        val c = KitLlmErrorClassifier.Default
        assertEquals(
            KitLlmErrorKind.CONTEXT_OVERFLOW,
            c.classify(IllegalStateException("This model's maximum context length is 4096 tokens"))
        )
        assertEquals(
            KitLlmErrorKind.TRANSIENT,
            c.classify(java.io.IOException("connection reset"))
        )
        assertEquals(
            KitLlmErrorKind.TRANSIENT,
            c.classify(IllegalStateException("Request timeout after 30s"))
        )
        assertEquals(
            KitLlmErrorKind.REQUEST_REJECTION,
            c.classify(IllegalStateException("401 unauthorized: bad api key"))
        )
        assertEquals(
            KitLlmErrorKind.FATAL,
            c.classify(IllegalStateException("weird unknown failure"))
        )
    }
}
