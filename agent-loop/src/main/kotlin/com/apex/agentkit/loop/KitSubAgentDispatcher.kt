package com.apex.agentkit.loop

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * # Kit Sub-Agent Dispatcher — task 工具的派发执行器
 *
 * 主代理调用合成 `task` 工具时，本派发器构造一个**隔离上下文**的子代理：
 *
 * - 子代理 = 全新 [AgentLoopEngine] 实例（工厂 [childEngineFactory]）+
 *   请求指定的画像（explore 只读 / research 联网 / reviewer 评审 / general
 *   通用）+ 子代理运行约束（无交互问答、回合预算更小、事件经转发进父流）；
 * - 子代理跑完整任务循环（自己的回合 / 工具 / 权限门），**中间过程不进
 *   父上下文**——只有最终结论文本作为 task 工具结果返回；
 * - 事件转发：子代理的工具事件经 **id 前缀重写**（`sub_`）后流入父流——
 *   时间轴能看到子代理的工作过程（透明可审计），但正文/思考/迭代事件
 *   不转发（不污染父会话的正文气泡与思考链）。
 *
 * ## 资源限制
 *
 * - 预算（并发 / 超时 / 输出上限 / 总开关）从 [budgetSource] 快照读取
 *   ——设置层运行中调整即时生效；
 * - 并发闸门：[runningCount] 动态计数（AtomicInteger 原子准入 + 退避
 *   重试）；
 * - 整体超时（含排队）：超时但有部分输出 → truncated 部分结果；
 *   无输出 → 失败折叠；
 * - 子代理内部的权限 ASK 自动折叠 DENY（无交互通道，宁可保守）。
 *
 * ## 工厂契约
 *
 * [childEngineFactory] **每次调用必须返回全新实例**（子代理各自持有独立
 * 会话状态；复用实例 = 子代理互相串历史，破坏隔离）。
 */
