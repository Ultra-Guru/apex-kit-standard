package com.apex.standardkit.loop

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * KitMcpBridge MCP 桥接子系统测试：命名 / 工具桥接定义 / 执行管线
 * （权限 / 错误映射 / 内容与输出归一）/ 注册表防抖再平衡 / 资源读取 /
 * 服务器健康熔断。
 */
class KitMcpBridgeTest {

    // ═══════════════════════ 测试基座 ═══════════════════════

    /** 脚本化 MCP 客户端端口。 */
    private class FakeMcpClient(
        var tools: List<KitMcpToolDescriptor> = emptyList(),
        var outcomes: MutableMap<String, KitMcpCallOutcome> = mutableMapOf(),
        var callLog: MutableList<Pair<String, String>> = mutableListOf()
    ) : KitMcpClientPort {

        val changes = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 16)

        fun announceChange() {
            changes.tryEmit(Unit)
        }

        override suspend fun listTools(): List<KitMcpToolDescriptor> = tools

        override suspend fun callTool(
            server: String,
            name: String,
            arguments: KitJson.KitJsonValue
        ): KitMcpCallOutcome {
            callLog += (server to name)
            return outcomes["$server/$name"] ?: KitMcpCallOutcome.Responded(
                KitMcpCallResult(content = listOf(KitMcpContentPart.Text("ok:$name")))
            )
        }

