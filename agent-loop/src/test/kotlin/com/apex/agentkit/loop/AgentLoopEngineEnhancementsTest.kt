package com.apex.agentkit.loop

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AgentLoopEngine 增强语义测试（库版本的核心增量）：
 *
 * - **并行工具执行**：连续只读调用并行（并发上限内重叠执行）；读写调用
 *   与读调用之间保序（不并行破坏隐式依赖）；
 * - **输出截断续跑**：finishReason=length → 注入续跑指令继续循环（≤2 次）；
 * - **溢出压缩恢复**：上下文溢出错误 → shrink 压缩 → 同轮重试；
 * - **预算收官轮**：toolChoice=NONE + 工具定义保留；
 * - **运行中转向（steering）**：入队指令在回合边界注入会话；
 * - **MCP 指引注入**：服务器 instructions 只在工具面命中时注入；
 * - **工具输出头尾截断**：超预算时保留头尾。
 */
class AgentLoopEngineEnhancementsTest {

    // ═══ 并行工具执行 ═══

    @Test
    fun `consecutive readonly calls execute in parallel`() = runTest {
        val llm = ScriptedKitModelClient()
        // 一回合内 3 个只读调用（并行安全组）
        llm.enqueue(
            KitStreamChunk(
                toolCalls = listOf(
                    KitToolCall(id = "r1", name = "code_read", arguments = """{"path":"a"}"""),
                    KitToolCall(id = "r2", name = "code_read", arguments = """{"path":"b"}"""),
                    KitToolCall(id = "r3", name = "code_grep", arguments = """{"pattern":"x"}""")
                )
            )
        )
        llm.enqueue(KitStreamChunk(content = "并行读完成", isFinish = true))

        val executor = object : KitToolRunner {
            val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
            var maxInFlight = 0

            override fun executeStream(name: String, arguments: String) =
                kotlinx.coroutines.flow.flow {
                    val cur = inFlight.incrementAndGet()
                    if (cur > maxInFlight) maxInFlight = cur
                    delay(100) // 虚拟时间：并行组内重叠
                    emit(KitToolStreamEvent.Output("out:$name"))
                    emit(KitToolStreamEvent.Complete("out:$name"))
                    inFlight.decrementAndGet()
                }
        }
        val engine = testEngine(llm, executor, tools = listOf("code_read", "code_grep"))
        val events = engine.execute(KitUserInput.text("q")).toList()

        assertEquals(3, events.filterIsInstance<KitEvent.ToolCallComplete>().size)
        assertTrue("并行组内应出现重叠执行（maxInFlight>1）", executor.maxInFlight > 1)
    }

    @Test
    fun `write between reads breaks the parallel group`() = runTest {
        val llm = ScriptedKitModelClient()
        // read → write → read：write 前必须冲刷并行组（保序），write 后 read 单独执行
        llm.enqueue(
            KitStreamChunk(
                toolCalls = listOf(
                    KitToolCall(id = "r1", name = "code_read", arguments = """{"path":"a"}"""),
                    KitToolCall(id = "w1", name = "code_write", arguments = """{"path":"a","content":"x"}""")
                )
            )
        )
        llm.enqueue(KitStreamChunk(content = "ok", isFinish = true))

        val executor = FakeKitToolRunner()
        val engine = testEngine(
            llm, executor,
            tools = listOf("code_read", "code_write"),
            permissionSource = object : KitPermissionSource {
                override fun snapshot() = KitPermissionSource.Snapshot(
                    mode = KitPermissionMode.BYPASS
                )
            }
        )
        val events = engine.execute(KitUserInput.text("q")).toList()

        // 两个调用都执行（BYPASS 下 write 放行）
        assertEquals(2, executor.executed.size)
        assertEquals(2, events.filterIsInstance<KitEvent.ToolCallComplete>().size)
        // ToolResult 按原顺序追加（会话协议确定性）
        val results = llm.streamRequests[1].first.filterIsInstance<KitMessage.ToolResult>()
        assertEquals(listOf("r1", "w1"), results.map { it.toolCallId })
    }

    // ═══ 输出截断续跑 ═══

