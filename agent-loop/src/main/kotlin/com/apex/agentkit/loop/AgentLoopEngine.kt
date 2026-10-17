package com.apex.agentkit.loop

import java.io.File
import java.time.LocalDate
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/**
 * # Agent Loop Engine — 标准任务循环引擎
 *
 * 业界标准 Agent 任务循环的完整落地（纯 Kotlin，零 Android 依赖）：
 *
 * ```
 * user msg ──► [steer drain] ──► [compaction?] ──► build system(profile+rules+tools+mcp)
 *      ▲                                                  │
 *      │                                                  ▼
 *      │                                            chatStream ◄── Thinking/Response/Usage 事件
 *      │                                                  │
 *      │              ┌── finish=length ── 续跑指令 ──┐└── tool calls ── 纯文本 ──► ResponseComplete ──► DONE
 *      │              │        (≤2 次)              │        │
 *      │   [interrupt?]── 续跑指令 ──┐               │        ▼
 *      │   [overflow?]── 压缩shrink ─┤               │   permission gate (allow/ask/deny)
 *      │                            │               │        ▼
 *      │                            └───────────────┤   execute (并行安全组 ∥ / 有序组 串行 / task → 子代理)
 *      │                                            ▼
 *      └──────────── append ToolResult（循环至无工具调用 / 预算耗尽 → 收官轮 toolChoice=none）
 * ```
 *
 * ## 相对「朴素任务循环」的关键增强
 *
 * - **并行工具执行**：一回合内**连续的并行安全调用**（只读/task/mcp/skill）
 *   成组并行（并发上限 [KitEngineConfig.maxToolParallelism]）；状态改变类
 *   （shell/写/编辑）与 ASK 门控调用按原顺序串行——保序语义不破坏，
 *   读风暴（模型一口气发 5 个 read）延迟从「加法」变「最大值」；
 * - **输出截断续跑**：finishReason=length 不当结论收尾——注入续跑指令
 *   继续循环（≤ [KitEngineConfig.outputLimitContinuations] 次）；
 * - **中断续跑**：流式传输中断但已有部分输出 → 保留部分输出为 assistant
 *   消息 + 注入「从断点继续」指令（≤ [KitEngineConfig.interruptContinuations]
 *   次，超过按部分结果诚实收尾）；
 * - **溢出压缩恢复**：provider 拒绝请求明示上下文超限 → shrink 阶梯
 *   （0.7 / 0.5 / 0.35）压缩后整轮重试（≤ [KitEngineConfig.overflowCompactionAttempts] 次）；
 * - **预算收官轮**：回合预算耗尽的最后一轮带 MAX_STEPS 提示 + 请求级
 *   toolChoice=none（工具定义保留——prompt cache 前缀不破坏）；
 * - **上下文压缩升级**：七节结构化摘要模板 + 格式纠偏（NUDGE）+ 增量
 *   合并 + token 保留窗 + 滑窗降级；
 * - **MCP 使用指引**：握手 instructions 注入系统提示词（仅当该服务器
 *   工具真的在当前工具面上——防幻觉指引）；
 * - **工具输出头尾截断**：行/字节/字符三重预算，头 70% + 尾 30%——
 *   「报错在尾部、上下文在头部」的工具输出不再丢关键信号；
 * - **运行中转向（steering）**：补充指令入队，回合边界注入——不打断
 *   当前回合也不丢失。
 *
 * ## 关键决策
 *
 * - **独立循环**：自有回合语义（turn = 一次 LLM 请求 + 工具批处理）；
 * - **PLAN 档人控门**：规划师画像 → [KitPlan] → 确认挂起 → 切构建者画像；
 * - **task 工具**：合成定义，派发 [KitSubAgentDispatcher] 隔离子代理；
 * - **记忆通道**：经 [KitMemory] 端口（null = 子代理/测试：纯内存会话）。
 */
