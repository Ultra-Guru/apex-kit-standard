package com.apex.standardkit.loop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * # Kit Mcp Bridge — MCP（模型上下文协议）桥接子系统
 *
 * 把 MCP 服务器的能力桥进标准任务循环的工具面，完整对齐业界 agent 的
 * MCP 消费语义：
 *
 * 1. **工具发现**（[KitMcpClientPort.listTools]）：服务器握手后枚举工具
 *    清单（名称 / 描述 / JSON Schema 入参 / 可选出参 schema）；
 * 2. **注册表**（[KitMcpToolRegistry]）：工具清单的防抖再平衡——服务器
 *    会成批广播工具变更、每次读取都拉全量目录，所以**滑动合并 + ~100ms
 *    防抖后统一 reconcile**（不是每个变更事件都触发全量重载）；
 * 3. **工具桥接**（[KitMcpToolBridge]）：MCP 工具 → 任务循环工具定义
 *    （注册表 id 约定 `mcp__{server}__{tool}`，与画像白名单 / 工具名
 *    清洗管线天然兼容）+ 执行管线：
 *    - 权限门（action = 复合名，资源/记忆通配——拒绝即失败返回）；
 *    - 错误映射（服务器不可用 / 工具调用失败 / 工具自报 isError /
 *      未知异常 → 统一 ToolFailure 形态文案）；
 *    - 内容归一（text 部件拼接；二进制部件 → data URI 形态）；
 *    - 输出归一（structured > 文本 JSON 探测解析 > 纯文本——服务器
 *      未声明 outputSchema 时，以 `{`/`[` 起头的文本按约定尝试结构化）；
 * 4. **资源读取**（[KitMcpResourceReader]）：MCP 资源的列举与读取
 *    （文本直出 / 二进制 data URI），模型通过资源工具按 URI 取用；
 * 5. **服务器健康**（[KitMcpServerHealth]）：连续失败计数 + 阈值熔断 +
 *    冷却窗——坏服务器不再每轮拖慢整个工具面发现。
 *
 * 所有 IO 走端口（[KitMcpClientPort] / [KitMcpResourcePort]）——库本体
 * 零协议实现，宿主接入自家 MCP 客户端栈即可。
 */
// ═══════════════════════ 数据模型 ═══════════════════════

/**
 * 一个 MCP 服务器工具的描述（发现清单条目）。
 *
 * @param server 所属服务器名（原始名，可含点号/非 ASCII——注册表 id
 *   与 provider 名清洗由下游管线处理）
 * @param name 工具原始名（服务器侧命名）
 * @param description 工具描述（透传给模型；缺省空串）
 * @param inputSchema 入参 JSON Schema（字符串形态；缺省空对象 schema）
 * @param outputSchema 出参 JSON Schema（字符串形态；null = 未声明——
 *   影响输出归一的「文本 JSON 探测」分支）
 * @param codemode false = 该工具不参与代码模式自动调用（显式调用可达）
 */
data class KitMcpToolDescriptor(
    val server: String,
    val name: String,
    val description: String = "",
    val inputSchema: String? = null,
    val outputSchema: String? = null,
    val codemode: Boolean = true
)

/**
 * MCP 命名工具集（注册表 id 约定 + provider 安全名）。
 *
 * 业界两种口径都提供：
 * - [registryId]：`mcp__{server}__{tool}`（本库工具面 / 画像白名单 /
 *   别名体系的锚点——原始名透传，非法字符由通用清洗管线兜底）；
 * - [namespace] / [compositeName]：`[^a-zA-Z0-9_-]` → `_` 的直接清洗
 *   形态（宿主注册表若要求 provider 安全名可直接采用）。
 */
object KitMcpNames {

    /** 注册表 id 前缀（与工具面 `mcp__` 白名单约定对齐）。 */
    const val REGISTRY_PREFIX = "mcp__"