    @Test
    fun `output limit continuation injects directive and continues`() = runTest {
        val llm = ScriptedKitModelClient()
        // 第 1 轮：finish=length（截断）；第 2 轮：续跑后正常收官
        llm.enqueue(KitStreamChunk(content = "这是前半段", isFinish = true, finishReason = "length"))
        llm.enqueue(KitStreamChunk(content = "，续上后半段", isFinish = true, finishReason = "stop"))

        val engine = testEngine(llm, FakeKitToolRunner())
        val events = engine.execute(KitUserInput.text("q")).toList()

        // 续跑通知可见
        val notices = events.filterIsInstance<KitEvent.LlmRetryScheduled>()
        assertEquals(1, notices.size)
        assertTrue(notices.single().reason.contains("长度上限"))
        // 最终结论 = 续跑轮的输出
        val response = events.filterIsInstance<KitEvent.ResponseComplete>().single()
        assertEquals("，续上后半段", response.fullText)
        // 第 2 轮请求含续跑指令（System 消息）
        val second = llm.streamRequests[1].first
        assertTrue(second.any { it is KitMessage.System && it.content.contains("output token limit") })
        // 部分回复保留进会话
        assertTrue(second.any { it is KitMessage.Assistant && it.content == "这是前半段" })
    }

    @Test
    fun `output limit continuation is capped and settles with partial`() = runTest {
        val llm = ScriptedKitModelClient()
        // 3 轮全部 length（超过 2 次续跑预算）→ 第 3 次按截断文本收尾
        repeat(3) {
            llm.enqueue(KitStreamChunk(content = "段$it", isFinish = true, finishReason = "length"))
        }

        val engine = testEngine(llm, FakeKitToolRunner())
        val events = engine.execute(KitUserInput.text("q")).toList()

        // 恰好 2 次续跑（3 次请求）
        assertEquals(3, llm.streamRequests.size)
        val response = events.filterIsInstance<KitEvent.ResponseComplete>().single()
        assertEquals("段2", response.fullText)
    }

    // ═══ 溢出压缩恢复 ═══

    @Test
    fun `context overflow triggers compaction and retries the turn`() = runTest {
        val llm = ScriptedKitModelClient()
        // 第 1 轮：普通工具轮（产出大工具结果——为压缩备料）
        llm.enqueue(
            KitStreamChunk(
                toolCalls = listOf(
                    KitToolCall(id = "r1", name = "code_read", arguments = """{"path":"a"}""")
                )
            )
        )
        // 第 2 轮：溢出拒绝；压缩 chat 摘要（模板合规）；第 3 次请求（同轮重试）：成功
        llm.enqueueFailure(IllegalStateException("This model supports maximum context length of 8192 tokens"))
        llm.chatResponse = (
            "## Objective\n- 继续任务\n\n## Requirements\n- (none)\n\n## Decisions\n- (none)\n" +
                "## Work State\n### Completed\n- 已读文件 a\n### Active\n- 改造中\n### Blocked\n- (none)\n" +
                "## Next Move\n1. 继续\n\n## Relevant Files\n- a\n\n## Important Context\n- (none)\n"
            ) + "x".repeat(80)
        llm.enqueue(KitStreamChunk(content = "压缩后继续完成", isFinish = true))

        val executor = FakeKitToolRunner()
        executor.handler = { _, _ -> "R".repeat(2_000) } // 大工具结果（可压缩旧段）

        val classifier = KitLlmErrorClassifier { t ->
            val msg = (t.message ?: "").lowercase()
            if (msg.contains("context length") || msg.contains("maximum context")) {
                KitLlmErrorKind.CONTEXT_OVERFLOW
            } else KitLlmErrorClassifier.Default.classify(t)
        }
        val engine = AgentLoopEngine(
            modelClient = llm,
            toolSource = object : KitToolSource {
                override fun definitions() = fakeToolDefs("code_read")
            },
            toolRunner = executor,
            errorClassifier = classifier,
            // 收紧保留窗：短会话也能压出旧段
            config = KitEngineConfig(
                maxContextTokens = 100_000,
                compressionThreshold = 0.95f,
                preserveRecentMessages = 2,
                preserveRecentTokens = 50
            )
        )
        val events = engine.execute(KitUserInput.text("q")).toList()

        // 溢出 → 压缩请求（chat 摘要）→ 同轮重试成功
        assertEquals(3, llm.streamRequests.size)
        assertTrue(llm.chatCalls.isNotEmpty())
        // 压缩事件可见（策略含 OVERFLOW_SHRINK 标注）
        val compressed = events.filterIsInstance<KitEvent.ContextCompressed>()
        assertTrue(compressed.isNotEmpty())
        assertTrue(compressed.single().strategy.contains("OVERFLOW_SHRINK"))
        // 续跑通知可见
        val notices = events.filterIsInstance<KitEvent.LlmRetryScheduled>()
        assertTrue(notices.any { it.reason.contains("溢出") })
        // 任务完成而非死亡
        val response = events.filterIsInstance<KitEvent.ResponseComplete>().single()
        assertEquals("压缩后继续完成", response.fullText)
    }

