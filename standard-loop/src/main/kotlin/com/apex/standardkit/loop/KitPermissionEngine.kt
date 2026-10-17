package com.apex.standardkit.loop

import java.util.concurrent.ConcurrentHashMap

/**
 * # Kit Permission Engine — 任务循环的工具权限门
 *
 * 每一次工具调用在执行前必须过本门：**规则 → 会话记忆 → 敏感文件保护 →
 * 模式兜底** 多级裁决，输出三态效应 [KitPermissionDecision]。
 *
 * ## 裁决顺序（先命中先出）
 *
 * 1. **DENY 规则**优先短路（显式拒绝永远最硬——BYPASS 模式也尊重 DENY）；
 * 2. **PLAN 档只读硬门**（引擎级：规划档运行时任何写副作用直接 DENY）；
 * 3. **会话记忆**（用户在 ASK 弹窗选过「本会话总允许」的工具/命令模式）；
 * 4. **命令级规则**（shell 类工具，命令首词尾缀通配）；
 * 5. **工具级 ALLOW/ASK 规则**（前缀通配匹配工具 id）；
 * 6. **敏感文件保护**（`.env` 族/密钥文件命中即 ASK）；
 * 7. **模式兜底**（BYPASS 全放行 / ACCEPT_EDITS 编辑放行 / DEFAULT
 *    只读放行写问 / PLAN 只读放行写拒）。
 *
 * ## MCP 语义
 *
 * MCP 一等工具（`mcp__server__tool`）默认只读放行；**按服务器封禁**走
 * 规则 `mcp__server__*` → DENY（尾星前缀匹配命中该服务器全部工具）。
 */
enum class KitPermissionEffect {
    /** 放行（含显式规则放行与会话记忆放行）。 */
    ALLOW,

    /** 询问用户（用户可「本次允许/本会话总允许/拒绝」）。 */
    ASK,

    /** 拒绝（向模型返回结构化拒因，模型可改道）。 */
    DENY
}

/** 权限模式（四档兜底策略）。 */
enum class KitPermissionMode {
    /** 全放行（信任场景；规则 DENY 仍生效）。 */
    BYPASS,

    /** 规则 + 按需询问（默认：只读放行，写/命令先问）。 */
    DEFAULT,

    /** 编辑类自动放行（文件写/编辑/git 提交不问，其余非只读仍问）。 */
    ACCEPT_EDITS,

    /** 只读模式（任何写副作用直接拒绝，规则不可越级）。 */
    PLAN
}

/** 权限规则：工具 id 模式（精确 / 前缀尾星通配 / 单星全体）→ 效应。 */
data class KitPermissionRule(
    val pattern: String,
    val effect: KitPermissionEffect
)

/**
 * 单次工具调用的权限决策（含归因——可解释性与测试断言用）。
 */
data class KitPermissionDecision(
    val effect: KitPermissionEffect,
    val reason: String
)

/** 用户对一次 ASK 的应答。 */
enum class KitPermissionResponse {
    /** 本次允许。 */
    ALLOW_ONCE,

    /** 本会话内该工具（或命令模式）总允许（写入会话记忆）。 */
    ALLOW_SESSION,

    /** 拒绝（返回拒因给模型）。 */
    DENY
}