    /** 服务器名 → 安全命名空间（非法字符折叠为 `_`）。 */
    fun namespace(server: String): String = server.replace(Regex("[^a-zA-Z0-9_-]"), "_")

    /** 工具名 → 安全短名（非法字符折叠为 `_`）。 */
    fun sanitizeToolName(tool: String): String = tool.replace(Regex("[^a-zA-Z0-9_-]"), "_")

    /** 复合 provider 安全名（`namespace_tool`）。 */
    fun compositeName(server: String, tool: String): String =
        "${namespace(server)}_${sanitizeToolName(tool)}"

    /** 注册表 id（`mcp__{server}__{tool}`，原始名透传）。 */
    fun registryId(server: String, tool: String): String = "$REGISTRY_PREFIX${server}__$tool"

    /** 反解注册表 id → (server, tool)（非 MCP 形态 → null）。 */
    fun parseRegistryId(registryId: String): Pair<String, String>? {
        if (!registryId.startsWith(REGISTRY_PREFIX)) return null
        val rest = registryId.removePrefix(REGISTRY_PREFIX)
        val separator = rest.indexOf("__")
        if (separator <= 0 || separator == rest.length - 2) return null
        val server = rest.substring(0, separator)
        val tool = rest.substring(separator + 2)
        if (server.isEmpty() || tool.isEmpty()) return null
        return server to tool
    }
}

// ═══════════════════════ 调用结果模型 ═══════════════════════

/** MCP 内容部件（sealed 两型——文本 / 二进制）。 */
sealed interface KitMcpContentPart {
    /** 文本部件。 */
    data class Text(val text: String) : KitMcpContentPart

    /** 二进制部件（MIME + base64 数据；渲染为 data URI）。 */
    data class File(val mimeType: String, val base64Data: String) : KitMcpContentPart
}

/**
 * MCP 工具调用结果（服务器侧应答）。
 *
 * @param isError 服务器自报失败（true = 业务层失败，文本部件即错误说明）
 * @param content 内容部件清单
 * @param structured 结构化输出（服务器声明 outputSchema 时的直出值；null = 未声明）
 */
data class KitMcpCallResult(
    val isError: Boolean = false,
    val content: List<KitMcpContentPart> = emptyList(),
    val structured: KitJson.KitJsonValue? = null
) {
    /** 全部文本部件拼接（换行连接；空部件集 → 空串）。 */
    val joinedText: String
        get() = content.mapNotNull { (it as? KitMcpContentPart.Text)?.text }.joinToString("\n")
}

/** MCP 调用出口（端口侧不抛异常——失败全部走类型化出口）。 */
sealed interface KitMcpCallOutcome {
    /** 服务器应答（含 isError 业务失败）。 */
    data class Responded(val result: KitMcpCallResult) : KitMcpCallOutcome

    /** 服务器不可用（未连接 / 已断开 / 未注册）。 */
    data class ServerUnavailable(val server: String) : KitMcpCallOutcome

    /** 工具调用失败（协议层错误 / 服务器拒绝）。 */
    data class ToolCallFailed(val message: String) : KitMcpCallOutcome
}

// ═══════════════════════ 端口 ═══════════════════════

/**
 * MCP 客户端端口（宿主接入自家 MCP 会话管理器）。
 *
 * 契约：
 * - [listTools] 全量枚举（各服务器工具扁平合并；连接失败的服务器
 *   静默跳过——单服务器故障不拖垮整个发现面）；
 * - [callTool] 单次调用（协议错误走 [KitMcpCallOutcome] 类型化出口，
 *   **不抛异常**）；
 * - [toolChanges] 工具变更通知流（服务器上线 / 下线 / 工具清单变化时
 *   发射；无变更语义的实现返回空流即可）。
 */
interface KitMcpClientPort {

    suspend fun listTools(): List<KitMcpToolDescriptor>

    suspend fun callTool(
        server: String,
        name: String,
        arguments: KitJson.KitJsonValue
    ): KitMcpCallOutcome