class AgentLoopEngine(
    private val modelClient: KitModelClient,
    private val toolSource: KitToolSource,
    private val toolRunner: KitToolRunner,
    private val config: KitEngineConfig = KitEngineConfig(),
    private val logger: KitLogger = KitLogger.Silent,
    /** 会话记忆（null = 子代理/测试：纯内存会话，不落盘）。 */
    private val memory: KitMemory? = null,
    private val textKernel: KitTextKernel? = null,
    private val permissionSource: KitPermissionSource = KitPermissionSource.NONE,
    private val modelInfoSource: KitModelInfoSource? = null,
    private val environmentInfoSource: KitEnvironmentInfoSource? = null,
    private val workspaceContextSource: KitWorkspaceContextSource? = null,
    private val mcpInstructionSource: KitMcpInstructionSource? = null,
    private val subAgentBudgetSource: KitSubAgentBudgetSource? = null,
    private val errorClassifier: KitLlmErrorClassifier = KitLlmErrorClassifier.Default,
    /** LLM 容错阶梯延迟执行器（测试注入；语义见 [KitLlmResilience]）。 */
    private val retrySleeper: suspend (Long) -> Unit = { delay(it) },
    /** 子代理运行标记（本实例由 task 工具派生）。 */
    internal val subAgentMode: Boolean = false
) : KitEngine {

    // ═══════════════════════ 状态 ═══════════════════════

    /** 会话（消息时间线 + fork 谱系）。 */
    private val session = KitSession("loop_root")

    /** 当前画像（BUILD / PLAN / GENERAL；子代理 = 派发时指定）。 */
    @Volatile
    private var profile: KitAgentDefinition = KitAgentCatalog.BUILD

    /** 权限门（每实例独立——子代理会话记忆不与主会话互通）。 */
    private val permissionEngine = KitPermissionEngine()

    /** 思考档位（七档 → 回合预算倍率 + 推理努力映射）。 */
    @Volatile
    private var thinkingLevel: KitThinkingLevel = KitThinkingLevel.STANDARD

    /** v6 专家模板人设段（null = 无角色）。 */
    @Volatile
    private var rolePersona: String? = null

    /** 会话附加段（JIT 注入）。 */
    @Volatile
    private var sessionExtras: String? = null

    /** 强制函数集 / 全量开关。 */
    @Volatile
    private var forcedToolIds: Set<String> = emptySet()

    @Volatile
    private var exposeAllTools: Boolean = false

    /** 工作区状态。 */
    @Volatile
    private var workspace: KitWorkspaceInfo? = null

    /** 运行态。 */
    @Volatile
    private var isRunning: Boolean = false
    private var userInputDeferred: CompletableDeferred<String>? = null
    private var planConfirmationDeferred: CompletableDeferred<PlanAnswer>? = null

    /** usage 校准：服务端 promptTokens / 本地估算比值。 */
    @Volatile
    private var usageCalibration: Float = 1.0f

    /** PLAN 档硬门（与权限模式正交）。 */
    @Volatile
    private var agentPlanGate: Boolean = false

    /** read-before-edit 硬约束。 */
    private val readGuard = KitReadGuard(workspaceRootProvider = { workspace?.rootPath })

    private val runCounter = AtomicLong(0)

    /** 压缩器（共享 modelClient；LLM 摘述 + 滑窗降级）。 */
    private val compactor = KitCompactor(
        modelClient = modelClient,
        logger = logger,
        compactionContextTag = config.compactionContextTag
    )

    /** 转向队列（运行中补充指令；回合边界消费）。 */
    private val steerQueue = ConcurrentLinkedQueue<String>()

    /** 工具输出截断器（头尾保留策略）。 */
    private val outputTruncator = ToolOutputTruncator(
        charBudget = config.toolOutputBudget,
        maxLines = config.toolOutputMaxLines,
        maxBytes = config.toolOutputMaxBytes
    )

    /** 子代理派发器（工厂 = 全新同构引擎；子代理不再派发——防递归）。 */
    private val subAgentDispatcher: KitSubAgentDispatcher? =
        if (subAgentMode) null
        else KitSubAgentDispatcher(
            childEngineFactory = { definition ->
                AgentLoopEngine(
                    modelClient = modelClient,
                    toolSource = toolSource,
                    toolRunner = toolRunner,
                    config = config.copy(
                        // 子代理预算快照统一源：toolOutputLimit 接管子代理工具输出预算
                        toolOutputBudget = runCatching { subAgentBudgetSource?.budget() }
                            .getOrNull()?.toolOutputLimit ?: config.toolOutputBudget
                    ),
                    logger = logger,
                    memory = null,
                    textKernel = textKernel,
                    permissionSource = permissionSource,
                    modelInfoSource = modelInfoSource,
                    environmentInfoSource = environmentInfoSource,
                    workspaceContextSource = workspaceContextSource,
                    mcpInstructionSource = mcpInstructionSource,
                    subAgentBudgetSource = subAgentBudgetSource,
                    errorClassifier = errorClassifier,
                    subAgentMode = true
                ).apply { profile = definition }
            },
            budgetSource = subAgentBudgetSource
                ?: KitSubAgentBudgetSource {
                    KitSubAgentBudget(
                        maxConcurrent = config.subAgentMaxConcurrent,
                        timeoutMs = config.subAgentTimeoutMs
                    )
                },
            logger = logger
        )

    // ═══════════════════════ KitEngine ═══════════════════════

    override fun execute(input: KitUserInput): Flow<KitEvent> = flow {
        if (isRunning) {
            emit(KitEvent.Error("标准引擎已在运行中（上一轮任务未结束）"))
            return@flow
        }
        isRunning = true
        val runId = runCounter.incrementAndGet()
        val startedAt = System.currentTimeMillis()
        val report = RunReportBuilder()
        try {
            if (runId == 1L) repairDanglingResults()
            refreshPermissionConfig()

            val userText = buildUserText(input)
            val userMessage = KitMessage.User(userText, input.images)
            session.append(userMessage)
            memory?.append(userMessage)

            // flow 构建器内的 emit 是成员函数而非函数值——包一层 lambda 传递
            val emitEvents: suspend (KitEvent) -> Unit = { event -> emit(event) }
            val planText = runTurns(emitEvents, report)
            if (planText != null) {
                // PLAN 档：规划产出 → 人控门 →（确认后）切构建者执行
                handlePlanGate(planText, emitEvents, report)
            }
            emit(
                KitEvent.Complete(
                    summary = finalSummaryText(report),
                    totalIterations = report.turns,
                    totalToolCalls = report.toolCalls,
                    totalDurationMs = System.currentTimeMillis() - startedAt
                )
            )
        } catch (e: CancellationException) {
            isRunning = false
            persistSnapshot()
            emit(KitEvent.Aborted)
            throw e
        } catch (t: Throwable) {
            report.errorMessage = t.message ?: t::class.simpleName ?: "unknown error"
            emit(KitEvent.Error(report.errorMessage ?: "unknown error", recoverable = false))
            emit(
                KitEvent.Complete(
                    summary = finalSummaryText(report),
                    totalIterations = report.turns,
                    totalToolCalls = report.toolCalls,
                    totalDurationMs = System.currentTimeMillis() - startedAt
                )
            )
        } finally {
            isRunning = false
            userInputDeferred?.complete("")
            planConfirmationDeferred?.complete(PlanAnswer.rejected())
            userInputDeferred = null
            planConfirmationDeferred = null
        }
    }

    override suspend fun abort() {
        isRunning = false
        userInputDeferred?.complete("")
        planConfirmationDeferred?.complete(PlanAnswer.rejected())
    }

    override fun submitUserInput(answer: String) {
        userInputDeferred?.complete(answer)
    }

    override fun cancelUserInput() {
        userInputDeferred?.complete("")
    }

    override fun pushSteer(text: String): Boolean {
        if (!isRunning) return false
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (steerQueue.size >= config.steerQueueCapacity) return false
        steerQueue.add(trimmed)
        return true
    }

    override fun isRunning(): Boolean = isRunning

    // ═══════════════════════ 子代理入口 ═══════════════════════

    /**
     * 子代理执行入口（派发器驱动）：独立会话 + 工具事件回调外送。
     *
     * @return 最终结论文本（最后一条 assistant 消息）+ 成本统计
     */
    internal suspend fun executeAsSubAgent(
        prompt: String,
        onEvent: suspend (KitEvent) -> Unit
    ): SubAgentRunResult {
        isRunning = true
        val report = RunReportBuilder()
        refreshPermissionConfig()
        session.append(KitMessage.User(prompt))
        try {
            runTurns(onEvent, report)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            report.errorMessage = t.message ?: t::class.simpleName
        } finally {
            isRunning = false
        }
        return SubAgentRunResult(
            output = lastAssistantText(),
            turns = report.turns,
            toolCalls = report.toolCalls
        )
    }

    /** 子代理运行结果（派发器消费）。 */
    internal data class SubAgentRunResult(
        val output: String,
        val turns: Int,
        val toolCalls: Int
    )

    // ═══════════════════════ 主循环 ═══════════════════════

    /**
     * 回合循环：直到无工具调用 / 预算耗尽 / 中止 / 异常。
     * @return PLAN 档纯文本收官时的规划文本；其余返回 null（已正常收尾）
     */
    private suspend fun runTurns(
        emit: suspend (KitEvent) -> Unit,
        report: RunReportBuilder
    ): String? {
        var turn = 0
        val maxTurns = effectiveMaxTurns()
        var finalText: String? = null
        var planText: String? = null
        val repeatGuard = RepeatGuard()
        var outputLimitContinuations = 0
        var interruptContinuations = 0
        var overflowAttempts = 0

        while (isRunning && turn < maxTurns) {
            turn++
            report.turns = turn
            emit(KitEvent.IterationStart(turn))

            // ── 转向指令消费（回合边界注入为用户消息）──
            drainSteer(emit)

            // ── 压缩检查（消息域不含本轮 system 头）──
            maybeCompact(emit)

            // ── 装配请求 ──
            val plan = KitToolSurface.buildToolPlan(
                profile, toolSource.definitions(), forcedToolIds, exposeAllTools,
                includeSyntheticTools = !subAgentMode
            )
            val planIds = plan.definitions.map { it.name }
            val systemPrompt = buildSystemPrompt(plan, planIds)
            val messages = buildList {
                add(KitMessage.System(systemPrompt))
                addAll(session.snapshot().map { it.message })
                // PLAN 档回合级只读提醒（请求级注入——不进会话时间线）
                if (agentPlanGate && !subAgentMode) {
                    add(KitMessage.System(KitPrompts.planModeReminder()))
                }
            }

            val contentBuilder = StringBuilder()
            val reasoningBuilder = StringBuilder()
            val toolCallsAccumulator = LinkedHashMap<String, KitToolCallAccumulator>()
            var streamFinishReason: String? = null

            val outcome = KitLlmResilience.run(
                emit = emit,
                retrySleeper = retrySleeper,
                classifier = errorClassifier,
                hasPartialOutput = {
                    contentBuilder.isNotEmpty() ||
                        reasoningBuilder.isNotEmpty() || toolCallsAccumulator.isNotEmpty()
                }
            ) { textOnly ->
                val options = KitRequestOptions(
                    reasoningEffort = thinkingLevel.reasoningEffort
                )
                modelClient.chatStream(
                    contextTag = if (subAgentMode) config.subAgentContextTag else config.contextTag,
                    messages = messages,
                    tools = if (textOnly) emptyList() else plan.definitions,
                    options = options
                ).collect { chunk ->
                    chunk.content?.let {
                        contentBuilder.append(it)
                        emit(KitEvent.ResponseChunk(it))
                    }
                    chunk.reasoningContent?.let {
                        reasoningBuilder.append(it)
                        emit(KitEvent.ThinkingChunk(it))
                    }
                    chunk.usage?.let { usage ->
                        report.promptTokens += usage.promptTokens
                        report.completionTokens += usage.completionTokens
                        calibrate(usage.promptTokens, messages)
                        emit(
                            KitEvent.UsageUpdated(
                                usage.promptTokens, usage.completionTokens, usage.totalTokens
                            )
                        )
                    }
                    chunk.finishReason?.let { streamFinishReason = it }
                    for (tc in chunk.toolCalls) {
                        val key = if (tc.index >= 0) "_idx_${tc.index}" else tc.id
                        if (key.isBlank()) continue
                        val acc = toolCallsAccumulator.getOrPut(key) {
                            KitToolCallAccumulator(tc.id.ifBlank { key }, tc.name)
                        }
                        acc.append(tc.name, tc.arguments)
                    }
                }
                streamFinishReason
            }

            // ── 容错结果分派（溢出恢复 / 中断续跑 / 诚实失败）──
            when (outcome) {
                is KitLlmResilience.ResilienceOutcome.Overflow -> {
                    overflowAttempts++
                    if (overflowAttempts <= config.overflowCompactionAttempts &&
                        compactForOverflow(emit, overflowAttempts - 1)
                    ) {
                        emit(
                            KitEvent.LlmRetryScheduled(
                                attempt = 0,
                                delayMs = 0L,
                                reason = "上下文溢出，已按 shrink 阶梯压缩" +
                                    "（第 $overflowAttempts 次），重试本轮"
                            )
                        )
                        continue
                    }
                    throw IllegalStateException(
                        "上下文溢出且压缩恢复失败：${outcome.reason}"
                    )
                }

                is KitLlmResilience.ResilienceOutcome.Interrupted -> {
                    interruptContinuations++
                    if (interruptContinuations <= config.interruptContinuations) {
                        // 断点续跑：保留部分输出 + 续跑指令（不重放整轮）
                        val partial = contentBuilder.toString().trim()
                        if (partial.isNotEmpty()) {
                            session.append(KitMessage.Assistant(partial))
                            memory?.append(KitMessage.Assistant(partial))
                        }
                        session.append(KitMessage.System(KitPrompts.continueAfterInterrupt()))
                        emit(
                            KitEvent.LlmRetryScheduled(
                                attempt = 0,
                                delayMs = 0L,
                                reason = "流式传输中断（${outcome.reason}），已保留部分输出，从断点继续"
                            )
                        )
                        continue
                    }
                    // 续跑预算耗尽：按部分结果诚实收尾
                    val settled = contentBuilder.toString().trim()
                        .ifBlank { "（本轮输出因传输中断不完整）" }
                    session.append(KitMessage.Assistant(settled))
                    memory?.append(KitMessage.Assistant(settled))
                    if (profile.kind == KitAgentKind.PLAN && !subAgentMode) {
                        planText = settled
                    } else {
                        emit(KitEvent.ResponseComplete(settled))
                    }
                    finalText = settled
                    break
                }

                is KitLlmResilience.ResilienceOutcome.Failed ->
                    throw outcome.error

                is KitLlmResilience.ResilienceOutcome.Completed ->
                    Unit // 正常路径继续
            }

            val assistantText = contentBuilder.toString().trim()
            val toolCalls = toolCallsAccumulator.values
                .map { it.build() }
                .filter { it.name.isNotBlank() }

            // ── 无工具调用：本回合即结论（或输出截断续跑）──
            if (toolCalls.isEmpty()) {
                val hitOutputLimit = streamFinishReason == FINISH_LENGTH
                if (hitOutputLimit && outputLimitContinuations < config.outputLimitContinuations) {
                    outputLimitContinuations++
                    // 输出截断续跑：保留部分回复 + 续跑指令（不道歉不复述）
                    session.append(KitMessage.Assistant(assistantText))
                    memory?.append(KitMessage.Assistant(assistantText))
                    session.append(KitMessage.System(KitPrompts.continueAfterOutputLimit()))
                    emit(
                        KitEvent.LlmRetryScheduled(
                            attempt = 0,
                            delayMs = 0L,
                            reason = "输出达到长度上限（finish=length），继续剩余工作" +
                                "（第 $outputLimitContinuations 次续跑）"
                        )
                    )
                    continue
                }
                val finalOut = assistantText.ifBlank {
                    reasoningBuilder.toString().trim().ifBlank { "（本轮无输出）" }
                }
                session.append(KitMessage.Assistant(finalOut))
                memory?.append(KitMessage.Assistant(finalOut))
                if (profile.kind == KitAgentKind.PLAN && !subAgentMode) {
                    // PLAN 档：规划文本交人控门（ResponseComplete 延后发射）
                    planText = finalOut
                } else {
                    emit(KitEvent.ResponseComplete(finalOut))
                }
                finalText = finalOut
                break
            }

            // ── 有工具调用：记录 assistant(toolCalls) 并执行本批 ──
            session.append(KitMessage.Assistant(assistantText, toolCalls))
            memory?.append(KitMessage.Assistant(assistantText, toolCalls))

            executeToolBatch(toolCalls, emit, report, repeatGuard, plan.providerNameToId)

            // 工具有进展 → 续跑预算复位（新一轮的正常推进不该被旧债扣额度）
            outputLimitContinuations = 0
            interruptContinuations = 0

            // ── 防循环守卫：同参重复调用告警 / 强收敛 ──
            repeatGuard.warningFor()?.let { warn ->
                session.append(KitMessage.System(warn))
            }
            if (repeatGuard.shouldForceFinal()) {
                // 强收敛 = 直接跳出循环，走预算耗尽收尾（纯文本收敛轮）
                break
            }
        }

        // ── 预算耗尽仍无结论：收官轮（MAX_STEPS 提示 + toolChoice=none，
        //    工具定义保留——prompt cache 前缀不破坏）──
        if (finalText == null && isRunning && planText == null) {
            session.append(KitMessage.System(KitPrompts.maxStepsPrompt()))
            try {
                val plan = KitToolSurface.buildToolPlan(
                    profile, toolSource.definitions(), forcedToolIds, exposeAllTools,
                    includeSyntheticTools = false
                )
                val systemPrompt = buildSystemPrompt(plan, plan.definitions.map { it.name })
                val messages = buildList {
                    add(KitMessage.System(systemPrompt))
                    addAll(session.snapshot().map { it.message })
                }
                val sb = StringBuilder()
                modelClient.chatStream(
                    contextTag = if (subAgentMode) config.subAgentContextTag else config.contextTag,
                    messages = messages,
                    tools = plan.definitions,
                    options = KitRequestOptions(toolChoice = KitToolChoice.NONE)
                ).collect { chunk ->
                    chunk.content?.let {
                        sb.append(it)
                        emit(KitEvent.ResponseChunk(it))
                    }
                }
                val out = sb.toString().trim()
                    .ifBlank { "回合预算耗尽（$maxTurns turns）。" }
                session.append(KitMessage.Assistant(out))
                memory?.append(KitMessage.Assistant(out))
                emit(KitEvent.ResponseComplete(out))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val fallback = "回合预算耗尽（$maxTurns turns），且收尾请求失败：${e.message}"
                session.append(KitMessage.Assistant(fallback))
                emit(KitEvent.ResponseComplete(fallback))
            }
        }
        return planText
    }

    // ═══════════════════════ 工具批执行（并行安全组 ∥ + 有序组串行）═══════════════════════

    /**
     * 执行一回合的工具调用批。
     *
     * 分组策略（保序 + 并行两全）：
     * - 按**原顺序**扫描；**连续的并行安全调用**（只读 / task / mcp / skill）
     *   累积成组并行执行（并发上限 [KitEngineConfig.maxToolParallelism]）；
     * - 非并行安全（shell / 写 / 编辑 / git）与 ASK 门控调用**串行执行**；
     * - 遇到非并行安全调用即冲刷当前并行组——「read A → write A」的隐式
     *   顺序依赖不被并行化破坏；
     * - 未知工具 / read-guard 拦截 / DENY 决策就地出结果（不执行）。
     *
     * 结果按原顺序追加 ToolResult（协议要求的确定性），ToolCallComplete
     * 在各自完成时刻发射（callId 区分并行流）。
     */
    private suspend fun executeToolBatch(
        calls: List<KitToolCall>,
        emit: suspend (KitEvent) -> Unit,
        report: RunReportBuilder,
        repeatGuard: RepeatGuard,
        providerNameToId: Map<String, String>
    ) {
        if (calls.isEmpty()) return
        val knownToolNames = toolSource.definitions().map { it.name }.toSet()

        // ── 预检（顺序、廉价）：名字归一 / read-guard / 权限裁决 ──
        data class Prepared(
            val call: KitToolCall,
            val registryName: String,
            val callId: String,
            val decision: KitPermissionDecision?,
            /** 未执行直接出结果的输出（未知工具 / read-guard / DENY）。 */
            val blockedOutput: String?
        )

        val prepared = calls.map { call ->
            val registryName = providerNameToId[call.name]
                ?: KitToolSurface.resolveRegistryId(call.name)
            val callId = call.id.ifBlank {
                KitIds.toolCallId(if (subAgentMode) "sub_" else "std_")
            }
            repeatGuard.record(fingerprintOf(registryName, call.arguments))
            emit(KitEvent.ToolCallStart(callId, registryName, call.arguments))
            report.toolCalls++

            val knownTool = KitToolSurface.isSyntheticTaskTool(registryName) ||
                registryName in knownToolNames
            val readGuardBlock = if (knownTool) readGuard.check(registryName, call.arguments) else null
            val blocked: String? = when {
                !knownTool -> KitPrompts.unknownToolResult(
                    call.name,
                    KitToolSurface.suggestToolIds(call.name, knownToolNames.toList())
                )
                readGuardBlock != null -> readGuardBlock
                else -> null
            }
            val decision = if (blocked == null) {
                permissionEngine.decide(
                    toolId = registryName,
                    rawArguments = call.arguments,
                    subAgentContext = subAgentMode,
                    planGate = agentPlanGate
                )
            } else null
            Prepared(call, registryName, callId, decision, blocked)
        }

        data class Executed(
            val output: String,
            val success: Boolean,
            val durationMs: Long
        )

        /** 单调用执行 + 截断 + 事件（并行组与串行共用；计时从真实执行起算）。 */
        suspend fun runOne(p: Prepared): Executed {
            val startedAt = System.currentTimeMillis()
            val result = runTool(p.callId, p.registryName, p.call.arguments, emit, report)
            var (output, success) = result
            if (success) readGuard.record(p.registryName, p.call.arguments)
            val durationMs = System.currentTimeMillis() - startedAt
            output = outputTruncator.truncate(output)
            emit(
                KitEvent.ToolCallComplete(
                    callId = p.callId,
                    toolName = p.registryName,
                    arguments = p.call.arguments,
                    output = output,
                    fullOutput = output,
                    success = success,
                    durationMs = durationMs
                )
            )
            return Executed(output, success, durationMs)
        }

        /** 就地出结果（未知工具 / read-guard / DENY / ASK 拒绝）。 */
        suspend fun settleBlocked(p: Prepared, output: String): Executed {
            val durationMs = 0L
            emit(
                KitEvent.ToolCallComplete(
                    callId = p.callId,
                    toolName = p.registryName,
                    arguments = p.call.arguments,
                    output = output,
                    fullOutput = output,
                    success = false,
                    durationMs = durationMs
                )
            )
            return Executed(output, false, durationMs)
        }

        val results = arrayOfNulls<Executed>(prepared.size)
        val semaphore = Semaphore(config.maxToolParallelism.coerceAtLeast(1))

        // ── 分组执行：连续并行安全 ALLOW 调用成组并行；其余串行 ──
        var i = 0
        var parallelGroup = mutableListOf<Int>()
        suspend fun flushParallelGroup() {
            if (parallelGroup.isEmpty()) return
            val group = parallelGroup
            parallelGroup = mutableListOf()
            if (!isRunning) return // 中止：未启动的并行组直接弃批（结果由占位补齐）
            if (group.size == 1) {
                val idx = group[0]
                results[idx] = runOne(prepared[idx])
            } else {
                coroutineScope {
                    group.forEach { idx ->
                        launch {
                            semaphore.withPermit {
                                results[idx] = runOne(prepared[idx])
                            }
                        }
                    }
                }
            }
        }

        while (i < prepared.size && isRunning) {
            val p = prepared[i]
            val decision = p.decision
            when {
                p.blockedOutput != null -> {
                    // 未知工具 / read-guard 拦截：就地出结果（不执行、不占权限门）
                    flushParallelGroup()
                    results[i] = settleBlocked(p, p.blockedOutput)
                }
                decision == null -> {
                    // 预检保证不可能（blocked==null 必有决策）——防御式就地拒
                    flushParallelGroup()
                    results[i] = settleBlocked(p, "内部错误：未裁决的调用")
                }
                decision.effect == KitPermissionEffect.DENY -> {
                    flushParallelGroup()
                    report.permissionDenied++
                    results[i] = settleBlocked(
                        p,
                        KitPrompts.permissionDeniedToolResult(p.registryName, decision.reason)
                    )
                }
                decision.effect == KitPermissionEffect.ALLOW -> {
                    if (isParallelSafe(p.registryName)) {
                        parallelGroup.add(i)
                    } else {
                        flushParallelGroup()
                        results[i] = runOne(p)
                    }
                }
                decision.effect == KitPermissionEffect.ASK -> {
                    flushParallelGroup()
                    report.permissionAsks++
                    val (response, denyFeedback) = askPermission(p.registryName, p.call, emit)
                    if (response == KitPermissionResponse.DENY) {
                        report.permissionDenied++
                        results[i] = settleBlocked(
                            p,
                            KitPrompts.permissionDeniedToolResult(
                                p.registryName,
                                if (denyFeedback != null) {
                                    "用户拒绝，并给出指示：$denyFeedback（请改按指示选择更安全的路线）"
                                } else {
                                    "用户拒绝了本次执行"
                                }
                            )
                        )
                    } else {
                        if (response == KitPermissionResponse.ALLOW_SESSION) {
                            rememberSessionAllow(p.registryName, p.call.arguments)
                        }
                        results[i] = runOne(p)
                    }
                }
                else -> Unit // 不可达（三态效应封闭枚举）
            }
            i++
        }
        flushParallelGroup()

        // ── 中止时未执行的调用补齐结果（悬挂 toolCall 会让下轮请求 400）──
        for (idx in results.indices) {
            if (results[idx] == null) {
                results[idx] = Executed("(执行被中止：任务在调用前被取消)", false, 0L)
            }
        }

        // ── 按原顺序追加 ToolResult（会话协议确定性）──
        for (idx in prepared.indices) {
            val p = prepared[idx]
            val r = results[idx] ?: continue
            session.append(KitMessage.ToolResult(p.callId, r.output))
            memory?.append(KitMessage.ToolResult(p.callId, r.output))
        }
    }

    /**
     * 并行安全判定：只读 / task / mcp / skill 工具无共享可变状态；
     * shell / 写 / 编辑 / git 有顺序副作用语义（或共享终端会话）——串行。
     */
    private fun isParallelSafe(registryName: String): Boolean =
        KitPermissionEngine.isReadOnlyTool(registryName) ||
            KitToolSurface.isSyntheticTaskTool(registryName)

    /**
     * ASK → UserInputRequired + 挂起等答案（超时 = 拒绝）。
     */
    private suspend fun askPermission(
        toolName: String,
        call: KitToolCall,
        emit: suspend (KitEvent) -> Unit
    ): Pair<KitPermissionResponse, String?> {
        val preview = call.arguments.take(160)
        emit(
            KitEvent.UserInputRequired(
                prompt = "工具 `$toolName` 请求执行（权限门：$toolName）。\n" +
                    "参数预览：$preview\n" +
                    "回复「允许」= 本次执行；「总是」= 本会话内该工具总允许；" +
                    "其他任意回复 = 拒绝（回复内容会作为指示转达给模型）；不回复 = 拒绝。",
                type = KitInputType.CONFIRMATION
            )
        )
        val raw = awaitUserInput()
        val answer = raw.trim().lowercase()
        val response = when {
            answer in ALLOW_ONCE_WORDS -> KitPermissionResponse.ALLOW_ONCE
            answer in ALLOW_SESSION_WORDS -> KitPermissionResponse.ALLOW_SESSION
            else -> KitPermissionResponse.DENY
        }
        val feedback = raw.trim().takeIf {
            response == KitPermissionResponse.DENY && it.isNotEmpty()
        }
        return response to feedback
    }

    /** 实际执行（task 派发 / 注册表），返回 (输出, 成功)。 */
    private suspend fun runTool(
        callId: String,
        registryName: String,
        arguments: String,
        emit: suspend (KitEvent) -> Unit,
        report: RunReportBuilder
    ): Pair<String, Boolean> {
        // ── 合成 task 工具 → 子代理派发 ──
        if (KitToolSurface.isSyntheticTaskTool(registryName)) {
            val dispatcher = subAgentDispatcher
            if (dispatcher == null) {
                return "task 工具不可用（子代理不能再派发子代理）" to false
            }
            if (extractJsonString(arguments, "subagent_type") == "custom") {
                return ("custom 子代理类型当前思考逻辑线暂不支持，请切深潜线（code_task）" +
                    "或使用内置类型（explore/research/general/reviewer）") to false
            }
            val request = parseSubAgentRequest(arguments)
                ?: return ("task 参数解析失败：需要 description 与 prompt 字段" to false)
            report.subAgents++
            val outcome = dispatcher.dispatch(request) { event -> emit(event) }
            return KitPrompts.subAgentToolResult(outcome) to
                !outcome.output.startsWith("子代理执行失败")
        }

        // ── 注册表工具 ──
        val outputBuilder = StringBuilder()
        var success = true
        var sawOutput = false
        try {
            toolRunner.executeStream(registryName, arguments).collect { event ->
                when (event) {
                    is KitToolStreamEvent.Output -> {
                        sawOutput = true
                        outputBuilder.append(event.chunk)
                        emit(KitEvent.ToolOutputChunk(callId, event.chunk))
                    }
                    is KitToolStreamEvent.Progress ->
                        emit(KitEvent.ToolProgress(callId, event.percent, event.message))
                    is KitToolStreamEvent.Complete -> {
                        // 已流式输出过则 Complete 仅作成功信号
                        if (!sawOutput && event.output.isNotEmpty()) {
                            outputBuilder.append(event.output)
                            sawOutput = true
                        }
                    }
                    is KitToolStreamEvent.Error -> {
                        outputBuilder.append(event.message)
                        success = false
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            outputBuilder.append("工具执行异常：${t.message ?: t::class.simpleName}")
            success = false
        }
        if (outputBuilder.isEmpty()) {
            outputBuilder.append(if (success) "(no output)" else "(failed with no output)")
        }
        return outputBuilder.toString() to success
    }

    private fun rememberSessionAllow(registryName: String, arguments: String) {
        if (KitPermissionEngine.isShellTool(registryName)) {
            KitPermissionEngine.extractCommand(arguments)?.let { cmd ->
                permissionEngine.rememberSessionAllow(cmd.trim(), isCommand = true)
            }
        } else {
            permissionEngine.rememberSessionAllow(registryName, isCommand = false)
        }
    }

    /** 挂起等待用户输入（超时 = 空串 = 拒绝语义）。 */
    private suspend fun awaitUserInput(): String {
        val deferred = CompletableDeferred<String>()
        userInputDeferred = deferred
        return try {
            withTimeout(config.userInputTimeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            ""
        } finally {
            userInputDeferred = null
        }
    }

    // ═══════════════════════ 转向 ═══════════════════════

    /** 回合边界消费转向队列：注入为用户消息（可见、可持久化）。 */
    private suspend fun drainSteer(emit: suspend (KitEvent) -> Unit) {
        while (true) {
            val text = steerQueue.poll() ?: return
            session.append(KitMessage.User(text))
            memory?.append(KitMessage.User(text))
            emit(KitEvent.SteeringAccepted(text))
        }
    }

    // ═══════════════════════ 压缩 ═══════════════════════

    private suspend fun maybeCompact(emit: suspend (KitEvent) -> Unit) {
        val history = session.snapshot().map { it.message }
        val estimated = currentTokenCount()
        val report = compactor.compactIfNeeded(
            messages = history,
            estimatedTokens = estimated,
            maxContextTokens = effectiveContextTokens(),
            threshold = config.compressionThreshold,
            preserveRecentMessages = config.preserveRecentMessages,
            preserveRecentTokens = config.preserveRecentTokens
        ) ?: return
        if (report.effective) {
            session.replaceAll(report.replacement)
            memory?.save(session.snapshot().map { it.message })
            emit(
                KitEvent.ContextCompressed(
                    beforeTokens = report.beforeTokens,
                    afterTokens = report.afterTokens,
                    strategy = report.strategy,
                    summary = report.summary,
                    messagesRemoved = report.messagesRemoved
                )
            )
        }
    }

    /** 溢出恢复压缩（shrink 阶梯）；true = 已压缩可重试。 */
    private suspend fun compactForOverflow(
        emit: suspend (KitEvent) -> Unit,
        attempt: Int
    ): Boolean {
        val history = session.snapshot().map { it.message }
        val report = compactor.compactForOverflow(
            messages = history,
            attempt = attempt,
            preserveRecentMessages = config.preserveRecentMessages,
            preserveRecentTokens = config.preserveRecentTokens
        ) ?: return false
        if (!report.effective) return false
        session.replaceAll(report.replacement)
        memory?.save(session.snapshot().map { it.message })
        emit(
            KitEvent.ContextCompressed(
                beforeTokens = report.beforeTokens,
                afterTokens = report.afterTokens,
                strategy = report.strategy + "+OVERFLOW_SHRINK",
                summary = report.summary,
                messagesRemoved = report.messagesRemoved
            )
        )
        return true
    }

    // ═══════════════════════ PLAN 档人控门 ═══════════════════════

    /**
     * PLAN 档收尾：解析计划 → 人控门 → 确认后切构建者执行。
     */
    private suspend fun handlePlanGate(
        planText: String,
        emit: suspend (KitEvent) -> Unit,
        report: RunReportBuilder
    ) {
        val plan = KitPlanParser.parse(planText, session.title) ?: run {
            // 解析不出步骤：按普通回复收尾（规划师输出可能是答疑式）
            emit(KitEvent.ResponseComplete(planText))
            return
        }
        emit(KitEvent.PlanGenerated(plan))
        emit(KitEvent.PlanAwaitingConfirmation(plan))

        val answer = awaitPlanConfirmation()
        if (!answer.confirmed) {
            session.append(KitMessage.System("用户驳回了该计划。"))
            emit(
                KitEvent.ResponseComplete(
                    planText + "\n\n（计划已被用户驳回，未执行任何步骤。）"
                )
            )
            return
        }

        emit(KitEvent.PlanConfirmed(plan))
        // 确认步骤（勾选/重排口径：原 index）
        val orderedSteps = answer.enabledSteps
            ?.filter { it in plan.steps.indices }
            ?.sortedBy { idx -> answer.order?.indexOf(idx) ?: idx }
            ?.map { plan.steps[it].description }
            ?: plan.steps.map { it.description }

        // 切构建者画像 + 注入执行简报 + 再跑循环（档位硬门同步解除）
        profile = KitAgentCatalog.BUILD
        agentPlanGate = false
        val brief = KitPrompts.planExecutionBrief(plan.goal, orderedSteps)
        session.append(KitMessage.User(brief))
        memory?.append(KitMessage.User(brief))
        runTurns(emit, report)
    }

    /** 计划确认挂起（超时 = 驳回）。 */
    private suspend fun awaitPlanConfirmation(): PlanAnswer {
        val deferred = CompletableDeferred<PlanAnswer>()
        planConfirmationDeferred = deferred
        return try {
            withTimeout(config.planConfirmationTimeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            PlanAnswer.rejected()
        } finally {
            planConfirmationDeferred = null
        }
    }

    /** 计划应答（勾选/重排口径）。 */
    internal data class PlanAnswer(
        val confirmed: Boolean,
        val enabledSteps: List<Int>? = null,
        val order: List<Int>? = null
    ) {
        companion object {
            fun rejected() = PlanAnswer(confirmed = false)
        }
    }

    // ═══════════════════════ KitEngine 状态 API ═══════════════════════

    override fun setActiveWorkspace(workspace: KitWorkspaceInfo?) {
        this.workspace = workspace
        // 换工作区 = 新文件域：read-before-edit 追踪面清零重新累积
        readGuard.reset()
        val info = workspace ?: return
        memory?.bindWorkspace(info.workspaceId)
        val history = memory?.load() ?: emptyList()
        session.restore(history)
        logger.info(
            TAG,
            "Loop engine workspace activated: ${info.workspaceId} (history=${history.size})"
        )
    }

    override fun setActiveFile(path: String?) {
        workspace = workspace?.copy(activeFilePath = path)
    }

    override fun prepareForTask() {
        // JIT 语义：系统提示词每回合重建，workspace/rules/extras 全部下次生效
    }

    override fun updateMode(modeKey: String) {
        profile = KitAgentCatalog.primaryFor(modeKey.lowercase())
        // PLAN 档 = 引擎级硬门——与设置层权限模式正交
        agentPlanGate = profile.kind == KitAgentKind.PLAN
    }

    override fun submitPlanConfirmation(
        confirmed: Boolean,
        enabledSteps: List<Int>?,
        order: List<Int>?
    ) {
        planConfirmationDeferred?.complete(PlanAnswer(confirmed, enabledSteps, order))
    }

    override fun updateRolePersona(roleDefinition: String, rolePrompt: String?) {
        rolePersona = buildString {
            append("## Coding Role\n")
            append(roleDefinition.trim())
            if (!rolePrompt.isNullOrBlank()) {
                append("\n\nAdditional directive from the user:\n")
                append(rolePrompt.trim())
            }
        }.takeIf { it.isNotBlank() }
    }

    override fun updateSessionExtras(extras: String?) {
        sessionExtras = extras?.takeIf { it.isNotBlank() }
    }

    override fun updateForcedTools(toolIds: Set<String>, exposeAll: Boolean) {
        this.forcedToolIds = toolIds
        this.exposeAllTools = exposeAll
    }

    override fun updateThinkingLevel(level: KitThinkingLevel) {
        thinkingLevel = level
    }

    override fun thinkingLevel(): KitThinkingLevel = thinkingLevel

    override fun clearConversation() {
        session.replaceAll(emptyList())
        permissionEngine.resetSessionMemory()
        steerQueue.clear()
        memory?.clear()
    }

    override fun historyCount(): Int = memory?.count() ?: session.size()

    override fun currentWorkspace(): KitWorkspaceInfo? = workspace

    override fun currentTokenCount(): Int {
        val raw = session.stats(::tokenEstimate).estimatedTokens
        return (raw * usageCalibration).toInt()
    }

    override fun maxContextTokens(): Int = effectiveContextTokens()

    /** 生效上下文预算：模型真实窗口优先，静态配置兜底。 */
    private fun effectiveContextTokens(): Int =
        currentModelInfo()?.contextWindow?.takeIf { it > 0 } ?: config.maxContextTokens

    /** 当前模型信息（provider 异常 → null）。 */
    private fun currentModelInfo(): KitModelInfo? =
        runCatching { modelInfoSource?.modelInfo() }.getOrNull()

    /** 拉取权限快照（每轮任务开始；改设置即时生效）。 */
    private fun refreshPermissionConfig() {
        val snapshot = runCatching { permissionSource.snapshot() }.getOrNull() ?: return
        permissionEngine.updateMode(snapshot.mode)
        permissionEngine.updateRules(snapshot.rules)
        permissionEngine.updateCommandRules(snapshot.commandRules)
    }

    // ═══════════════════════ 装配 ═══════════════════════

    /** 系统提示词：画像身份 + 核心行为 + 方法论 + 工具面/纪律 + MCP 指引 + env + 工作区 + 规则 + 人设 + 附加段。 */
    private fun buildSystemPrompt(plan: KitToolPlan, planToolIds: List<String>): String = buildString {
        append(profilePrompt())
        appendLine()
        appendLine(KitPrompts.coreConduct())
        appendLine()
        appendLine(KitPrompts.taskMethodology())
        appendLine()
        val mcpVisible = visibleMcpInstructions(plan)
        appendLine(
            KitPrompts.toolSurfaceSection(
                KitToolSurface.buildToolGuide(profile, planToolIds, mcpVisible)
            )
        )
        val discipline = KitPrompts.toolDiscipline(
            hasShell = planToolIds.any {
                it.startsWith("shell_") || it.startsWith("terminal") || it == "bash"
            },
            hasWrite = planToolIds.any { it == "code_write" || it == "write" },
            hasEdit = planToolIds.any { it == "code_edit" || it == "edit" }
        )
        if (discipline.isNotBlank()) {
            appendLine()
            appendLine(discipline)
        }
        if (KitToolSurface.planHasTodo(planToolIds)) {
            appendLine()
            appendLine(KitPrompts.todoGuidance())
        }
        appendLine()
        appendLine(
            KitPrompts.environmentContext(
                modelId = currentModelInfo()?.modelId,
                todayText = LocalDate.now().toString(),
                platformText = environmentInfo()?.platformText ?: DEFAULT_PLATFORM,
                workdirLabel = environmentInfo()?.workdirLabel ?: workspace?.rootPath,
                isGitRepo = environmentInfo()?.isGitRepo
                    ?: workspace?.rootPath?.let { File(it, ".git").exists() }
            )
        )
        workspaceContextSource?.workspaceSection()?.let { section ->
            if (section.isNotBlank()) {
                appendLine()
                appendLine(section)
            }
        }
        // ── MCP 服务器指引（仅当该服务器工具真的在当前工具面上）──
        KitPrompts.mcpInstructionsSection(mcpVisible)?.let { section ->
            appendLine()
            appendLine(section)
        }
        rolePersona?.let { persona ->
            appendLine()
            appendLine(persona)
        }
        workspaceContextSource?.globalRulesSection()?.let { section ->
            if (section.isNotBlank()) {
                appendLine()
                appendLine(section)
            }
        }
        workspaceContextSource?.projectRulesSection()?.let { section ->
            if (section.isNotBlank()) {
                appendLine()
                appendLine(section)
            }
        }
        sessionExtras?.let {
            appendLine()
            appendLine(it)
        }
        if (subAgentMode) {
            appendLine()
            appendLine(
                "You are running as an isolated sub-agent: you cannot ask the user " +
                    "questions (there is no interactive channel). Risky tools will be " +
                    "denied — design your investigation to stay read-only."
            )
        }
    }.trim() + "\n"

    /** 环境信息（源缺省 → null = 引擎兜底渲染）。 */
    private fun environmentInfo(): KitEnvironmentInfo? =
        runCatching { environmentInfoSource?.environmentInfo() }.getOrNull()

    /** MCP 指引过滤：当前工具面（注册表 id 口径）存在该服务器工具才注入。 */
    private fun visibleMcpInstructions(plan: KitToolPlan): List<KitMcpServerInstructions> {
        val instructions = runCatching { mcpInstructionSource?.serverInstructions() }
            .getOrNull() ?: return emptyList()
        if (instructions.isEmpty()) return emptyList()
        val registryIds = plan.providerNameToId.values
        return instructions.filter { entry ->
            registryIds.any { it.startsWith(entry.toolIdPrefix) }
        }
    }

    private fun profilePrompt(): String = when (profile.kind) {
        KitAgentKind.BUILD -> KitPrompts.buildAgent()
        KitAgentKind.PLAN -> KitPrompts.planAgent()
        // GENERAL：主代理档 = 兜底画像；子代理档 = 全工具面通用执行者
        KitAgentKind.GENERAL ->
            if (subAgentMode) KitPrompts.generalSubAgent() else KitPrompts.generalAgent()
        KitAgentKind.EXPLORE -> KitPrompts.exploreSubAgent()
        KitAgentKind.RESEARCH -> KitPrompts.researchSubAgent()
        KitAgentKind.REVIEWER -> KitPrompts.reviewerSubAgent()
    }

    /** token 估算（native 核优先，纯 Kotlin 回退；实现异常兜底）。 */
    private fun tokenEstimate(text: String): Int =
        runCatching { textKernel?.estimateTokens(text) }.getOrNull()
            ?.takeIf { it > 0 }
            ?: KitSession.defaultTokenEstimator(text)

    // ═══════════════════════ usage 校准 ═══════════════════════

    /** usage 校准（服务端真值 / 本地估算 → 比值，钳制 0.5..3.0）。 */
    private fun calibrate(promptTokens: Int, messages: List<KitMessage>) {
        if (promptTokens <= 0) return
        val local = messages.sumOf { m ->
            when (m) {
                is KitMessage.User -> tokenEstimate(m.content)
                is KitMessage.Assistant -> tokenEstimate(m.content) +
                    m.toolCalls.sumOf { tokenEstimate(it.arguments) }
                is KitMessage.ToolResult -> (tokenEstimate(m.content) + 1) / 2
                is KitMessage.System -> tokenEstimate(m.content)
            }
        }
        if (local <= 0) return
        usageCalibration = (promptTokens.toFloat() / local).coerceIn(0.5f, 3.0f)
    }

    /** 生效回合预算：画像基数 × 思考档位倍率（子代理 = 画像子代理预算）。 */
    internal fun effectiveMaxTurns(): Int {
        val base = if (subAgentMode) profile.subagentMaxTurns else profile.defaultMaxTurns
        val multiplier = when (thinkingLevel) {
            KitThinkingLevel.NONE -> 0.75f
            KitThinkingLevel.LIGHT -> 0.85f
            KitThinkingLevel.STANDARD -> 1.0f
            KitThinkingLevel.DEEP -> 1.15f
            KitThinkingLevel.MAXIMUM -> 1.3f
            KitThinkingLevel.ULTRACODE -> 1.45f
            KitThinkingLevel.APEXCODE -> 1.6f
            KitThinkingLevel.AUTO -> 1.0f
        }
        return (base * multiplier).toInt().coerceAtLeast(4)
    }

    /** 恢复历史时的悬挂 toolCall 修复（assistant.toolCalls 无 result → 补占位）。 */
    private fun repairDanglingResults() {
        val messages = session.snapshot().map { it.message }
        var repaired = 0
        val result = mutableListOf<KitMessage>()
        var i = 0
        while (i < messages.size) {
            val msg = messages[i]
            result.add(msg)
            if (msg is KitMessage.Assistant && msg.toolCalls.isNotEmpty()) {
                val pending = msg.toolCalls.map { it.id }.toMutableSet()
                var j = i + 1
                while (j < messages.size && pending.isNotEmpty()) {
                    val next = messages[j]
                    if (next is KitMessage.ToolResult) {
                        pending.remove(next.toolCallId)
                        result.add(next)
                        j++
                    } else break
                }
                pending.forEach { callId ->
                    result.add(
                        KitMessage.ToolResult(callId, "(结果缺失：上次会话在执行前被中断)")
                    )
                    repaired++
                }
                i = j
            } else {
                i++
            }
        }
        if (repaired > 0) {
            session.replaceAll(result)
            memory?.save(result)
            logger.warn(TAG, "Repaired $repaired dangling tool result(s) on session restore")
        }
    }

    private fun persistSnapshot() {
        memory?.let { m ->
            runCatching { m.save(session.snapshot().map { it.message }) }
        }
    }

    /** 最近一条 assistant 纯文本（子代理结论文本）。 */
    private fun lastAssistantText(): String =
        session.snapshot().lastOrNull { it.message is KitMessage.Assistant }
            ?.let { (it.message as KitMessage.Assistant).content }
            ?.trim()
            .orEmpty()

    private fun finalSummaryText(report: RunReportBuilder): String =
        "标准模式完成：${report.turns} 回合 / ${report.toolCalls} 工具调用 / " +
            "${report.permissionAsks} 次询问 / ${report.subAgents} 次子代理" +
            (report.errorMessage?.let { " / 错误：$it" } ?: "")

    private fun buildUserText(input: KitUserInput): String {
        if (input.files.isEmpty()) return input.text
        return input.text + "\n\n[attached files]\n" +
            input.files.joinToString("\n") { "- ${it.name} (${it.mimeType}, ${it.sizeBytes}B)" }
    }

    companion object {
        private const val TAG = "AgentLoopEngine"

        /** env 块平台兜底行（宿主环境源注入真实描述）。 */
        private const val DEFAULT_PLATFORM = "mobile device"

        /** provider 收尾原因：输出截断（长度上限）。 */
        private const val FINISH_LENGTH = "length"

        private val ALLOW_ONCE_WORDS =
            setOf("允许", "allow", "yes", "y", "好", "ok", "1", "执行")
        private val ALLOW_SESSION_WORDS =
            setOf("总是", "always", "全部允许", "session", "2")

        /** 指纹（重复调用检测）。 */
        internal fun fingerprintOf(toolName: String, arguments: String): String =
            toolName + "|" + arguments.filterNot { it.isWhitespace() }

        /** task 工具参数解析（description / prompt / subagent_type）。 */
        internal fun parseSubAgentRequest(arguments: String): KitSubAgentRequest? {
            val description = extractJsonString(arguments, "description") ?: return null
            val prompt = extractJsonString(arguments, "prompt") ?: return null
            val typeKey = extractJsonString(arguments, "subagent_type") ?: "explore"
            val kind = KitAgentKind.fromKey(typeKey)
                ?.takeIf { it.role == KitAgentRole.SUBAGENT || it == KitAgentKind.GENERAL }
                ?: KitAgentKind.EXPLORE
            return KitSubAgentRequest(
                kind = kind,
                description = description,
                prompt = prompt,
                workspaceRoot = null
            )
        }

        /** 轻量 JSON 字符串字段提取（与权限引擎同款纯字符串口径）。 */
        internal fun extractJsonString(json: String, field: String): String? {
            val key = "\"$field\""
            val idx = json.indexOf(key)
            if (idx < 0) return null
            val colon = json.indexOf(':', idx + key.length)
            if (colon < 0) return null
            var i = colon + 1
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length || json[i] != '"') return null
            val sb = StringBuilder()
            i++
            while (i < json.length && json[i] != '"') {
                if (json[i] == '\\' && i + 1 < json.length) {
                    sb.append(json[i + 1])
                    i += 2
                } else {
                    sb.append(json[i])
                    i++
                }
            }
            return sb.toString().takeIf { it.isNotBlank() }
        }
    }
}