class KitPermissionEngine(
    /** 初始模式（运行期经 [updateMode] 热切换）。 */
    initialMode: KitPermissionMode = KitPermissionMode.DEFAULT
) {

    @Volatile
    private var mode: KitPermissionMode = initialMode

    /** 工具级规则（按序首匹配）。 */
    @Volatile
    private var rules: List<KitPermissionRule> = emptyList()

    /** 命令级规则（仅作用于 shell 类工具；pattern = 命令首词尾缀通配）。 */
    @Volatile
    private var commandRules: List<KitPermissionRule> = emptyList()

    /** 会话记忆：已「本会话总允许」的工具 id。 */
    private val sessionAllowedTools = ConcurrentHashMap.newKeySet<String>()

    /** 会话记忆：已「本会话总允许」的命令模式。 */
    private val sessionAllowedCommands = ConcurrentHashMap.newKeySet<String>()

    // ═══════════════════════ 配置 API ═══════════════════════

    /** 切换权限模式（立即生效）。 */
    fun updateMode(newMode: KitPermissionMode) {
        mode = newMode
    }

    /** 当前模式。 */
    fun currentMode(): KitPermissionMode = mode

    /** 更新规则集（整体替换，快照语义）。 */
    fun updateRules(newRules: List<KitPermissionRule>) {
        rules = newRules.filter { it.pattern.isNotBlank() }
    }

    /** 更新命令级规则集（整体替换）。 */
    fun updateCommandRules(newRules: List<KitPermissionRule>) {
        commandRules = newRules.filter { it.pattern.isNotBlank() }
    }

    /** 清空会话记忆（新会话 / 用户显式重置）。 */
    fun resetSessionMemory() {
        sessionAllowedTools.clear()
        sessionAllowedCommands.clear()
    }

    /** 记住一条「本会话总允许」（工具 id 或命令模式）。 */
    fun rememberSessionAllow(target: String, isCommand: Boolean = false) {
        if (isCommand) sessionAllowedCommands.add(target) else sessionAllowedTools.add(target)
    }

    // ═══════════════════════ 裁决 API ═══════════════════════

    /**
     * 裁决一次工具调用。
     *
     * @param toolId 注册表工具 id（合成工具 task 也走本门）
     * @param rawArguments 原始参数 JSON（命令级通配解析用）
     * @param subAgentContext 是否子代理上下文（无交互通道：ASK 折叠为 DENY）
     * @param planGate 引擎级 PLAN 档只读硬门
     */
    fun decide(
        toolId: String,
        rawArguments: String,
        subAgentContext: Boolean = false,
        planGate: Boolean = false
    ): KitPermissionDecision {
        // ── 1. DENY 规则短路（最高优先）──
        rules.firstOrNull { it.effect == KitPermissionEffect.DENY && matches(it.pattern, toolId) }
            ?.let {
                return KitPermissionDecision(
                    KitPermissionEffect.DENY,
                    "规则 ${it.pattern} → DENY"
                )
            }

        val readOnly = isReadOnlyTool(toolId)

        // ── 2a. 引擎级 PLAN 档只读硬门 ──
        if (planGate && !readOnly) {
            return KitPermissionDecision(
                KitPermissionEffect.DENY,
                "PLAN 档只读硬门：$toolId 有写副作用，规划阶段被硬性拒绝"
            )
        }

        // ── 2b. PLAN 模式前置门（规则不可越级）──
        if (mode == KitPermissionMode.PLAN && !readOnly) {
            return KitPermissionDecision(
                KitPermissionEffect.DENY,
                "PLAN 只读模式：$toolId 有写副作用，规划阶段被硬性拒绝"
            )
        }

        // ── 3. 会话记忆 ──
        if (sessionAllowedTools.contains(toolId)) {
            return KitPermissionDecision(
                KitPermissionEffect.ALLOW,
                "会话记忆：$toolId 本会话已总允许"
            )
        }
        // ── 4. 命令级规则（shell 类工具）──
        if (isShellTool(toolId)) {
            val command = extractCommand(rawArguments)
            if (command != null) {
                val deniedCmd = commandRules.firstOrNull {
                    it.effect == KitPermissionEffect.DENY &&
                        commandPatternMatches(it.pattern, command)
                }
                if (deniedCmd != null) {
                    return KitPermissionDecision(
                        KitPermissionEffect.DENY,
                        "命令规则 ${deniedCmd.pattern} → DENY"
                    )
                }
                if (sessionAllowedCommands.any { commandPatternMatches(it, command) }) {
                    return KitPermissionDecision(
                        KitPermissionEffect.ALLOW,
                        "会话记忆：命令模式命中（$command）"
                    )
                }
                val askCmd = commandRules.firstOrNull {
                    it.effect == KitPermissionEffect.ASK &&
                        commandPatternMatches(it.pattern, command)
                }
                if (askCmd != null) {
                    return askDecision(subAgentContext, "命令规则 ${askCmd.pattern} → ASK")
                }
                val allowCmd = commandRules.firstOrNull {
                    it.effect == KitPermissionEffect.ALLOW &&
                        commandPatternMatches(it.pattern, command)
                }
                if (allowCmd != null) {
                    return KitPermissionDecision(
                        KitPermissionEffect.ALLOW,
                        "命令规则 ${allowCmd.pattern} → ALLOW"
                    )
                }
            }
        }

        // ── 5. 工具级 ALLOW / ASK 规则 ──
        val hit = rules.firstOrNull { matches(it.pattern, toolId) }
        if (hit != null) {
            return when (hit.effect) {
                KitPermissionEffect.ALLOW -> KitPermissionDecision(
                    KitPermissionEffect.ALLOW,
                    "规则 ${hit.pattern} → ALLOW"
                )
                KitPermissionEffect.DENY -> KitPermissionDecision(
                    KitPermissionEffect.DENY,
                    "规则 ${hit.pattern} → DENY"
                )
                KitPermissionEffect.ASK -> askDecision(subAgentContext, "规则 ${hit.pattern} → ASK")
            }
        }

        // ── 6. 敏感文件保护（显式放行面不受拦截；BYPASS 跳过）──
        if (mode != KitPermissionMode.BYPASS) {
            val path = extractPath(rawArguments)
            if (path != null && isSensitivePath(path)) {
                val fileName = sensitiveFileName(path)
                return askDecision(subAgentContext, "敏感文件保护：$fileName 可能包含密钥")
            }
        }

        // ── 7. 模式兜底 ──
        return when (mode) {
            KitPermissionMode.BYPASS ->
                KitPermissionDecision(KitPermissionEffect.ALLOW, "BYPASS 模式兜底 → ALLOW")

            KitPermissionMode.ACCEPT_EDITS ->
                if (readOnly || isEditTool(toolId)) {
                    KitPermissionDecision(
                        KitPermissionEffect.ALLOW,
                        "ACCEPT_EDITS 模式：${if (readOnly) "只读" else "编辑类"} → ALLOW"
                    )
                } else {
                    askDecision(subAgentContext, "ACCEPT_EDITS 模式：非编辑写类 → ASK")
                }

            KitPermissionMode.DEFAULT ->
                if (readOnly) {
                    KitPermissionDecision(
                        KitPermissionEffect.ALLOW,
                        "DEFAULT 模式：只读 → ALLOW"
                    )
                } else {
                    askDecision(subAgentContext, "DEFAULT 模式：写/命令类 → ASK")
                }

            KitPermissionMode.PLAN ->
                KitPermissionDecision(
                    KitPermissionEffect.ALLOW,
                    "PLAN 模式：只读 → ALLOW"
                )
        }
    }

    /** 子代理上下文：ASK 折叠 DENY（无交互通道，宁可保守）。 */
    private fun askDecision(subAgentContext: Boolean, reason: String): KitPermissionDecision =
        if (subAgentContext) {
            KitPermissionDecision(
                KitPermissionEffect.DENY,
                "子代理无询问通道：$reason 折叠为 DENY（子代理应改用低风险工具）"
            )
        } else {
            KitPermissionDecision(KitPermissionEffect.ASK, reason)
        }

    companion object {

        /** 只读工具 id 前缀表（静态——不依赖注册表实例）。 */
        private val READONLY_EXACT = setOf(
            "code_read", "code_grep", "code_glob", "code_check",
            "code_git_status", "code_git_diff", "code_git_log",
            "web_search", "web_fetch",
            "task", "code_todo"
        )

        private val READONLY_PREFIXES = listOf("mcp__", "skill_")

        /** shell 执行类工具 id 集（命令级规则的作用域）。 */
        private val SHELL_TOOLS = setOf("shell_execute", "terminal.exec", "bash")

        /** 编辑/写类工具 id 集（ACCEPT_EDITS 放行面）。 */
        private val EDIT_TOOLS = setOf(
            "code_edit", "code_write", "code_git_commit", "code_git_branch"
        )

        /** 是否只读工具（无环境副作用）。 */
        fun isReadOnlyTool(toolId: String): Boolean =
            toolId in READONLY_EXACT || READONLY_PREFIXES.any { toolId.startsWith(it) }

        /** 是否 shell 执行类工具。 */
        fun isShellTool(toolId: String): Boolean = toolId in SHELL_TOOLS

        /** 是否编辑/写类工具。 */
        fun isEditTool(toolId: String): Boolean = toolId in EDIT_TOOLS

        /**
         * 工具 id 规则匹配：
         * - 单独 `*` 匹配一切；
         * - 尾缀 `*` → 前缀匹配（`mcp__github__*` 封禁整台服务器）；
         * - 其余精确相等。
         */
        fun matches(pattern: String, toolId: String): Boolean {
            if (pattern.isBlank()) return false
            if (pattern == "*") return true
            return if (pattern.endsWith("*")) {
                val prefix = pattern.dropLast(1)
                prefix.isNotEmpty() && toolId.startsWith(prefix)
            } else {
                pattern == toolId
            }
        }

        /**
         * 命令模式匹配：pattern 尾缀 `*` = 命令字符串前缀匹配；
         * 无星 = 首词精确（或整串相等）。
         */
        fun commandPatternMatches(pattern: String, command: String): Boolean {
            if (pattern.isBlank() || command.isBlank()) return false
            val normalized = command.trim()
            return if (pattern.endsWith("*")) {
                val prefix = pattern.dropLast(1).trim()
                prefix.isNotEmpty() && normalized.startsWith(prefix)
            } else {
                normalized == pattern || normalized.split(WHITESPACE).firstOrNull() == pattern
            }
        }

        private val WHITESPACE = Regex("\\s+")

        /**
         * 从工具参数 JSON 提取命令字符串（shell 类工具的 command/cmd 字段；
         * 纯字符串提取不引 JSON 库——键名带引号整体搜索，`"command"` 不会
         * 误命中 `"sub_command"`）。
         */
        fun extractCommand(rawArguments: String): String? =
            extractJsonStringField(rawArguments, listOf("\"command\"", "\"cmd\""))

        /** .env 模板文件（不含真实密钥的示例文件，业界惯例可安全读取）。 */
        private val ENV_TEMPLATE_NAMES = setOf(".env.example", ".env.sample")

        /** 凭据/密钥清单类文件名（精确匹配）。 */
        private val SENSITIVE_EXACT = setOf(
            ".env", "credentials.json", "secrets.json", "secrets.yaml"
        )

        /** 密钥/证书类文件后缀。 */
        private val SENSITIVE_SUFFIXES = listOf(".pem", ".key", ".keystore", ".jks")

        /** 取路径的文件名段（最后一段；容忍 Windows 分隔符与尾部分隔符）。 */
        private fun sensitiveFileName(path: String): String = path.trim()
            .trimEnd('/', '\\')
            .substringAfterLast('/')
            .substringAfterLast('\\')

        /**
         * 敏感路径判定（read 默认询问面）：
         * `.env` 族（模板除外）/ `*.pem` `*.key` `*.keystore` `*.jks` /
         * `id_rsa*` / `credentials.json` `secrets.json` `secrets.yaml`。
         */
        fun isSensitivePath(path: String): Boolean {
            val name = sensitiveFileName(path).lowercase()
            if (name.isEmpty()) return false
            if (name in ENV_TEMPLATE_NAMES) return false
            if (name == ".env" || name.startsWith(".env.")) return true
            if (SENSITIVE_SUFFIXES.any { name.endsWith(it) }) return true
            if (name.startsWith("id_rsa")) return true
            return name in SENSITIVE_EXACT
        }

        /**
         * 从工具参数 JSON 提取文件路径（read/write/edit 类工具的
         * "path" / "file_path" / "file" 字段）。
         */
        fun extractPath(rawArguments: String): String? =
            extractJsonStringField(rawArguments, listOf("\"path\"", "\"file_path\"", "\"file\""))

        /** 轻量 JSON 字符串字段提取（纯字符串口径；键名带引号整体搜索）。 */
        internal fun extractJsonStringField(json: String, keys: List<String>): String? {
            val trimmed = json.trim()
            if (!trimmed.startsWith("{")) return null
            for (key in keys) {
                val idx = trimmed.indexOf(key)
                if (idx < 0) continue
                val colon = trimmed.indexOf(':', idx + key.length)
                if (colon < 0) continue
                var i = colon + 1
                while (i < trimmed.length && trimmed[i].isWhitespace()) i++
                if (i >= trimmed.length || trimmed[i] != '"') continue
                val sb = StringBuilder()
                i++
                while (i < trimmed.length && trimmed[i] != '"') {
                    if (trimmed[i] == '\\' && i + 1 < trimmed.length) {
                        sb.append(trimmed[i + 1])
                        i += 2
                    } else {
                        sb.append(trimmed[i])
                        i++
                    }
                }
                if (sb.isNotEmpty()) return sb.toString()
            }
            return null
        }
    }
}
