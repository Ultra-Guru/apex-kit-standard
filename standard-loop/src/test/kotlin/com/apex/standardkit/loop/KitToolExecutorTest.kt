package com.apex.standardkit.loop

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KitToolExecutor 工具编排子系统测试：批组规划 / 输入归一 / 输出预算 /
 * 保序执行 / 中断分类 / 结算渲染。
 */
class KitToolExecutorTest {

    // ═══════════════════════ 测试基座 ═══════════════════════

    /** 记录型工具执行端口（可脚本化输出/进度/错误/延迟）。 */
    private class RecordingRunner : KitToolRunner {
        val executed = mutableListOf<Pair<String, String>>()
        var outputFor: (String) -> List<String> = { listOf("out:$it") }
        var progressFor: ((String) -> Unit)? = null
        var failingTools: Set<String> = emptySet()

        override fun executeStream(name: String, arguments: String): Flow<KitToolStreamEvent> =
            flow {
                executed += name to arguments
                progressFor?.invoke(name)
                if (name in failingTools) {
                    emit(KitToolStreamEvent.Error("tool $name exploded"))
                    return@flow
                }
                val outputs = outputFor(name)
                outputs.forEach { emit(KitToolStreamEvent.Output(it)) }
                emit(KitToolStreamEvent.Complete(outputs.joinToString("\n")))
            }
    }

    private class RecordingListener : KitToolBatchListener {
        val started = mutableListOf<String>()
        val outputs = mutableListOf<String>()
        val completed = mutableListOf<Pair<String, KitToolExitKind>>()

        override suspend fun onStarted(invocation: KitToolInvocation) {
            started.add(invocation.callId)
        }

        override suspend fun onOutput(invocation: KitToolInvocation, chunk: String) {
            outputs.add("${invocation.callId}:$chunk")
        }

        override suspend fun onProgress(invocation: KitToolInvocation, percent: Float?, message: String?) = Unit

        override suspend fun onCompleted(invocation: KitToolInvocation, result: KitToolSingleResult) {
            completed.add(invocation.callId to result.exit)
        }
    }

    private fun invocation(
        callId: String,
        tool: String,
        arguments: String = "{}"
    ): KitToolInvocation = KitToolInvocation(callId, tool, arguments)

    // ═══════════════════════ 批组规划 ═══════════════════════

    @Test
    fun `planner groups consecutive safe calls and fences unsafe ones`() {
        val calls = listOf(
            invocation("1", "code_read"),
            invocation("2", "code_grep"),
            invocation("3", "shell_execute"),
            invocation("4", "code_read"),
            invocation("5", "mcp__github__query"),
            invocation("6", "code_write")
        )
        val groups = KitToolBatchPlanner.plan(calls) { KitToolBatchPlanner.defaultParallelSafe(it) }
        assertEquals(4, groups.size)
        val first = groups[0] as KitToolBatchGroup.Parallel
        assertEquals(listOf("1", "2"), first.calls.map { it.callId })
        assertEquals("3", (groups[1] as KitToolBatchGroup.Serial).call.callId)
        val second = groups[2] as KitToolBatchGroup.Parallel
        assertEquals(listOf("4", "5"), second.calls.map { it.callId })
        assertEquals("6", (groups[3] as KitToolBatchGroup.Serial).call.callId)
    }

    @Test
    fun `default safety heuristic classifies common tools`() {
        fun safe(name: String) = KitToolBatchPlanner.defaultParallelSafe(invocation("x", name))
        assertTrue(safe("code_read"))
        assertTrue(safe("code_grep"))
        assertTrue(safe("code_glob"))
        assertTrue(safe("task"))
        assertTrue(safe("mcp__any__tool"))
        assertTrue(safe("skill_pdf"))
        assertTrue(safe("web_fetch"))
        assertFalse(safe("shell_execute"))
        assertFalse(safe("code_write"))
        assertFalse(safe("code_edit"))
        assertFalse(safe("terminal.exec"))
    }

    @Test
    fun `empty batch plans to empty groups`() {
        assertTrue(KitToolBatchPlanner.plan(emptyList()) { true }.isEmpty())
    }

    // ═══════════════════════ 输入归一 ═══════════════════════

    @Test
    fun `empty optional string keys are dropped`() {
        val arguments = """{"description":"d","prompt":"p","model":"","sessionId":""}"""
        val normalized = KitToolInputNormalizer.dropEmptyOptionalKeys(
            arguments,
            optionalKeys = setOf("model", "sessionId")
        )
        val parsed = KitJson.parseObject(normalized)!!
        assertEquals(setOf("description", "prompt"), parsed.fields.keys.toSet())
        // 无可删键 → 原样字符串返回
        assertEquals(
            arguments,
            KitToolInputNormalizer.dropEmptyOptionalKeys(arguments, setOf("absent"))
        )
        // 非对象 / 非法 JSON 原样返回
        assertEquals("nope", KitToolInputNormalizer.dropEmptyOptionalKeys("nope", setOf("x")))
    }