    fun toolChanges(): Flow<Unit>
}

/**
 * MCP 资源端口（资源列举与读取；宿主按需实现——无资源语义的服务器
 * 返回空清单 / null）。
 */
interface KitMcpResourcePort {

    suspend fun listResources(server: String): List<KitMcpResource>

    suspend fun readResource(server: String, uri: String): KitMcpResourceContent?
}

/** 一条 MCP 资源描述。 */
data class KitMcpResource(
    val server: String,
    val uri: String,
    val name: String,
    val mimeType: String? = null,
    val description: String? = null
)

/** MCP 资源内容（sealed 两型）。 */
sealed interface KitMcpResourceContent {
    /** 文本资源。 */
    data class Text(val text: String) : KitMcpResourceContent

    /** 二进制资源（渲染为 data URI）。 */
    data class Blob(val mimeType: String, val base64Data: String) : KitMcpResourceContent
}

// ═══════════════════════ 权限门 ═══════════════════════

/** MCP 调用上下文（权限门与进度回调的寻址信息）。 */
data class KitMcpInvocationContext(
    val sessionId: String,
    val agentId: String,
    val messageId: String,
    val callId: String
)

/**
 * MCP 工具权限门端口。
 *
 * 业界口径：MCP 工具按复合名整体裁决（action = `namespace_tool`，
 * 资源与记忆通配 `*`）——具体规则引擎由宿主注入（本库的权限引擎
 * 亦可适配此形态）。
 */
fun interface KitMcpPermissionGate {
    /** @return true = 放行；false = 拒绝（拒绝即调用失败收尾）。 */
    suspend fun permit(context: KitMcpInvocationContext, action: String): Boolean

    companion object {
        /** 全放行门（测试 / 无权限体系宿主）。 */
        val AllowAll: KitMcpPermissionGate = KitMcpPermissionGate { _, _ -> true }
    }
}

// ═══════════════════════ 工具桥接 ═══════════════════════

/**
 * MCP 工具执行出口（桥接层的统一结果形态）。
 *
 * @param failure 非null = 失败（ToolFailure 语义文案；null = 成功）
 * @param output 归一化输出（structured > 文本 JSON 解析 > 文本字符串；
 *   空内容 → null）
 * @param text 全部文本部件拼接（展示层直用）
 * @param content 完整内容部件清单（多模态宿主渲染用）
 * @param dataUris 二进制部件的 data URI 渲染清单
 */
data class KitMcpExecutionOutcome(
    val failure: String?,
    val output: KitJson.KitJsonValue?,
    val text: String,
    val content: List<KitMcpContentPart>,
    val dataUris: List<String>
)

/**
 * MCP 工具 ↔ 任务循环工具面桥接器。
 *
 * 职责：
 * 1. 发现清单 → [KitToolDefinition]（注册表 id `mcp__{server}__{tool}`，
 *    入参 schema 缺省补空对象，出参 schema 不进定义——只影响输出归一）；
 * 2. 执行管线：权限门 → 端口调用 → 错误映射 → 内容/输出归一。
 */
