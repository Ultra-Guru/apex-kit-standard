package com.apex.agentkit.loop

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KitCompactor 压缩器测试：
 * 触发阈值 / 边界对齐 / LLM 摘述 / 滑窗降级 / 不压缩路径 /
 * 增量摘要（priorSummary）/ NUDGE 纠偏 / token 保留窗 /
 * 压缩后继续指令 / 摘要预算 / 溢出 shrink 阶梯。
 */
class KitCompactorTest {

    private fun compactor(client: ScriptedKitModelClient): KitCompactor =
        KitCompactor(modelClient = client)

    private fun messagesOf(count: Int): List<KitMessage> = buildList {
        add(KitMessage.User("任务开始"))
        repeat(count) { i ->
            add(KitMessage.Assistant("步骤 $i", listOf(KitToolCall("c$i", "code_read", "{}"))))
            add(KitMessage.ToolResult("c$i", "结果 $i " + "x".repeat(200)))
        }
    }

    @Test
    fun `no compaction below threshold`() = runTest {
        val compactor = compactor(ScriptedKitModelClient())
        val report = compactor.compactIfNeeded(
            messages = messagesOf(4),
            estimatedTokens = 100,
            maxContextTokens = 100_000,
            threshold = 0.8f,
            preserveRecentMessages = 6,
            preserveRecentTokens = 15_000
        )
        assertNull(report)
    }

    @Test
    fun `llm summary compaction replaces old segment`() = runTest {
        val summary = "## Objective\n- x\n\n## Work State\n### Completed\n- y\n" + "x".repeat(80)
        val client = ScriptedKitModelClient().apply { chatResponse = summary }
        val compactor = compactor(client)
        val messages = messagesOf(10)
        val report = compactor.compactIfNeeded(
            messages = messages,
            estimatedTokens = 500_000,
            maxContextTokens = 100_000,
            threshold = 0.8f,
            preserveRecentMessages = 6,
            preserveRecentTokens = 15_000
        )
        assertNotNull(report)
        assertTrue(report!!.effective)
        assertEquals("LLM_SUMMARY", report.strategy)
        // 替换 = [System 摘要] + 最近段 + [System 继续指令]
        assertTrue(report.replacement.first() is KitMessage.System)
        assertTrue(report.replacement.last() is KitMessage.System)
        assertTrue(report.replacement.size < messages.size)
        assertTrue(report.afterTokens < report.beforeTokens)
    }

    @Test
    fun `llm failure falls back to sliding window digest`() = runTest {
        // chat 抛异常 → 滑窗降级
        val failing = object : KitModelClient {
            override fun chatStream(
                contextTag: String,
                messages: List<KitMessage>,
                tools: List<KitToolDefinition>,
                options: KitRequestOptions
            ) = kotlinx.coroutines.flow.flow { }

            override suspend fun chat(
                contextTag: String,
                messages: List<KitMessage>,
                temperature: Float,
                maxTokens: Int
            ): KitChatResponse = throw IllegalStateException("network down")
        }
        val compactor = KitCompactor(failing)
        val report = compactor.compactIfNeeded(
            messages = messagesOf(10),
            estimatedTokens = 500_000,
            maxContextTokens = 100_000,
            threshold = 0.8f,
            preserveRecentMessages = 6,
            preserveRecentTokens = 15_000
        )
        assertNotNull(report)
        assertTrue(report!!.effective)
        assertEquals("SLIDING_WINDOW", report.strategy)
        assertTrue(report.summary.contains("sliding-window"))
        // 最近 6 条保留：replacement = 摘要 + 6 + 继续指令
        assertEquals(8, report.replacement.size)
        // 末尾是压缩后继续指令
        val tail = report.replacement.last()
        assertTrue(tail is KitMessage.System)
        assertTrue((tail as KitMessage.System).content.startsWith("SYSTEM: Earlier context was compacted"))
    }

    @Test
    fun `boundary aligns to tool result edge`() {
        val compactor = compactor(ScriptedKitModelClient())
        val messages = listOf(
            KitMessage.User("q"),
            KitMessage.Assistant("a", listOf(KitToolCall("c1", "code_read", "{}"))),
            KitMessage.ToolResult("c1", "r1"),
            KitMessage.Assistant("done")
        )
        // 边界 2：切在 assistant(toolCalls) 与 result 之间 → 对齐到 3（result 之后）
        assertEquals(3, compactor.alignBoundary(messages, 2))
        // 边界 1：段末是 User（其后 assistant 属保留段）→ 不动
        assertEquals(1, compactor.alignBoundary(messages, 1))
        // 边界 3：result 之后 → 不动
        assertEquals(3, compactor.alignBoundary(messages, 3))
        // 超尾边界 → 钳制
        assertEquals(4, compactor.alignBoundary(messages, 99))
    }

