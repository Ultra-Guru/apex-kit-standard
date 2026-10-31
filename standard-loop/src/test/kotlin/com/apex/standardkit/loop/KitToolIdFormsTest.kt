package com.apex.standardkit.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * # Kit Tool Id Forms — 注册表 id 两代形态归一测试
 *
 * 宿主注册表的工具 id 先后存在两代形态（旧代 snake_case / 点号 →
 * 新代 PascalCase）。归一层必须做到：同一 id 两代形态互认、不同工具
 * 永不误折叠、`mcp__` 命名空间保留原样匹配。
 */
class KitToolIdFormsTest {

    // ═══ key：两代形态折叠到同一比较键 ═══

    @Test
    fun `key folds both id generations to the same form`() {
        assertEquals(KitToolIdForms.key("code_read"), KitToolIdForms.key("CodeRead"))
        assertEquals(KitToolIdForms.key("shell_execute"), KitToolIdForms.key("ShellExecute"))
        assertEquals(KitToolIdForms.key("terminal.exec"), KitToolIdForms.key("TerminalExec"))
        assertEquals(KitToolIdForms.key("code_git_status"), KitToolIdForms.key("CodeGitStatus"))
        assertEquals(KitToolIdForms.key("web_search"), KitToolIdForms.key("WebSearch"))
        assertEquals(KitToolIdForms.key("skill_list"), KitToolIdForms.key("SkillList"))
        assertEquals("mcp__github__x".let { KitToolIdForms.key(it) }, "mcpgithubx")
    }

    @Test
    fun `key never folds distinct tools together`() {
        assertFalse(KitToolIdForms.key("code_read").equals(KitToolIdForms.key("code_write")))
        assertFalse(KitToolIdForms.key("code_read").equals(KitToolIdForms.key("codetask")))
        assertFalse(KitToolIdForms.key("terminal.exec").equals(KitToolIdForms.key("terminal.exec.legacy")))
    }

    // ═══ sameId：同一工具判定 ═══

    @Test
    fun `sameId accepts both generations and rejects strangers`() {
        assertTrue(KitToolIdForms.sameId("CodeRead", "code_read"))
        assertTrue(KitToolIdForms.sameId("CodeRead", "CodeRead"))
        assertTrue(KitToolIdForms.sameId("TerminalExec", "terminal.exec"))
        assertTrue(KitToolIdForms.sameId("ShellExecute", "shell_execute"))
        assertFalse(KitToolIdForms.sameId("CodeRead", "code_write"))
        assertFalse(KitToolIdForms.sameId("CodeRead", ""))
        assertFalse(KitToolIdForms.sameId("", "CodeRead"))
    }

    // ═══ hasPrefix：家族前缀判定 ═══

    @Test
    fun `hasPrefix matches family prefixes across generations`() {
        assertTrue(KitToolIdForms.hasPrefix("CodeRead", "code_"))
        assertTrue(KitToolIdForms.hasPrefix("CodeGitStatus", "code_git_"))
        assertTrue(KitToolIdForms.hasPrefix("TerminalExec", "terminal."))
        assertTrue(KitToolIdForms.hasPrefix("TerminalCreate", "terminal"))
        assertTrue(KitToolIdForms.hasPrefix("WebSearch", "web_"))
        assertTrue(KitToolIdForms.hasPrefix("HttpRequest", "http_"))
        assertTrue(KitToolIdForms.hasPrefix("SkillList", "skill_"))
        assertTrue(KitToolIdForms.hasPrefix("code_read", "code_"))
        assertTrue(KitToolIdForms.hasPrefix("Anything", ""))
    }

    @Test
    fun `hasPrefix rejects non-family ids`() {
        assertFalse(KitToolIdForms.hasPrefix("BrowserClick", "code_"))
        assertFalse(KitToolIdForms.hasPrefix("AppInfo", "terminal"))
        assertFalse(KitToolIdForms.hasPrefix("Clipboard", "shell_"))
    }

    // ═══ matchesPrefix：mcp__ 命名空间保留原样匹配 ═══

    @Test
    fun `mcp namespace prefix stays literal and never absorbs manager tools`() {
        // 动态一等工具（两代注册表同形态）→ 命中
        assertTrue(KitToolIdForms.matchesPrefix("mcp__github__create_issue", "mcp__"))
        // 管理工具（McpCall / McpList / McpConnect）→ 键以 mcp 开头但不命中
        assertFalse(KitToolIdForms.matchesPrefix("McpCall", "mcp__"))
        assertFalse(KitToolIdForms.matchesPrefix("McpList", "mcp__"))
        assertFalse(KitToolIdForms.matchesPrefix("McpConnect", "mcp__"))
        // 非 mcp 家族其余前缀仍形态归一
        assertTrue(KitToolIdForms.matchesPrefix("SkillList", "skill_"))
        assertTrue(KitToolIdForms.matchesPrefix("CodeTodo", "code_"))
    }
}
