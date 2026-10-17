package com.apex.standardkit.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KitPermissionEngine 裁决矩阵测试：
 * 规则短路 / PLAN 档硬门 / 会话记忆 / 模式兜底 / 命令级通配 /
 * 子代理折叠 / 敏感文件保护。
 */
class KitPermissionEngineTest {

    private fun engine(mode: KitPermissionMode = KitPermissionMode.DEFAULT) =
        KitPermissionEngine(mode)

    // ═══ 规则 ═══

    @Test
    fun `deny rule short-circuits even under bypass`() {
        val e = engine(KitPermissionMode.BYPASS)
        e.updateRules(listOf(KitPermissionRule("code_edit", KitPermissionEffect.DENY)))
        val d = e.decide("code_edit", "{}")
        assertEquals(KitPermissionEffect.DENY, d.effect)
        assertTrue(d.reason.contains("DENY"))
    }

    @Test
    fun `prefix wildcard rule matches dynamic mcp ids`() {
        val e = engine()
        e.updateRules(listOf(KitPermissionRule("mcp__github_*", KitPermissionEffect.ALLOW)))
        assertEquals(KitPermissionEffect.ALLOW, e.decide("mcp__github__create_issue", "{}").effect)
        assertEquals(KitPermissionEffect.ALLOW, e.decide("mcp__github__list_prs", "{}").effect)
        // 非同前缀不命中 → 走模式兜底
        assertEquals(KitPermissionEffect.ASK, e.decide("gitlab__x", "{}").effect)
    }

    @Test
    fun `single star matches everything`() {
        val e = engine()
        e.updateRules(listOf(KitPermissionRule("*", KitPermissionEffect.ALLOW)))
        assertEquals(KitPermissionEffect.ALLOW, e.decide("anything_at_all", "{}").effect)
    }

    @Test
    fun `blank pattern never matches`() {
        val e = engine()
        e.updateRules(listOf(KitPermissionRule("  ", KitPermissionEffect.ALLOW)))
        assertEquals(KitPermissionEffect.ASK, e.decide("code_write", "{}").effect)
    }

    // ═══ 模式兜底 ═══

    @Test
    fun `default mode allows readonly and asks for writes`() {
        val e = engine()
        assertEquals(KitPermissionEffect.ALLOW, e.decide("code_read", "{}").effect)
        assertEquals(KitPermissionEffect.ALLOW, e.decide("code_grep", "{}").effect)
        assertEquals(KitPermissionEffect.ASK, e.decide("code_write", "{}").effect)
        assertEquals(KitPermissionEffect.ASK, e.decide("shell_execute", "{}").effect)
    }

    @Test
    fun `bypass mode allows everything except deny rules`() {
        val e = engine(KitPermissionMode.BYPASS)
        assertEquals(KitPermissionEffect.ALLOW, e.decide("code_write", "{}").effect)
        assertEquals(KitPermissionEffect.ALLOW, e.decide("shell_execute", "{}").effect)
    }

    @Test
    fun `accept_edits allows edit-family but still asks for shell`() {
        val e = engine(KitPermissionMode.ACCEPT_EDITS)
        assertEquals(KitPermissionEffect.ALLOW, e.decide("code_edit", "{}").effect)
        assertEquals(KitPermissionEffect.ALLOW, e.decide("code_write", "{}").effect)
        assertEquals(KitPermissionEffect.ALLOW, e.decide("code_git_commit", "{}").effect)
        assertEquals(KitPermissionEffect.ASK, e.decide("shell_execute", "{}").effect)
    }

    @Test
    fun `plan mode hard-denies writes regardless of allow rules`() {
        val e = engine(KitPermissionMode.PLAN)
        e.updateRules(listOf(KitPermissionRule("code_write", KitPermissionEffect.ALLOW)))
        assertEquals(KitPermissionEffect.DENY, e.decide("code_write", "{}").effect)
        assertEquals(KitPermissionEffect.ALLOW, e.decide("code_read", "{}").effect)
    }

    // ═══ 会话记忆 ═══

    @Test
    fun `session memory allows subsequent calls without asking`() {
        val e = engine()
        assertEquals(KitPermissionEffect.ASK, e.decide("code_write", "{}").effect)
        e.rememberSessionAllow("code_write")
        val d = e.decide("code_write", "{}")
        assertEquals(KitPermissionEffect.ALLOW, d.effect)
        assertTrue(d.reason.contains("会话记忆"))
    }

    @Test
    fun `reset session memory restores asking`() {
        val e = engine()
        e.rememberSessionAllow("shell_execute")
        assertEquals(KitPermissionEffect.ALLOW, e.decide("shell_execute", "{}").effect)
        e.resetSessionMemory()
        assertEquals(KitPermissionEffect.ASK, e.decide("shell_execute", "{}").effect)
    }

    @Test
    fun `deny rule outranks session memory`() {
        val e = engine(KitPermissionMode.BYPASS)
        e.rememberSessionAllow("code_edit")
        e.updateRules(listOf(KitPermissionRule("code_edit", KitPermissionEffect.DENY)))
        assertEquals(KitPermissionEffect.DENY, e.decide("code_edit", "{}").effect)
    }

    // ═══ 子代理上下文 ═══