    @Test
    fun `empty summary gives up compaction`() = runTest {
        val compactor = compactor(ScriptedKitModelClient())
        // 旧段只含 System 注入（滑窗骨架也不产线）→ 两条摘述链都空 → 放弃压缩
        val report = compactor.compactIfNeeded(
            messages = listOf(
                KitMessage.System("sys1"), KitMessage.System("sys2"),
                KitMessage.User("q"), KitMessage.Assistant("a")
            ),
            estimatedTokens = 500_000,
            maxContextTokens = 100_000,
            threshold = 0.8f,
            preserveRecentMessages = 2,
            preserveRecentTokens = 15_000
        )
        assertNull(report)
    }

    // ═══ NUDGE 纠偏 ═══

    @Test
    fun `non-template summary triggers nudge retry`() = runTest {
        // 首次回复不合模板（无 Objective/Work State 标题）→ 追问一次
        val bad = "自由发挥的散文摘要" + "y".repeat(140)
        val good = "## Objective\n- t\n\n## Work State\n### Completed\n- c\n" + "z".repeat(100)
        val responses = ArrayDeque(listOf(bad, good))
        val client = object : KitModelClient {
            override fun chatStream(
                contextTag: String,
                messages: List<KitMessage>,
                tools: List<KitToolDefinition>,
                options: KitRequestOptions
            ) = kotlinx.coroutines.flow.flow { }

            override suspend fun chat(
                contextTag: String,
                messages: List<KitMessage>,
                temperature: Float,
                maxTokens: Int
            ): KitChatResponse = KitChatResponse(content = responses.removeFirst())
        }
        val compactor = KitCompactor(client)
        val report = compactor.compactIfNeeded(
            messages = messagesOf(8),
            estimatedTokens = 500_000,
            maxContextTokens = 100_000,
            threshold = 0.8f,
            preserveRecentMessages = 4,
            preserveRecentTokens = 15_000
        )
        assertNotNull(report)
        // 纠偏后的模板合规摘要胜出（LLM_SUMMARY 策略）
        assertEquals("LLM_SUMMARY", report!!.strategy)
        assertTrue(report.summary.contains("## Objective"))
    }

    // ═══ token 保留窗 ═══

    @Test
    fun `token keep window widens boundary beyond message count`() {
        val compactor = compactor(ScriptedKitModelClient())
        // 小消息 × 6（每条 ~30 token）：token 窗 50 → 从尾累计 2 条就超
        val small = listOf(
            KitMessage.User("q"),
            KitMessage.Assistant("a1"),
            KitMessage.Assistant("a2"),
            KitMessage.Assistant("a3"),
            KitMessage.Assistant("a4"),
            KitMessage.Assistant("a5")
        )
        val byCount = compactor.keepBoundary(small, preserveRecentMessages = 4, preserveRecentTokens = 0)
        assertEquals(2, byCount)
        val byTokens = compactor.keepBoundary(small, preserveRecentMessages = 0, preserveRecentTokens = 120)
        // token 窗 120（约 4 条小消息）→ 边界 2
        assertEquals(2, byTokens)
        // 两者取大：合成为 max(2, 2) = 2
        assertEquals(2, compactor.keepBoundary(small, 4, 120))
    }

    // ═══ 溢出 shrink 阶梯 ═══

    @Test
    fun `overflow compaction shrinks keep window progressively`() = runTest {
        val summary = "## Objective\n- x\n\n## Work State\n### Completed\n- y\n" + "x".repeat(80)
        val client = ScriptedKitModelClient().apply { chatResponse = summary }
        val compactor = compactor(client)
        val messages = messagesOf(12) // 25 条
        val first = compactor.compactForOverflow(messages, attempt = 0, preserveRecentMessages = 8, preserveRecentTokens = 15_000)
        val third = compactor.compactForOverflow(messages, attempt = 2, preserveRecentMessages = 8, preserveRecentTokens = 15_000)
        assertNotNull(first)
        assertNotNull(third)
        // 第 3 次溢出（shrink 0.35）比第 1 次（0.7）压得更狠
        assertTrue(third!!.messagesRemoved > first!!.messagesRemoved)
    }

    @Test
    fun `overflow compaction gives up when nothing compactable`() = runTest {
        val compactor = compactor(ScriptedKitModelClient())
        // 单条消息：保留窗 ≥ 消息数 → 无旧段可压
        val report = compactor.compactForOverflow(
            listOf(KitMessage.User("only")),
            attempt = 0,
            preserveRecentMessages = 8,
            preserveRecentTokens = 15_000
        )
        assertNull(report)
    }

    // ═══ 压缩后继续指令 ═══