class KitMcpToolBridge(
    private val client: KitMcpClientPort,
    private val permissionGate: KitMcpPermissionGate = KitMcpPermissionGate.AllowAll,
    private val health: KitMcpServerHealth = KitMcpServerHealth(),
    private val logger: KitLogger = KitLogger.Silent
) {

    // ═══════════════════ 定义生成 ═══════════════════

    /**
     * 发现清单 → 工具定义清单（顺序稳定：server 字典序 → 工具名字典序）。
     */
    fun definitionsFor(descriptors: List<KitMcpToolDescriptor>): List<KitToolDefinition> =
        descriptors
            .sortedWith(compareBy({ it.server }, { it.name }))
            .map { descriptor ->
                KitToolDefinition(
                    name = KitMcpNames.registryId(descriptor.server, descriptor.name),
                    description = descriptor.description.ifEmpty {
                        "MCP tool ${descriptor.name} from server ${descriptor.server}"
                    },
                    parameters = descriptor.inputSchema ?: EMPTY_OBJECT_SCHEMA
                )
            }

    /** 缺省入参 schema（空对象——无 schema 声明的服务器工具用）。 */
    companion object {
        const val EMPTY_OBJECT_SCHEMA = """{"type":"object","properties":{}}"""

        private const val TAG = "KitMcpToolBridge"
    }

    // ═══════════════════ 执行管线 ═══════════════════

    /**
     * 执行一次 MCP 工具调用（完整管线）。
     *
     * @param descriptor 目标工具描述
     * @param argumentsJson 模型输出的参数 JSON 字符串（非法 JSON → 空对象兜底）
     * @param context 调用上下文（权限门寻址）
     * @return 统一出口（失败在 [KitMcpExecutionOutcome.failure] 里，不抛异常）
     */
    suspend fun execute(
        descriptor: KitMcpToolDescriptor,
        argumentsJson: String,
        context: KitMcpInvocationContext
    ): KitMcpExecutionOutcome {
        val action = KitMcpNames.compositeName(descriptor.server, descriptor.name)
        if (!permissionGate.permit(context, action)) {
            return failureOutcome("The user declined this tool call", descriptor)
        }
        // 熔断快失败：连续失败达到阈值且冷却窗未过 → 不再拖慢链路
        if (health.isTripped(descriptor.server)) {
            return failureOutcome(
                "MCP server \"${descriptor.server}\" is temporarily unavailable (circuit open)",
                descriptor
            )
        }
        val arguments = KitJson.parse(argumentsJson) ?: KitJson.obj()
        val outcome = client.callTool(descriptor.server, descriptor.name, arguments)
        return when (outcome) {
            is KitMcpCallOutcome.ServerUnavailable -> {
                health.recordFailure(descriptor.server, "server unavailable")
                failureOutcome(
                    "MCP server \"${outcome.server}\" is not available",
                    descriptor
                )
            }
            is KitMcpCallOutcome.ToolCallFailed -> {
                health.recordFailure(descriptor.server, outcome.message)
                failureOutcome(outcome.message, descriptor)
            }
            is KitMcpCallOutcome.Responded -> {
                val result = outcome.result
                health.recordSuccess(descriptor.server)
                if (result.isError) {
                    val message = result.joinedText.trim()
                        .ifEmpty { "MCP tool returned an error" }
                    failureOutcome(message, descriptor)
                } else {
                    normalize(result, descriptor)
                }
            }
        }
    }

    /** 成功结果的内容/输出归一。 */
    internal fun normalize(
        result: KitMcpCallResult,
        descriptor: KitMcpToolDescriptor
    ): KitMcpExecutionOutcome {
        val text = result.joinedText
        val dataUris = result.content.mapNotNull { part ->
            (part as? KitMcpContentPart.File)?.let { file ->
                "data:${file.mimeType};base64,${file.base64Data}"
            }
        }
        val output = normalizeOutput(result, descriptor, text)
        return KitMcpExecutionOutcome(
            failure = null,
            output = output,
            text = text,
            content = result.content,
            dataUris = dataUris
        )
    }

    /**
     * 输出归一三级阶梯：
     * 1. 服务器直出 structured（声明 outputSchema 的应答体）；
     * 2. 文本为空 → null；
     * 3. 服务器未声明 outputSchema 且文本疑似 JSON（`{`/`[` 起头）→
     *    尝试解析（模型约定：文本形态的 JSON 视作对象回传）；
     * 4. 其余 → 文本字符串值。
     */
    internal fun normalizeOutput(
        result: KitMcpCallResult,
        descriptor: KitMcpToolDescriptor,
        text: String
    ): KitJson.KitJsonValue? {
        if (result.structured != null) return result.structured
        if (text.isEmpty()) return null
        if (descriptor.outputSchema == null && KitJson.looksStructured(text)) {
            return KitJson.parse(text)
        }
        return KitJson.KitJsonValue.Str(text)
    }

    /** 失败出口（空内容形态）。 */
    private fun failureOutcome(
        message: String,
        descriptor: KitMcpToolDescriptor
    ): KitMcpExecutionOutcome {
        logger.warn(TAG, "mcp tool failed: ${descriptor.server}/${descriptor.name} — $message")
        return KitMcpExecutionOutcome(
            failure = message,
            output = null,
            text = "",
            content = emptyList(),
            dataUris = emptyList()
        )
    }
}

