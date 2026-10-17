package com.apex.standardkit.loop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * # Kit Tool Executor — 工具调用编排子系统
 *
 * 标准任务循环的工具执行层组件化：把「模型吐出一组工具调用」到「全部
 * 调用结算完成」之间的全部编排语义收进可复用单元（引擎内已有同源
 * 实现，本组件把它公开为库级原语，宿主可独立用于自研循环）：
 *
 * 1. **批组规划**（[KitToolBatchPlanner]）：连续**并行安全**调用
 *    （只读 / task / MCP / skill 类）成组并行；shell/写/编辑与一切
 *    ASK 门控调用按原顺序串行——保序不破；
 * 2. **保序执行**（[KitToolExecutor]）：
 *    - start 事件逐条顺序广播（每条广播完成后才启动下一条执行——
 *      源序在任何观察者视角都不乱）；
 *    - 并行组内并发执行（并发度钳制），输出/进度按 callId 区分实时
 *      上报，完成事件即时结算，结果数组保持源序；
 * 3. **输入钩子**（[KitToolExecutionHook]）：执行前参数归一——内置
 *      [KitToolInputNormalizer.dropEmptyOptionalKeys]（模型对可选参数
 *      吐空串的业界顽疾：空串键直接删除，恢复「未提供」语义）；
 * 4. **单调用生命周期**：输出流收集 → 超时钳制 → 异常分类
 *      （完成 / 拒绝 / 中断 / 失败四型出口）；
 * 5. **输出预算**（[KitToolOutputBudget]）：行 / 字节 / 字符三重上限，
 *      头 70% + 尾 30% 保留（工具刷屏不烧上下文，头部看用法、尾部看
 *      结论的两端语义都保住）；
 * 6. **结算**（[KitToolSettlement]）：模型声明了但未被执行的调用
 *      （流中断 / 解析器半截产物）统一补合成失败——「Provider did
 *      not return a tool result」口径，模型侧不留悬挂引用。
 */
// ═══════════════════════ 调用模型 ═══════════════════════

/**
 * 一次工具调用（编排层视角——比 [KitToolCall] 多了编排元数据）。
 *
 * @param callId 调用 id（与模型回显的 tool_call id 一致）
 * @param toolName 注册表 id（已过别名归一）
 * @param arguments 参数 JSON 字符串（原样透传给 runner）
 * @param providerExecuted provider 侧已执行（本地位只做结果收集——
 *   托管式工具调用场景；false = 常规本地执行）
 */
data class KitToolInvocation(
    val callId: String,
    val toolName: String,
    val arguments: String,
    val providerExecuted: Boolean = false
)

/** 单调用出口四型（结算与中断分类的统一口径）。 */
sealed interface KitToolExitKind {
    /** 正常完成（含业务层错误输出——success=false 但流程完整）。 */
    data object Completed : KitToolExitKind

    /** 用户拒绝（权限门 ASK 被拒 / 问答被取消）。 */
    data object Declined : KitToolExitKind

    /** 被中断（会话中止 / 批取消——非调用自身故障）。 */
    data object Interrupted : KitToolExitKind

    /** 执行失败（异常 / 超时 / runner 报错）。 */
    data class Failed(val message: String) : KitToolExitKind
}

// ═══════════════════════ 批组规划 ═══════════════════════

/** 执行批组（sealed 两型——并行组 / 串行单元）。 */
sealed interface KitToolBatchGroup {
    /** 连续并行安全调用成组（组内并发、源序保留）。 */
    data class Parallel(val calls: List<KitToolInvocation>) : KitToolBatchGroup

    /** 串行单元（单条；shell/写/编辑与 ASK 门控调用）。 */
    data class Serial(val call: KitToolInvocation) : KitToolBatchGroup
}

