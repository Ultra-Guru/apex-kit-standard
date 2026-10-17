package com.apex.agentkit.loop

import java.io.IOException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AgentLoopEngine LLM 请求容错测试。
 *
 * 覆盖流式请求块的四条容错路径：
 * 1. 瞬时失败 → 阶梯重试（2s/5s/10s/…；ladderExhausted 即停诚实上抛）；
 * 2. 请求级拒绝 → **一次**去工具纯文本降级续跑；降级再败才上抛；
 * 3. 阶梯穷尽 → 诚实 Error（任务收口不悬挂）；
 * 4. 已流出部分输出的失败 → 中断信号（引擎断点续跑，防重复 chunk）。
 *
 * 全部经注入 retrySleeper 记录器驱动（无真实 sleep，runTest 虚拟时钟）。
 */
class AgentLoopEngineResilienceTest {

    private fun unavailable(message: String): Exception =
        IOException(message)

    private fun engine(
        llm: ScriptedKitModelClient,
        executor: FakeKitToolRunner,
        delays: MutableList<Long>,
        tools: List<String> = listOf("code_read", "code_edit", "code_write")
    ): AgentLoopEngine = testEngine(
        modelClient = llm,
        toolRunner = executor,
        tools = tools,
        retrySleeper = { ms -> delays.add(ms) }
    )

    // ═══ 1. 瞬时失败：阶梯重试后成功 ═══

    @Test
    fun `transient failures are ladder-retried then task completes`() = runTest {
        val llm = ScriptedKitModelClient()
        llm.enqueueFailure(unavailable("5xx blip"))
        llm.enqueueFailure(unavailable("conn refused"))
        llm.enqueue(KitStreamChunk(content = "恢复完成", isFinish = true))

        val delays = mutableListOf<Long>()
        val engine = engine(llm, FakeKitToolRunner(), delays)
        val events = engine.execute(KitUserInput.text("q")).toList()

        // 两次失败 → 2s / 5s 两次阶梯等待 → 第 3 次请求成功
        assertEquals(listOf(2_000L, 5_000L), delays)
        assertEquals(3, llm.streamRequests.size)
        // 重试重发完整工具面（同请求体）
        assertTrue(llm.streamRequests.all { it.second.isNotEmpty() })

        // UI 可见性：每档重试一条调度通知
        val retries = events.filterIsInstance<KitEvent.LlmRetryScheduled>()
        assertEquals(2, retries.size)
        assertEquals(1, retries[0].attempt)
        assertEquals(2_000L, retries[0].delayMs)
        assertEquals(2, retries[1].attempt)
        assertEquals(5_000L, retries[1].delayMs)

        // 任务正常收官：重试不消耗回合预算（1 回合）
        val response = events.filterIsInstance<KitEvent.ResponseComplete>().single()
        assertEquals("恢复完成", response.fullText)
        assertEquals(1, events.filterIsInstance<KitEvent.Complete>().single().totalIterations)
        assertTrue(events.none { it is KitEvent.Error })
    }

    @Test
    fun `io exception is treated as transient and retried`() = runTest {
        val llm = ScriptedKitModelClient()
        llm.enqueueFailure(IOException("connection reset"))
        llm.enqueue(KitStreamChunk(content = "ok", isFinish = true))

        val delays = mutableListOf<Long>()
        val engine = engine(llm, FakeKitToolRunner(), delays)
        val events = engine.execute(KitUserInput.text("q")).toList()

        assertEquals(listOf(2_000L), delays)
        assertEquals(2, llm.streamRequests.size)
        assertTrue(events.filterIsInstance<KitEvent.ResponseComplete>().isNotEmpty())
    }

    // ═══ 2. 请求级拒绝：一次去工具降级续跑 ═══