// ═══════════════════════ 工具清单注册表 ═══════════════════════

/**
 * MCP 工具清单注册表（防抖再平衡）。
 *
 * 语义（对齐业界 MCP 工具注册）：
 * - 构造后在宿主 [scope] 里启动再平衡循环：首个 tick（立即）→ 100ms
 *   防抖 → 全量 reconcile——即「初始发现完成后注册一次」；
 * - 之后每次 [KitMcpClientPort.toolChanges] 发射只是抬 tick 计数，
 *   真正的 reconcile 在防抖窗内合并（服务器成批广播不触发风暴）；
 * - reconcile 持互斥锁全量刷新发现清单并回调 [onReload]
 *   （宿主在回调里重建工具面）；
 * - [awaitInitial] 供需要「初始注册完成」屏障的调用方挂起等待。
 */
class KitMcpToolRegistry(
    private val client: KitMcpClientPort,
    private val scope: CoroutineScope,
    private val logger: KitLogger = KitLogger.Silent,
    private val reconcileDebounceMs: Long = 100L,
    private val onReload: suspend (List<KitMcpToolDescriptor>) -> Unit = {}
) {

    private val mutex = Mutex()
    private val changeTicks = MutableStateFlow(0L)
    private val initialReconciled = CompletableDeferred<Unit>()

    /** 当前发现清单快照（未 reconcile 过 → 空表）。 */
    val discovered: List<KitMcpToolDescriptor>
        get() = discoveredSnapshot

    @Volatile
    private var discoveredSnapshot: List<KitMcpToolDescriptor> = emptyList()

    init {
        scope.launch {
            client.toolChanges().collect { changeTicks.value = changeTicks.value + 1L }
        }
        scope.launch {
            changeTicks.collectLatest {
                delay(reconcileDebounceMs)
                runCatching { reconcile() }
                    .onFailure { error ->
                        logger.error(TAG, "reconcile failed", error)
                    }
            }
        }
    }

    /** 手动触发一次全量再平衡（防抖循环之外的管理入口）。 */
    suspend fun reconcile(): List<KitMcpToolDescriptor> = mutex.withLock {
        val fresh = client.listTools()
        discoveredSnapshot = fresh
        if (!initialReconciled.isCompleted) initialReconciled.complete(Unit)
        onReload(fresh)
        fresh
    }

    /** 挂起等待初始注册完成（再平衡循环至少跑完一轮）。 */
    suspend fun awaitInitial() {
        initialReconciled.await()
    }

    private companion object {
        const val TAG = "KitMcpToolRegistry"
    }
}

// ═══════════════════════ 资源读取 ═══════════════════════

/**
 * MCP 资源读取器（列举 + 按 URI 读取 + 统一渲染）。
 *
 * 渲染口径：文本资源原样；二进制资源 → data URI 单行（模型可直接
 * 引用，宿主可直接内联展示）。
 */