        override fun toolChanges(): Flow<Unit> = changes
    }

    private fun descriptor(
        server: String,
        tool: String,
        outputSchema: String? = null
    ): KitMcpToolDescriptor = KitMcpToolDescriptor(
        server = server,
        name = tool,
        description = "does $tool",
        inputSchema = """{"type":"object","properties":{"q":{"type":"string"}}}""",
        outputSchema = outputSchema
    )

    private fun context(): KitMcpInvocationContext =
        KitMcpInvocationContext("s1", "agent", "m1", "c1")

    // ═══════════════════════ 命名 ═══════════════════════

    @Test
    fun `names sanitize and roundtrip registry ids`() {
        assertEquals("github_ci", KitMcpNames.namespace("github.ci"))
        assertEquals("my_server", KitMcpNames.namespace("my server"))
        assertEquals("srv_tool", KitMcpNames.compositeName("srv", "tool"))
        assertEquals("mcp__github__create_issue", KitMcpNames.registryId("github", "create_issue"))
        val parsed = KitMcpNames.parseRegistryId("mcp__github__create_issue")!!
        assertEquals("github" to "create_issue", parsed)
        assertNull(KitMcpNames.parseRegistryId("code_read"))
        assertNull(KitMcpNames.parseRegistryId("mcp__onlyserver__"))
        assertNull(KitMcpNames.parseRegistryId("mcp____tool"))
    }

    // ═══════════════════════ 定义生成 ═══════════════════════

    @Test
    fun `bridge builds stable tool definitions`() {
        val bridge = KitMcpToolBridge(FakeMcpClient())
        val defs = bridge.definitionsFor(
            listOf(
                descriptor("beta", "query"),
                descriptor("alpha", "run"),
                KitMcpToolDescriptor(server = "alpha", name = "bare", description = "")
            )
        )
        assertEquals(
            listOf("mcp__alpha__bare", "mcp__alpha__run", "mcp__beta__query"),
            defs.map { it.name }
        )
        assertEquals("does run", defs.first { it.name == "mcp__alpha__run" }.description)
        // 无描述 → 兜底文案；无 schema → 空对象
        val bare = defs.first { it.name == "mcp__alpha__bare" }
        assertTrue(bare.description.contains("MCP tool bare from server alpha"))
        assertEquals(KitMcpToolBridge.EMPTY_OBJECT_SCHEMA, bare.parameters)
        assertTrue(defs.first { it.name == "mcp__alpha__run" }.parameters.contains("\"q\""))
    }

    // ═══════════════════════ 执行管线 ═══════════════════════

    @Test
    fun `permission denial fails without calling server`() = runTest {
        val client = FakeMcpClient()
        val bridge = KitMcpToolBridge(
            client,
            permissionGate = KitMcpPermissionGate { _, _ -> false }
        )
        val outcome = bridge.execute(descriptor("github", "run"), "{}", context())
        assertEquals("The user declined this tool call", outcome.failure)
        assertTrue(client.callLog.isEmpty())
    }

    @Test
    fun `server unavailable maps to typed failure`() = runTest {
        val client = FakeMcpClient()
        client.outcomes["ghost/run"] = KitMcpCallOutcome.ServerUnavailable("ghost")
        val bridge = KitMcpToolBridge(client)
        val outcome = bridge.execute(descriptor("ghost", "run"), "{}", context())
        assertEquals("MCP server \"ghost\" is not available", outcome.failure)
    }

    @Test
    fun `tool call failure passes message through`() = runTest {
        val client = FakeMcpClient()
        client.outcomes["srv/run"] = KitMcpCallOutcome.ToolCallFailed("connection reset by peer")
        val outcome = KitMcpToolBridge(client).execute(descriptor("srv", "run"), "{}", context())
        assertEquals("connection reset by peer", outcome.failure)
    }

    @Test
    fun `isError result maps joined text to failure`() = runTest {
        val client = FakeMcpClient()
        client.outcomes["srv/run"] = KitMcpCallOutcome.Responded(
            KitMcpCallResult(
                isError = true,
                content = listOf(KitMcpContentPart.Text("bad input shape"))
            )
        )
        val outcome = KitMcpToolBridge(client).execute(descriptor("srv", "run"), "{}", context())
        assertEquals("bad input shape", outcome.failure)
    }

    @Test
    fun `successful text result normalizes to string output`() = runTest {
        val client = FakeMcpClient()
        client.outcomes["srv/run"] = KitMcpCallOutcome.Responded(
            KitMcpCallResult(
                content = listOf(
                    KitMcpContentPart.Text("first"),
                    KitMcpContentPart.Text("second")
                )
            )
        )
        val outcome = KitMcpToolBridge(client).execute(descriptor("srv", "run"), "{}", context())
        assertNull(outcome.failure)
        assertEquals("first\nsecond", outcome.text)
        assertEquals("first\nsecond", (outcome.output as KitJson.KitJsonValue.Str).value)
        assertEquals(2, outcome.content.size)
    }

    @Test
    fun `structured output wins over text`() = runTest {
        val client = FakeMcpClient()
        val structured = KitJson.parse("""{"rows":3}""")!!
        client.outcomes["srv/run"] = KitMcpCallOutcome.Responded(
            KitMcpCallResult(
                content = listOf(KitMcpContentPart.Text("{\"rows\":3}")),
                structured = structured
            )
        )
        val outcome = KitMcpToolBridge(client).execute(
            descriptor("srv", "run", outputSchema = """{"type":"object"}"""),
            "{}",
            context()
        )
        assertEquals(structured, outcome.output)
    }

    @Test
    fun `text json is parsed when no output schema declared`() = runTest {
        val client = FakeMcpClient()
        client.outcomes["srv/run"] = KitMcpCallOutcome.Responded(
            KitMcpCallResult(content = listOf(KitMcpContentPart.Text("[1,2,3]")))
        )
        val outcome = KitMcpToolBridge(client).execute(descriptor("srv", "run"), "{}", context())
        val parsed = outcome.output as KitJson.KitJsonValue.Arr
        assertEquals(3, parsed.items.size)
    }

    @Test
    fun `text json stays text when output schema declared`() = runTest {
        val client = FakeMcpClient()
        client.outcomes["srv/run"] = KitMcpCallOutcome.Responded(
            KitMcpCallResult(content = listOf(KitMcpContentPart.Text("[1,2,3]")))
        )
        val outcome = KitMcpToolBridge(client).execute(
            descriptor("srv", "run", outputSchema = """{"type":"array"}"""),
            "{}",
            context()
        )
        assertTrue(outcome.output is KitJson.KitJsonValue.Str)
    }

    @Test
    fun `binary parts render as data uris`() = runTest {
        val client = FakeMcpClient()
        client.outcomes["srv/run"] = KitMcpCallOutcome.Responded(
            KitMcpCallResult(
                content = listOf(
                    KitMcpContentPart.Text("chart attached"),
                    KitMcpContentPart.File(mimeType = "image/png", base64Data = "AAAA")
                )
            )
        )
        val outcome = KitMcpToolBridge(client).execute(descriptor("srv", "run"), "{}", context())
        assertEquals(1, outcome.dataUris.size)
        assertEquals("data:image/png;base64,AAAA", outcome.dataUris[0])
    }

    @Test
    fun `invalid arguments json falls back to empty object`() = runTest {
        val client = FakeMcpClient()
        var seenArguments: KitJson.KitJsonValue? = null
        client.outcomes["srv/run"] = KitMcpCallOutcome.Responded(
            KitMcpCallResult(content = listOf(KitMcpContentPart.Text("done")))
        )
        KitMcpToolBridge(client).execute(descriptor("srv", "run"), "not-json{{", context())
        // 空对象兜底不炸——调用正常完成
        assertTrue(client.callLog.isNotEmpty())
    }

    // ═══════════════════════ 注册表防抖 ═══════════════════════

    @Test
    fun `registry reconciles on change with debounce`() = runTest {
        val client = FakeMcpClient(
            tools = listOf(descriptor("alpha", "run"), descriptor("beta", "query"))
        )
        val reloaded = mutableListOf<Int>()
        val registry = KitMcpToolRegistry(
            client = client,
            scope = this,
            reconcileDebounceMs = 50L,
            onReload = { fresh -> reloaded.add(fresh.size) }
        )
        registry.awaitInitial()
        assertEquals(2, registry.discovered.size)
        // 变更风暴：3 次广播在防抖窗内合并（初始 reconcile + 合并后的 1 次）
        client.tools = listOf(descriptor("alpha", "run"))
        client.announceChange()
        client.announceChange()
        client.announceChange()
        kotlinx.coroutines.delay(200L)
        assertEquals(1, registry.discovered.size)
        assertTrue(reloaded.size >= 2)
        assertTrue(reloaded.last() == 1)
    }

    // ═══════════════════════ 资源读取 ═══════════════════════

    private class FakeResourcePort : KitMcpResourcePort {
        val resources = listOf(
            KitMcpResource("docs", "docs://guide", "Guide", "text/markdown", "the guide")
        )

        override suspend fun listResources(server: String): List<KitMcpResource> =
            if (server == "docs") resources else emptyList()

        override suspend fun readResource(server: String, uri: String): KitMcpResourceContent? =
            when (uri) {
                "docs://guide" -> KitMcpResourceContent.Text("# Guide\nread me")
                "docs://logo" -> KitMcpResourceContent.Blob("image/png", "AAAB")
                else -> null
            }
    }

    @Test
    fun `resource reader lists and renders contents`() = runTest {
        val reader = KitMcpResourceReader(FakeResourcePort())
        val listed = reader.list("docs")
        assertEquals(1, listed.size)
        assertEquals("docs://guide", listed[0].uri)
        assertEquals("# Guide\nread me", reader.readAsText("docs", "docs://guide"))
        assertEquals("data:image/png;base64,AAAB", reader.readAsText("docs", "docs://logo"))
        val missing = reader.readAsText("docs", "docs://missing")
        assertTrue(missing.contains("Resource not available"))
    }

    // ═══════════════════════ 健康熔断 ═══════════════════════

    @Test
    fun `circuit trips after consecutive failures and half opens after cooldown`() {
        var now = 1_000L
        val health = KitMcpServerHealth(
            failureThreshold = 3,
            cooldownMs = 30_000L,
            clock = { now }
        )
        health.recordFailure("srv", "e1")
        health.recordFailure("srv", "e2")
        assertFalse(health.isTripped("srv"))
        health.recordFailure("srv", "e3")
        assertTrue(health.isTripped("srv"))
        val snapshot = health.snapshot("srv")
        assertEquals(3, snapshot.consecutiveFailures)
        assertEquals("e3", snapshot.lastError)
        // 冷却窗过 → 半开（不再熔断）
        now += 31_000L
        assertFalse(health.isTripped("srv"))
        // 半开期再失败 → 重新熔断
        health.recordFailure("srv", "e4")
        assertTrue(health.isTripped("srv"))
        // 成功复位
        health.recordSuccess("srv")
        assertFalse(health.isTripped("srv"))
        assertEquals(0, health.snapshot("srv").consecutiveFailures)
    }

    @Test
    fun `bridge fast fails while circuit open`() = runTest {
        val client = FakeMcpClient()
        client.outcomes["srv/run"] = KitMcpCallOutcome.ToolCallFailed("boom")
        val health = KitMcpServerHealth(failureThreshold = 2, cooldownMs = 60_000L)
        val bridge = KitMcpToolBridge(client, health = health)
        // 两次失败 → 熔断
        bridge.execute(descriptor("srv", "run"), "{}", context())
        bridge.execute(descriptor("srv", "run"), "{}", context())
        assertTrue(health.isTripped("srv"))
        val before = client.callLog.size
        val outcome = bridge.execute(descriptor("srv", "run"), "{}", context())
        assertTrue(outcome.failure!!.contains("circuit open"))
        assertEquals(before, client.callLog.size)
    }
}