    @Test
    fun `request rejection degrades once to text-only and completes`() = runTest {
        val llm = ScriptedKitModelClient()
        llm.enqueueFailure(IllegalStateException("请求被拒绝 (400): Invalid 'tools[14].function.name'"))
        llm.enqueue(KitStreamChunk(content = "已用纯文本方式继续完成", isFinish = true))

        val delays = mutableListOf<Long>()
        // 自定义分类器：把 IllegalStateException 400 类消息归为请求级拒绝
        val classifier = KitLlmErrorClassifier { t ->
            val msg = t.message ?: ""
            when {
                msg.contains("400") || msg.contains("请求被拒绝") -> KitLlmErrorKind.REQUEST_REJECTION
                else -> KitLlmErrorClassifier.Default.classify(t)
            }
        }
        val executor = FakeKitToolRunner()
        val engine = AgentLoopEngine(
            modelClient = llm,
            toolSource = object : KitToolSource {
                override fun definitions() = fakeToolDefs("code_read", "code_edit", "code_write")
            },
            toolRunner = executor,
            retrySleeper = { ms -> delays.add(ms) },
            errorClassifier = classifier
        )
        val events = engine.execute(KitUserInput.text("q")).toList()

        // 两次请求：第一次带工具被拒 → 降级重发 tools=空 → 成功收尾
        assertEquals(2, llm.streamRequests.size)
        assertTrue("首次请求携带工具面", llm.streamRequests[0].second.isNotEmpty())
        assertTrue("降级重发为纯文本（无工具）", llm.streamRequests[1].second.isEmpty())

        // 降级不是重试：零延迟
        assertTrue(delays.isEmpty())
        val notices = events.filterIsInstance<KitEvent.LlmRetryScheduled>()
        assertEquals(1, notices.size)
        assertEquals(0L, notices.single().delayMs)
        assertTrue(notices.single().reason.contains("降级"))

        // 消息列表不动（降级不污染会话：两次请求 messages 一致）
        assertEquals(llm.streamRequests[0].first, llm.streamRequests[1].first)

        // 任务继续收尾而不是死掉
        val response = events.filterIsInstance<KitEvent.ResponseComplete>().single()
        assertEquals("已用纯文本方式继续完成", response.fullText)
        assertTrue(events.none { it is KitEvent.Error })
    }

    @Test
    fun `degraded retry failing again surfaces honest error`() = runTest {
        val llm = ScriptedKitModelClient()
        llm.enqueueFailure(IllegalStateException("请求被拒绝 (400)"))
        llm.enqueueFailure(IllegalStateException("请求被拒绝 (400)"))

        val delays = mutableListOf<Long>()
        val classifier = KitLlmErrorClassifier { t ->
            val msg = t.message ?: ""
            when {
                msg.contains("400") || msg.contains("请求被拒绝") -> KitLlmErrorKind.REQUEST_REJECTION
                else -> KitLlmErrorClassifier.Default.classify(t)
            }
        }
        val engine = AgentLoopEngine(
            modelClient = llm,
            toolSource = object : KitToolSource {
                override fun definitions() = fakeToolDefs("code_read")
            },
            toolRunner = FakeKitToolRunner(),
            retrySleeper = { ms -> delays.add(ms) },
            errorClassifier = classifier
        )
        val events = engine.execute(KitUserInput.text("q")).toList()

        // 降级（tools=空）也失败 → 诚实上抛（Error 事件收口，不再重试）
        assertEquals(2, llm.streamRequests.size)
        assertTrue(llm.streamRequests[1].second.isEmpty())
        assertTrue(delays.isEmpty())
        val error = events.filterIsInstance<KitEvent.Error>().single()
        assertTrue(error.message.contains("400"))
        assertTrue(events.none { it is KitEvent.ResponseComplete })
    }

    // ═══ 3. 阶梯穷尽：诚实错误（任务收口不悬挂） ═══

    @Test
    fun `persistent unavailability exhausts ladder and surfaces honest error`() = runTest {
        val llm = ScriptedKitModelClient()
        // 7 次请求（初始 1 + 阶梯 6）：末级 ladderExhausted(6) 为真即停
        repeat(7) { llm.enqueueFailure(unavailable("down #$it")) }

        val delays = mutableListOf<Long>()
        val engine = engine(llm, FakeKitToolRunner(), delays)
        val events = engine.execute(KitUserInput.text("q")).toList()

        // 2s/5s/10s/20s/40s/80s 六档耗尽 → 第 7 次失败后不再等待、诚实上抛
        assertEquals(listOf(2_000L, 5_000L, 10_000L, 20_000L, 40_000L, 80_000L), delays)
        assertEquals(7, llm.streamRequests.size)
        assertEquals(6, events.filterIsInstance<KitEvent.LlmRetryScheduled>().size)
        assertTrue(events.filterIsInstance<KitEvent.Error>().isNotEmpty())
        // 收口完整（Error 后 Complete 仍发，任务不悬挂）
        assertTrue(events.filterIsInstance<KitEvent.Complete>().isNotEmpty())
    }

    // ═══ 4. 部分输出：中断续跑（新语义）═══

