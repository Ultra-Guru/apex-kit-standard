package com.apex.standardkit.loop

/**
 * # Kit SubAgent Policy — 子代理编排策略
 *
 * 标准任务循环的子代理派生策略组件化（派发器 [KitSubAgentDispatcher]
 * 已有执行侧；这里补齐**准入与语义裁决**层的业界口径）：
 *
 * 1. **深度限制**（[KitSubAgentDepthRule]）：沿父链上溯计数（子代理
 *    再派子代理的嵌套深度），达到上限拒绝——「子代理的子代理」是
 *    失控递归的头号来源；
 * 2. **主代理拒绝**（[KitSubAgentCatalogPort]）：primary 模式的画像
 *    不允许作为子代理运行（它是循环的主人，不是工人）；
 * 3. **模型覆写解析**（[KitSubAgentModelRef]）：`provider/model#variant`
 *    三段式引用解析 + 目录校验（未知模型 / 未知变体的错误文案——
 *    模型侧可自纠错重试）；
 * 4. **续接语义**（[KitSubAgentContinuation]）：按 sessionId 续接既有
 *    子会话时校验「确为当前会话的子」；换代理续接时模型选择语义
 *    （显式覆写 > 换代理后的代理默认 > 继承原值）；
 * 5. **入场白**（[KitSubAgentAdmission]）：新子会话的首条提示词前缀
 *    （「你是被另一个会话派生的子代理」——身份对齐）与后台模式
 *    结果文案（「不要 sleep / 轮询 / 重复它的工作」——业界原文语义）。
 */
// ═══════════════════════ 派生请求 ═══════════════════════

/**
 * 子代理派生请求（策略层视角——执行层见 [KitSubAgentDispatcher]）。
 *
 * @param agentId 目标子代理画像 id
 * @param description 3~5 词任务标签（用户展示）
 * @param prompt 自包含任务指令（新会话唯一输入——子代理看不到父会话）
 * @param modelRef 显式模型覆写（`provider/model#variant`；null = 不覆写）
 * @param sessionId 续接的既有子会话 id（null = 新建）
 * @param background true = 后台执行（立即返回，完成时通知）
 */
data class KitSubAgentSpawnRequest(
    val agentId: String,
    val description: String,
    val prompt: String,
    val modelRef: String? = null,
    val sessionId: String? = null,
    val background: Boolean = false
) {
    init {
        require(agentId.isNotEmpty()) { "agentId must not be empty" }
        require(description.isNotEmpty()) { "description must not be empty" }
        require(prompt.isNotEmpty()) { "prompt must not be empty" }
    }
}

// ═══════════════════════ 画像目录端口 ═══════════════════════

/** 子代理画像摘要（策略裁决所需的全部信息）。 */
data class KitSubAgentAgentSummary(
    val id: String,
    val name: String,
    val mode: KitSubAgentAgentMode,
    val defaultModelRef: String?,
    val description: String? = null
)

/** 画像模式（PRIMARY = 循环主人；SUBAGENT = 可被派生）。 */
enum class KitSubAgentAgentMode { PRIMARY, SUBAGENT }

/**
 * 画像目录端口（宿主注入——通常是对 [KitAgents] 注册表的薄投影）。
 */
fun interface KitSubAgentCatalogPort {
    /** 解析画像（未知 id → null）。 */
    fun resolve(agentId: String): KitSubAgentAgentSummary?
}

// ═══════════════════════ 深度限制 ═══════════════════════

/**
 * 子代理嵌套深度规则。
 *
 * 语义（业界口径）：从当前会话沿父链上溯到根，每层 +1；深度达到
 * [limit] 即拒绝（默认 1 = 不允许「子代理的子代理」）。宿主可调高
 * （配置项语义），但必须有上限——失控递归的成本是指数级的。
 */
object KitSubAgentDepthRule {

    /** 默认深度上限（一层嵌套）。 */
    const val DEFAULT_LIMIT = 1

    /**
     * 计算当前会话的子代理深度。
     *
     * @param sessionId 当前会话 id
     * @param parentOf 父会话查找端口（无父 → null；环状谱系 → 视为根，
     *   防御性熔断——持久层脏数据不该打挂派生链）
     */
    fun depthOf(sessionId: String, parentOf: (String) -> String?): Int {
        var depth = 0
        var current: String? = sessionId
        val visited = mutableSetOf<String>()
        while (current != null) {
            if (!visited.add(current)) break // 环状谱系防御
            val parent = parentOf(current)
            if (parent == null) break
            depth += 1
            current = parent
        }
        return depth
    }

