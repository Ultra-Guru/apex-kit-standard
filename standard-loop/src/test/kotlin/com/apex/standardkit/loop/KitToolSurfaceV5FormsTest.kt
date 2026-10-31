package com.apex.standardkit.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * # 回归锁：PascalCase（v5）注册表下标准线工具面完整性
 *
 * ## 事故形态（2026-10 用户反馈「agent 用不了 Ubuntu / 无 shell 工具」）
 *
 * 宿主注册表升级到 v5 PascalCase id（`CodeRead` / `ShellExecute` /
 * `TerminalExec`…），而本库的优先序前缀 / 画像白名单 / 只读 / shell /
 * 编辑分类表全部按旧代 snake_case 字面量精确比较——**整体静默失配**：
 *
 * 1. `PRIORITY_PREFIXES`（`code_` / `shell_execute` / `terminal.`…）一个
 *    都匹配不上 → 全部工具跌到 `Int.MIN_VALUE` 优先级 → `MAX_TOOLS=48`
 *    截断退化为**字典序** → 工具面只剩字母序靠前的家族（Agent、App、
 *    Browser 族……），编码 / shell / 终端 / web / skill 族**整体消失**；
 * 2. 画像白名单 `code_read` 过滤不到 `CodeRead` → 只读画像（规划师 /
 *    探索 / 评审）工具面为**空**；
 * 3. 只读 / shell / 编辑分类全部判否 → DEFAULT 模式对 `CodeRead` 也弹
 *    权限询问；子代理 ASK 折叠 DENY → 连读操作都被拒；
 * 4. `bash` 别名归一到 `shell_execute`（注册表里已不存在）→ 未知工具。
 *
 * 修复：全部 id 匹配经 [KitToolIdForms] 形态归一（含 `mcp__` 命名空间
 * 原样匹配保留）。本测试用一份**仿宿主 v5 注册表快照**锁住修复行为。
 */
class KitToolSurfaceV5FormsTest {

    /** 仿宿主 v5 注册表：编码/shell/终端/web/skill 族 + 字典序靠前的干扰族。 */
    private fun v5Registry(): List<KitToolDefinition> {
        val coding = listOf(
            "CodeCheck", "CodeEdit", "CodeGitBranch", "CodeGitCommit", "CodeGitDiff",
            "CodeGitLog", "CodeGitStatus", "CodeGlob", "CodeGrep", "CodeRead",
            "CodeTask", "CodeTodo", "CodeWrite",
            "ShellExecute",
            "TerminalBackends", "TerminalBridge", "TerminalClose", "TerminalCreate",
            "TerminalDiagnostics", "TerminalExec", "TerminalFs", "TerminalLinuxPackages",
            "TerminalLinuxStatus", "TerminalObserve", "TerminalRun", "TerminalSnapshot",
            "TerminalUbuntuEnsure", "TerminalWait", "TerminalWorkspaces", "TerminalWrite",
            "WebFetch", "WebSearch", "HttpRequest",
            "SkillActivate", "SkillCreate", "SkillInstall", "SkillList",
            "SkillSearch", "SkillUninstall"
        )
        // 字典序靠前的非编码族（事故形态里正是它们挤掉了编码族）
        val fillers = listOf(
            "AgentProfileList", "AgentProviderSetKey", "AgentSettingGet", "AgentSettingSet",
            "AlarmSet",
            "AppDownloadInstall", "AppForceStop", "AppInfo", "AppInstall", "AppLaunch",
            "AppList", "AppScreenOpen", "AppUninstall",
            "AskUser", "AskUserChoice",
            "BatteryStatus",
            "BrowserClick", "BrowserExecuteJs", "BrowserInput", "BrowserNavigate",
            "BrowserScreenshot", "BrowserScroll", "BrowserSnapshot", "BrowserTabs"
        )
        return (coding + fillers).map { KitToolDefinition(name = it, description = "d", parameters = "{}") }
    }

    private fun defs(vararg names: String): List<KitToolDefinition> =
        names.map { KitToolDefinition(name = it, description = "d", parameters = "{}") }

    private fun planIds(plan: KitToolPlan): List<String> =
        plan.providerNameToId.values.toList()

    // ═══ 1. 核心：截断优先序必须按「编码相关优先」而非字典序 ═══