/**
 * 批组规划器。
 *
 * 规则（业界 agent 工具编排共识）：
 * - 连续的并行安全调用合并为一个 [KitToolBatchGroup.Parallel]；
 * - 任一非并行安全调用自成 [KitToolBatchGroup.Serial]，且**打断**
 *   前后的并行组（写操作是栅栏——其前的读必须先完成，其后的读必须
 *   看到写后状态）；
 * - 顺序在全流程保持稳定（无重排、无合并跨栅栏组）。
 */
object KitToolBatchPlanner {

    /**
     * 规划执行批组。
     *
     * @param invocations 模型吐出的调用清单（源序）
     * @param parallelSafe 并行安全判定（read-only / task / MCP / skill 类
     *   = true；shell / write / edit / ASK 门控 = false）
     */
    fun plan(
        invocations: List<KitToolInvocation>,
        parallelSafe: (KitToolInvocation) -> Boolean
    ): List<KitToolBatchGroup> {
        if (invocations.isEmpty()) return emptyList()
        val groups = mutableListOf<KitToolBatchGroup>()
        val pending = mutableListOf<KitToolInvocation>()
        for (invocation in invocations) {
            if (parallelSafe(invocation)) {
                pending.add(invocation)
            } else {
                if (pending.isNotEmpty()) {
                    groups.add(KitToolBatchGroup.Parallel(pending.toList()))
                    pending.clear()
                }
                groups.add(KitToolBatchGroup.Serial(invocation))
            }
        }
        if (pending.isNotEmpty()) {
            groups.add(KitToolBatchGroup.Parallel(pending.toList()))
        }
        return groups
    }

    /**
     * 默认并行安全判定（名称启发——宿主有真实工具元数据时应注入自己的谓词）。
     *
     * 安全面：code_read / code_grep / code_glob / code_todo / code_check /
     * task（子代理派发）/ mcp__ 前缀 / skill_ 前缀 / web_ 与 http_ 前缀。
     */
    fun defaultParallelSafe(invocation: KitToolInvocation): Boolean {
        val name = invocation.toolName
        return when {
            name.startsWith("mcp__") -> true
            name.startsWith("skill_") -> true
            name.startsWith("web_") || name.startsWith("http_") -> true
            name == "task" -> true
            name.startsWith("code_read") ||
                name.startsWith("code_grep") ||
                name.startsWith("code_glob") ||
                name.startsWith("code_todo") ||
                name.startsWith("code_check") -> true
            else -> false
        }
    }
}

// ═══════════════════════ 执行钩子 ═══════════════════════

/**
 * 执行前钩子（参数归一等——返回可能修改过的调用）。
 *
 * 业界用法：provider 差异补丁（空串可选参数删除 / 大小写键归一 /
 * 参数重命名兼容层）。钩子按注册顺序串联，全部在 start 广播**之前**
 * 应用——观察者看到的参数即最终执行参数。
 */
fun interface KitToolExecutionHook {
    suspend fun intercept(invocation: KitToolInvocation): KitToolInvocation
}

/**
 * 工具参数归一器（业界顽疾补丁集）。
 */
object KitToolInputNormalizer {

    /**
     * 删除值为空串的可选键。
     *
     * 模型对可选参数经常吐 `""`（而非省略键）——对「缺省 = 引擎默认」
     * 语义的工具（如 task 的 model/sessionId）这是语义污染：空串会被
     * 当成显式指定。这里把空串值的键从参数对象中删除，恢复「未提供」。
     *
     * @param argumentsJson 参数 JSON 字符串
     * @param optionalKeys 可选键名清单（不在清单中的空串键保留——显式
     *   空串可能是合法值，如清空操作）
     * @return 归一后的 JSON 字符串（非法 JSON / 非对象 → 原样返回）
     */
    fun dropEmptyOptionalKeys(argumentsJson: String, optionalKeys: Set<String>): String {
        val parsed = KitJson.parseObject(argumentsJson) ?: return argumentsJson
        if (parsed.fields.isEmpty()) return argumentsJson
        val filtered = LinkedHashMap<String, KitJson.KitJsonValue>(parsed.fields)
        var changed = false
        for (key in optionalKeys) {
            val value = filtered[key]
            if (value is KitJson.KitJsonValue.Str && value.value.isEmpty()) {
                filtered.remove(key)
                changed = true
            }
        }
        if (!changed) return argumentsJson
        return KitJson.write(KitJson.KitJsonValue.Obj(filtered))
    }