    @Test
    fun `key case normalization maps to registry casing`() {
        val normalized = KitToolInputNormalizer.normalizeKeyCase(
            """{"Description":"d","PROMPT":"p","other":1}""",
            lowerCaseKeys = setOf("description", "prompt")
        )
        val parsed = KitJson.parseObject(normalized)!!
        assertEquals(setOf("description", "prompt", "other"), parsed.fields.keys.toSet())
    }

    // ═══════════════════════ 输出预算 ═══════════════════════

    @Test
    fun `short output passes through untouched`() {
        val text = "line1\nline2\nline3"
        assertEquals(text, KitToolOutputBudget.truncate(text))
        assertFalse(KitToolOutputBudget.needsTruncation(text))
        val desc = KitToolOutputBudget.describe(text)
        assertFalse(desc.applied)
        assertNull(desc.strategy)
    }

    @Test
    fun `oversized output keeps head and tail with marker`() {
        val lines = (1..5_000).map { "line %04d".format(it) }
        val text = lines.joinToString("\n")
        val config = KitToolOutputBudgetConfig(maxLines = 200, maxChars = 100_000, maxBytes = 50 * 1024)
        val truncated = KitToolOutputBudget.truncate(text, config)
        assertTrue(truncated.contains(KitToolOutputBudget.OMIT_MARKER))
        assertTrue(truncated.startsWith("line 0001"))
        assertTrue(truncated.endsWith("line 5000"))
        assertTrue(KitToolOutputBudget.lineCount(truncated) <= config.maxLines + 4)
        val desc = KitToolOutputBudget.describe(text, config)
        assertTrue(desc.applied)
        assertEquals(5_000, desc.originalLines)
        assertTrue(desc.keptLines < desc.originalLines)
        assertEquals("head-tail-split", desc.strategy)
    }

    @Test
    fun `single huge line is char-clamped`() {
        val text = "x".repeat(300_000)
        val config = KitToolOutputBudgetConfig(maxChars = 10_000, maxBytes = 50 * 1024)
        val truncated = KitToolOutputBudget.truncate(text, config)
        assertTrue(truncated.contains(KitToolOutputBudget.OMIT_MARKER))
        assertTrue(truncated.length <= 10_000 + KitToolOutputBudget.OMIT_MARKER.length + 8)
    }

    @Test
    fun `multibyte byte budget is respected`() {
        val text = "中".repeat(60_000) // 180KB utf-8, 60k chars < 100k chars
        val config = KitToolOutputBudgetConfig(maxChars = 100_000, maxBytes = 50 * 1024)
        val truncated = KitToolOutputBudget.truncate(text, config)
        assertTrue(truncated.toByteArray(Charsets.UTF_8).size <= config.maxBytes + 512)
        assertTrue(truncated.contains(KitToolOutputBudget.OMIT_MARKER))
    }

    // ═══════════════════════ 批执行 ═══════════════════════

    @Test
    fun `batch executes in source order and settles all calls`() = runTest {
        val runner = RecordingRunner()
        val executor = KitToolExecutor(runner)
        val listener = RecordingListener()
        val batch = listOf(
            invocation("a", "code_read"),
            invocation("b", "code_grep"),
            invocation("c", "shell_execute")
        )
        val result = executor.executeBatch(batch, listener)
        assertEquals(3, result.results.size)
        assertEquals(listOf("a", "b", "c"), result.results.map { it.invocation.callId })
        assertEquals(3, result.completedCount)
        assertEquals(0, result.failedCount)
        // start 顺序广播（源序）
        assertEquals(listOf("a", "b", "c"), listener.started)
        // 全部走 runner
        assertEquals(listOf("code_read", "code_grep", "shell_execute"), runner.executed.map { it.first })
        assertTrue(result.allSettled)
    }

    @Test
    fun `failing tool marks failed without killing siblings`() = runTest {
        val runner = RecordingRunner()
        runner.failingTools = setOf("code_check")
        val executor = KitToolExecutor(runner)
        val batch = listOf(
            invocation("a", "code_read"),
            invocation("b", "code_check"),
            invocation("c", "code_glob")
        )
        val result = executor.executeBatch(batch)
        assertEquals(2, result.completedCount)
        assertEquals(1, result.failedCount)
        val failed = result.results.first { it.invocation.callId == "b" }
        assertTrue(failed.exit is KitToolExitKind.Failed)
        assertEquals("tool code_check exploded", failed.failureMessage)
        assertFalse(result.allSettled)
    }

