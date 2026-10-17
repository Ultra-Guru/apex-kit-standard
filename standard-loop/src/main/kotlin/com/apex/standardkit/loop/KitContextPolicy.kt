package com.apex.standardkit.loop

/**
 * # Kit Context Policy — 上下文窗口策略子系统
 *
 * 标准任务循环的上下文治理组件化（引擎内已有同源实现，这里公开为
 * 库级原语 + 补齐成本核算）：
 *
 * 1. **预算规划**（[KitTokenBudgetPlanner]）：给定模型窗口与各组成
 *    （系统提示词 / 会话历史 / 工具定义 / 输出预留），计算历史可用
 *    预算与决策（OK / 触发压缩 / 溢出）；
 * 2. **压力分级**（[KitContextPressure]）：四级压力信号（常规 / 升高 /
 *    临界 / 溢出）——宿主 UI 的「上下文水位条」直用；
 * 3. **溢出收缩阶梯**（[KitOverflowShrinkLadder]）：provider 明示
 *    上下文超限后的目标保留率阶梯（1.0 → 0.7 → 0.5 → 0.35）——
 *    每级触发对应力度的压缩重试，三级仍溢出才诚实失败；
 * 4. **用量台账**（[KitUsageLedger]）：跨回合 token / 成本累计
 *    （输入价 / 输出价 / 缓存价分离计价），单回合记录 + 运行总计
 *    双口径；
 * 5. **成本费率**（[KitCostRates]）：模型维度费率注入（每百万 token
 *    单价）——台账的成本换算依据。
 */
// ═══════════════════════ 压力分级 ═══════════════════════

/** 上下文压力四级（水位信号）。 */
enum class KitContextPressure {
    /** < 70% —— 常态。 */
    NORMAL,

    /** 70% ~ 90% —— 升高（宿主可提示「会话较长」）。 */
    ELEVATED,

    /** 90% ~ 压缩触发线 —— 临界（即将压缩）。 */
    CRITICAL,

    /** ≥ 压缩触发线 —— 溢出区（本回合必须压缩 / 收缩）。 */
    OVERFLOW;

    companion object {
        /** 压力分级的统一入口（[ratio] = 已用 / 窗口）。 */
        fun of(ratio: Float, compactionTriggerRatio: Float): KitContextPressure = when {
            ratio >= compactionTriggerRatio -> OVERFLOW
            ratio >= 0.9f -> CRITICAL
            ratio >= 0.7f -> ELEVATED
            else -> NORMAL
        }
    }
}

// ═══════════════════════ 窗口策略 ═══════════════════════

/**
 * 上下文窗口策略参数。
 *
 * @param contextWindow 模型上下文窗口（token；null = 未知走保守默认）
 * @param outputReserveTokens 输出预留（生成侧保底——避免「写满即断」）
 * @param compactionTriggerRatio 压缩触发线（已用比例达到即触发）
 * @param safetyMarginRatio 安全边际（估算误差缓冲）
 */
data class KitContextWindowPolicy(
    val contextWindow: Int? = null,
    val outputReserveTokens: Int = 4_096,
    val compactionTriggerRatio: Float = 0.92f,
    val safetyMarginRatio: Float = 0.05f
) {
    /** 有效窗口（未知 → 保守回退 [FALLBACK_WINDOW]）。 */
    val effectiveWindow: Int
        get() = (contextWindow ?: FALLBACK_WINDOW).coerceAtLeast(MIN_WINDOW)

    /** 历史可用预算（窗口 − 输出预留 − 安全边际）。 */
    val historyBudget: Int
        get() = (effectiveWindow * (1f - safetyMarginRatio)).toInt() - outputReserveTokens

    companion object {
        /** 窗口未知时的保守回退（8k——最保守的主流口径）。 */
        const val FALLBACK_WINDOW = 8_192

        /** 最小窗口钳制（低于此值预算恒负，直接夹到可用下限）。 */
        const val MIN_WINDOW = 4_096
    }
}

// ═══════════════════════ 预算规划 ═══════════════════════

/** 预算决策（sealed 三型）。 */
sealed interface KitBudgetDecision {
    /** 预算充足——正常发请求。 */
    data object Ok : KitBudgetDecision

    /** 触发压缩（历史可用不足，但未硬溢出）。 */
    data class Compact(val deficitTokens: Int) : KitBudgetDecision