    /**
     * 键名大小写归一（模型偶尔吐 PascalCase / 全大写——注册表口径归一）。
     *
     * @param lowerCaseKeys 目标小写键名清单（命中其它大小写形态即改名）
     */
    fun normalizeKeyCase(argumentsJson: String, lowerCaseKeys: Set<String>): String {
        val parsed = KitJson.parseObject(argumentsJson) ?: return argumentsJson
        if (parsed.fields.isEmpty()) return argumentsJson
        val target = lowerCaseKeys.associateBy { it.lowercase() }
        val normalized = LinkedHashMap<String, KitJson.KitJsonValue>()
        var changed = false
        for ((key, value) in parsed.fields) {
            val canonical = target[key.lowercase()]
            if (canonical != null && canonical != key) {
                normalized[canonical] = value
                changed = true
            } else {
                normalized[key] = value
            }
        }
        if (!changed) return argumentsJson
        return KitJson.write(KitJson.KitJsonValue.Obj(normalized))
    }
}

// ═══════════════════════ 输出预算 ═══════════════════════

/** 输出截断预算配置。 */
data class KitToolOutputBudgetConfig(
    val maxLines: Int = 2_000,
    val maxBytes: Int = 50 * 1024,
    val maxChars: Int = 100_000,
    val headRatio: Float = 0.7f
) {
    init {
        require(headRatio in 0.1f..0.9f) { "headRatio must be within 0.1..0.9" }
    }

    companion object {
        val DEFAULT = KitToolOutputBudgetConfig()
    }
}

/** 截断结果描述（事件/日志口径）。 */
data class KitToolTruncation(
    val applied: Boolean,
    val originalChars: Int,
    val keptChars: Int,
    val originalLines: Int,
    val keptLines: Int,
    val strategy: String?
)

/**
 * 工具输出头尾截断预算。
 *
 * 三重上限（行 / 字节 / 字符）任一超限即截断；保留策略为**头 70% +
 * 尾 30%**：头部覆盖用法与入口上下文，尾部覆盖退出码与结论——
 * 中段是最可牺牲的重复性内容。截断处插入统一的省略标记行。
 */
object KitToolOutputBudget {

    /** 省略标记（模型可读的截断说明）。 */
    const val OMIT_MARKER = "[... output truncated (kept head 70% / tail 30%) ...]"

    /** 是否需要截断（不产生新字符串——预算探测）。 */
    fun needsTruncation(text: String, config: KitToolOutputBudgetConfig): Boolean {
        if (text.length > config.maxChars) return true
        if (text.toByteArray(Charsets.UTF_8).size > config.maxBytes) return true
        if (lineCount(text) > config.maxLines) return true
        return false
    }

