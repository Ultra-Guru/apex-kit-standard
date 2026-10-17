package com.apex.standardkit.loop

import kotlinx.coroutines.flow.Flow

/**
 * # Kit Engine — 任务循环引擎门面（宿主消费的 API 面）
 *
 * 宿主适配层持有 [KitEngine]（实现 = [AgentLoopEngine]）并把它映射到
 * 自家的引擎接口；本接口是库对外承诺的**稳定契约面**。
 */
interface KitEngine {

    /** 执行一轮任务（冷流——收集时才开跑；事件序列见 [KitEvent]）。 */
    fun execute(input: KitUserInput): Flow<KitEvent>

    /** 中止当前运行（isRunning 置假 + 未决问答/计划门收口）。 */
    suspend fun abort()

    /** 权限门 ASK 的用户应答回传。 */
    fun submitUserInput(answer: String)

    /** 用户输入取消（= 拒绝语义）。 */
    fun cancelUserInput()

    /** PLAN 档人控门应答回传。 */
    fun submitPlanConfirmation(
        confirmed: Boolean,
        enabledSteps: List<Int>?,
        order: List<Int>?
    )

    /**
     * 运行中转向（steering）：把补充指令入队，下个回合边界注入为用户
     * 消息——不打断当前回合，也不丢失。
     *
     * @return false = 未在运行 / 文本为空 / 队列满（调用方改走常规发送）
     */
    fun pushSteer(text: String): Boolean

    /** 是否正在运行。 */
    fun isRunning(): Boolean

    /** 激活工作区（rootPath 供 read-guard 路径解析与子代理简报用）。 */
    fun setActiveWorkspace(workspace: KitWorkspaceInfo?)

    /** 活跃文件（宿主上下文段自行消费；库透传给子代理简报）。 */
    fun setActiveFile(path: String?)

    /** 任务前置准备（JIT 语义下为空操作——保留契约位）。 */
    fun prepareForTask()

    /** 主代理档位切换（build / plan / 其余 → general 兜底）。 */
    fun updateMode(modeKey: String)

    /** 强制函数集收窄（非空 = 只暴露选中工具 + task）。 */
    fun updateForcedTools(toolIds: Set<String>, exposeAll: Boolean)

    /** 思考档位（回合预算倍率 + 推理努力映射）。 */
    fun updateThinkingLevel(level: KitThinkingLevel)

    /** 当前思考档位。 */
    fun thinkingLevel(): KitThinkingLevel

    /** 专家人设段（roleDefinition 必填；rolePrompt 可选追加；空 = 清除）。 */
    fun updateRolePersona(roleDefinition: String, rolePrompt: String?)

    /** 会话附加段（null/空白 = 清除）。 */
    fun updateSessionExtras(extras: String?)

    /** 清空会话（含权限会话记忆）。 */
    fun clearConversation()

    /** 历史条数（持久化口径优先）。 */
    fun historyCount(): Int

    /** 当前会话 token 估算（含 usage 校准）。 */
    fun currentTokenCount(): Int

    /** 生效上下文预算（模型真实窗口优先，静态配置兜底）。 */
    fun maxContextTokens(): Int

    /** 当前工作区。 */
    fun currentWorkspace(): KitWorkspaceInfo?
}

/**
 * 引擎配置（预算与行为参数——全部有默认值，宿主按需覆盖）。
 */
data class KitEngineConfig(
    // ── 工具输出预算 ──
    /** 工具输出字符预算（头尾截断的保留窗）。 */
    val toolOutputBudget: Int = 8_000,
    /** 工具输出行数上限。 */
    val toolOutputMaxLines: Int = ToolOutputDefaults.MAX_LINES,
    /** 工具输出字节上限。 */
    val toolOutputMaxBytes: Int = ToolOutputDefaults.MAX_BYTES,

    // ── 上下文预算 ──
    /** 静态上下文窗口预算（模型真实窗口缺省时的兜底）。 */
    val maxContextTokens: Int = 128_000,
    /** 压缩触发阈值（0..1）。 */
    val compressionThreshold: Float = 0.8f,
    /** 保留最近 N 条不压缩（下限）。 */
    val preserveRecentMessages: Int = 8,
    /** 保留最近约 N token 不压缩（token 保留窗——消息大小差异大时比条数窗更准）。 */
    val preserveRecentTokens: Int = 15_000,

    // ── 子代理基线（未接 [KitSubAgentBudgetSource] 时的回落值）──
    val subAgentMaxConcurrent: Int = 3,
    val subAgentTimeoutMs: Long = 240_000L,

    // ── 并行工具执行 ──
    /** 一批并行安全工具调用的并发上限（连续只读调用成组并行）。 */
    val maxToolParallelism: Int = 4,

    // ── 续跑预算 ──
    /** 输出截断（finishReason=length）续跑次数上限。 */
    val outputLimitContinuations: Int = 2,
    /** 流式中断续跑次数上限（超过则按部分输出诚实收尾）。 */
    val interruptContinuations: Int = 3,
    /** 溢出压缩恢复次数上限（shrink 阶梯 0.7/0.5/0.35）。 */
    val overflowCompactionAttempts: Int = 3,

    // ── 转向队列 ──
    /** 运行中转向指令队列容量。 */
    val steerQueueCapacity: Int = 8,

    // ── 交互超时 ──
    /** 用户输入等待超时（权限门 ASK / 引擎问答）。 */
    val userInputTimeoutMs: Long = 300_000L,
    /** 计划确认等待超时。 */
    val planConfirmationTimeoutMs: Long = 300_000L,

    // ── 请求标记（宿主路由/诊断用）──
    val contextTag: String = "agent_loop",
    val subAgentContextTag: String = "agent_subagent_loop",
    val compactionContextTag: String = "agent_compaction"
)

/** 工具输出截断默认口径（行 2000 / 50KiB——对齐业界标准工具输出上限）。 */
object ToolOutputDefaults {
    const val MAX_LINES: Int = 2_000
    const val MAX_BYTES: Int = 50 * 1024
}
