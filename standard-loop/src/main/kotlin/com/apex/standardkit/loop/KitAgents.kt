package com.apex.standardkit.loop

/**
 * # Kit Agents — 画像目录（任务循环的「用工表」）
 *
 * 六个内置画像（对齐业界标准 Agent 工作流的角色分工）：
 *
 * | 画像 | 角色 | 工具面 | 典型用途 |
 * |---|---|---|---|
 * | [BUILD] 构建者 | 主代理 | 全量 | 默认档：读-改-验-总结闭环 |
 * | [PLAN] 规划师 | 主代理 | 只读 | 产出待确认计划，零副作用 |
 * | [GENERAL] 通用 | 主代理 | 全量 | 兜底（答疑/小改动）；亦可作子代理类型 |
 * | [EXPLORE] 探索 | 子代理 | 只读 | 「找出所有用到 X 的地方」类委派 |
 * | [RESEARCH] 调研 | 子代理 | 联网+读 | 「查一下这个库怎么用」类委派 |
 * | [REVIEWER] 评审 | 子代理 | 只读+git | 「评审本次改动」类委派 |
 *
 * ## 工具面前缀白名单语义
 *
 * [LoopToolSurface] 按前缀集过滤注册表：
 * - `""`（空串）= 通配一切（构建者/通用）；
 * - `code_read` = 精确 id；
 * - `code_git_` / `mcp__` = 前缀（MCP 一等工具全部放行给构建者/通用/调研）。
 */
enum class KitAgentRole {
    /** 主代理（与用户对话的那个循环）。 */
    PRIMARY,

    /** 子代理（由 task 工具派生，隔离上下文跑完即销毁）。 */
    SUBAGENT
}

/** Agent 画像种类（key 对外稳定，task 工具的 `subagent_type` 取值域）。 */
enum class KitAgentKind(
    /** 对外标识（task 工具参数 / 日志）。 */
    val key: String,
    /** 展示名（日志与结果标题）。 */
    val displayName: String,
    /** 角色定位。 */
    val role: KitAgentRole
) {
    /** 构建者（主代理默认）：可读可写可执行，任务闭环主力。 */
    BUILD("build", "构建者", KitAgentRole.PRIMARY),

    /** 规划师（主代理 PLAN 档）：只读工具，产出待确认计划。 */
    PLAN("plan", "规划师", KitAgentRole.PRIMARY),

    /** 通用（主代理兜底 / 可作为子代理类型）：完整工具面。 */
    GENERAL("general", "通用", KitAgentRole.PRIMARY),

    /** 探索（子代理）：只读代码探索，返回 path:line 结论。 */
    EXPLORE("explore", "探索", KitAgentRole.SUBAGENT),

    /** 调研（子代理）：联网检索，返回带来源结论。 */
    RESEARCH("research", "调研", KitAgentRole.SUBAGENT),

    /** 评审（子代理）：只读代码评审，返回分级发现与合入结论。 */
    REVIEWER("reviewer", "评审", KitAgentRole.SUBAGENT);

    companion object {
        /** 由 task 工具的 subagent_type 解析；未知值返回 null。 */
        fun fromKey(key: String?): KitAgentKind? =
            key?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                ?.let { raw -> entries.firstOrNull { it.key == raw } }
    }
}

/**
 * Agent 画像定义：一个「干活的虚拟人格」的全部静态配置。
 */
data class KitAgentDefinition(
    val kind: KitAgentKind,
    val description: String,
    /** 工具面前缀白名单（空串前缀 = 不过滤）。 */
    val toolAllowlist: Set<String>,
    /** 是否注入合成工具（task 子代理委派）——仅主代理（子代理不能再派）。 */
    val includeSyntheticTools: Boolean = kind.role == KitAgentRole.PRIMARY,
    /** 回合预算基数（按思考档位倍率缩放）。 */
    val defaultMaxTurns: Int = 24,
    /** 子代理回合预算（本画像作为子代理运行时）。 */
    val subagentMaxTurns: Int = 12,
    /** 是否允许写类工具（规划师/探索=false）。 */
    val writeToolsAllowed: Boolean = true
) {
    /** 该画像是否可执行写类/命令类工具（权限模式的硬约束）。 */
    val isReadOnly: Boolean get() = !writeToolsAllowed
}

/** 画像目录（稳定顺序：主代理三画像在前，子代理三画像在后）。 */
object KitAgentCatalog {

    /** 构建者（主代理默认）。 */
    val BUILD: KitAgentDefinition = KitAgentDefinition(
        kind = KitAgentKind.BUILD,
        description = "构建者：直接完成任务——读、改、跑、验一体，" +
            "以最小改动闭环交付，从不留 TODO 占位。",
        toolAllowlist = setOf(
            "", // 通配：code_* / shell_execute / git / web / skill / mcp__* 全量
        ),
        defaultMaxTurns = 32,
        subagentMaxTurns = 12
    )

