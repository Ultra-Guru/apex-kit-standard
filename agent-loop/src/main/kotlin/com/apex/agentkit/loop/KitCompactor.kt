package com.apex.agentkit.loop

/**
 * # Kit Compactor — 任务循环的上下文压缩器
 *
 * 长任务跑到中途，会话 token 逼近窗口上限时，把「旧消息段」摘述成一条
 * System 摘要消息，保留近期消息不压缩——会话可以继续跑而不爆窗。
 *
 * ## 触发与产出
 *
 * - 触发（自动）：`estimatedTokens > maxContextTokens × threshold`；
 * - 触发（溢出恢复）：provider 明示上下文超限 → [compactForOverflow]
 *   按 shrink 阶梯（0.7 / 0.5 / 0.35）瞄准**拒绝请求的成比例规模**压缩；
 * - 产出 [CompactionReport]：替换后的会话消息 + 摘要文本 + 前后 token
 *   估算（引擎据此发射 ContextCompressed 事件 + 替换会话）。
 *
 * ## 摘要质量链
 *
 * 1. **结构化模板**（七节：Objective / Requirements / Decisions /
 *    Work State / Next Move / Relevant Files / Important Context）——
 *    [KitPrompts.compactionSummary]；
 * 2. **格式纠偏（NUDGE）**：首次摘要缺模板标题 → 追问一次；仍不合规
 *    → 滑窗降级（宁可骨架摘要也不要自由发挥的散文）；
 * 3. **增量合并**：会话中已有旧摘要时作为 priorSummary 传入——新摘要
 *    在旧摘要基础上合并演进而非每次从零重述；
 * 4. **继续指令**：压缩成功后在替换消息流末尾追加 compaction_continue；
 * 5. LLM 失败（网络/超时）→ **滑窗降级**：丢最旧消息段（保留工具结果
 *    首行 + 用户消息首行作为骨架摘要）；
 * 6. 摘要为空 → 保持原会话（不压缩比坏压缩好）。
 *
 * ## 保留窗（keep window）
 *
 * 压缩段 = `[0, boundary)`，边界取**消息条数窗与 token 窗的较大者**
 * （近期上下文按 token 计量更符合窗口语义——消息条数在小消息长会话里
 * 保太少、大消息短会话里保太多），且**尾部对齐工具结果**：边界切在
 * 「assistant(toolCalls) 与其 tool results 之间」时向后扩展——不产生
 * 悬挂 toolCall（OpenAI 协议会 400）。
 */
