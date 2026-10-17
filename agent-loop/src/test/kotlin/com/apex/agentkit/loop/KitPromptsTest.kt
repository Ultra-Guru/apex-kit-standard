package com.apex.agentkit.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KitPrompts 提示词层测试：非空 / 关键指令在场 / 结构段完整 / 续跑指令语义。
 *
 * 提示词是任务循环的灵魂——这里锁住每段的核心动词，防重构时静默漂移。
 */
class KitPromptsTest {

    @Test
    fun `all profile prompts are non-blank and carry identity`() {
        val prompts = mapOf(
            "build" to KitPrompts.buildAgent(),
            "plan" to KitPrompts.planAgent(),
            "general" to KitPrompts.generalAgent(),
            "explore" to KitPrompts.exploreSubAgent(),
            "research" to KitPrompts.researchSubAgent(),
            "general-sub" to KitPrompts.generalSubAgent(),
            "reviewer" to KitPrompts.reviewerSubAgent()
        )
        prompts.forEach { (key, prompt) ->
            assertTrue("$key prompt not blank", prompt.isNotBlank())
            assertTrue("$key prompt 有体量", prompt.length > 200)
        }
        assertTrue(KitPrompts.planAgent().contains("READ-ONLY"))
        assertTrue(KitPrompts.exploreSubAgent().contains("READ-ONLY"))
        assertTrue(KitPrompts.buildAgent().contains("BUILD agent"))
    }

    @Test
    fun `plan prompt defines the output contract`() {
        val p = KitPrompts.planAgent()
        assertTrue(p.contains("### Goal"))
        assertTrue(p.contains("### Steps"))
        assertTrue(p.contains("### Verification"))
        assertTrue(p.contains("### Risks"))
    }

    @Test
    fun `environment context renders the env block with model identity`() {
        val env = KitPrompts.environmentContext(
            modelId = "glm-4.7",
            todayText = "Sat Oct 4 2026",
            platformText = "android-arm64",
            workdirLabel = "/ws/demo",
            isGitRepo = true
        )
        assertTrue(env.startsWith("You are powered by the model named glm-4.7."))
        assertTrue(env.contains("<env>"))
        assertTrue(env.contains("</env>"))
        assertTrue(env.contains("Working directory: /ws/demo"))
        assertTrue(env.contains("Is directory a git repo: yes"))
        assertTrue(env.contains("Platform: android-arm64"))
        assertTrue(env.contains("Today's date: Sat Oct 4 2026"))
    }

    @Test
    fun `environment context degrades gracefully on unknowns`() {
        val env = KitPrompts.environmentContext(
            modelId = null,
            todayText = "Sat Oct 4 2026",
            platformText = "android-arm64",
            workdirLabel = null,
            isGitRepo = null
        )
        assertFalse("模型未知时省略首句", env.contains("powered by"))
        assertTrue(env.contains("Is directory a git repo: unknown"))
        assertTrue(env.contains("Working directory: (unknown)"))
        val noRepo = KitPrompts.environmentContext(
            modelId = null, todayText = "d", platformText = "p",
            workdirLabel = null, isGitRepo = false
        )
        assertTrue(noRepo.contains("Is directory a git repo: no"))
    }

    @Test
    fun `core conduct locks tone objectivity file git and citation rules`() {
        val c = KitPrompts.coreConduct()
        assertTrue(c.contains("## Core Conduct"))
        // 语气
        assertTrue(c.contains("information-dense"))
        assertTrue(c.contains("under 4 lines"))
        assertTrue(c.contains("emoji if the user explicitly requests"))
        // 并行化（新增）
        assertTrue(c.contains("Prefer parallel tool calls when they are independent"))
        // 专业客观
        assertTrue(c.contains("technical accuracy beats agreement"))
        // 文件纪律
        assertTrue(c.contains("ALWAYS prefer editing an existing file"))
        assertTrue(c.contains("proactively create documentation files"))
        // git 纪律
        assertTrue(c.contains("NEVER commit unless the user explicitly asks"))
        assertTrue(c.contains("git status / git diff / git log"))
        // 代码引用格式
        assertTrue(c.contains("path/to/file.kt:123"))
        // 通信与运行时提醒机制
        assertTrue(c.contains("<system-reminder>"))
        assertTrue(c.contains("\"SYSTEM:\""))
        assertTrue(c.contains("not user input"))
        // 完成门槛
        assertTrue(c.contains("Never claim completion"))
    }

