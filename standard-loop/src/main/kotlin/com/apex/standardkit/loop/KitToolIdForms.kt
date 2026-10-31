package com.apex.standardkit.loop

/**
 * # Kit Tool Id Forms — 注册表工具 id 形态归一（容错比较层）
 *
 * 背景：宿主注册表的工具 id 先后存在两代形态——
 * - 旧代 snake_case / 点号分隔（`code_read` / `shell_execute` / `terminal.exec`）；
 * - 新代 PascalCase（`CodeRead` / `ShellExecute` / `TerminalExec`）。
 *
 * 本库按「语义家族」理解工具面（优先序前缀、画像白名单、只读 / shell /
 * 编辑分类、别名归一），而这些判定历史上全部基于字符串精确比较——两代
 * 形态混用时**整体失配**，且失配是静默的：
 *
 * - 优先序前缀 `code_` / `shell_execute` / `terminal.` 匹配不到 PascalCase
 *   id → 全部工具跌到最低优先级 → MAX_TOOLS 截断退化为字典序 →
 *   编码 / shell / 终端族被整体裁掉，工具面只剩字母序靠前的家族；
 * - 画像白名单 `code_read` 过滤不到 `CodeRead` → 只读画像面为空；
 * - 只读 / shell / 编辑分类全部判否 → DEFAULT 模式对只读工具也弹权限
 *   询问、子代理 ASK 折叠 DENY 连读操作都被拒。
 *
 * [key] 把两代形态折叠到同一比较键：小写 + 剔除非字母数字字符。
 * `code_read ≡ CodeRead`（`coderead`）、`terminal.exec ≡ TerminalExec`
 * （`terminalexec`）、`shell_execute ≡ ShellExecute`（`shellexecute`）。
 * MCP 一等工具的 `mcp__<server>__<tool>` 前缀与点号动态 id 同样被折叠
 * 覆盖（`mcp__github__x` → `mcpgithubx`）。
 *
 * 约束：本层只做「同一 id 的两代形态互认」，绝不把不同的工具折叠成同
 * 一个键（`code_read` 与 `code_write` 的键天然不同，不存在跨界碰撞）。
 */
internal object KitToolIdForms {

    /**
     * 形态归一比较键（trim + 小写 + 剔除非字母数字字符）。
     * 空串保留原样——空 id 永不等于任何 id（空前缀的通配语义由
     * [hasPrefix] 单独处理，不走键比较）。
     */
    fun key(raw: String): String = raw.trim().lowercase().filter { it.isLetterOrDigit() }

    /**
     * 同一工具判定：精确相等，或归一键相等（两代形态互认）。
     */
    fun sameId(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.isEmpty() || b.isEmpty()) return false
        return key(a) == key(b)
    }

    /**
     * 家族前缀判定：`id` 以 `prefix` 开头，或 `id` 的归一键以 `prefix`
     * 的归一键开头（`CodeRead` 命中 `code_`、`TerminalExec` 命中
     * `terminal.`）。空前缀 = 通配一切（画像白名单的既有语义）。
     */
    fun hasPrefix(id: String, prefix: String): Boolean {
        if (prefix.isEmpty()) return true
        if (id.startsWith(prefix)) return true
        val idKey = key(id)
        val prefixKey = key(prefix)
        return idKey.isNotEmpty() && prefixKey.isNotEmpty() && idKey.startsWith(prefixKey)
    }

    /**
     * 命名空间前缀保留字（**仅原样匹配，不参与键折叠**）。
     *
     * `mcp__` 是动态注册的 MCP 一等工具（`mcp__<server>__<tool>`）的
     * 命名空间——两代注册表里它们**同形态**（改名只波及静态内置工具），
     * 键折叠不但无必要，还会把 `McpCall` / `McpList` / `McpConnect`
     * （key 以 `mcp` 开头的管理工具）误吸入 `mcp__` 家族——改变其
     * 只读分类与优先序，属于语义回归。
     */
    private val NAMESPACE_PREFIXES = setOf("mcp__")

    /**
     * 前缀判定的统一入口：命名空间前缀走原样匹配，其余家族前缀走
     * [hasPrefix] 形态归一。所有前缀数据表（优先序 / 白名单 / 只读面 /
     * 并行安全面）都应经由本入口消费。
     */
    fun matchesPrefix(id: String, prefix: String): Boolean =
        if (prefix in NAMESPACE_PREFIXES) id.startsWith(prefix) else hasPrefix(id, prefix)
}