    @Test
    fun `continue directive appended at tail after compaction`() = runTest {
        val summary = "## Objective\n- y\n\n## Work State\n### Completed\n- z\n" + "y".repeat(140)
        val client = ScriptedKitModelClient().apply { chatResponse = summary }
        val compactor = compactor(client)
        val messages = messagesOf(8)
        val report = compactor.compactIfNeeded(
            messages = messages,
            estimatedTokens = 500_000,
            maxContextTokens = 100_000,
            threshold = 0.8f,
            preserveRecentMessages = 4,
            preserveRecentTokens = 15_000
        )
        assertNotNull(report)
        assertTrue(report!!.effective)
        val tail = report.replacement.last()
        assertTrue(tail is KitMessage.System)
        assertEquals(KitPrompts.COMPACTION_CONTINUE, (tail as KitMessage.System).content)
        // 指令在最近段之后：倒数第二条仍是原会话消息
        assertTrue(report.replacement[report.replacement.size - 2] is KitMessage.ToolResult)
    }

    // ═══ 增量摘要（priorSummary）═══

    @Test
    fun `prior summary extraction drops header and takes first summary only`() {
        val compactor = compactor(ScriptedKitModelClient())
        // 命中：去头行取正文
        assertEquals(
            "旧摘要正文：已修复登录缺陷",
            compactor.extractPriorSummary(
                listOf(
                    KitMessage.System("[SESSION SUMMARY — earlier context was compacted]\n旧摘要正文：已修复登录缺陷"),
                    KitMessage.User("q")
                )
            )
        )
        // 多条摘要取第一条
        assertEquals(
            "第一条",
            compactor.extractPriorSummary(
                listOf(
                    KitMessage.System("[SESSION SUMMARY]\n第一条"),
                    KitMessage.System("[SESSION SUMMARY]\n第二条")
                )
            )
        )
        // 普通系统消息 / 空会话 / 只剩头行 → null
        assertNull(compactor.extractPriorSummary(listOf(KitMessage.System("sys"), KitMessage.User("q"))))
        assertNull(compactor.extractPriorSummary(emptyList()))
        assertNull(
            compactor.extractPriorSummary(
                listOf(KitMessage.System("[SESSION SUMMARY — earlier context was compacted]"))
            )
        )
    }

    @Test
    fun `prior summary feeds the compaction prompt incrementally`() = runTest {
        val client = ScriptedKitModelClient().apply {
            chatResponse = "## Objective\n- x\n\n## Work State\n### Completed\n- y\n" + "x".repeat(140)
        }
        val compactor = compactor(client)
        val messages = buildList {
            add(
                KitMessage.System(
                    "[SESSION SUMMARY — earlier context was compacted]\n" +
                        "已修复登录缺陷：A.kt / B.kt 已验证"
                )
            )
            addAll(messagesOf(8))
        }
        val report = compactor.compactIfNeeded(
            messages = messages,
            estimatedTokens = 500_000,
            maxContextTokens = 100_000,
            threshold = 0.8f,
            preserveRecentMessages = 4,
            preserveRecentTokens = 15_000
        )
        assertNotNull(report)
        // 摘要请求的 System 提示词携带旧摘要正文（增量合并输入）
        val prompt = client.chatCalls.first().first.first() as KitMessage.System
        assertTrue(prompt.content.contains("已修复登录缺陷：A.kt / B.kt 已验证"))
    }

    @Test
    fun `no prior summary when session has none`() = runTest {
        val client = ScriptedKitModelClient().apply {
            chatResponse = "## Objective\n- z\n\n## Work State\n### Completed\n- y\n" + "z".repeat(140)
        }
        val compactor = compactor(client)
        compactor.compactIfNeeded(
            messages = messagesOf(8),
            estimatedTokens = 500_000,
            maxContextTokens = 100_000,
            threshold = 0.8f,
            preserveRecentMessages = 4,
            preserveRecentTokens = 15_000
        )
        // 首轮压缩：提示词不携带旧摘要段
        val prompt = client.chatCalls.first().first.first() as KitMessage.System
        assertTrue(!prompt.content.contains("<prior-summary>"))
    }

    // ═══ 摘要请求预算 ═══

    @Test
    fun `summary request budget is 1000 tokens`() = runTest {
        val client = ScriptedKitModelClient().apply {
            chatResponse = "## Objective\n- w\n\n## Work State\n### Completed\n- y\n" + "w".repeat(140)
        }
        val compactor = compactor(client)
        compactor.compactIfNeeded(
            messages = messagesOf(8),
            estimatedTokens = 500_000,
            maxContextTokens = 100_000,
            threshold = 0.8f,
            preserveRecentMessages = 4,
            preserveRecentTokens = 15_000
        )
        assertEquals(1000, client.chatCalls.first().second)
    }
}