    @Test
    fun `tool discipline maps file ops to dedicated tools`() {
        val d = KitPrompts.toolDiscipline(hasShell = true, hasWrite = true, hasEdit = true)
        assertTrue(d.contains("## Tool Discipline"))
        assertTrue(d.contains("NOT `find`"))
        assertTrue(d.contains("NOT `grep`"))
        assertTrue(d.contains("NOT `cat`"))
        assertTrue(d.contains("NOT `sed`"))
        assertTrue(d.contains("NOT `echo >`"))
        assertTrue(d.contains("&&"))
        assertTrue(d.contains("sub-agent via `task`"))
        // write/edit 用法行（按工具存在性拼装——上游 render 语义）
        assertTrue(d.contains("completely replace"))
        assertTrue(d.contains("targeted changes"))
        // 噪声链警告（上游新约束）
        assertTrue(d.contains("echo \"====\";"))
    }

    @Test
    fun `tool discipline empty without relevant tools`() {
        assertEquals("", KitPrompts.toolDiscipline(hasShell = false, hasWrite = false, hasEdit = false))
        // 只有 write 时无 shell 段
        val w = KitPrompts.toolDiscipline(hasShell = false, hasWrite = true, hasEdit = false)
        assertTrue(w.contains("completely replace"))
        assertFalse(w.contains("NOT `find`"))
    }

    @Test
    fun `task methodology carries the working laws`() {
        val m = KitPrompts.taskMethodology()
        assertTrue(m.contains("Read before you write"))
        assertTrue(m.contains("minimal diff"))
        assertTrue(m.contains("Verify after every change"))
        assertTrue(m.contains("Keep a todo list"))
        assertTrue(m.contains("Delegate exploration, execute changes"))
        // 并行法则（新增）
        assertTrue(m.contains("Parallelize independent tool calls"))
        assertTrue(m.contains("in the SAME response"))
        assertTrue(m.contains("Report honestly"))
    }

    @Test
    fun `todo guidance locks the contract`() {
        val t = KitPrompts.todoGuidance()
        assertTrue(t.contains("## Working With The Todo List"))
        assertTrue(t.contains("`pending` → `in_progress` → `completed`"))
        assertTrue(t.contains("never batch updates"))
        assertTrue(t.contains("verified"))
    }

    // ═══ 压缩模板（七节结构）═══

    @Test
    fun `compaction summary template carries seven sections`() {
        val p = KitPrompts.compactionSummary("instruction")
        assertTrue(p.contains("## Objective"))
        assertTrue(p.contains("## Requirements"))
        assertTrue(p.contains("## Decisions"))
        assertTrue(p.contains("## Work State"))
        assertTrue(p.contains("## Next Move"))
        assertTrue(p.contains("## Relevant Files"))
        assertTrue(p.contains("## Important Context"))
        // 文件清单上限
        assertTrue(p.contains("at most 15"))
        // 模板规则
        assertTrue(p.contains("single-line bullets"))
        assertTrue(p.contains("Do not mention the summary process"))
    }

    @Test
    fun `compaction nudge demands template headings`() {
        val n = KitPrompts.compactionNudge()
        assertTrue(n.contains("exact section headings"))
        assertTrue(n.contains("## Objective"))
    }

