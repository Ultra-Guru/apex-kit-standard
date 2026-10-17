package com.apex.standardkit.loop

/**
 * # Kit Contracts — 库协议类型层
 *
 * 本库（com.apex.standardkit:standard-loop）自带一套**完整的任务循环协议类型**：
 * 消息、事件、工具调用、计划、用量等。库不依赖任何宿主类型——宿主通过
 * 适配层（端口见 [KitPorts]）把自己的类型映射进来。
 *
 * 这样设计的原因：
 * 1. 库可以独立演进（提示词、循环语义、压缩策略），宿主只做薄适配；
 * 2. 协议类型按本库语义裁剪（只保留任务循环真正消费的字段），宿主的
 *    富类型（多模态输出、视频帧等）不会渗透进循环内核；
 * 3. 任何新宿主（CLI / 服务端 / 其它移动端）实现端口即可接入。
 */
// ═══════════════════════ 消息模型 ═══════════════════════

/**
 * 任务循环消息（四型——System / User / Assistant / ToolResult）。
 *
 * 与业界标准 chat 消息协议同构；[Assistant] 携带工具调用列表，
 * [ToolResult] 以 toolCallId 关联回 assistant 的某个调用。
 */
sealed interface KitMessage {
    data class System(val content: String) : KitMessage

    data class User(
        val content: String,
        val images: List<KitImageContent> = emptyList()
    ) : KitMessage

    data class Assistant(
        val content: String,
        val toolCalls: List<KitToolCall> = emptyList()
    ) : KitMessage

    data class ToolResult(
        val toolCallId: String,
        val content: String
    ) : KitMessage
}

/** 多模态输入图片（base64 内联或远程 URL，二选一）。 */
data class KitImageContent(
    val base64Data: String,
    val mimeType: String = "image/jpeg",
    /** 远程图片直链（https://...）；非空时请求体直接传 URL，忽略 base64。 */
    val url: String? = null
)

/** 用户附件（仅元数据——引擎把它渲染成用户消息的附件清单行）。 */
data class KitAttachment(
    val name: String,
    val mimeType: String,
    val sizeBytes: Long
)

/** 一次执行的用户输入（文本 + 图片 + 附件元数据）。 */
data class KitUserInput(
    val text: String,
    val images: List<KitImageContent> = emptyList(),
    val files: List<KitAttachment> = emptyList()
) {
    companion object {
        fun text(text: String) = KitUserInput(text = text)
    }
}

// ═══════════════════════ 工具模型 ═══════════════════════

/**
 * 工具调用（流式分片累加后的一次完整调用）。
 *
 * [index] 是流式响应里并行工具调用的分片键（OpenAI `tool_calls[].index`）：
 * 首片携带 id+index，续片只携带 index——非流式响应恒为 -1。
 */
data class KitToolCall(
    val id: String,
    val name: String,
    /** 参数 JSON 字符串。 */
    val arguments: String,
    val index: Int = -1
)

/** 发给模型的工具定义（name 合法 `^[a-zA-Z0-9_-]{1,64}$`；parameters 为 JSON Schema 字符串）。 */
data class KitToolDefinition(
    val name: String,
    val description: String,
    /** JSON Schema 字符串。 */
    val parameters: String
)

// ═══════════════════════ 流式/用量模型 ═══════════════════════

/** 用量统计（服务端真值帧）。 */
data class KitUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)

/**
 * 流式响应分片。
 *
 * [finishReason] 透传 provider 的收尾原因（`stop` / `tool_calls` / `length` /
 * `content_filter` …）——引擎据此识别**输出截断**（`length`）并触发续跑，
 * 而不是把半截回答当结论收尾。
 */
data class KitStreamChunk(
    val content: String? = null,
    val toolCalls: List<KitToolCall> = emptyList(),
    val reasoningContent: String? = null,
    val usage: KitUsage? = null,
    val isFinish: Boolean = false,
    val finishReason: String? = null
)

/** 非流式响应（摘要压缩等辅助请求用）。 */
data class KitChatResponse(
    val content: String?,
    val usage: KitUsage? = null
)

// ═══════════════════════ 事件模型 ═══════════════════════

/**
 * 任务循环事件协议（宿主适配层 1:1 映射到自己的 UI 事件）。
 *
 * 语义与粒度对齐业界标准 Agent 事件流：迭代边界 / 思考与正文流 / 工具
 * 生命周期 / 权限问答 / 压缩通知 / 计划人控门 / 容错调度可见性 / 终态。
 */
sealed interface KitEvent {

    /** 回合开始（第 [iteration] 轮，1-based）。 */
    data class IterationStart(val iteration: Int) : KitEvent

    /** 思考链增量（原生 reasoning 流）。 */
    data class ThinkingChunk(val text: String) : KitEvent

    /** 正文增量。 */
    data class ResponseChunk(val text: String) : KitEvent

    /** 本轮正文完成（无工具调用的结论文本）。 */
    data class ResponseComplete(val fullText: String) : KitEvent

    /** 服务端真实用量帧到达。 */
    data class UsageUpdated(
        val promptTokens: Int,
        val completionTokens: Int,
        val totalTokens: Int
    ) : KitEvent

    /** 工具调用受理（进入执行管线——含并行队列中的调用）。 */
    data class ToolCallStart(
        val callId: String,
        val toolName: String,
        val arguments: String
    ) : KitEvent

    /** 工具输出增量（callId 区分并行调用）。 */
    data class ToolOutputChunk(val callId: String, val chunk: String) : KitEvent

    /** 工具进度（percent 0..1，null = 无明确完成度）。 */
    data class ToolProgress(
        val callId: String,
        val percent: Float?,
        val message: String?
    ) : KitEvent

