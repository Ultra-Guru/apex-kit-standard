package com.apex.agentkit.loop

import kotlinx.coroutines.delay

/**
 * # Kit Run Support — 回合循环的运行支撑结构
 *
 * 从引擎按职责缝拆出的纯数据/纯行为小结构：
 *
 * - [RunReportBuilder]：运行报告可变累计，收官快照给 Complete 事件；
 * - [KitToolCallAccumulator]：流式工具调用分片累加（并行分片 index 键
 *   策略——首片携带 id+index，续片只携带 index）；
 * - [RepeatGuard]：同指纹重复调用守卫（3 次告警 / 4 次强收敛）；
 * - [ToolOutputTruncator]：工具输出头尾截断（行/字节/字符三重预算）。
 */
internal class RunReportBuilder {
    var turns: Int = 0
    var toolCalls: Int = 0
    var permissionAsks: Int = 0
    var permissionDenied: Int = 0
    var subAgents: Int = 0
    var promptTokens: Int = 0
    var completionTokens: Int = 0
    var errorMessage: String? = null
}

/**
 * 流式工具调用累加器（分片键策略：首片携带 id+index，续片只携带
 * index——index 为稳定键，id 兜底）。
 */
internal class KitToolCallAccumulator(initialId: String, initialName: String) {
    var id: String = initialId
    var name: String = initialName
    private val args = StringBuilder()

    fun append(namePart: String?, argumentsPart: String?) {
        if (!namePart.isNullOrBlank()) name = namePart
        argumentsPart?.let { args.append(it) }
    }

    fun build(): KitToolCall = KitToolCall(id = id, name = name, arguments = args.toString())
}

/** 重复调用守卫（同指纹 3 次告警 / 4 次强收敛）。 */
internal class RepeatGuard {
    private val counts = mutableMapOf<String, Int>()

    fun record(fingerprint: String) {
        counts[fingerprint] = (counts[fingerprint] ?: 0) + 1
    }

    fun warningFor(): String? {
        val worst = counts.entries.firstOrNull { it.value == WARN_THRESHOLD } ?: return null
        return KitPrompts.repetitiveCallWarning(
            worst.value, worst.key.substringBefore("|")
        )
    }

    fun shouldForceFinal(): Boolean =
        counts.values.any { it >= FORCE_THRESHOLD }

    companion object {
        private const val WARN_THRESHOLD = 3
        private const val FORCE_THRESHOLD = 4
    }
}

/**
 * 工具输出截断器（头尾保留策略）。
 *
 * 预算三重口径（同时生效，最紧的赢）：
 * - **字符预算**（[charBudget]，引擎工具输出预算）；
 * - **行数上限**（[maxLines]，默认 2000——超长日志/转储的首选防线）；
 * - **字节上限**（[maxBytes]，默认 50KiB——二进制/超宽行防线）。
 *
 * 截断策略：**头 70% + 尾 30%**——工具输出里「报错在尾部、上下文在
 * 头部」是常态（编译错误、测试失败摘要），纯头部截断会把最关键的
 * 尾部信号丢掉。中缝插入一行截断说明（含原始规模统计，模型可据此
 * 换更窄的查询）。
 */
internal class ToolOutputTruncator(
    private val charBudget: Int,
    private val maxLines: Int = DEFAULT_MAX_LINES,
    private val maxBytes: Int = DEFAULT_MAX_BYTES
) {

    /** 截断（超预算才动刀；未超返回原文）。 */
    fun truncate(output: String): String {
        val overChars = output.length > charBudget
        val lineCount = output.lineSequence().count()
        val overLines = lineCount > maxLines
        val overBytes = output.toByteArray(Charsets.UTF_8).size > maxBytes
        if (!overChars && !overLines && !overBytes) return output

        val totalBytes = output.toByteArray(Charsets.UTF_8).size
        val stats = "output truncated: ${output.length} chars / $lineCount lines / $totalBytes bytes"

        // ── 纯行数超限（短行大列表）：按行头尾保留 ──
        if (overLines && !overChars && !overBytes) {
            val all = output.lines()
            val headLines = (maxLines * 7) / 10
            val tailLines = (maxLines - headLines).coerceAtLeast(1)
            val head = all.take(headLines).joinToString("\n")
            val tail = all.takeLast(tailLines).joinToString("\n")
            return "$head\n…[$stats — head+tail kept within $maxLines lines]\n$tail"
        }

        // ── 字符/字节口径：头尾字符保留 ──
        val keep = charBudget.coerceAtLeast(MIN_KEEP_CHARS).coerceAtMost(output.length)
        if (keep >= output.length) {
            // 行数与字节均已达标（此时只可能 overBytes 且字符窗覆盖全文）——直接返回
            return output
        }
        val headLen = (keep * 7) / 10
        val tailLen = keep - headLen
        val head = output.take(headLen)
        val tail = output.takeLast(tailLen)
        return "$head\n…[$stats — head+tail kept within $keep chars]\n$tail"
    }

    companion object {
        const val DEFAULT_MAX_LINES = 2_000
        const val DEFAULT_MAX_BYTES = 50 * 1024

        /** 保底保留窗（防极端小预算把输出截成空）。 */
        private const val MIN_KEEP_CHARS = 256
    }
}