    /** 硬溢出（压缩也救不了——收缩阶梯或诚实失败）。 */
    data class Overflow(val deficitTokens: Int) : KitBudgetDecision
}

/**
 * token 预算规划器。
 *
 * 输入各组成的估算 token，输出「这轮请求能不能直接发」的决策。
 * 判定阶梯：
 * 1. 系统提示词 + 工具定义 + 输出预留已经吃掉全部窗口 → [KitBudgetDecision.Overflow]
 *    （结构性超限——历史再空也塞不下）；
 * 2. 历史 token 超过 [KitContextWindowPolicy.historyBudget] → [KitBudgetDecision.Compact]；
 * 3. 其余 → [KitBudgetDecision.Ok]。
 */
object KitTokenBudgetPlanner {

    /** 预算决策。 */
    fun decide(
        policy: KitContextWindowPolicy,
        systemPromptTokens: Int,
        toolDefinitionsTokens: Int,
        historyTokens: Int
    ): KitBudgetDecision {
        val fixed = systemPromptTokens + toolDefinitionsTokens + policy.outputReserveTokens
        val budget = policy.historyBudget
        if (budget <= 0) {
            return KitBudgetDecision.Overflow(deficitTokens = fixed - policy.effectiveWindow)
        }
        if (fixed >= policy.effectiveWindow) {
            return KitBudgetDecision.Overflow(
                deficitTokens = fixed - policy.effectiveWindow
            )
        }
        if (historyTokens > budget) {
            return KitBudgetDecision.Compact(
                deficitTokens = historyTokens - budget
            )
        }
        return KitBudgetDecision.Ok
    }

    /** 压力信号（水位 = 已用 / 窗口）。 */
    fun pressure(
        policy: KitContextWindowPolicy,
        systemPromptTokens: Int,
        toolDefinitionsTokens: Int,
        historyTokens: Int
    ): KitContextPressure {
        val used = systemPromptTokens + toolDefinitionsTokens + historyTokens
        val ratio = used.toFloat() / policy.effectiveWindow.toFloat()
        return KitContextPressure.of(ratio, policy.compactionTriggerRatio)
    }
}

// ═══════════════════════ 溢出收缩阶梯 ═══════════════════════

/**
 * 溢出收缩阶梯（压缩目标保留率序列）。
 *
 * 语义：provider 明示上下文超限后的恢复不是一步到位——先轻压
 * （保留 70%）、再中压（50%）、再重压（35%）。每级压缩后整轮重试，
 * 三级仍溢出则诚实失败（不再硬塞——半截上下文的回答比失败更糟）。
 */
object KitOverflowShrinkLadder {

    /** 保留率阶梯（1.0 = 原样；后续为各级目标保留率）。 */
    val LADDER: List<Float> = listOf(1.0f, 0.7f, 0.5f, 0.35f)

    /** 最大重试级数（阶梯长度 − 1——首级是原样不算重试）。 */
    const val MAX_RETRIES: Int = 3

    /**
     * 当前保留率的下一级（已在末级 → null = 诚实失败）。
     *
     * @param current 当前保留率（阶梯内任意值；未在阶梯中 → 归一到
     *   **不小于它的最小阶梯级**再进位；低于末级 → 视为耗尽）
     */
    fun next(current: Float): Float? {
        val at = if (current >= LADDER.first()) {
            0
        } else {
            LADDER.indexOfLast { it >= current }
        }
        if (at < 0) return null
        val nextIndex = at + 1
        if (nextIndex >= LADDER.size) return null
        return LADDER[nextIndex]
    }

    /** 目标保留率对应的目标 token 数（向上取整）。 */
    fun targetTokens(totalTokens: Int, retention: Float): Int =
        (totalTokens * retention).toInt().coerceAtLeast(0)

    /** 阶梯是否已耗尽（当前保留率已是末级）。 */
    fun exhausted(current: Float): Boolean = next(current) == null
}

// ═══════════════════════ 成本费率 ═══════════════════════

/**
 * 模型费率（每百万 token 单位成本）。
 *
 * @param inputCostPerMillion 输入单价
 * @param outputCostPerMillion 输出单价
 * @param cacheReadCostPerMillion 缓存读单价（未支持缓存计价 = 0）
 */
data class KitCostRates(
    val inputCostPerMillion: Double = 0.0,
    val outputCostPerMillion: Double = 0.0,
    val cacheReadCostPerMillion: Double = 0.0
) {
    companion object {
        /** 未配置费率（成本恒 0）。 */
        val Unknown = KitCostRates()
    }
}