    @Test
    fun `compaction summary incremental merge carries prior`() {
        val p = KitPrompts.compactionSummary("instruction", priorSummary = "旧摘要：X 已完成")
        assertTrue(p.contains("<prior-summary>"))
        assertTrue(p.contains("旧摘要：X 已完成"))
        assertTrue(p.contains("consolidated summary"))
        // 无旧摘要时不带段
        val fresh = KitPrompts.compactionSummary("instruction")
        assertFalse(fresh.contains("<prior-summary>"))
    }

    // ═══ 续跑指令（新增语义）═══

    @Test
    fun `continue after output limit forbids apology and repetition`() {
        val c = KitPrompts.continueAfterOutputLimit()
        assertTrue(c.contains("output token limit"))
        assertTrue(c.contains("Do not apologize, recap, or repeat yourself"))
        assertTrue(c.contains("Continue the remaining work"))
    }

    @Test
    fun `continue after interrupt resumes without repeating`() {
        val c = KitPrompts.continueAfterInterrupt()
        assertTrue(c.contains("interrupted"))
        assertTrue(c.contains("without repeating completed content"))
    }

    @Test
    fun `max steps prompt is a strict text-only contract`() {
        val m = KitPrompts.maxStepsPrompt()
        assertTrue(m.contains("MAXIMUM STEPS REACHED"))
        assertTrue(m.contains("Do NOT make any tool calls"))
        assertTrue(m.contains("overrides ALL other instructions"))
        assertTrue(m.contains("Recommendations"))
    }

    // ═══ 运行时注入文案 ═══

    @Test
    fun `permission denial guides the model to a safer route`() {
        val d = KitPrompts.permissionDeniedToolResult("code_write", "DEFAULT 模式")
        assertTrue(d.contains("Permission denied for `code_write`"))
        assertTrue(d.contains("safer route"))
        assertTrue(d.contains("Do not retry the same call"))
    }

    @Test
    fun `unknown tool result suggests corrections`() {
        val u = KitPrompts.unknownToolResult("code_reads", listOf("code_read"))
        assertTrue(u.contains("Unknown tool: 'code_reads'"))
        assertTrue(u.contains("Did you mean: code_read?"))
    }

    @Test
    fun `repetitive call warning nudges strategy change`() {
        val w = KitPrompts.repetitiveCallWarning(3, "code_grep")
        assertTrue(w.contains("3 times"))
        assertTrue(w.contains("loop"))
    }

    @Test
    fun `sub agent tool result wraps xml envelope`() {
        val ok = KitPrompts.subAgentToolResult(
            KitSubAgentOutcome(KitAgentKind.EXPLORE, "结论", 3, 5, 1000)
        )
        assertTrue(ok.contains("<task state=\"completed\">"))
        assertTrue(ok.contains("结论"))
        assertTrue(ok.contains("3 turns"))
        val failed = KitPrompts.subAgentToolResult(
            KitSubAgentOutcome.failure(KitAgentKind.EXPLORE, "超时")
        )
        assertTrue(failed.contains("<task state=\"error\">"))
        val partial = KitPrompts.subAgentToolResult(
            KitSubAgentOutcome(KitAgentKind.EXPLORE, "部分", 2, 3, 1000, truncated = true)
        )
        assertTrue(partial.contains("<task state=\"partial\">"))
    }

    // ═══ MCP 指引段 ═══

    @Test
    fun `mcp instructions section renders server envelope`() {
        val section = KitPrompts.mcpInstructionsSection(
            listOf(
                KitMcpServerInstructions(
                    serverName = "github",
                    instructions = "Use create_issue with template.",
                    toolIdPrefix = "mcp__github__"
                )
            )
        )
        assertNotNull(section)
        assertTrue(section!!.contains("<mcp_instructions>"))
        assertTrue(section.contains("<server name=\"github\">"))
        assertTrue(section.contains("Use create_issue with template."))
        // 空列表 → 不注入
        assertNull(KitPrompts.mcpInstructionsSection(emptyList()))
    }

    @Test
    fun `compaction continue directive stays stable`() {
        assertTrue(KitPrompts.COMPACTION_CONTINUE.startsWith("SYSTEM: Earlier context was compacted"))
    }
}