    /**
     * 截断（未超限 → 原样返回）。
     *
     * 三级收缩阶梯（确定性收敛）：
     * 1. 行粒度收缩：头尾按 [KitToolOutputBudgetConfig.headRatio] 切分，
     *    行数/字符/字节任一仍超限 → 头尾各砍 30% 再试（保底各 1 行）；
     * 2. 字符粒度钳制（单行巨输出——行收缩对它无效）；
     * 3. 字节粒度兜底（多字节文本字符入预算但字节仍超）：头尾各半收缩。
     */
    fun truncate(text: String, config: KitToolOutputBudgetConfig = KitToolOutputBudgetConfig.DEFAULT): String {
        if (!needsTruncation(text, config)) return text
        val lines = text.split('\n')
        var headCount = (lines.size * config.headRatio).toInt().coerceAtLeast(1).coerceAtMost(lines.size)
        var tailCount = (lines.size - headCount).coerceAtLeast(0)
        var candidate = joinHeadTail(lines, headCount, tailCount)
        while (exceedsAny(candidate, config) && headCount + tailCount > 2) {
            val newHead = (headCount * 0.7f).toInt().coerceAtLeast(1)
            val newTail = (tailCount * 0.7f).toInt().coerceAtLeast(1)
            if (newHead == headCount && newTail == tailCount) break
            headCount = newHead
            tailCount = newTail
            candidate = joinHeadTail(lines, headCount, tailCount)
        }
        if (exceedsAny(candidate, config)) {
            val headBudget = (config.maxChars * config.headRatio).toInt().coerceAtLeast(1)
            val tailBudget = (config.maxChars - headBudget).coerceAtLeast(1)
            candidate = text.take(headBudget) + "\n" + OMIT_MARKER + "\n" + text.takeLast(tailBudget)
        }
        while (candidate.toByteArray(Charsets.UTF_8).size > config.maxBytes && candidate.length > 16) {
            val half = candidate.length / 2
            candidate = candidate.take(half / 2) + "\n" + OMIT_MARKER + "\n" + candidate.takeLast(half / 2)
        }
        return candidate
    }

    /** 行粒度的头尾拼接（重叠防护：尾起点不早于头终点）。 */
    private fun joinHeadTail(lines: List<String>, headCount: Int, tailCount: Int): String {
        val safeHead = headCount.coerceIn(0, lines.size)
        val head = lines.subList(0, safeHead).joinToString("\n")
        val tailStart = (lines.size - tailCount.coerceAtLeast(0)).coerceAtLeast(safeHead).coerceAtMost(lines.size)
        val tail = lines.subList(tailStart, lines.size).joinToString("\n")
        if (tail.isEmpty()) {
            return head + "\n" + OMIT_MARKER
        }
        return head + "\n" + OMIT_MARKER + "\n" + tail
    }

    private fun exceedsAny(text: String, config: KitToolOutputBudgetConfig): Boolean =
        lineCount(text) > config.maxLines ||
            text.length > config.maxChars ||
            text.toByteArray(Charsets.UTF_8).size > config.maxBytes

    /** 截断描述（事件透传用）。 */
    fun describe(text: String, config: KitToolOutputBudgetConfig = KitToolOutputBudgetConfig.DEFAULT): KitToolTruncation {
        val originalLines = lineCount(text)
        val originalChars = text.length
        val result = truncate(text, config)
        return KitToolTruncation(
            applied = result != text,
            originalChars = originalChars,
            keptChars = result.length,
            originalLines = originalLines,
            keptLines = lineCount(result),
            strategy = if (result != text) "head-tail-split" else null
        )
    }

    internal fun lineCount(text: String): Int {
        if (text.isEmpty()) return 0
        var count = 1
        for (c in text) {
            if (c == '\n') count += 1
        }
        return count
    }
}

// ═══════════════════════ 批执行 ═══════════════════════

/** 单调用执行结果（结果数组元素——源序）。 */
data class KitToolSingleResult(
    val invocation: KitToolInvocation,
    val exit: KitToolExitKind,
    val output: String,
    val fullOutput: String,
    val failureMessage: String?,
    val durationMs: Long
) {
    val succeeded: Boolean get() = exit == KitToolExitKind.Completed
}

/** 批执行汇总。 */
data class KitToolBatchResult(
    val results: List<KitToolSingleResult>,
    val completedCount: Int,
    val failedCount: Int,
    val declinedCount: Int,
    val interruptedCount: Int,
    val totalDurationMs: Long
) {
    val allSettled: Boolean get() = results.all { it.exit !is KitToolExitKind.Failed }
    val anyDeclined: Boolean get() = results.any { it.exit == KitToolExitKind.Declined }
}

/** 批执行生命周期监听（事件保序：start 顺序广播；输出/进度/完成实时）。 */
interface KitToolBatchListener {
    suspend fun onStarted(invocation: KitToolInvocation)

    suspend fun onOutput(invocation: KitToolInvocation, chunk: String)