    // ═══ 预算收官轮 ═══

    @Test
    fun `budget exhaustion settlement uses toolChoice none with definitions kept`() = runTest {
        val llm = ScriptedKitModelClient()
        // BUILD 画像 32 回合预算：每轮都调用工具，永不收官 → 预算耗尽走收官轮
        repeat(32) {
            llm.enqueue(
                KitStreamChunk(
                    toolCalls = listOf(
                        KitToolCall(id = "t$it", name = "code_read", arguments = """{"path":"f$it"}""")
                    )
                )
            )
        }
        // 收官轮：纯文本总结
        llm.enqueue(KitStreamChunk(content = "预算耗尽总结：已读 32 个文件", isFinish = true))

        val engine = testEngine(
            llm, FakeKitToolRunner(),
            tools = listOf("code_read")
        )
        val events = engine.execute(KitUserInput.text("q")).toList()

        // 收官轮请求：工具定义保留 + toolChoice=NONE
        val settlementRequest = llm.streamRequests.last()
        assertTrue("收官轮保留工具定义（cache 前缀不破坏）", settlementRequest.second.isNotEmpty())
        assertEquals(
            KitToolChoice.NONE,
            llm.streamOptions.last().toolChoice
        )
        // MAX_STEPS 提示注入
        val lastMessages = settlementRequest.first
        assertTrue(lastMessages.any { it is KitMessage.System && it.content.contains("MAXIMUM STEPS REACHED") })
        // 收官文本
        val response = events.filterIsInstance<KitEvent.ResponseComplete>().single()
        assertEquals("预算耗尽总结：已读 32 个文件", response.fullText)
    }

    // ═══ 运行中转向（steering）═══

    @Test
    fun `steer queue injects user message at turn boundary`() = runTest {
        val llm = ScriptedKitModelClient()
        // 第 1 轮：工具调用；第 2 轮（转向注入后）：收官
        llm.enqueue(
            KitStreamChunk(
                toolCalls = listOf(
                    KitToolCall(id = "r1", name = "code_read", arguments = """{"path":"a"}""")
                )
            )
        )
        llm.enqueue(KitStreamChunk(content = "已按补充指令完成", isFinish = true))

        // 慢工具执行器：执行期 delay(100)（虚拟时间）——制造确定性入队窗口
        val slowRunner = object : KitToolRunner {
            override fun executeStream(name: String, arguments: String) =
                kotlinx.coroutines.flow.flow {
                    emit(KitToolStreamEvent.Output("out:$name"))
                    delay(100)
                    emit(KitToolStreamEvent.Complete("out:$name"))
                }
        }
        val engine = testEngine(llm, slowRunner, tools = listOf("code_read"))

        val collected = mutableListOf<KitEvent>()
        val job = async {
            engine.execute(KitUserInput.text("开始")).collect { collected += it }
            true
        }
        // 工具已开始执行（ToolCallStart 已见、执行仍在 delay 窗口内）→ 入队转向
        while (collected.none { it is KitEvent.ToolCallStart }) delay(10)
        delay(10) // 仍在执行窗口内（100 >> 10+10）
        assertTrue(engine.pushSteer("顺便也检查一下 b 文件"))
        job.await()

        // 转向受理事件
        val steer = collected.filterIsInstance<KitEvent.SteeringAccepted>().single()
        assertEquals("顺便也检查一下 b 文件", steer.text)
        // 第 2 轮请求含转向注入的用户消息
        val second = llm.streamRequests[1].first
        assertTrue(second.any { it is KitMessage.User && it.content == "顺便也检查一下 b 文件" })
        // 未运行时入队被拒
        assertFalse(engine.pushSteer("late"))
    }