    /** 工具调用完成（含截断后的展示输出与完整输出）。 */
    data class ToolCallComplete(
        val callId: String,
        val toolName: String,
        val arguments: String,
        val output: String,
        val fullOutput: String,
        val success: Boolean,
        val durationMs: Long
    ) : KitEvent

    /** 需要用户输入/确认（权限门 ASK / 引擎问答）。 */
    data class UserInputRequired(
        val prompt: String,
        val type: KitInputType = KitInputType.CONFIRMATION
    ) : KitEvent

    /** 用户输入等待超时（引擎已按超时语义继续）。 */
    data object UserInputExpired : KitEvent

    /** 上下文压缩完成。 */
    data class ContextCompressed(
        val beforeTokens: Int,
        val afterTokens: Int,
        val strategy: String,
        val summary: String,
        val messagesRemoved: Int,
        val messagesTruncated: Int = 0
    ) : KitEvent

    /** 规划产出（PLAN 档人控门）。 */
    data class PlanGenerated(val plan: KitPlan) : KitEvent

    /** 规划待确认。 */
    data class PlanAwaitingConfirmation(val plan: KitPlan) : KitEvent

    /** 规划已确认（即将切执行画像）。 */
    data class PlanConfirmed(val plan: KitPlan) : KitEvent

    /** 运行中转向指令已入队（steering——下个回合边界注入会话）。 */
    data class SteeringAccepted(val text: String) : KitEvent

    /** LLM 容错调度通知（重试/降级/续跑——过程可见性，非终态）。 */
    data class LlmRetryScheduled(
        val attempt: Int,
        val delayMs: Long,
        val reason: String
    ) : KitEvent

    /** 错误（任务收口）。 */
    data class Error(
        val message: String,
        val recoverable: Boolean = true
    ) : KitEvent

    /** 全部完成（运行报告摘要）。 */
    data class Complete(
        val summary: String,
        val totalIterations: Int,
        val totalToolCalls: Int,
        val totalDurationMs: Long
    ) : KitEvent

    /** 被中止。 */
    data object Aborted : KitEvent
}

/** 用户输入请求类型（确认 / 自由文本 / 多选一）。 */
enum class KitInputType { CONFIRMATION, TEXT, CHOICE }

// ═══════════════════════ 计划模型 ═══════════════════════

/** 执行计划（规划师画像产物，人控门确认后交构建者执行）。 */
data class KitPlan(
    val goal: String,
    val steps: List<KitPlanStep>,
    val estimatedToolCalls: Int,
    val riskLevel: KitRiskLevel,
    val reasoning: String
)

data class KitPlanStep(
    val index: Int,
    val description: String,
    val toolName: String? = null,
    val estimatedArgs: String? = null,
    val dependsOn: List<Int> = emptyList()
)

enum class KitRiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

// ═══════════════════════ 思考档位 ═══════════════════════

/**
 * 思考档位（七档 + AUTO 自适应）。
 *
 * 标准任务循环的消化方式（与自研阶梯引擎不同——档位在这里是**预算与
 * 采样偏好**的输入）：
 * 1. 回合预算倍率（低档少跑、高档多跑，见 AgentLoopEngine.effectiveMaxTurns）；
 * 2. 推理努力映射（[KitReasoningEffort]）——随请求下发给支持 reasoning
 *    effort 的模型（不支持时适配层忽略）。
 */
enum class KitThinkingLevel {
    NONE, LIGHT, STANDARD, DEEP, MAXIMUM, ULTRACODE, APEXCODE, AUTO;

    /** 推理努力映射（请求级提示；适配层不支持的端点自然忽略）。 */
    val reasoningEffort: KitReasoningEffort?
        get() = when (this) {
            NONE -> KitReasoningEffort.LOW
            LIGHT -> KitReasoningEffort.LOW
            STANDARD -> null       // 模型默认
            DEEP -> KitReasoningEffort.HIGH
            MAXIMUM -> KitReasoningEffort.HIGH
            ULTRACODE -> KitReasoningEffort.HIGH
            APEXCODE -> KitReasoningEffort.HIGH
            AUTO -> null
        }

    companion object {
        /** 持久化名解析（大小写不敏感；未知 → null 由调用方兜底 STANDARD）。 */
        fun fromName(name: String?): KitThinkingLevel? =
            name?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
                ?.let { raw -> entries.firstOrNull { it.name == raw } }
    }
}

/** 请求级推理努力（OpenAI reasoning_effort / Anthropic thinking budget 的抽象）。 */
enum class KitReasoningEffort { LOW, MEDIUM, HIGH }

/** 请求级工具选择（预算收官轮 = NONE——保留工具定义但禁用调用）。 */
enum class KitToolChoice { AUTO, NONE }

/**
 * 请求选项（适配层映射到自家 LLM 客户端参数）。
 *
 * - [temperature] / [maxTokens]：哨兵语义（< 0 = 未指定，用宿主 Profile 值）；
 * - [toolChoice]：NONE = 本轮禁工具（预算收官/降级续跑）；
 * - [reasoningEffort]：null = 不下发。
 */
data class KitRequestOptions(
    val temperature: Float = -1f,
    val maxTokens: Int = -1,
    val toolChoice: KitToolChoice = KitToolChoice.AUTO,
    val reasoningEffort: KitReasoningEffort? = null
) {
    companion object {
        val DEFAULT = KitRequestOptions()
    }
}

// ═══════════════════════ 工具执行流模型 ═══════════════════════

/** 工具执行流事件（适配层从自家工具执行管线映射）。 */
sealed interface KitToolStreamEvent {
    data class Output(val chunk: String) : KitToolStreamEvent
    data class Progress(val percent: Float?, val message: String?) : KitToolStreamEvent
    data class Complete(val output: String) : KitToolStreamEvent
    data class Error(val message: String) : KitToolStreamEvent
}
