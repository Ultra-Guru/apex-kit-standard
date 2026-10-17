package com.apex.agentkit.loop

import kotlinx.coroutines.flow.Flow

/**
 * # Kit Ports — 宿主接入端口（SPI）
 *
 * 库内核只依赖这些接口；宿主实现它们（通常每个都是十行以内的薄适配），
 * 即可把任务循环接进自己的模型路由 / 工具注册表 / 日志 / 持久化体系。
 *
 * | 端口 | 职责 | 必选 |
 * |---|---|---|
 * | [KitModelClient] | 流式/非流式 LLM 请求 | ✅ |
 * | [KitToolSource] | 工具定义清单 | ✅ |
 * | [KitToolRunner] | 工具执行流 | ✅ |
 * | [KitLogger] | 结构化日志 | ✅（可用 [KitLogger.Silent]） |
 * | [KitLlmErrorClassifier] | LLM 失败分类（瞬时/拒绝/溢出） | ✅（可用 [KitLlmErrorClassifier.Default]） |
 * | [KitMemory] | 会话持久化 | ❌（null = 纯内存会话） |
 * | [KitTextKernel] | token 估算加速核 | ❌ |
 * | [KitPermissionSource] | 权限配置快照 | ❌ |
 * | [KitModelInfoSource] | 模型 id / 真实上下文窗口 | ❌ |
 * | [KitEnvironmentInfoSource] | 平台/工作目录/git 探测 | ❌ |
 * | [KitWorkspaceContextSource] | 工作区/规则等宿主提示词段 | ❌ |
 * | [KitMcpInstructionSource] | MCP 服务器使用指引 | ❌ |
 * | [KitSubAgentBudgetSource] | 子代理预算快照 | ❌ |
 */

// ═══════════════════════ 模型客户端 ═══════════════════════

/**
 * LLM 请求端口。
 *
 * 实现约定：
 * - 流内失败按原始异常抛出（分类交给 [KitLlmErrorClassifier]）；
 * - [options.toolChoice] = NONE 时**保留 tools 定义**但请求体带
 *   tool_choice=none（预算收官轮语义——prompt cache 前缀不破坏）；
 *   不支持该参数的端点可退化为忽略（模型在收官提示词约束下自然收敛）；
 * - [options.reasoningEffort] 不支持的端点直接忽略。
 */
interface KitModelClient {
    fun chatStream(
        contextTag: String,
        messages: List<KitMessage>,
        tools: List<KitToolDefinition>,
        options: KitRequestOptions
    ): Flow<KitStreamChunk>

    suspend fun chat(
        contextTag: String,
        messages: List<KitMessage>,
        temperature: Float,
        maxTokens: Int
    ): KitChatResponse
}

// ═══════════════════════ 工具面 ═══════════════════════

/** 工具定义清单端口（注册表快照；引擎每回合重建工具面时读取）。 */
interface KitToolSource {
    fun definitions(): List<KitToolDefinition>
}

/** 工具执行端口（未知名 / 执行失败在引擎侧统一兜底）。 */
interface KitToolRunner {
    fun executeStream(name: String, arguments: String): Flow<KitToolStreamEvent>
}

// ═══════════════════════ 基础设施 ═══════════════════════

/** 结构化日志端口。 */
interface KitLogger {
    fun info(tag: String, message: String)
    fun warn(tag: String, message: String)
    fun error(tag: String, message: String, throwable: Throwable? = null)

    companion object {
        /** 静默实现（测试 / 不关心日志的宿主）。 */
        val Silent: KitLogger = object : KitLogger {
            override fun info(tag: String, message: String) = Unit
            override fun warn(tag: String, message: String) = Unit
            override fun error(tag: String, message: String, throwable: Throwable?) = Unit
        }
    }
}

// ═══════════════════════ LLM 失败分类 ═══════════════════════

/** LLM 失败分类（容错阶梯 / 降级 / 溢出恢复的路由依据）。 */
enum class KitLlmErrorKind {
    /** 瞬时失败（限流/超时/断连/5xx/响应无效）——阶梯延迟重试。 */
    TRANSIENT,

    /** 请求级拒绝（400/404/配置/鉴权）——重发同一请求体无意义，降级续跑。 */
    REQUEST_REJECTION,

    /** 上下文溢出（provider 明示 too long / context length）——压缩后重试。 */
    CONTEXT_OVERFLOW,

    /** 未分类——诚实上抛。 */
    FATAL
}

/**
 * LLM 失败分类器端口。
 *
 * 宿主把自家模型运行时异常体系映射到 [KitLlmErrorKind]；
 * [Default] 提供基于异常类型的通用启发式（宿主无专业分类器时的兜底）。
 */
fun interface KitLlmErrorClassifier {
    fun classify(t: Throwable): KitLlmErrorKind

    companion object {
        /**
         * 通用分类：消息文本嗅探（溢出特征词）→ IOException（瞬时）→ 其余
         * 4xx/配置类按类型名启发（宿主专用异常类型应优先由宿主分类）。
         */
        val Default: KitLlmErrorClassifier = KitLlmErrorClassifier { t ->
            val text = (t.message ?: "").lowercase()
            when {
                OVERFLOW_MARKERS.any { text.contains(it) } -> KitLlmErrorKind.CONTEXT_OVERFLOW
                t is java.io.IOException -> KitLlmErrorKind.TRANSIENT
                text.contains("timeout") || text.contains("timed out") -> KitLlmErrorKind.TRANSIENT
                text.contains("rate limit") || text.contains("429") -> KitLlmErrorKind.TRANSIENT
                text.contains("401") || text.contains("403") || text.contains("404") ||
                    text.contains("api key") || text.contains("unauthorized") ->
                    KitLlmErrorKind.REQUEST_REJECTION
                else -> KitLlmErrorKind.FATAL
            }
        }

        private val OVERFLOW_MARKERS = listOf(
            "context length", "context window", "maximum context",
            "too long", "prompt is too long", "input tokens exceed",
            "exceeds the", "token limit", "context_length_exceeded",
            "maximum number of tokens"
        )
    }
}