    @Test
    fun `v5 registry keeps coding families inside the max tools budget`() {
        val plan = KitToolSurface.buildToolPlan(KitAgentCatalog.BUILD, v5Registry())
        val ids = planIds(plan)

        // 编码 / shell / 终端族全部在场（事故形态里被字典序截掉）
        listOf(
            "CodeRead", "CodeWrite", "CodeEdit", "CodeGrep", "CodeGlob", "CodeTodo",
            "CodeCheck", "CodeTask",
            "ShellExecute", "TerminalExec", "TerminalCreate", "TerminalRun",
            "TerminalUbuntuEnsure", "TerminalWait",
            "WebSearch", "WebFetch", "HttpRequest", "SkillList"
        ).forEach { expected ->
            assertTrue("v5 工具面应包含 $expected（实际：$ids）", expected in ids)
        }
        // 合成 task 工具恒在
        assertTrue(plan.definitions.any { it.name == "task" })
        // 预算纪律不破坏
        assertTrue(plan.definitions.size <= KitToolSurface.MAX_TOOLS + 1)
    }

    @Test
    fun `v5 registry truncation drops fillers instead of coding tools`() {
        // 字典序靠前的干扰族必须给编码族让位（55 个工具 > 48 预算）
        val plan = KitToolSurface.buildToolPlan(KitAgentCatalog.BUILD, v5Registry())
        val ids = planIds(plan)
        assertTrue("字典序干扰族应被截断（App*/Browser* 不应全量在场）", ids.size < 55)
        assertFalse("SkillUninstall（skill 族末位）在预算内可被裁，但 CodeRead 不可", "CodeRead" !in ids)
    }

    @Test
    fun `legacy snake_case registry still prioritizes coding families`() {
        // 旧代注册表行为不回归（双形态同权）
        val legacy = defs(
            "code_read", "code_write", "code_edit", "shell_execute", "terminal.exec",
            "app_info", "browser_click", "battery_status"
        )
        val ids = planIds(KitToolSurface.buildToolPlan(KitAgentCatalog.BUILD, legacy))
        assertTrue("code_read" in ids)
        assertTrue("shell_execute" in ids)
        assertTrue("terminal.exec" in ids)
    }

    // ═══ 2. 画像白名单：v5 注册表下只读画像不再空面 ═══

    @Test
    fun `readonly personas surface v5 coding tools instead of nothing`() {
        val registry = v5Registry()
        // 规划师：只读 + git 状态 + todo
        val planIds = planIds(KitToolSurface.buildToolPlan(KitAgentCatalog.PLAN, registry))
        listOf("CodeRead", "CodeGrep", "CodeGlob", "CodeCheck", "CodeGitStatus", "CodeTodo")
            .forEach { expected -> assertTrue("PLAN 画像应包含 $expected", expected in planIds) }
        assertFalse("CodeWrite" in planIds)
        assertFalse("ShellExecute" in planIds)

        // 探索（子代理）：同只读面
        val exploreIds = planIds(KitToolSurface.buildToolPlan(KitAgentCatalog.EXPLORE, registry))
        assertTrue("CodeRead" in exploreIds)
        assertFalse("CodeWrite" in exploreIds)

        // 调研（子代理）：联网 + 只读 + mcp__ 命名空间
        val researchIds = planIds(KitToolSurface.buildToolPlan(KitAgentCatalog.RESEARCH, registry))
        assertTrue("WebSearch" in researchIds)
        assertTrue("CodeRead" in researchIds)
        assertFalse("ShellExecute" in researchIds)
    }

    @Test
    fun `mcp namespace entry admits dynamic tools but not manager tools`() {
        val registry = v5Registry() + defs("mcp__github__create_issue", "McpCall", "McpList")
        val researchIds = planIds(KitToolSurface.buildToolPlan(KitAgentCatalog.RESEARCH, registry))
        assertTrue("mcp__github__create_issue" in researchIds)
        assertFalse("McpCall 不应被 mcp__ 前缀吸入（命名空间保留原样匹配）", "McpCall" in researchIds)
    }

    // ═══ 3. 强制函数集：v5 形态精确收窄 ═══

    @Test
    fun `forced tool ids narrow the surface in v5 form`() {
        val plan = KitToolSurface.buildToolPlan(
            KitAgentCatalog.BUILD,
            v5Registry(),
            forcedToolIds = setOf("CodeRead", "ShellExecute")
        )
        assertEquals(setOf("CodeRead", "ShellExecute"), planIds(plan).toSet())
    }

    @Test
    fun `forced tool ids accept legacy form against v5 registry`() {
        // 用户圈选持久化的是旧形态 id 时，归一层折算到注册表现役形态
        val plan = KitToolSurface.buildToolPlan(
            KitAgentCatalog.BUILD,
            v5Registry(),
            forcedToolIds = setOf("code_read")
        )
        assertEquals(setOf("CodeRead"), planIds(plan).toSet())
    }

    // ═══ 4. 别名归一：折算到注册表现役形态 ═══