    /** 深度准入（达到上限 → 拒绝文案；否则 null）。 */
    fun admission(sessionId: String, limit: Int, parentOf: (String) -> String?): String? {
        val depth = depthOf(sessionId, parentOf)
        if (depth >= limit) {
            return "Subagent depth limit reached ($limit). Increase the depth limit " +
                "configuration to allow nested subagents."
        }
        return null
    }
}

// ═══════════════════════ 模型覆写 ═══════════════════════

/** 模型引用三段式（`provider/model#variant`；variant 可缺省）。 */
data class KitSubAgentModelRef(
    val providerId: String,
    val modelId: String,
    val variantId: String? = null
) {
    override fun toString(): String =
        "$providerId/$modelId" + (variantId?.let { "#$it" } ?: "")

    companion object {
        /**
         * 解析模型引用（非法形态 → null）。
         *
         * 合法形：`provider/model` 或 `provider/model#variant`——
         * 三段各自非空、无空白、无多余分隔符。
         */
        fun parse(raw: String): KitSubAgentModelRef? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed != raw) return null
            val variantPart = if (trimmed.contains('#')) {
                val hash = trimmed.indexOf('#')
                if (hash != trimmed.lastIndexOf('#')) return null
                trimmed.substring(hash + 1).takeIf { it.isNotEmpty() } ?: return null
            } else {
                null
            }
            val base = if (variantPart == null) trimmed else trimmed.substring(0, trimmed.indexOf('#'))
            val slash = base.indexOf('/')
            if (slash <= 0 || slash != base.lastIndexOf('/')) return null
            val provider = base.substring(0, slash)
            val model = base.substring(slash + 1)
            if (provider.isEmpty() || model.isEmpty()) return null
            if (provider.any { it.isWhitespace() } || model.any { it.isWhitespace() }) return null
            return KitSubAgentModelRef(provider, model, variantPart)
        }
    }
}

/** 目录中的模型条目（变体清单 + 默认变体）。 */
data class KitSubAgentCatalogModel(
    val providerId: String,
    val modelId: String,
    val variants: List<String> = emptyList()
)

/**
 * 模型覆写裁决（解析 + 目录校验）。
 *
 * 错误文案面向模型（可自纠错重试）——与业界口径一致：未知模型指向
 * 模型清单工具；未知变体列出可用变体。
 */
object KitSubAgentModelOverride {

    /** 未覆写（null 覆写 = 继承语义）。 */
    data class NotOverridden(val reason: String?) : Result

    /** 覆写生效。 */
    data class Overridden(val ref: KitSubAgentModelRef) : Result

    sealed interface Result

    /**
     * 裁决覆写。
     *
     * @param rawRef 模型输出的覆写引用（null / 空 = 未覆写）
     * @param catalog 模型目录查找端口（按 provider+model 查找——变体
     *   不参与目录命中，命中后单独校验）
     */
    fun resolve(
        rawRef: String?,
        catalog: (providerId: String, modelId: String) -> KitSubAgentCatalogModel?
    ): Result {
        if (rawRef == null || rawRef.isBlank()) {
            return NotOverridden(reason = null)
        }
        val ref = KitSubAgentModelRef.parse(rawRef)
            ?: return NotOverridden(
                reason = "Invalid model \"$rawRef\". Use \"providerID/modelID\" or " +
                    "\"providerID/modelID#variant\"."
            )
        val model = catalog(ref.providerId, ref.modelId)
            ?: return NotOverridden(
                reason = "Model \"${ref.providerId}/${ref.modelId}\" is not available. " +
                    "Use the models tool to see what is available."
            )
        if (ref.variantId != null && model.variants.isNotEmpty() &&
            ref.variantId !in model.variants
        ) {
            return NotOverridden(
                reason = "Variant \"${ref.variantId}\" is not available for " +
                    "\"${ref.providerId}/${ref.modelId}\". Available: " +
                    model.variants.joinToString(", ") + "."
            )
        }
        return Overridden(ref)
    }
}

// ═══════════════════════ 续接语义 ═══════════════════════