    // ═══ MCP 指引注入 ═══

    @Test
    fun `mcp instructions injected only when server tools are on the surface`() = runTest {
        val llm = ScriptedKitModelClient()
        llm.enqueue(KitStreamChunk(content = "ok", isFinish = true))

        val engine = testEngine(
            llm, FakeKitToolRunner(),
            tools = listOf("code_read", "mcp__github__create_issue"),
            mcpInstructionSource = {
                listOf(
                    KitMcpServerInstructions(
                        serverName = "github",
                        instructions = "Use create_issue with full issue template.",
                        toolIdPrefix = "mcp__github__"
                    ),
                    KitMcpServerInstructions(
                        serverName = "weather",
                        instructions = "Weather server guidance.",
                        toolIdPrefix = "mcp__weather__"
                    )
                )
            }
        )
        engine.execute(KitUserInput.text("q")).toList()

        val system = llm.streamRequests.single().first.first() as KitMessage.System
        // 在面的服务器指令注入
        assertTrue(system.content.contains("<mcp_instructions>"))
        assertTrue(system.content.contains("github"))
        assertTrue(system.content.contains("Use create_issue with full issue template."))
        // 不在面的服务器指令不注入（防幻觉指引）
        assertFalse(system.content.contains("Weather server guidance"))
        // 工具指引段提及 MCP 命名约定
        assertTrue(system.content.contains("mcp__<server>__<tool>"))
    }

    @Test
    fun `mcp instructions omitted without mcp tools on surface`() = runTest {
        val llm = ScriptedKitModelClient()
        llm.enqueue(KitStreamChunk(content = "ok", isFinish = true))

        val engine = testEngine(
            llm, FakeKitToolRunner(),
            tools = listOf("code_read"),
            mcpInstructionSource = {
                listOf(
                    KitMcpServerInstructions("github", "gh guidance", "mcp__github__")
                )
            }
        )
        engine.execute(KitUserInput.text("q")).toList()

        val system = llm.streamRequests.single().first.first() as KitMessage.System
        assertFalse(system.content.contains("<mcp_instructions>"))
    }

    // ═══ 工具输出头尾截断 ═══

    @Test
    fun `tool output truncation keeps head and tail`() = runTest {
        val llm = ScriptedKitModelClient()
        llm.enqueue(
            KitStreamChunk(
                toolCalls = listOf(
                    KitToolCall(id = "r1", name = "code_read", arguments = """{"path":"a"}""")
                )
            )
        )
        llm.enqueue(KitStreamChunk(content = "done", isFinish = true))

        val executor = FakeKitToolRunner()
        val head = "H".repeat(6_000)
        val tail = "T".repeat(6_000)
        executor.handler = { _, _ -> head + tail } // 12k chars > 8k 预算

        val engine = testEngine(llm, executor, tools = listOf("code_read"))
        val events = engine.execute(KitUserInput.text("q")).toList()

        val complete = events.filterIsInstance<KitEvent.ToolCallComplete>().single()
        // 头尾保留 + 中缝截断说明
        assertTrue(complete.output.startsWith("HHHH"))
        assertTrue(complete.output.endsWith("TTTT"))
        assertTrue(complete.output.contains("output truncated"))
        assertTrue("截断后显著小于原文", complete.output.length < head.length + tail.length)
    }

    // ═══ 会话附加段与工作区段（宿主提示词段端口）═══

    @Test
    fun `workspace context sections render into system prompt`() = runTest {
        val llm = ScriptedKitModelClient()
        llm.enqueue(KitStreamChunk(content = "ok", isFinish = true))

        val engine = testEngine(
            llm, FakeKitToolRunner(),
            tools = listOf("code_read"),
            workspaceContextSource = object : KitWorkspaceContextSource {
                override fun workspaceSection() = "## Workspace\n- root: /demo (10 files)"
                override fun globalRulesSection() = "## Global Rules\n- Use Kotlin idioms"
                override fun projectRulesSection() = "## Project Rules\n- Follow AGENTS.md"
            }
        )
        engine.execute(KitUserInput.text("q")).toList()

        val system = llm.streamRequests.single().first.first() as KitMessage.System
        assertTrue(system.content.contains("## Workspace"))
        assertTrue(system.content.contains("## Global Rules"))
        assertTrue(system.content.contains("## Project Rules"))
    }
}