    @Test
    fun `partial output failure continues from breakpoint instead of settling`() = runTest {
        val llm = ScriptedKitModelClient()
        // 第 1 轮：吐一半断流；第 2 轮（续跑）：从断点继续到收官
        llm.enqueuePartialThenFailure(KitStreamChunk(content = "已经输出了一半"))
        llm.enqueue(KitStreamChunk(content = "，这是剩余的一半", isFinish = true))

        val delays = mutableListOf<Long>()
        val engine = engine(llm, FakeKitToolRunner(), delays)
        val events = engine.execute(KitUserInput.text("q")).toList()

        // 不重放整轮（防重复 chunk）：零阶梯等待；两次请求 = 原轮 + 续跑轮
        assertTrue(delays.isEmpty())
        assertEquals(2, llm.streamRequests.size)
        // 中断通知可见（状态行）
        val notices = events.filterIsInstance<KitEvent.LlmRetryScheduled>()
        assertEquals(1, notices.size)
        assertTrue(notices.single().reason.contains("断点继续"))
        // 最终结论 = 断点续跑后的完整回复
        val response = events.filterIsInstance<KitEvent.ResponseComplete>().single()
        assertEquals("，这是剩余的一半", response.fullText)
        // 会话里保留了部分输出（第 2 轮请求含部分 assistant 消息）
        val secondRequest = llm.streamRequests[1].first
        assertTrue(secondRequest.any { it is KitMessage.Assistant && it.content == "已经输出了一半" })
    }

    @Test
    fun `repeated interruptions eventually settle with honest partial result`() = runTest {
        val llm = ScriptedKitModelClient()
        // 连续中断超过预算（默认 3 次）→ 第 4 次中断按部分结果诚实收尾
        repeat(4) { llm.enqueuePartialThenFailure(KitStreamChunk(content = "段$it")) }

        val delays = mutableListOf<Long>()
        val engine = engine(llm, FakeKitToolRunner(), delays)
        val events = engine.execute(KitUserInput.text("q")).toList()

        assertEquals(4, llm.streamRequests.size)
        val response = events.filterIsInstance<KitEvent.ResponseComplete>().single()
        assertTrue(response.fullText.contains("段3"))
        assertTrue(response.fullText.contains("中断"))
    }

    // ═══ 5. 端到端清洗路由 ═══

    @Test
    fun `dotted and unicode names are sanitized in request and routed back`() = runTest {
        val llm = ScriptedKitModelClient()
        // 模型按 tools 数组里的 provider 名调用（"mcp" = 中文 MCP 工具的清洗名）
        llm.enqueue(
            KitStreamChunk(toolCalls = listOf(KitToolCall(id = "c1", name = "mcp", arguments = "{}")))
        )
        llm.enqueue(KitStreamChunk(content = "查到了天气", isFinish = true))

        val delays = mutableListOf<Long>()
        val executor = FakeKitToolRunner()
        val engine = engine(
            llm, executor, delays,
            tools = listOf("terminal.exec", "mcp__天气服务__查天气", "code_read")
        )
        val events = engine.execute(KitUserInput.text("q")).toList()

        // 请求体：点号/中文名 → provider 合法名（严格端点不再 400）
        val requestToolNames = llm.streamRequests.first().second.map { it.name }
        val providerLegal = Regex("^[a-zA-Z0-9_-]{1,64}$")
        requestToolNames.forEach {
            assertTrue("request tool name not provider-safe: '$it'", providerLegal.matches(it))
        }
        assertTrue(requestToolNames.contains("terminal_exec"))
        assertTrue(requestToolNames.contains("mcp"))
        assertTrue(requestToolNames.none { it.contains('.') })
        assertTrue(requestToolNames.none { name -> name.any { it.code > 127 } })

        // 执行路由：回显名 "mcp" → 注册表 id（事件与执行同口径）
        val start = events.filterIsInstance<KitEvent.ToolCallStart>().single()
        assertEquals("mcp__天气服务__查天气", start.toolName)
        assertEquals(1, executor.executed.count { it.first == "mcp__天气服务__查天气" })
        val complete = events.filterIsInstance<KitEvent.ToolCallComplete>().single()
        assertTrue(complete.success)
        assertTrue(events.none { it is KitEvent.Error })
    }

    // ═══ 6. 阶梯表语义（纯常量断言）═══

    @Test
    fun `ladder schedule matches user-spec table`() {
        assertEquals(2_000L, KitLlmResilience.ladderDelayMs(0))
        assertEquals(5_000L, KitLlmResilience.ladderDelayMs(1))
        assertEquals(160_000L, KitLlmResilience.ladderDelayMs(6))
        assertEquals(180_000L, KitLlmResilience.ladderDelayMs(99))
        assertTrue(!KitLlmResilience.ladderExhausted(0))
        assertTrue(!KitLlmResilience.ladderExhausted(5))
        assertTrue(KitLlmResilience.ladderExhausted(6))
        assertTrue(KitLlmResilience.ladderExhausted(7))
    }
}