// ═══════════════════════ 会话持久化 ═══════════════════════

/**
 * 会话记忆端口（引擎 append 写入 + 恢复读取 + 工作区绑定）。
 *
 * 实现只需把这五个方法落到自家持久化（文件/DB）；null 注入 = 纯内存
 * 会话（子代理/测试）。
 */
interface KitMemory {
    fun append(message: KitMessage)
    suspend fun save(messages: List<KitMessage>)
    fun load(): List<KitMessage>
    fun clear()
    fun count(): Int
    fun bindWorkspace(workspaceId: String)
}

// ═══════════════════════ 文本加速核 ═══════════════════════

/**
 * token 估算加速核（可选 SPI——native JNI 实现注入点）。
 *
 * 契约：无副作用、线程安全、任何异常自吞（估算失败返回 0 = 未知，
 * 引擎走纯 Kotlin 估算）。
 */
interface KitTextKernel {
    fun estimateTokens(text: String): Int
}

// ═══════════════════════ 配置快照源 ═══════════════════════

/** 权限配置快照（每轮任务开始拉取——改设置即时生效）。 */
interface KitPermissionSource {
    fun snapshot(): Snapshot

    data class Snapshot(
        val mode: KitPermissionMode = KitPermissionMode.DEFAULT,
        val rules: List<KitPermissionRule> = emptyList(),
        val commandRules: List<KitPermissionRule> = emptyList()
    )

    companion object {
        val NONE: KitPermissionSource = object : KitPermissionSource {
            override fun snapshot() = Snapshot()
        }
    }
}

/** 模型信息（env 块注入 + 上下文预算）。 */
data class KitModelInfo(
    val modelId: String?,
    val contextWindow: Int?
)

/** 模型信息源（解析失败 → null = 静态配置兜底）。 */
fun interface KitModelInfoSource {
    fun modelInfo(): KitModelInfo?
}

/** 环境信息（env 块的平台/目录/git 行——宿主探测）。 */
data class KitEnvironmentInfo(
    val platformText: String,
    val workdirLabel: String?,
    val isGitRepo: Boolean?
)

fun interface KitEnvironmentInfoSource {
    fun environmentInfo(): KitEnvironmentInfo?
}

// ═══════════════════════ 系统提示词宿主段 ═══════════════════════

/**
 * 宿主提示词段提供者（JIT——系统提示词每回合重建时调用）。
 *
 * 库负责渲染：画像身份 / 方法论 / 工具面 / 环境块 / 人设 / 附加段 /
 * MCP 指引 / 压缩摘要。**工作区上下文与规则体系**（AGENTS.md、
 * Global Rules、目录结构摘要等）是宿主域知识——宿主在这返回拼好的
 * 段落文本（null = 该段省略）。
 */
interface KitWorkspaceContextSource {
    /** 工作区上下文段（目录结构摘要 / 活跃文件 / 环境说明等）。 */
    fun workspaceSection(): String?

    /** 全局规则段（用户在设置里写的规则，宿主格式化）。 */
    fun globalRulesSection(): String?

    /** 项目规则段（AGENTS.md / .cursorrules 等仓库级规则，宿主发现与格式化）。 */
    fun projectRulesSection(): String?
}

/** 工作区信息（引擎的 read-guard 路径解析 + 子代理简报用）。 */
data class KitWorkspaceInfo(
    val workspaceId: String,
    val name: String,
    /** 宿主侧工作区根绝对路径（guest 挂载语义由宿主工具口径处理）。 */
    val rootPath: String?,
    val activeFilePath: String? = null
)

// ═══════════════════════ MCP 指引源 ═══════════════════════

/**
 * MCP 服务器使用指引（握手 initialize 响应的 instructions 字段）。
 *
 * @param serverName 服务器名（展示）
 * @param instructions 服务器自述的使用指引原文
 * @param toolIdPrefix 该服务器工具的注册表 id 前缀（如 `mcp__github__`）
 *   ——引擎据此过滤：只有当前工具面里真的存在该前缀工具，指引才注入
 *   （防幻觉：模型看到指引却调不到工具）。
 */
data class KitMcpServerInstructions(
    val serverName: String,
    val instructions: String,
    val toolIdPrefix: String
)

/** MCP 指引源（宿主从 MCP 会话管理器读取；无连接服务器 = 空表）。 */
fun interface KitMcpInstructionSource {
    fun serverInstructions(): List<KitMcpServerInstructions>
}

// ═══════════════════════ 子代理预算源 ═══════════════════════

/** 子代理预算快照（设置层实时值）。 */
data class KitSubAgentBudget(
    val enabled: Boolean = true,
    val maxConcurrent: Int = 3,
    val timeoutMs: Long = 240_000L,
    /** 子代理结论长度上限（字符）。 */
    val outputLimit: Int = 4_000,
    /** 子代理工具输出预算（字符）。 */
    val toolOutputLimit: Int = 8_000
)

fun interface KitSubAgentBudgetSource {
    fun budget(): KitSubAgentBudget
}