    @Test
    fun `bash alias resolves to the v5 form when registry is v5`() {
        val available = v5Registry().map { it.name }
        assertEquals("ShellExecute", KitToolSurface.resolveRegistryId("bash", available))
        assertEquals("CodeRead", KitToolSurface.resolveRegistryId("read", available))
        assertEquals("CodeEdit", KitToolSurface.resolveRegistryId("edit", available))
        // 未知名原样返回（不因归一层引入幻觉）
        assertEquals("mcp__github__x", KitToolSurface.resolveRegistryId("mcp__github__x", available))
    }

    @Test
    fun `bash alias resolves to the legacy form when registry is legacy`() {
        val available = listOf("code_read", "shell_execute", "code_write")
        assertEquals("shell_execute", KitToolSurface.resolveRegistryId("bash", available))
    }

    @Test
    fun `suggest tool ids works across forms`() {
        val suggestions = KitToolSurface.suggestToolIds(
            "CodeReads",
            listOf("CodeRead", "CodeWrite", "ShellExecute")
        )
        assertTrue(suggestions.contains("CodeRead"))
    }

    // ═══ 5. 提示词开关：纪律段 / Todo 指引不再静默缺失 ═══

    @Test
    fun `plan capability predicates recognize v5 ids`() {
        val v5Shell = listOf("ShellExecute", "TerminalExec")
        val v5Write = listOf("CodeWrite")
        val v5Edit = listOf("CodeEdit")
        val v5Todo = listOf("CodeTodo")

        assertTrue(KitToolSurface.planHasShell(v5Shell))
        assertTrue(KitToolSurface.planHasShell(listOf("TerminalCreate")))
        assertTrue(KitToolSurface.planHasShell(listOf("terminal.exec")))
        assertFalse(KitToolSurface.planHasShell(listOf("BrowserClick")))

        assertTrue(KitToolSurface.planHasWrite(v5Write))
        assertTrue(KitToolSurface.planHasWrite(listOf("code_write")))
        assertFalse(KitToolSurface.planHasWrite(listOf("CodeRead")))

        assertTrue(KitToolSurface.planHasEdit(v5Edit))
        assertTrue(KitToolSurface.planHasEdit(listOf("code_edit")))
        assertFalse(KitToolSurface.planHasEdit(listOf("CodeRead")))

        assertTrue(KitToolSurface.planHasTodo(v5Todo))
        assertTrue(KitToolSurface.planHasTodo(listOf("code_todo")))
        // task（子代理派发）与 todo（清单工具）是不同工具——不互认
        assertFalse(KitToolSurface.planHasTodo(listOf("task")))
        assertFalse(KitToolSurface.planHasTodo(listOf("CodeRead")))
    }

    @Test
    fun `tool guide renders aliases against the registry-live form`() {
        val plan = KitToolSurface.buildToolPlan(KitAgentCatalog.BUILD, v5Registry())
        val guide = KitToolSurface.buildToolGuide(
            KitAgentCatalog.BUILD,
            plan.definitions.map { it.name }
        )
        assertTrue(guide.contains("aliases are auto-resolved"))
        assertTrue("指引应显示 bash→ShellExecute（现役形态）", guide.contains("bash→ShellExecute"))
        assertFalse("指引不应再显示旧形态 bash→shell_execute", guide.contains("bash→shell_execute"))
    }

    // ═══ 6. 权限门：v5 id 的分类与裁决 ═══

    @Test
    fun `permission engine classifies v5 ids correctly`() {
        assertTrue(KitPermissionEngine.isReadOnlyTool("CodeRead"))
        assertTrue(KitPermissionEngine.isReadOnlyTool("CodeGrep"))
        assertTrue(KitPermissionEngine.isReadOnlyTool("CodeGitStatus"))
        assertTrue(KitPermissionEngine.isReadOnlyTool("WebSearch"))
        assertTrue(KitPermissionEngine.isReadOnlyTool("CodeTodo"))
        assertTrue(KitPermissionEngine.isReadOnlyTool("SkillList"))
        assertTrue(KitPermissionEngine.isReadOnlyTool("mcp__github__x"))
        assertFalse(KitPermissionEngine.isReadOnlyTool("McpCall"))
        assertFalse(KitPermissionEngine.isReadOnlyTool("ShellExecute"))
        assertFalse(KitPermissionEngine.isReadOnlyTool("CodeWrite"))

        assertTrue(KitPermissionEngine.isShellTool("ShellExecute"))
        assertTrue(KitPermissionEngine.isShellTool("TerminalExec"))
        assertTrue(KitPermissionEngine.isShellTool("terminal.exec"))
        assertTrue(KitPermissionEngine.isShellTool("shell_execute"))
        assertFalse(KitPermissionEngine.isShellTool("TerminalCreate"))

        assertTrue(KitPermissionEngine.isEditTool("CodeEdit"))
        assertTrue(KitPermissionEngine.isEditTool("CodeWrite"))
        assertTrue(KitPermissionEngine.isEditTool("CodeGitCommit"))
        assertFalse(KitPermissionEngine.isEditTool("CodeRead"))
    }

