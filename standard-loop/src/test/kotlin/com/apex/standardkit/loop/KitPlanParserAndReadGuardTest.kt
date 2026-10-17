package com.apex.standardkit.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * KitPlanParser / KitReadGuard 单元测试。
 */
class KitPlanParserAndReadGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ═══ KitPlanParser ═══

    @Test
    fun `plan parser extracts goal and numbered steps`() {
        val text = """
            ## Plan

            ### Goal
            Add dark theme support

            ### Steps
            1. Audit existing color tokens
            2. Introduce a theme provider
            3. Wire Compose surfaces to the provider

            ### Verification
            Manual toggle works.
        """.trimIndent()
        val plan = KitPlanParser.parse(text, null)
        assertNotNull(plan)
        assertEquals("Add dark theme support", plan!!.goal)
        assertEquals(3, plan.steps.size)
        assertEquals("Audit existing color tokens", plan.steps[0].description)
        assertEquals("Wire Compose surfaces to the provider", plan.steps[2].description)
    }

    @Test
    fun `plan parser accepts checkbox and bullet list forms`() {
        val text = """
            ### Goal
            Migrate build scripts

            ### Steps
            - [ ] inventory groovy scripts
            - [x] pilot migration of :app
            * document the DSL mapping
        """.trimIndent()
        val plan = KitPlanParser.parse(text, null)
        assertNotNull(plan)
        assertEquals(3, plan!!.steps.size)
        assertEquals("pilot migration of :app", plan.steps[1].description)
        assertEquals("document the DSL mapping", plan.steps[2].description)
    }

    @Test
    fun `plan parser returns null without steps section`() {
        assertNull(KitPlanParser.parse("### Goal\nJust answering a question.", null))
    }

    @Test
    fun `plan parser returns null for empty steps section`() {
        assertNull(KitPlanParser.parse("### Steps\n\n### Verification\nnone", null))
    }

    @Test
    fun `plan parser falls back to provided title when goal missing`() {
        val plan = KitPlanParser.parse("### Steps\n1. Do the thing", "fallback title")
        assertNotNull(plan)
        assertEquals("fallback title", plan!!.goal)
    }

    @Test
    fun `plan parser stops at next section header`() {
        val text = """
            ### Goal
            Ship it

            ### Steps
            1. step one
            ### Risks
            - none
        """.trimIndent()
        val plan = KitPlanParser.parse(text, null)
        assertNotNull(plan)
        assertEquals(1, plan!!.steps.size)
        assertEquals("step one", plan.steps[0].description)
    }

    // ═══ KitReadGuard ═══

    private fun guard(root: java.io.File?) = KitReadGuard(workspaceRootProvider = { root?.absolutePath })

    @Test
    fun `edit without prior read is rejected`() {
        val file = tmp.newFile("App.kt")
        val g = guard(tmp.root)
        val block = g.check("code_edit", """{"path":"${file.name}"}""")
        assertNotNull(block)
        assertTrue(block!!.contains("has not been read"))
    }

    @Test
    fun `read then edit passes`() {
        val file = tmp.newFile("App.kt")
        val g = guard(tmp.root)
        g.record("code_read", """{"path":"${file.name}"}""")
        assertNull(g.check("code_edit", """{"path":"${file.name}"}"""))
    }

    @Test
    fun `write of existing file needs prior read`() {
        val file = tmp.newFile("Existing.kt")
        val g = guard(tmp.root)
        assertNotNull(g.check("code_write", """{"path":"${file.name}"}"""))
        g.record("code_read", """{"path":"${file.name}"}""")
        assertNull(g.check("code_write", """{"path":"${file.name}"}"""))
    }

    @Test
    fun `write of new file is exempt`() {
        val g = guard(tmp.root)
        assertNull(g.check("code_write", """{"path":"brand_new_dir/Nuke.kt"}"""))
    }

    @Test
    fun `path forms normalize to the same tracked key`() {
        val file = tmp.newFile("A.kt")
        val g = guard(tmp.root)
        // 绝对路径读
        g.record("code_read", """{"path":"${file.absolutePath}"}""")
        // 相对路径编（同文件不同形态）→ 已读过
        assertNull(g.check("code_edit", """{"path":"${file.name}"}"""))
        // guest 前缀形态 → 同样命中
        assertNull(g.check("code_edit", """{"path":"/workspace/${file.name}"}"""))
    }

    @Test
    fun `unrelated tools bypass the guard`() {
        val g = guard(tmp.root)
        assertNull(g.check("shell_execute", """{"command":"ls"}"""))
        assertNull(g.check("code_grep", """{"pattern":"x"}"""))
    }

    @Test
    fun `reset clears tracked files`() {
        val file = tmp.newFile("B.kt")
        val g = guard(tmp.root)
        g.record("code_read", """{"path":"${file.name}"}""")
        assertNull(g.check("code_edit", """{"path":"${file.name}"}"""))
        g.reset()
        assertNotNull(g.check("code_edit", """{"path":"${file.name}"}"""))
    }
}