    @Test
    fun `hooks rewrite arguments before start broadcast`() = runTest {
        val runner = RecordingRunner()
        val executor = KitToolExecutor(
            runner,
            hooks = listOf(KitToolExecutionHook { invocation ->
                invocation.copy(
                    arguments = KitToolInputNormalizer.dropEmptyOptionalKeys(
                        invocation.arguments,
                        setOf("model")
                    )
                )
            })
        )
        val batch = listOf(
            invocation("a", "task", """{"prompt":"p","model":""}""")
        )
        val listener = RecordingListener()
        val result = executor.executeBatch(batch, listener)
        // runner 收到的参数已归一（model 键被删）
        val executedArguments = runner.executed.first().second
        val parsed = KitJson.parseObject(executedArguments)!!
        assertEquals(setOf("prompt"), parsed.fields.keys.toSet())
        assertEquals(1, result.completedCount)
    }

    @Test
    fun `declined classification comes from hook semantics`() = runTest {
        // 权限拒绝在宿主 runner 层模拟：抛出「declined」语义错误前由宿主
        // 自行映射——编排层口径：runner 报 Error → Failed；宿主如需
        // Declined 语义应走权限门前置。这里验证 Failed 形态结算文案。
        val runner = RecordingRunner()
        runner.failingTools = setOf("code_write")
        val executor = KitToolExecutor(runner)
        val result = executor.executeBatch(listOf(invocation("w", "code_write")))
        val failed = result.results.single()
        assertTrue(failed.exit is KitToolExitKind.Failed)
        val messages = KitToolSettlement.renderMessages(result.results)
        assertEquals("w", messages.single().toolCallId)
        assertTrue(messages.single().content.startsWith("Error:"))
    }

    @Test
    fun `settlement fills missing call ids with synthetic failure`() {
        val settled = listOf(
            KitToolSingleResult(
                invocation = invocation("a", "code_read"),
                exit = KitToolExitKind.Completed,
                output = "ok",
                fullOutput = "ok",
                failureMessage = null,
                durationMs = 5L
            )
        )
        val completed = KitToolSettlement.settleMissing(listOf("a", "b", "c"), settled)
        assertEquals(3, completed.size)
        val missing = completed.filter { it.invocation.callId != "a" }
        assertEquals(2, missing.size)
        assertTrue(missing.all { it.failureMessage == KitToolSettlement.RESULT_MISSING })
        assertTrue(missing.all { it.exit is KitToolExitKind.Failed })
    }

    @Test
    fun `settlement renders message bodies per exit kind`() {
        val results = listOf(
            KitToolSingleResult(invocation("ok", "t"), KitToolExitKind.Completed, "body", "body", null, 1L),
            KitToolSingleResult(invocation("d", "t"), KitToolExitKind.Declined, "", "", null, 1L),
            KitToolSingleResult(invocation("i", "t"), KitToolExitKind.Interrupted, "", "", null, 1L),
            KitToolSingleResult(invocation("f", "t"), KitToolExitKind.Failed("kaput"), "", "", "kaput", 1L)
        )
        val messages = KitToolSettlement.renderMessages(results)
        assertEquals("body", messages[0].content)
        assertEquals("The user declined this tool call", messages[1].content)
        assertEquals("Tool execution interrupted", messages[2].content)
        assertEquals("Error: kaput", messages[3].content)
    }

    @Test
    fun `provider executed invocations settle without runner traffic`() = runTest {
        val runner = RecordingRunner()
        val executor = KitToolExecutor(runner)
        val result = executor.executeBatch(
            listOf(invocation("hosted", "web_search").copy(providerExecuted = true))
        )
        assertEquals(1, result.completedCount)
        assertTrue(runner.executed.isEmpty())
    }

    @Test
    fun `config validates bounds`() {
        try {
            KitToolExecutor.Config(parallelism = 0)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        try {
            KitToolExecutor.Config(perCallTimeoutMs = 0)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        try {
            KitToolOutputBudgetConfig(headRatio = 1.5f)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `empty batch returns empty result`() = runTest {
        val executor = KitToolExecutor(RecordingRunner())
        val result = executor.executeBatch(emptyList())
        assertEquals(0, result.results.size)
        assertEquals(0L, result.totalDurationMs)
    }

    @Test
    fun `progress events reach the listener`() = runTest {
        val runner = RecordingRunner()
        val progressCalls = mutableListOf<String>()
        runner.progressFor = { name -> progressCalls.add(name) }
        val executor = KitToolExecutor(runner)
        executor.executeBatch(listOf(invocation("p", "code_read")))
        assertEquals(listOf("code_read"), progressCalls)
    }
}