    suspend fun onProgress(invocation: KitToolInvocation, percent: Float?, message: String?)

    suspend fun onCompleted(invocation: KitToolInvocation, result: KitToolSingleResult)

    /** 静默监听（不关心过程的调用方）。 */
    companion object {
        val Silent: KitToolBatchListener = object : KitToolBatchListener {
            override suspend fun onStarted(invocation: KitToolInvocation) = Unit
            override suspend fun onOutput(invocation: KitToolInvocation, chunk: String) = Unit
            override suspend fun onProgress(invocation: KitToolInvocation, percent: Float?, message: String?) = Unit
            override suspend fun onCompleted(invocation: KitToolInvocation, result: KitToolSingleResult) = Unit
        }
    }
}

/**
 * 工具批执行器。
 *
 * 执行流程：
 * 1. [KitToolBatchPlanner.plan] 规划批组（谓词注入或默认启发）；
 * 2. 钩子链逐调用应用（start 广播前）；
 * 3. 并行组：start 顺序广播 → 并发执行（[Config.parallelism] 钳制）；
 *    串行单元：完整生命周期后进下一条；
 * 4. 每调用：输出流收集 → [KitToolOutputBudget] 截断 → 四型出口；
 * 5. 结果数组源序返回 + 汇总计数。
 *
 * 异常口径：
 * - 单调用异常 → [KitToolExitKind.Failed]（不拖垮同批其它调用）；
 * - 批取消（外层协程取消）→ [KitToolExitKind.Interrupted] 并向上传播；
 * - [KitToolRunner] 未知名等执行错误 → 统一 Failed 文案。
 */