/** 会话谱系端口（续接校验的宿主投影）。 */
interface KitSubAgentSessionLineage {
    /** 会话存在性（不存在 → null）。 */
    fun find(sessionId: String): KitSubAgentLineageInfo?

    /** 当前会话 id（派生动作的发起者）。 */
    fun currentSessionId(): String
}

/** 会话谱系信息。 */
data class KitSubAgentLineageInfo(
    val sessionId: String,
    val parentSessionId: String?,
    val agentId: String,
    val modelRef: String?
)

/**
 * 子代理续接语义裁决。
 *
 * - 续接目标必须**确为当前会话的子**（别人的子会话不可劫持）；
 * - 换代理续接 = 切换子代理画像：模型选择按「显式覆写 > 换代理后的
 *   代理默认 > 继承原值」三级裁决（与业界 create 语义镜像）；
 * - 新建 = 永远从「入场白 + 任务指令」开始。
 */
object KitSubAgentContinuation {

    /** 续接裁决结果。 */
    sealed interface Verdict {
        /** 新建子会话。 */
        data class Create(val admissionPrompt: String) : Verdict

        /** 续接既有子会话。 */
        data class Continue(
            val sessionId: String,
            val switchAgent: Boolean,
            val effectiveModelRef: String?
        ) : Verdict

        /** 拒绝（劫持 / 目标不存在）。 */
        data class Rejected(val reason: String) : Verdict
    }

    /**
     * 裁决续接。
     */
    fun decide(
        request: KitSubAgentSpawnRequest,
        lineage: KitSubAgentSessionLineage,
        catalog: KitSubAgentCatalogPort
    ): Verdict {
        // 画像裁决前置：primary 拒绝与未知画像拒绝在「新建 / 续接」两分支之前
        // （primary 画像任何时候都不能作为子代理运行——不只是续接时）
        val summary = catalog.resolve(request.agentId)
            ?: return Verdict.Rejected("Unknown agent: ${request.agentId}")
        if (summary.mode == KitSubAgentAgentMode.PRIMARY) {
            return Verdict.Rejected("Agent ${request.agentId} cannot run as a subagent")
        }
        val target = request.sessionId
        if (target == null) {
            return Verdict.Create(admissionPrompt = KitSubAgentAdmission.admissionPrompt(request.prompt))
        }
        val info = lineage.find(target)
            ?: return Verdict.Rejected("Subagent session not found: $target")
        if (info.parentSessionId != lineage.currentSessionId()) {
            return Verdict.Rejected("Session $target is not a child of the current session")
        }
        val switched = info.agentId != request.agentId
        val effective = when {
            request.modelRef != null -> request.modelRef
            switched -> summary.defaultModelRef
            else -> null
        }
        return Verdict.Continue(
            sessionId = target,
            switchAgent = switched,
            effectiveModelRef = effective
        )
    }
}

// ═══════════════════════ 入场白与结果文案 ═══════════════════════

/**
 * 子代理提示词与结果文案（业界口径原文语义）。
 */
object KitSubAgentAdmission {

    /** 新子会话的首条提示词（身份前缀 + 任务指令）。 */
    fun admissionPrompt(task: String): String =
        listOf(
            "You are a subagent spawned by another session.",
            task
        ).joinToString("\n")

    /** 后台模式的立即返回文案。 */
    fun backgroundNotice(sessionId: String): String = listOf(
        "The subagent is working in the background (sessionID: $sessionId). " +
            "You will be notified automatically when it finishes.",
        "DO NOT sleep, poll for progress, ask the subagent for status, or " +
            "duplicate this subagent's work; avoid working with the same files or " +
            "topics it is using.",
        "Work on non-overlapping tasks, or briefly tell the user what you " +
            "launched and end your response."
    ).joinToString("\n")

    /** 完成态的结果渲染（结构化包裹——模型可续接该 sessionID）。 */
    fun completedResult(sessionId: String, output: String): String =
        "<subagent sessionID=\"$sessionId\" state=\"completed\">\n$output\n</subagent>"

    /** 失败态的结果渲染（保住 sessionID——模型可续接救活）。 */
    fun failedResult(sessionId: String, error: String?): String =
        "Subagent failed (sessionID: $sessionId): ${error ?: "unknown error"}"

    /** 取消态的结果渲染。 */
    fun cancelledResult(sessionId: String): String =
        "Subagent cancelled (sessionID: $sessionId)"
}