internal class KitSubAgentDispatcher(
    private val childEngineFactory: (KitAgentDefinition) -> AgentLoopEngine,
    private val budgetSource: () -> KitSubAgentBudget = { KitSubAgentBudget() },
    private val logger: KitLogger = KitLogger.Silent
) {

    /** 动态并发闸门：在跑子代理计数。 */
    private val runningCount = AtomicInteger(0)

    /**
     * 派发一个子代理任务。
     *
     * @param request 任务请求（画像 / 描述 / 指令 / 工作区根）
     * @param eventSink 父流事件汇
     * @return 执行结果（失败折叠为 outcome.output = 失败说明）
     */
    suspend fun dispatch(
        request: KitSubAgentRequest,
        eventSink: suspend (KitEvent) -> Unit
    ): KitSubAgentOutcome {
        val definition = KitAgentCatalog.definitionOf(request.kind)
        // 防御式：设置层读取异常折叠为默认预算（派发不因设置层故障拒工）
        val s = runCatching { budgetSource() }.getOrElse { KitSubAgentBudget() }
        if (!s.enabled) {
            return KitSubAgentOutcome.failure(
                request.kind,
                "子代理已全局停用（设置 → 子代理）——请主代理亲自执行该探索/调研"
            )
        }
        val timeoutBudgetMs = s.timeoutMs.coerceAtLeast(1)
        val outputLimit = s.outputLimit.coerceAtLeast(MIN_OUTPUT_CHARS)
        val startedAt = System.currentTimeMillis()
        logger.info(
            TAG,
            "Sub-agent [${request.kind.key}] start: ${request.description} " +
                "(budget: ${timeoutBudgetMs / 1000}s / concurrency ${s.maxConcurrent} / " +
                "turns ${definition.subagentMaxTurns} / toolOut ${s.toolOutputLimit})"
        )

        var finalOutput: String? = null
        var turns = 0
        var toolCalls = 0

        val completed = withTimeoutOrNull(timeoutBudgetMs) {
            acquireGate(s.maxConcurrent)
            try {
                val child = childEngineFactory(definition)
                try {
                    child.executeAsSubAgent(
                        prompt = KitPrompts.subAgentBrief(
                            request.description, request.prompt, request.workspaceRoot
                        ),
                        onEvent = { event -> forward(event, eventSink) }
                    ).let { outcome ->
                        finalOutput = outcome.output
                        turns = outcome.turns
                        toolCalls = outcome.toolCalls
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    logger.warn(TAG, "Sub-agent [${request.kind.key}] crashed (isolated): ${t.message}")
                    finalOutput = null
                }
            } finally {
                releaseGate()
            }
            true
        }

        val durationMs = System.currentTimeMillis() - startedAt
        return when {
            completed == null -> {
                val partial = finalOutput
                if (partial.isNullOrBlank()) {
                    KitSubAgentOutcome.failure(
                        request.kind,
                        "执行超时（${timeoutBudgetMs / 1000}s）且没有输出"
                    )
                } else {
                    KitSubAgentOutcome(
                        kind = request.kind, output = partial, turns = turns,
                        toolCalls = toolCalls, durationMs = durationMs, truncated = true
                    )
                }
            }
            finalOutput.isNullOrBlank() ->
                KitSubAgentOutcome.failure(request.kind, "子代理未返回任何内容")
            else -> {
                val capped = capOutput(finalOutput!!, outputLimit)
                logger.info(
                    TAG,
                    "Sub-agent [${request.kind.key}] done: $turns turns / " +
                        "$toolCalls tool calls / ${durationMs}ms"
                )
                KitSubAgentOutcome(
                    kind = request.kind, output = capped.first, turns = turns,
                    toolCalls = toolCalls, durationMs = durationMs, truncated = capped.second
                )
            }
        }
    }

    /**
     * 动态并发闸门准入：每次尝试读当前上限，AtomicInteger 原子抢占——
     * 抢到名额即返回，抢不到退避重试。排队计入外层整体预算。
     */
    private suspend fun acquireGate(fallbackLimit: Int) {
        while (true) {
            val limit = runCatching { budgetSource().maxConcurrent }
                .getOrDefault(fallbackLimit)
                .coerceAtLeast(1)
            if (runningCount.incrementAndGet() <= limit) return
            runningCount.decrementAndGet()
            delay(GATE_RETRY_MS)
        }
    }

    /** 闸门释放（finally 兜底）。 */
    private fun releaseGate() {
        runningCount.decrementAndGet()
    }

    /**
     * 事件转发（id 前缀重写）：仅工具族事件进父流。
     */
    private suspend fun forward(
        event: KitEvent,
        sink: suspend (KitEvent) -> Unit
    ) {
        when (event) {
            is KitEvent.ToolCallStart -> sink(
                event.copy(callId = prefixId(event.callId))
            )
            is KitEvent.ToolOutputChunk -> sink(
                event.copy(callId = prefixId(event.callId))
            )
            is KitEvent.ToolProgress -> sink(
                event.copy(callId = prefixId(event.callId))
            )
            is KitEvent.ToolCallComplete -> sink(
                event.copy(callId = prefixId(event.callId))
            )
            else -> Unit // 正文/思考/迭代/完成等不进父流（隔离语义）
        }
    }

    private fun prefixId(id: String): String =
        if (id.startsWith(SUB_PREFIX)) id else SUB_PREFIX + id

    /** 超长输出裁剪：保留前 limit 字符并追加提示行。 */
    private fun capOutput(text: String, limit: Int): Pair<String, Boolean> {
        if (text.length <= limit) return text to false
        return (text.take(limit) +
            "\n…[子代理输出超长（${text.length} 字符），已截断至 $limit 字符]") to true
    }

    companion object {
        private const val TAG = "KitSubAgent"

        /** 子代理事件 callId 前缀（父流与主循环胶囊的命名空间隔离）。 */
        const val SUB_PREFIX = "sub_"

        /** 结论长度下限（快照值被手改 JSON 弄坏时的兜底）。 */
        const val MIN_OUTPUT_CHARS = 100

        /** 动态闸门排队退避间隔。 */
        const val GATE_RETRY_MS = 250L
    }
}