    @Test
    fun `default mode allows v5 readonly and asks for v5 writes`() {
        val e = KitPermissionEngine(KitPermissionMode.DEFAULT)
        assertEquals(KitPermissionEffect.ALLOW, e.decide("CodeRead", "{}").effect)
        assertEquals(KitPermissionEffect.ALLOW, e.decide("CodeGrep", "{}").effect)
        assertEquals(KitPermissionEffect.ASK, e.decide("CodeWrite", "{}").effect)
        assertEquals(KitPermissionEffect.ASK, e.decide("ShellExecute", "{}").effect)
    }

    @Test
    fun `accept_edits allows v5 edit family but still asks for v5 shell`() {
        val e = KitPermissionEngine(KitPermissionMode.ACCEPT_EDITS)
        assertEquals(KitPermissionEffect.ALLOW, e.decide("CodeEdit", "{}").effect)
        assertEquals(KitPermissionEffect.ALLOW, e.decide("CodeWrite", "{}").effect)
        assertEquals(KitPermissionEffect.ASK, e.decide("ShellExecute", "{}").effect)
    }

    @Test
    fun `plan mode allows v5 readonly and hard-denies v5 writes`() {
        val e = KitPermissionEngine(KitPermissionMode.PLAN)
        assertEquals(KitPermissionEffect.ALLOW, e.decide("CodeRead", "{}").effect)
        assertEquals(KitPermissionEffect.DENY, e.decide("CodeWrite", "{}").effect)
    }

    @Test
    fun `legacy rule patterns still hit v5 tool ids`() {
        val e = KitPermissionEngine(KitPermissionMode.DEFAULT)
        e.updateRules(listOf(KitPermissionRule("code_write", KitPermissionEffect.ALLOW)))
        assertEquals(KitPermissionEffect.ALLOW, e.decide("CodeWrite", "{}").effect)
        // 前缀通配规则同理
        e.updateRules(listOf(KitPermissionRule("code_git_*", KitPermissionEffect.DENY)))
        assertEquals(KitPermissionEffect.DENY, e.decide("CodeGitCommit", "{}").effect)
    }

    @Test
    fun `shell command rules scope onto v5 shell tools`() {
        val e = KitPermissionEngine(KitPermissionMode.DEFAULT)
        // 会话记忆的命令级放行按 shell 工具识别（v5 形态）
        assertTrue(KitPermissionEngine.isShellTool("ShellExecute"))
        // 命令级规则匹配 shell 工具的 command 参数
        e.updateCommandRules(listOf(KitPermissionRule("git status*", KitPermissionEffect.ALLOW)))
        val d = e.decide("ShellExecute", """{"command":"git status --short"}""")
        assertEquals(KitPermissionEffect.ALLOW, d.effect)
    }

    // ═══ 7. read-before-edit 守卫：v5 id 受同等约束 ═══

    @Test
    fun `read guard guards and records v5 edit-write-read ids`() {
        val guard = KitReadGuard { null }
        // 未读先编 → 拦截
        val blocked = guard.check("CodeEdit", """{"path":"/tmp/exists.txt"}""")
        assertTrue(blocked != null)
        // 读过 → 放行
        guard.record("CodeRead", """{"path":"/tmp/exists.txt"}""")
        assertTrue(guard.check("CodeEdit", """{"path":"/tmp/exists.txt"}""") == null)
        // 守卫不作用于非编辑类
        assertTrue(guard.check("CodeRead", """{"path":"/tmp/exists.txt"}""") == null)
    }

    // ═══ 8. 并行安全面：v5 只读族可并行 ═══

    @Test
    fun `default parallel safe recognizes v5 readonly families`() {
        fun safe(name: String) =
            KitToolBatchPlanner.defaultParallelSafe(KitToolInvocation("c1", name, "{}"))
        assertTrue(safe("CodeRead"))
        assertTrue(safe("CodeGrep"))
        assertTrue(safe("CodeGlob"))
        assertTrue(safe("CodeTodo"))
        assertTrue(safe("CodeCheck"))
        assertTrue(safe("WebSearch"))
        assertTrue(safe("SkillList"))
        assertTrue(safe("mcp__github__x"))
        assertTrue(safe("task"))
        assertFalse(safe("ShellExecute"))
        assertFalse(safe("CodeWrite"))
        assertFalse(safe("CodeEdit"))
        assertFalse(safe("McpCall"))
    }
}
