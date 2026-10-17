package com.apex.agentkit.loop

/**
 * # Kit Tool Surface — 任务循环的工具编排层
 *
 * 四件事：
 *
 * 1. **画像 → 工具面**：按 [KitAgentDefinition.toolAllowlist] 前缀集过滤
 *    注册表（`""` = 通配全量；`code_git_` = 前缀；`mcp__` 动态注册的
 *    MCP 一等工具天然命中），叠加强制函数集收窄；
 * 2. **别名归一**：模型可能输出业界惯用名（read/write/edit/bash/grep/
 *    glob/todo/task…），[resolveRegistryId] 归一到注册表 id；未知名归一
 *    失败时 [suggestToolIds] 给出相近候选；
 * 3. **合成工具**：`task`（子代理委派）不在注册表——[syntheticTaskTool]
 *    现场合成定义（仅主代理画像注入）；
 * 4. **工具名 provider 清洗**：注册表原始 id（点号名 / 非 ASCII 的
 *    `mcp__中文服务__工具`）不能直接作 function name——严格 OpenAI 兼容
 *    端点按 `^[a-zA-Z0-9_-]{1,64}$` 校验，一个非法名即拒掉整个请求。
 *    [buildToolPlan] 对最终候选集统一过 [KitToolNameSanitizer]，返回
 *    provider 名定义 + 反查路由表。
 *
 * ## 工具面预算
 *
 * 部分网关对 tools 数量敏感。标准面按 [MAX_TOOLS] 钳制：注册表工具按
 * 「编码相关优先」的稳定序截断，合成工具恒不被截断。
 */
object KitToolSurface {

    /** 工具面规模上限（超限按优先序截断；task 合成工具不占预算）。 */
    const val MAX_TOOLS = 48

    /**
     * 业界惯用名 → 注册表 id 归一表。
     */
    private val ALIAS_TO_REGISTRY: Map<String, String> = mapOf(
        "read" to "code_read",
        "read_file" to "code_read",
        "write" to "code_write",
        "write_file" to "code_write",
        "edit" to "code_edit",
        "edit_file" to "code_edit",
        "multiedit" to "code_edit",
        "bash" to "shell_execute",
        "shell" to "shell_execute",
        "execute" to "shell_execute",
        "grep" to "code_grep",
        "search" to "code_grep",
        "grep_search" to "code_grep",
        "glob" to "code_glob",
        "list" to "code_glob",
        "ls" to "code_glob",
        "todo" to "code_todo",
        "todo_write" to "code_todo",
        "todo_read" to "code_todo",
        "todowrite" to "code_todo",
        "check" to "code_check",
        "lint" to "code_check",
        "task" to "task", // 合成工具（自映射：resolve 层面已归一）
        "subagent" to "task",
        "dispatch" to "task"
    )

    /** 编码相关前缀优先序（截断时的稳定排序依据）。 */
    private val PRIORITY_PREFIXES = listOf(
        "code_", "shell_execute", "terminal.", "code_git_", "web_", "http_",
        "skill_", "mcp__"
    )

    // ═══════════════════════ 归一 API ═══════════════════════

    /**
     * 名字归一（比较用）：去空白 + 小写 + 连字符/点号 → 下划线。
     */
    private fun normalizeKey(raw: String): String =
        raw.trim().lowercase().replace('-', '_').replace('.', '_')

    /** 归一键 → 注册表 id（大小写/分隔符漂移容错层）。 */
    private val NORMALIZED_ALIAS_TO_REGISTRY: Map<String, String> by lazy {
        ALIAS_TO_REGISTRY.mapKeys { (key, _) -> normalizeKey(key) }
    }

    /**
     * 别名归一（三层容错）：精确别名表 → 大小写/分隔符归一后再查 →
     * 仍未命中原样返回。
     */
    fun resolveRegistryId(name: String): String =
        ALIAS_TO_REGISTRY[name]
            ?: NORMALIZED_ALIAS_TO_REGISTRY[normalizeKey(name)]
            ?: name

    /**
     * 给未知工具名找相近候选（前 3 个，保持 available 原序）。
     */
    fun suggestToolIds(attempted: String, available: List<String>): List<String> {
        val target = normalizeKey(attempted).removePrefix("code_")
        if (target.isEmpty()) return emptyList()
        return available.filter { candidate ->
            val normalized = normalizeKey(candidate).removePrefix("code_")
            normalized.isNotEmpty() && (
                levenshteinWithin(target, normalized, limit = 2) ||
                    normalized.startsWith(target) ||
                    normalized.contains(target) ||
                    target.startsWith(normalized)
                )
        }.take(3)
    }