class KitToolExecutor(
    private val runner: KitToolRunner,
    private val logger: KitLogger = KitLogger.Silent,
    private val config: Config = Config(),
    private val hooks: List<KitToolExecutionHook> = emptyList()
) {

    /** 执行器配置。 */
    data class Config(
        val perCallTimeoutMs: Long = 120_000L,
        val parallelism: Int = 4,
        val outputBudget: KitToolOutputBudgetConfig = KitToolOutputBudgetConfig.DEFAULT
    ) {
        init {
            require(parallelism >= 1) { "parallelism must be >= 1" }
            require(perCallTimeoutMs > 0) { "perCallTimeoutMs must be > 0" }
        }
    }

    /**
     * 执行一批调用（默认并行安全谓词）。
     */
    suspend fun executeBatch(
        invocations: List<KitToolInvocation>,
        listener: KitToolBatchListener = KitToolBatchListener.Silent
    ): KitToolBatchResult = executeBatch(invocations, listener) { invocation ->
        KitToolBatchPlanner.defaultParallelSafe(invocation)
    }

    /**
     * 执行一批调用（注入并行安全谓词）。
     */
    suspend fun executeBatch(
        invocations: List<KitToolInvocation>,
        listener: KitToolBatchListener = KitToolBatchListener.Silent,
        parallelSafe: (KitToolInvocation) -> Boolean
    ): KitToolBatchResult = coroutineScope {
        val startedAt = System.currentTimeMillis()
        if (invocations.isEmpty()) {
            return@coroutineScope KitToolBatchResult(
                results = emptyList(),
                completedCount = 0,
                failedCount = 0,
                declinedCount = 0,
                interruptedCount = 0,
                totalDurationMs = 0L
            )
        }
        // 钩子链（start 广播前应用——观察者看到的就是最终参数）
        val effective = invocations.map { invocation ->
            var current = invocation
            for (hook in hooks) {
                current = hook.intercept(current)
            }
            current
        }
        val groups = KitToolBatchPlanner.plan(effective, parallelSafe)
        val results = mutableListOf<KitToolSingleResult>()
        for (group in groups) {
            when (group) {
                is KitToolBatchGroup.Parallel -> {
                    results.addAll(runParallelGroup(group, listener))
                }
                is KitToolBatchGroup.Serial -> {
                    listener.onStarted(group.call)
                    results.add(runSingle(group.call, listener))
                }
            }
        }
        val ordered = effective.map { invocation ->
            results.firstOrNull { it.invocation.callId == invocation.callId }
                ?: settlementFor(invocation)
        }
        KitToolBatchResult(
            results = ordered,
            completedCount = ordered.count { it.exit == KitToolExitKind.Completed },
            failedCount = ordered.count { it.exit is KitToolExitKind.Failed },
            declinedCount = ordered.count { it.exit == KitToolExitKind.Declined },
            interruptedCount = ordered.count { it.exit == KitToolExitKind.Interrupted },
            totalDurationMs = System.currentTimeMillis() - startedAt
        )
    }

    // ═══════════════════ 并行组执行 ═══════════════════

    private suspend fun runParallelGroup(
        group: KitToolBatchGroup.Parallel,
        listener: KitToolBatchListener
    ): List<KitToolSingleResult> = coroutineScope {
        // start 顺序广播：每条广播完成后才启动下一条执行——源序稳定
        group.calls.forEach { invocation -> listener.onStarted(invocation) }
        val semaphore = Semaphore(config.parallelism)
        val settled = mutableListOf<KitToolSingleResult>()
        group.calls.map { invocation ->
            async {
                semaphore.withPermit { runSingle(invocation, listener) }
            }
        }.forEach { deferred ->
            val result = deferred.await()
            settled.add(result)
        }
        // 结果按源序重排（settled 为完成序）
        group.calls.map { invocation ->
            settled.firstOrNull { it.invocation.callId == invocation.callId }
                ?: settlementFor(invocation)
        }
    }

    // ═══════════════════ 单调用执行 ═══════════════════

    private suspend fun runSingle(
        invocation: KitToolInvocation,
        listener: KitToolBatchListener
    ): KitToolSingleResult {
        val startedAt = System.currentTimeMillis()
        if (invocation.providerExecuted) {
            // provider 托管执行：本地位只做结果记账
            val result = KitToolSingleResult(
                invocation = invocation,
                exit = KitToolExitKind.Completed,
                output = "",
                fullOutput = "",
                failureMessage = null,
                durationMs = 0L
            )
            listener.onCompleted(invocation, result)
            return result
        }
        val collected = StringBuilder()
        var completeOutput: String? = null
        var errorMessage: String? = null
        try {
            val timedOut = withTimeoutOrNull(config.perCallTimeoutMs) {
                runner.executeStream(invocation.toolName, invocation.arguments).collect { event ->
                    when (event) {
                        is KitToolStreamEvent.Output -> {
                            collected.append(event.chunk)
                            listener.onOutput(invocation, event.chunk)
                        }
                        is KitToolStreamEvent.Progress -> {
                            listener.onProgress(invocation, event.percent, event.message)
                        }
                        is KitToolStreamEvent.Complete -> {
                            completeOutput = event.output
                        }
                        is KitToolStreamEvent.Error -> {
                            errorMessage = event.message
                        }
                    }
                }
                false
            }
            if (timedOut == null) {
                val result = failureResult(
                    invocation,
                    "tool execution timed out after ${config.perCallTimeoutMs}ms",
                    startedAt,
                    collected.toString()
                )
                listener.onCompleted(invocation, result)
                return result
            }
        } catch (cancelled: CancellationException) {
            val result = KitToolSingleResult(
                invocation = invocation,
                exit = KitToolExitKind.Interrupted,
                output = "",
                fullOutput = collected.toString(),
                failureMessage = "Tool execution interrupted",
                durationMs = System.currentTimeMillis() - startedAt
            )
            listener.onCompleted(invocation, result)
            throw cancelled
        } catch (error: Throwable) {
            val result = failureResult(
                invocation,
                error.message ?: error.javaClass.simpleName,
                startedAt,
                collected.toString()
            )
            listener.onCompleted(invocation, result)
            return result
        }
        val failure = errorMessage
        val rawOutput = completeOutput ?: collected.toString()
        val result = if (failure != null) {
            failureResult(invocation, failure, startedAt, rawOutput)
        } else {
            val truncated = KitToolOutputBudget.truncate(rawOutput, config.outputBudget)
            KitToolSingleResult(
                invocation = invocation,
                exit = KitToolExitKind.Completed,
                output = truncated,
                fullOutput = rawOutput,
                failureMessage = null,
                durationMs = System.currentTimeMillis() - startedAt
            )
        }
        listener.onCompleted(invocation, result)
        return result
    }

    private fun failureResult(
        invocation: KitToolInvocation,
        message: String,
        startedAt: Long,
        rawOutput: String
    ): KitToolSingleResult {
        logger.warn(TAG, "tool failed: ${invocation.toolName}#${invocation.callId} — $message")
        return KitToolSingleResult(
            invocation = invocation,
            exit = KitToolExitKind.Failed(message),
            output = KitToolOutputBudget.truncate(rawOutput, config.outputBudget),
            fullOutput = rawOutput,
            failureMessage = message,
            durationMs = System.currentTimeMillis() - startedAt
        )
    }

    /** 未结算调用的合成失败（流中断 / 解析器半截产物）。 */
    private fun settlementFor(invocation: KitToolInvocation): KitToolSingleResult =
        KitToolSingleResult(
            invocation = invocation,
            exit = KitToolExitKind.Failed(KitToolSettlement.RESULT_MISSING),
            output = "",
            fullOutput = "",
            failureMessage = KitToolSettlement.RESULT_MISSING,
            durationMs = 0L
        )

    private companion object {
        const val TAG = "KitToolExecutor"
    }
}