class KitCompactor(
    private val modelClient: KitModelClient,
    private val logger: KitLogger = KitLogger.Silent,
    private val compactionContextTag: String = "agent_compaction"
) {

    /** 压缩产出（引擎消费）。 */
    data class CompactionReport(
        /** 替换后的完整消息列表（首条 = System 摘要；空 = 放弃压缩）。 */
        val replacement: List<KitMessage>,
        val summary: String,
        val beforeTokens: Int,
        val afterTokens: Int,
        val messagesRemoved: Int,
        val strategy: String
    ) {
        /** 是否实际发生压缩。 */
        val effective: Boolean get() = messagesRemoved > 0 && replacement.isNotEmpty()
    }

    /**
     * 评估并执行一次压缩（阈值触发路径）。
     *
     * @param messages 当前会话消息（不含本轮 system 头）
     * @param estimatedTokens 当前估算（引擎口径）
     * @param maxContextTokens 上下文窗口预算
     * @param threshold 触发阈值（0..1）
     * @param preserveRecentMessages 保留最近 N 条不压缩（下限）
     * @param preserveRecentTokens 保留最近约 N token 不压缩（目标窗）
     */
    suspend fun compactIfNeeded(
        messages: List<KitMessage>,
        estimatedTokens: Int,
        maxContextTokens: Int,
        threshold: Float,
        preserveRecentMessages: Int,
        preserveRecentTokens: Int
    ): CompactionReport? {
        if (messages.isEmpty()) return null
        val limit = (maxContextTokens * threshold.coerceIn(0.25f, 0.95f)).toInt()
        if (estimatedTokens <= limit) return null
        return compact(
            messages = messages,
            preserveRecentMessages = preserveRecentMessages,
            preserveRecentTokens = preserveRecentTokens
        )
    }

    /**
     * 溢出恢复压缩（provider 拒绝请求明示 too long 时）。
     *
     * shrink 阶梯：第 1 次瞄准当前估算的 70%、第 2 次 50%、第 3 次 35%
     * ——压缩边界更激进（保留窗缩小），确保重试请求能装进窗口。
     *
     * @param attempt 溢出恢复序号（0-based）
     * @return 压缩报告；无可压缩段（保留窗已最小）返回 null
     */
    suspend fun compactForOverflow(
        messages: List<KitMessage>,
        attempt: Int,
        preserveRecentMessages: Int,
        preserveRecentTokens: Int
    ): CompactionReport? {
        if (messages.isEmpty()) return null
        val shrinkFactor = SHRINK_STEPS.getOrElse(attempt) { SHRINK_STEPS.last() }
        // 保留窗按 shrink 因子收窄：0.7 → 70% 的 token 窗与至少 2 条消息
        val shrunkTokens = (preserveRecentTokens * shrinkFactor).toInt()
        val shrunkMessages = (preserveRecentMessages * shrinkFactor).toInt().coerceAtLeast(2)
        return compact(
            messages = messages,
            preserveRecentMessages = shrunkMessages,
            preserveRecentTokens = shrunkTokens
        )
    }

    /** 执行一次压缩（边界计算 + 摘要链 + 替换流构建）。 */
    private suspend fun compact(
        messages: List<KitMessage>,
        preserveRecentMessages: Int,
        preserveRecentTokens: Int
    ): CompactionReport? {
        val boundary = keepBoundary(messages, preserveRecentMessages, preserveRecentTokens)
        if (boundary <= 0) return null // 保底：没有可压缩的旧段

        val oldSegment = messages.subList(0, boundary)
        val recentSegment = messages.subList(boundary, messages.size)

        // 增量摘要：会话里已有旧摘要 → 作为 priorSummary 传入
        val priorSummary = extractPriorSummary(messages)

        val summary = summarize(oldSegment, priorSummary)
            ?: slidingWindowDigest(oldSegment)
            ?: return null

        val replacement = buildList {
            add(KitMessage.System(SUMMARY_HEADER + summary))
            addAll(recentSegment)
            // 压缩后继续指令（注入在消息流最末）
            add(KitMessage.System(KitPrompts.COMPACTION_CONTINUE))
        }
        val before = estimate(messages)
        val after = estimate(replacement)
        val strategy = if (summary.startsWith(LLM_SUMMARY_MARKER)) "LLM_SUMMARY" else "SLIDING_WINDOW"
        return CompactionReport(
            replacement = replacement,
            summary = summary.removePrefix(LLM_SUMMARY_MARKER),
            beforeTokens = before,
            afterTokens = after,
            messagesRemoved = oldSegment.size - 1,
            strategy = strategy
        )
    }

    // ── 保留窗边界（条数窗 ∪ token 窗，尾部对齐工具结果）──────────────

    /**
     * 保留窗边界：从尾部累计 token 达 [preserveRecentTokens] 或消息数达
     * [preserveRecentMessages]（取较大的边界 = 保留更多），再对齐工具
     * 结果边界。
     */
    internal fun keepBoundary(
        messages: List<KitMessage>,
        preserveRecentMessages: Int,
        preserveRecentTokens: Int
    ): Int {
        val countBoundary = messages.size - preserveRecentMessages.coerceAtLeast(2)
        val tokenBoundary = run {
            var acc = 0
            var i = messages.size
            while (i > 0 && acc < preserveRecentTokens.coerceAtLeast(0)) {
                i--
                acc += estimate(listOf(messages[i]))
            }
            i
        }
        val raw = maxOf(countBoundary, tokenBoundary).coerceIn(0, messages.size)
        return alignBoundary(messages, raw)
    }

    /** 边界对齐：不把 assistant(toolCalls) 与其 tool results 切开。 */
    internal fun alignBoundary(messages: List<KitMessage>, rawBoundary: Int): Int {
        if (rawBoundary >= messages.size) return messages.size
        var boundary = rawBoundary.coerceAtLeast(0)
        while (boundary < messages.size) {
            val prev = messages.getOrNull(boundary - 1)
            val at = messages.getOrNull(boundary)
            val pendingCalls = (prev as? KitMessage.Assistant)?.toolCalls?.size ?: 0
            val nextIsResult = at is KitMessage.ToolResult
            if (pendingCalls > 0 && nextIsResult) boundary++ else break
        }
        return boundary
    }

    // ── 摘要链 ───────────────────────────────────────────────

    /** LLM 摘述（+ NUDGE 纠偏一次；失败/空返回 null → 降级）。 */
    private suspend fun summarize(segment: List<KitMessage>, priorSummary: String?): String? {
        if (segment.isEmpty()) return null
        return try {
            val transcript = renderTranscript(segment)
            val systemPrompt = KitPrompts.compactionSummary(
                "Compress the following coding-session transcript " +
                    "(${segment.size} messages).",
                priorSummary
            )
            val first = requestSummary(systemPrompt, transcript)
            var summary = first
            // NUDGE：缺模板标题 → 追问一次（仍不合规 → 走滑窗降级）
            if (summary != null && !followsTemplate(summary)) {
                val nudged = requestSummary(
                    KitPrompts.compactionNudge(),
                    transcript
                )
                if (nudged != null && followsTemplate(nudged)) {
                    summary = nudged
                } else if (nudged != null && !followsTemplate(nudged)) {
                    // 两次都不合规：取更合规的一次，否则放弃 LLM 摘要
                    summary = null
                }
            }
            summary?.let { LLM_SUMMARY_MARKER + it }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            logger.warn(TAG, "Compaction summary request failed: ${t.message}")
            null
        }
    }

    /** 摘要请求（低温；最小长度门槛过滤空回复）。 */
    private suspend fun requestSummary(systemPrompt: String, transcript: String): String? {
        val response = modelClient.chat(
            contextTag = compactionContextTag,
            messages = listOf(
                KitMessage.System(systemPrompt),
                KitMessage.User(transcript)
            ),
            temperature = 0.2f,
            maxTokens = SUMMARY_MAX_TOKENS
        )
        return response.content?.trim()?.takeIf { it.length >= MIN_SUMMARY_CHARS }
    }

    /** 模板合规检查（至少含 Objective 与 Work State 两个标题）。 */
    private fun followsTemplate(summary: String): Boolean {
        val hasObjective = summary.contains("## Objective", ignoreCase = true) ||
            summary.contains("Objective:", ignoreCase = true)
        val hasWorkState = summary.contains("## Work State", ignoreCase = true) ||
            summary.contains("Work State:", ignoreCase = true) ||
            summary.contains("### Completed", ignoreCase = true) ||
            summary.contains("### Active", ignoreCase = true)
        return hasObjective && hasWorkState
    }

    /** 转录渲染（工具结果截 3 行，控制摘要请求体积）。 */
    private fun renderTranscript(segment: List<KitMessage>): String = buildString {
        segment.forEach { msg ->
            when (msg) {
                is KitMessage.User -> appendLine("USER: " + firstLines(msg.content, 8))
                is KitMessage.Assistant -> {
                    appendLine("ASSISTANT: " + firstLines(msg.content, 6))
                    msg.toolCalls.forEach { tc ->
                        appendLine("ASSISTANT calls ${tcName(tc.name)}(${firstLines(tc.arguments, 2)})")
                    }
                }
                is KitMessage.ToolResult -> appendLine("TOOL ${msg.toolCallId}: " + firstLines(msg.content, 3))
                is KitMessage.System -> appendLine("SYSTEM: " + firstLines(msg.content, 4))
            }
        }
    }.toString()

    /** 滑窗降级摘要（骨架式；LLM 失败时兜底）。 */
    private fun slidingWindowDigest(segment: List<KitMessage>): String? {
        if (segment.isEmpty()) return null
        val lines = mutableListOf<String>()
        var toolResultsKept = 0
        for (msg in segment) {
            when (msg) {
                is KitMessage.User -> lines += "user: " + firstLines(msg.content, 1)
                is KitMessage.Assistant -> {
                    if (msg.content.isNotBlank()) lines += "assistant: " + firstLines(msg.content, 2)
                    msg.toolCalls.forEach { lines += "assistant → ${tcName(it.name)}" }
                }
                is KitMessage.ToolResult -> {
                    if (toolResultsKept < MAX_DIGEST_RESULTS) {
                        lines += "result: " + firstLines(msg.content, 1)
                        toolResultsKept++
                    }
                }
                is KitMessage.System -> Unit // 系统注入不进摘要骨架
            }
        }
        if (lines.isEmpty()) return null
        return buildString {
            appendLine("[sliding-window digest of ${segment.size} older messages]")
            lines.forEach { appendLine("- $it") }
        }.trim()
    }

    private fun tcName(name: String): String = KitToolSurface.resolveRegistryId(name)

    /** 首行截取（n 行 + "…" 标记）。 */
    private fun firstLines(text: String, lines: Int): String {
        val seq = text.lineSequence().take(lines + 1).toList()
        return if (seq.size <= lines) text.trim() else seq.take(lines).joinToString(" ⏎ ") + " …"
    }

    /**
     * 旧摘要提取（增量合并输入）：第一条以 "[SESSION SUMMARY" 开头的
     * System 消息，内容去掉头行。
     */
    internal fun extractPriorSummary(messages: List<KitMessage>): String? =
        messages.asSequence()
            .filterIsInstance<KitMessage.System>()
            .firstOrNull { it.content.startsWith(PRIOR_SUMMARY_PREFIX) }
            ?.content
            ?.substringAfter('\n', "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun estimate(messages: List<KitMessage>): Int = messages.sumOf { msg ->
        when (msg) {
            is KitMessage.User -> KitSession.defaultTokenEstimator(msg.content)
            is KitMessage.Assistant -> KitSession.defaultTokenEstimator(msg.content) +
                msg.toolCalls.sumOf { KitSession.defaultTokenEstimator(it.arguments) }
            is KitMessage.ToolResult -> (KitSession.defaultTokenEstimator(msg.content) + 1) / 2
            is KitMessage.System -> KitSession.defaultTokenEstimator(msg.content)
        }
    }

    companion object {
        private const val TAG = "KitCompactor"

        private const val SUMMARY_HEADER = "[SESSION SUMMARY — earlier context was compacted]\n"

        /** 旧摘要消息前缀（[extractPriorSummary] 的匹配锚点）。 */
        private const val PRIOR_SUMMARY_PREFIX = "[SESSION SUMMARY"

        /** LLM 摘述标记（区分 LLM_SUMMARY / SLIDING_WINDOW 策略归因）。 */
        private const val LLM_SUMMARY_MARKER = "@llm:"

        /** 摘要请求 maxTokens（结构化模板需要空间）。 */
        private const val SUMMARY_MAX_TOKENS = 1000

        private const val MIN_SUMMARY_CHARS = 80

        private const val MAX_DIGEST_RESULTS = 20

        /** 溢出恢复 shrink 阶梯（瞄准被拒请求规模的成比例份额）。 */
        internal val SHRINK_STEPS = listOf(0.7f, 0.5f, 0.35f)
    }
}