    /** 规划师（主代理 PLAN 档）：只读 + 计划产出。 */
    val PLAN: KitAgentDefinition = KitAgentDefinition(
        kind = KitAgentKind.PLAN,
        description = "规划师：只读探索工作区，产出可执行的分步计划（Todo 清单 + " +
            "每步的验证口径），交用户确认——规划阶段零副作用。",
        toolAllowlist = setOf(
            "code_read", "code_grep", "code_glob", "code_check",
            "code_git_status", "code_git_diff", "code_git_log",
            "code_todo"
        ),
        writeToolsAllowed = false,
        defaultMaxTurns = 16,
        subagentMaxTurns = 8
    )

    /** 通用（主代理兜底；亦可作 task 工具的子代理类型）。 */
    val GENERAL: KitAgentDefinition = KitAgentDefinition(
        kind = KitAgentKind.GENERAL,
        description = "通用执行：与构建者同工具面，回答式编码（答疑 / " +
            "小改动 / 现场检查）优先直答，不强行展开任务循环。",
        toolAllowlist = setOf(""),
        defaultMaxTurns = 24,
        subagentMaxTurns = 12
    )

    /** 探索（子代理）：只读代码探索。 */
    val EXPLORE: KitAgentDefinition = KitAgentDefinition(
        kind = KitAgentKind.EXPLORE,
        description = "探索员：只读探索代码，返回带 path:line 引用的结构化结论，" +
            "不改动任何文件。",
        toolAllowlist = setOf(
            "code_read", "code_grep", "code_glob", "code_check",
            "code_git_status", "code_git_diff", "code_git_log"
        ),
        writeToolsAllowed = false,
        defaultMaxTurns = 16,
        subagentMaxTurns = 10
    )

    /** 调研（子代理）：联网检索 + 本地阅读。 */
    val RESEARCH: KitAgentDefinition = KitAgentDefinition(
        kind = KitAgentKind.RESEARCH,
        description = "调研员：联网检索（web 搜索 / 抓取 / HTTP）+ 本地只读，" +
            "返回带来源 URL 的结论。",
        toolAllowlist = setOf(
            "web_search", "web_fetch", "http_request", "http",
            "code_read", "code_grep", "code_glob",
            "mcp__"
        ),
        writeToolsAllowed = false,
        defaultMaxTurns = 16,
        subagentMaxTurns = 10
    )

    /** 评审（子代理）：只读代码评审——先看 git 变更再深入读相关文件。 */
    val REVIEWER: KitAgentDefinition = KitAgentDefinition(
        kind = KitAgentKind.REVIEWER,
        description = "评审员：只读评审代码（先看 git 变更再深入读相关文件），" +
            "按严重性分级输出发现（阻断/警告/建议），给出是否可合入的结论。",
        toolAllowlist = setOf(
            "code_read", "code_grep", "code_glob", "code_check",
            "code_git_status", "code_git_diff", "code_git_log"
        ),
        writeToolsAllowed = false,
        defaultMaxTurns = 16,
        subagentMaxTurns = 10
    )

    /** 全量目录（稳定顺序）。 */
    val ALL: List<KitAgentDefinition> =
        listOf(BUILD, PLAN, GENERAL, EXPLORE, RESEARCH, REVIEWER)

    /** 可作为 task 工具 subagent_type 的画像。 */
    val SUBAGENT_KINDS: List<KitAgentKind> =
        ALL.map { it.kind }.filter { it.role == KitAgentRole.SUBAGENT }

    /** 主代理可切换画像。 */
    val PRIMARY_KINDS: List<KitAgentKind> =
        ALL.map { it.kind }.filter { it.role == KitAgentRole.PRIMARY }

    /** 按种类取画像（枚举封闭——缺项提前暴露）。 */
    fun definitionOf(kind: KitAgentKind): KitAgentDefinition =
        ALL.firstOrNull { it.kind == kind }
            ?: error("KitAgentCatalog missing definition for ${kind.key}")

    /**
     * 主代理档位映射（Build/Plan 双档 → 画像；其余历史档 → GENERAL）。
     */
    fun primaryFor(modeKey: String): KitAgentDefinition {
        val normalized = modeKey.trim().lowercase()
        return when (normalized) {
            "build" -> BUILD
            "plan" -> PLAN
            else -> GENERAL
        }
    }
}

// ═══════════════════════ 子代理请求/结果 ═══════════════════════

/** 子代理派发请求（task 工具的结构化参数）。 */
data class KitSubAgentRequest(
    val kind: KitAgentKind,
    val description: String,
    val prompt: String,
    /** 子代理可见的工作区根（与主会话一致）。 */
    val workspaceRoot: String? = null
)

/**
 * 子代理执行结果。
 *
 * @param kind 子代理画像
 * @param output 结论文本（子代理最终轮的助手文本）
 * @param turns / toolCalls / durationMs 成本统计
 * @param truncated 是否部分结果（超时截获）
 */
data class KitSubAgentOutcome(
    val kind: KitAgentKind = KitAgentKind.EXPLORE,
    val output: String,
    val turns: Int,
    val toolCalls: Int,
    val durationMs: Long,
    val truncated: Boolean = false
) {
    companion object {
        /** 失败折叠（异常路径统一形态）。 */
        fun failure(kind: KitAgentKind, message: String): KitSubAgentOutcome =
            KitSubAgentOutcome(
                kind = kind,
                output = "子代理执行失败：$message",
                turns = 0,
                toolCalls = 0,
                durationMs = 0
            )
    }
}