// ═══════════════════════ 结算 ═══════════════════════

/**
 * 工具结算器（模型声明 vs 实际执行的对账）。
 *
 * 场景：provider 流中断后，assistant 消息里已登记了 N 个 tool_call，
 * 但只有 M < N 个真正执行完。**悬挂引用必须补合成失败**——否则下一
 * 轮请求的消息序列里 tool 角色缺口，多数 provider 直接 4xx。
 */
object KitToolSettlement {

    /** 未被执行调用的统一结算文案（业界口径原文）。 */
    const val RESULT_MISSING = "Provider did not return a tool result"

    /**
     * 对账：对模型声明的调用 id 集合中未出现在执行结果里的，
     * 生成合成失败结果。
     *
     * @param declaredCallIds 模型声明的全部调用 id
     * @param settled 已结算结果（含失败/拒绝/中断）
     * @return 补齐后的完整结算清单（原结果 + 合成失败，源序由调用方自理）
     */
    fun settleMissing(
        declaredCallIds: List<String>,
        settled: List<KitToolSingleResult>
    ): List<KitToolSingleResult> {
        val settledIds = settled.map { it.invocation.callId }.toSet()
        val missing = declaredCallIds.filter { it !in settledIds }
        if (missing.isEmpty()) return settled
        val synthetic = missing.map { callId ->
            KitToolSingleResult(
                invocation = KitToolInvocation(
                    callId = callId,
                    toolName = "(unsettled)",
                    arguments = ""
                ),
                exit = KitToolExitKind.Failed(RESULT_MISSING),
                output = "",
                fullOutput = "",
                failureMessage = RESULT_MISSING,
                durationMs = 0L
            )
        }
        return settled + synthetic
    }

    /**
     * 批结算消息渲染（把结果清单渲染为 ToolResult 消息序列——
     * 引擎回合拼接直用）。
     */
    fun renderMessages(results: List<KitToolSingleResult>): List<KitMessage.ToolResult> =
        results.map { result ->
            val body = when (val exit = result.exit) {
                is KitToolExitKind.Failed -> "Error: ${exit.message}"
                KitToolExitKind.Declined -> "The user declined this tool call"
                KitToolExitKind.Interrupted -> "Tool execution interrupted"
                KitToolExitKind.Completed -> result.output
            }
            KitMessage.ToolResult(
                toolCallId = result.invocation.callId,
                content = body
            )
        }
}