// ═══════════════════════ 用量台账 ═══════════════════════

/** 单回合用量记录。 */
data class KitTurnUsage(
    val turn: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val cacheReadTokens: Int = 0,
    val costUsdMicros: Long
)

/** 台账汇总。 */
data class KitUsageTotals(
    val turns: Int,
    val promptTokens: Long,
    val completionTokens: Long,
    val cacheReadTokens: Long,
    val costUsdMicros: Long
) {
    val totalTokens: Long get() = promptTokens + completionTokens
}

/**
 * 跨回合用量台账（线程安全）。
 *
 * - 单回合以 [recordTurn] 记账（服务端真值帧——无真值时调用方自行
 *   估算后传入）；
 * - [totals] 运行总计 / [turnHistory] 逐回合明细；
 * - 成本按 [KitCostRates] 分离计价（输入 / 输出 / 缓存读），精度
 *   微美元（避免浮点累计漂移）。
 */
class KitUsageLedger(private val rates: KitCostRates = KitCostRates.Unknown) {

    private val lock = Any()
    private val history = mutableListOf<KitTurnUsage>()

    /** 记一回合（返回该回合成本，微美元）。 */
    fun recordTurn(
        turn: Int,
        promptTokens: Int,
        completionTokens: Int,
        cacheReadTokens: Int = 0
    ): Long {
        val cost = costMicros(promptTokens, completionTokens, cacheReadTokens)
        synchronized(lock) {
            history.add(
                KitTurnUsage(
                    turn = turn,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    cacheReadTokens = cacheReadTokens,
                    costUsdMicros = cost
                )
            )
        }
        return cost
    }

    /** 运行总计。 */
    fun totals(): KitUsageTotals = synchronized(lock) {
        KitUsageTotals(
            turns = history.size,
            promptTokens = history.sumOf { it.promptTokens.toLong() },
            completionTokens = history.sumOf { it.completionTokens.toLong() },
            cacheReadTokens = history.sumOf { it.cacheReadTokens.toLong() },
            costUsdMicros = history.sumOf { it.costUsdMicros }
        )
    }

    /** 逐回合明细快照。 */
    fun turnHistory(): List<KitTurnUsage> = synchronized(lock) {
        history.toList()
    }

    /** 成本换算（微美元；1e6 token 单位价 × token 数）。 */
    internal fun costMicros(
        promptTokens: Int,
        completionTokens: Int,
        cacheReadTokens: Int
    ): Long {
        val inputCost = promptTokens * rates.inputCostPerMillion / 1_000_000.0
        val outputCost = completionTokens * rates.outputCostPerMillion / 1_000_000.0
        val cacheCost = cacheReadTokens * rates.cacheReadCostPerMillion / 1_000_000.0
        return Math.round((inputCost + outputCost + cacheCost) * 1_000_000.0)
    }
}

// ═══════════════════════ 估算口径 ═══════════════════════

/**
 * token 估算口径工具（无词表时的业界通用启发式）。
 *
 * 与引擎内部估算保持同源：英文 ≈ 4 字符/token；CJK 按 1.5 字符/token
 * 折算（CJK 密度高时更准）；混合文本按两类字符分别累计。
 */
object KitTokenEstimates {

    /** 估算文本 token 数（混合 CJK/拉丁启发式）。 */
    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var asciiChars = 0
        var wideChars = 0
        for (c in text) {
            if (c.code < 0x2E80) {
                asciiChars += 1
            } else {
                wideChars += 1
            }
        }
        val asciiTokens = (asciiChars + 3) / 4
        val wideTokens = (wideChars + 1) / 2
        return asciiTokens + wideTokens
    }

    /** 工具定义的估算（名称+描述+schema 的打包口径）。 */
    fun estimateToolDefinition(definition: KitToolDefinition): Int =
        estimate(definition.name) + estimate(definition.description) +
            estimate(definition.parameters) + OVERHEAD_PER_TOOL

    /** 每条工具定义的协议开销（name 分隔符 / JSON 包装等）。 */
    const val OVERHEAD_PER_TOOL = 16

    /** 工具面整体估算。 */
    fun estimateToolSurface(definitions: List<KitToolDefinition>): Int =
        definitions.sumOf { estimateToolDefinition(it) } + SURFACE_OVERHEAD

    /** 工具面数组包装开销。 */
    const val SURFACE_OVERHEAD = 24
}