    /** 有界编辑距离（超过 limit 提前剪枝）。 */
    private fun levenshteinWithin(a: String, b: String, limit: Int): Boolean {
        if (a.isEmpty()) return b.length <= limit
        if (b.isEmpty()) return a.length <= limit
        if (kotlin.math.abs(a.length - b.length) > limit) return false
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = i
            for (j in 1..b.length) {
                cur[j] = minOf(
                    prev[j] + 1,
                    cur[j - 1] + 1,
                    prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                )
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > limit) return false
            val swap = prev
            prev = cur
            cur = swap
        }
        return prev[b.length] <= limit
    }

    /** 是否合成 task 工具（派发子代理的唯一入口）。 */
    fun isSyntheticTaskTool(name: String): Boolean =
        resolveRegistryId(name) == SYNTHETIC_TASK_ID

    /** 合成工具 id。 */
    const val SYNTHETIC_TASK_ID = "task"

    // ═══════════════════════ 工具面构建 ═══════════════════════

    /**
     * 请求工具面计划（provider-safe）。
     *
     * @param definition 画像
     * @param registryDefinitions 注册表快照
     * @param forcedToolIds 强制函数集（非空时只暴露选中工具 + task）
     * @param exposeAll true = 忽略画像白名单
     * @param includeSyntheticTools 是否注入合成 task 工具（子代理必须 false）
     */
    fun buildToolPlan(
        definition: KitAgentDefinition,
        registryDefinitions: List<KitToolDefinition>,
        forcedToolIds: Set<String> = emptySet(),
        exposeAll: Boolean = false,
        includeSyntheticTools: Boolean = definition.includeSyntheticTools
    ): KitToolPlan {
        val all = registryDefinitions
        val forced = forcedToolIds.mapNotNull { id -> all.firstOrNull { it.name == id } }

        val base: List<KitToolDefinition> = when {
            forced.isNotEmpty() -> forced
            exposeAll || definition.toolAllowlist.any { it.isEmpty() } -> all
            else -> all.filter { def ->
                definition.toolAllowlist.any { prefix ->
                    prefix.isEmpty() || def.name.startsWith(prefix)
                }
            }
        }

        val curated = (if (forced.isNotEmpty()) base else base.sortedWith(
            compareByDescending<KitToolDefinition> { def ->
                val id = def.name
                PRIORITY_PREFIXES.indexOfFirst { id.startsWith(it) }
                    .let { if (it < 0) Int.MIN_VALUE else -it }
            }.thenBy { it.name }
        )).take(MAX_TOOLS)

        val result = curated.toMutableList()
        if (includeSyntheticTools && forced.isEmpty()) {
            result.add(syntheticTaskTool())
        }

        // ── provider 名清洗（点号/非 ASCII/超长名 → 合法 [A-Za-z0-9_-]{1,64}）──
        val idToProvider = KitToolNameSanitizer.buildMapping(result.map { it.name })
        val definitions = result
            .map { def -> def.copy(name = idToProvider.getValue(def.name)) }
            .sortedBy { it.name }
        val providerNameToId = buildMap {
            result.forEach { def -> put(idToProvider.getValue(def.name), def.name) }
        }
        return KitToolPlan(
            definitions = definitions,
            providerNameToId = providerNameToId
        )
    }

    /**
     * 工具面文本指引（拼进系统提示词的 Tool Surface 段）。
     */
    fun buildToolGuide(
        definition: KitAgentDefinition,
        planToolIds: List<String>,
        mcpInstructions: List<KitMcpServerInstructions> = emptyList()
    ): String = buildString {
        val displayIds = planToolIds.filter { it != SYNTHETIC_TASK_ID }
        if (displayIds.isEmpty()) {
            appendLine("No tools are available this turn — answer directly in text.")
            if (definition.isReadOnly) {
                appendLine("(You are a READ-ONLY agent; the write/exec surface is absent by design.)")
            }
            return@buildString
        }
        appendLine("Available tools this turn (call by exact name):")
        displayIds.chunked(6).forEach { chunk ->
            appendLine("  " + chunk.joinToString(", "))
        }
        appendLine()
        appendLine("Common aliases are auto-resolved (read→code_read, write→code_write,")
        appendLine("edit→code_edit, bash→shell_execute, grep→code_grep, glob→code_glob,")
        appendLine("todo→code_todo, check→code_check) — but prefer the exact names above.")
        // ── MCP 工具指引（面里有 MCP 一等工具时给命名约定提示）──
        val mcpIds = displayIds.filter { it.startsWith("mcp__") }
        if (mcpIds.isNotEmpty()) {
            appendLine()
            appendLine("MCP server tools use the `mcp__<server>__<tool>` naming scheme.")
            appendLine("Each server's usage guidance (if any) is listed under")
            appendLine("<mcp_instructions> below — follow it when calling that server.")
            val serverPrefixes = mcpInstructions.map { it.toolIdPrefix }
            if (serverPrefixes.isNotEmpty()) {
                appendLine("Servers currently on your surface: " +
                    serverPrefixes.joinToString(", ") { prefix ->
                        prefix.removePrefix("mcp__").trimEnd('_')
                    })
            }
        }
        if (planToolIds.contains(SYNTHETIC_TASK_ID)) {
            appendLine()
            appendLine("`task` dispatches an isolated sub-agent. It does NOT see this")
            appendLine("conversation — the prompt you pass must be fully self-contained")
            appendLine("(goal, scope, expected output format, write-code-or-research-only,")
            appendLine("how to verify). subagent_type:")
            appendLine("- explore = read-only code investigation (returns path:line evidence)")
            appendLine("- research = web-grounded investigation (returns sourced conclusions)")
            appendLine("- reviewer = read-only code review (severity-graded findings + merge")
            appendLine("  verdict)")
            appendLine("- general = full-surface autonomous executor for self-contained")
            appendLine("  multi-step tasks")
            appendLine("Launch independent tasks in parallel. The sub-agent's result is")
            appendLine("invisible to the user — relay the conclusion yourself. Delegate")
            appendLine("exploration and research to keep your context lean, but keep the")
            appendLine("primary edit loop to yourself.")
        }
        if (definition.isReadOnly) {
            appendLine()
            appendLine(
                "You are a READ-ONLY agent: write/exec tools are intentionally absent " +
                    "from your surface. Do not attempt them."
            )
        }
    }.trim()

    /**
     * 合成 task 工具定义（schema 与 [KitSubAgentRequest] 对齐）。
     */
    fun syntheticTaskTool(): KitToolDefinition = KitToolDefinition(
        name = SYNTHETIC_TASK_ID,
        description = """
            Dispatch an isolated sub-agent to run a self-contained task and
            return its conclusion as this tool's result. The sub-agent has its
            own fresh context — it does NOT see this conversation — its own
            tool surface and a turn budget. Four types:
            - explore: read-only code investigation; returns path:line evidence.
            - research: web-grounded investigation; returns sourced conclusions.
            - reviewer: read-only code review of changes; returns severity-graded
              findings plus a merge verdict.
            - general: full-surface autonomous executor for self-contained
              multi-step tasks (can read, write, edit, and run commands).

            Usage notes:
            - Launch multiple independent tasks in parallel when they don't
              depend on each other.
            - The sub-agent does NOT see this conversation — the prompt must
              be fully self-contained (goal, scope, exact expected output
              format). State explicitly whether it should WRITE CODE or only
              research, and how to verify its work.
            - Its result is only visible to you — summarize it for the user
              yourself.
            - Delegate exploration and research to keep your context lean, but
              keep the primary edit loop to yourself unless the task is
              genuinely independent.
        """.trimIndent(),
        parameters = """
            {
              "type": "object",
              "properties": {
                "description": {
                  "type": "string",
                  "description": "One-line task summary (shown in logs and result header)"
                },
                "prompt": {
                  "type": "string",
                  "description": "Full self-contained instruction: goal, scope, expected output format, whether to write code or research only, how to verify. The sub-agent sees ONLY this."
                },
                "subagent_type": {
                  "type": "string",
                  "enum": ["explore", "research", "general", "reviewer"],
                  "description": "explore = read-only code investigation, research = web-grounded investigation, reviewer = read-only code review (severity-graded findings + merge verdict), general = full-surface autonomous executor (default: explore)"
                }
              },
              "required": ["description", "prompt"]
            }
        """.trimIndent()
    )

    /** 工具面里是否包含 todo 工具（决定提示词是否拼 Todo 指引段）。 */
    fun planHasTodo(planToolIds: List<String>): Boolean =
        planToolIds.any { it == "code_todo" || it == "todo" }
}