class KitMcpResourceReader(
    private val port: KitMcpResourcePort,
    private val logger: KitLogger = KitLogger.Silent
) {

    /** 列举一个服务器的全部资源（服务器不可用 → 空表 + warn）。 */
    suspend fun list(server: String): List<KitMcpResource> =
        runCatching { port.listResources(server) }
            .getOrElse { error ->
                logger.warn(TAG, "list resources failed for $server: ${error.message}")
                emptyList()
            }

    /**
     * 按 URI 读取资源并渲染为模型可消费文本。
     *
     * @return 渲染文本（文本资源原样；二进制资源 → data URI；
     *   读取失败 / 不存在 → 失败说明文本——模型需要看见原因）
     */
    suspend fun readAsText(server: String, uri: String): String {
        val content = runCatching { port.readResource(server, uri) }.getOrNull()
            ?: return "Resource not available: $uri (server: $server)"
        return when (content) {
            is KitMcpResourceContent.Text -> content.text
            is KitMcpResourceContent.Blob ->
                "data:${content.mimeType};base64,${content.base64Data}"
        }
    }

    private companion object {
        const val TAG = "KitMcpResourceReader"
    }
}

// ═══════════════════════ 服务器健康 ═══════════════════════

/** 单服务器健康快照。 */
data class KitMcpServerHealthSnapshot(
    val server: String,
    val consecutiveFailures: Int,
    val lastError: String?,
    val lastFailureAtMs: Long?,
    val tripped: Boolean
)

/**
 * MCP 服务器健康跟踪（连续失败计数 + 阈值熔断 + 冷却窗）。
 *
 * 动机：一个握手卡死的服务器会在每次工具发现/调用时拖慢整条链路。
 * 业界客户端普遍做**半开放电闸**：连续失败达到阈值 → 熔断（调用前
 * 快速失败）；冷却窗过后放行一次试探（成功即复位）。
 *
 * 本类线程安全、零协程依赖（时间源注入——[clock] 毫秒）。
 */
class KitMcpServerHealth(
    private val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    private data class State(
        var consecutiveFailures: Int = 0,
        var lastError: String? = null,
        var lastFailureAtMs: Long? = null
    )

    private val states = java.util.concurrent.ConcurrentHashMap<String, State>()

    /** 记录一次成功（复位计数；熔断态自动解除）。 */
    fun recordSuccess(server: String) {
        states.remove(server)
    }

    /** 记录一次失败（计数 +1；达到阈值进入熔断）。 */
    fun recordFailure(server: String, error: String?) {
        val state = states.compute(server) { _, existing ->
            existing ?: State()
        } ?: return
        synchronized(state) {
            state.consecutiveFailures += 1
            state.lastError = error
            state.lastFailureAtMs = clock()
        }
    }

    /** 服务器当前是否处于熔断态（冷却窗过后自动半开）。 */
    fun isTripped(server: String): Boolean {
        val state = states[server] ?: return false
        synchronized(state) {
            if (state.consecutiveFailures < failureThreshold) return false
            val lastFailure = state.lastFailureAtMs ?: return false
            val elapsed = clock() - lastFailure
            // 冷却窗已过 → 半开（不再快速失败；下一次成败决定复位或再熔断）
            return elapsed < cooldownMs
        }
    }

    /** 健康快照。 */
    fun snapshot(server: String): KitMcpServerHealthSnapshot {
        val state = states[server]
        if (state == null) {
            return KitMcpServerHealthSnapshot(
                server = server,
                consecutiveFailures = 0,
                lastError = null,
                lastFailureAtMs = null,
                tripped = false
            )
        }
        synchronized(state) {
            return KitMcpServerHealthSnapshot(
                server = server,
                consecutiveFailures = state.consecutiveFailures,
                lastError = state.lastError,
                lastFailureAtMs = state.lastFailureAtMs,
                tripped = isTripped(server)
            )
        }
    }

    companion object {
        /** 默认熔断阈值（连续失败次数）。 */
        const val DEFAULT_FAILURE_THRESHOLD = 3

        /** 默认冷却窗（毫秒）。 */
        const val DEFAULT_COOLDOWN_MS = 30_000L
    }
}