    @Test
    fun `ask folds to deny in sub-agent context`() {
        val e = engine()
        val d = e.decide("code_write", "{}", subAgentContext = true)
        assertEquals(KitPermissionEffect.DENY, d.effect)
        assertTrue(d.reason.contains("子代理"))
    }

    // ═══ 命令级通配 ═══

    @Test
    fun `shell command rules match command prefix`() {
        val e = engine(KitPermissionMode.BYPASS)
        e.updateCommandRules(
            listOf(
                KitPermissionRule("git status*", KitPermissionEffect.ALLOW),
                KitPermissionRule("rm*", KitPermissionEffect.DENY)
            )
        )
        val ok = e.decide("shell_execute", """{"command":"git status --short"}""")
        assertEquals(KitPermissionEffect.ALLOW, ok.effect)

        val denied = e.decide("shell_execute", """{"command":"rm -rf /data"}""")
        assertEquals(KitPermissionEffect.DENY, denied.effect)
    }

    @Test
    fun `command session memory allows matching commands only`() {
        val e = engine()
        e.rememberSessionAllow("git status", isCommand = true)
        assertEquals(
            KitPermissionEffect.ALLOW,
            e.decide("shell_execute", """{"command":"git status"}""").effect
        )
        assertEquals(
            KitPermissionEffect.ASK,
            e.decide("shell_execute", """{"command":"git push"}""").effect
        )
    }

    @Test
    fun `command extraction from json handles escapes`() {
        val cmd = KitPermissionEngine.extractCommand("""{"command":"echo \"hi\" && ls"}""")
        assertEquals("echo \"hi\" && ls", cmd)
        assertNull(KitPermissionEngine.extractCommand("not json"))
        assertNull(KitPermissionEngine.extractCommand("""{"path":"x"}"""))
    }

    @Test
    fun `command pattern without star matches first word`() {
        assertTrue(KitPermissionEngine.commandPatternMatches("git", "git status"))
        assertTrue(KitPermissionEngine.commandPatternMatches("git status", "git status"))
        assertTrue(!KitPermissionEngine.commandPatternMatches("git push", "git status"))
    }

    // ═══ 静态判定 ═══

    @Test
    fun `readonly classification`() {
        assertTrue(KitPermissionEngine.isReadOnlyTool("code_read"))
        assertTrue(KitPermissionEngine.isReadOnlyTool("code_todo"))
        assertTrue(KitPermissionEngine.isReadOnlyTool("mcp__github__get"))
        assertTrue(!KitPermissionEngine.isReadOnlyTool("code_write"))
        assertTrue(!KitPermissionEngine.isReadOnlyTool("shell_execute"))
    }

    @Test
    fun `tool id pattern matcher semantics`() {
        assertTrue(KitPermissionEngine.matches("code_read", "code_read"))
        assertTrue(!KitPermissionEngine.matches("code_read", "code_read2"))
        assertTrue(KitPermissionEngine.matches("code_git_*", "code_git_log"))
        assertTrue(!KitPermissionEngine.matches("code_git_*", "code_grep"))
        assertTrue(KitPermissionEngine.matches("*", "whatever"))
        assertTrue(!KitPermissionEngine.matches("", "whatever"))
    }

    // ═══ 敏感文件保护 ═══

    @Test
    fun `sensitive paths are gated by default and exempt under bypass`() {
        val e = engine()
        // .env 读 → ASK（敏感文件保护）
        assertEquals(
            KitPermissionEffect.ASK,
            e.decide("code_read", """{"path":".env"}""").effect
        )
        // 模板文件免检
        assertEquals(
            KitPermissionEffect.ALLOW,
            e.decide("code_read", """{"path":".env.example"}""").effect
        )
        // BYPASS 跳过敏感文件保护
        val bypass = engine(KitPermissionMode.BYPASS)
        assertEquals(
            KitPermissionEffect.ALLOW,
            bypass.decide("code_read", """{"path":".env"}""").effect
        )
    }

    @Test
    fun `sensitive path detection covers key material families`() {
        assertTrue(KitPermissionEngine.isSensitivePath(".env"))
        assertTrue(KitPermissionEngine.isSensitivePath("config/.env.production"))
        assertTrue(KitPermissionEngine.isSensitivePath("/home/u/server.pem"))
        assertTrue(KitPermissionEngine.isSensitivePath("signing.key"))
        assertTrue(KitPermissionEngine.isSensitivePath("release.keystore"))
        assertTrue(KitPermissionEngine.isSensitivePath("id_rsa"))
        assertTrue(KitPermissionEngine.isSensitivePath("~/.ssh/id_rsa_github"))
        assertTrue(KitPermissionEngine.isSensitivePath("credentials.json"))
        assertFalse(KitPermissionEngine.isSensitivePath(".env.example"))
        assertFalse(KitPermissionEngine.isSensitivePath(".env.sample"))
        assertFalse(KitPermissionEngine.isSensitivePath("src/Main.kt"))
    }

    @Test
    fun `path extraction from tool arguments`() {
        assertEquals(
            "src/App.kt",
            KitPermissionEngine.extractPath("""{"path":"src/App.kt","content":"x"}""")
        )
        assertEquals(
            "a/b.txt",
            KitPermissionEngine.extractPath("""{"file_path":"a/b.txt"}""")
        )
        assertNull(KitPermissionEngine.extractPath("""{"pattern":"x"}"""))
    }
}