/**
 * 请求工具面计划（provider-safe）。
 *
 * @param definitions 发给 LLM API 的定义列表（name = provider 名，合法
 *   `^[a-zA-Z0-9_-]{1,64}$`；按 provider 名稳定排序，prompt-cache 友好）
 * @param providerNameToId 模型回显名 → 注册表 id（执行路由反查表）
 */
data class KitToolPlan(
    val definitions: List<KitToolDefinition>,
    val providerNameToId: Map<String, String>
)

/**
 * Provider 名清洗器（库自含实现——不依赖宿主注册表的分级策略）。
 *
 * - 非法字符（点号/非 ASCII 等）→ `_`；长度钳制 64；
 * - 冲突确定性消解（长 id 优先 + 字典序 + 数字后缀）。
 */
internal object KitToolNameSanitizer {

    private val ILLEGAL = Regex("[^a-zA-Z0-9_-]")
    private const val MAX_NAME_LENGTH = 64

    /** registry id → provider 名双向映射（冲突确定性消解）。 */
    fun buildMapping(registryIds: Collection<String>): Map<String, String> {
        val ordered = registryIds.sortedWith(
            compareByDescending<String> { it.length }
                .thenBy { it }
        )
        val result = linkedMapOf<String, String>()
        val taken = mutableSetOf<String>()
        for (id in ordered) {
            var name = sanitize(id)
            if (name in taken) {
                var suffix = 2
                var candidate: String
                do {
                    val room = MAX_NAME_LENGTH - "_$suffix".length
                    candidate = name.take(room) + "_$suffix"
                    suffix++
                } while (candidate in taken)
                name = candidate
            }
            taken += name
            result[id] = name
        }
        return result
    }

    /** 单 id 清洗（不去重；钳制 64 字符）。 */
    fun sanitize(id: String): String =
        ILLEGAL.replace(id, "_").trim('_', '-').take(MAX_NAME_LENGTH).ifEmpty { "tool" }
}
