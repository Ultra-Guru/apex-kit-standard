package com.apex.standardkit.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KitSkills 技能子系统测试：文档解析 / 路径安全 / 注册表 / 激活渲染 /
 * 指引增量 / 远程索引原子拉取 / 目录装载。
 */
class KitSkillsTest {

    // ═══════════════════════ 测试基座 ═══════════════════════

    /** 内存文件系统（目录即 key 前缀约定）。 */
    private class FakeSkillFs : KitSkillFileSystem {
        val files = LinkedHashMap<String, String>()

        override fun exists(path: String): Boolean = files.containsKey(path)

        override fun readFileStringSafe(path: String): String? = files[path]

        override fun writeFileString(path: String, content: String): Boolean {
            files[path] = content
            return true
        }

        override fun writeWithDirs(path: String, content: String): Boolean {
            files[path] = content
            return true
        }

        override fun rename(from: String, to: String): Boolean {
            var moved = false
            if (files.containsKey(from)) {
                files[to] = files.remove(from)!!
                moved = true
            }
            // 目录语义：把 from 前缀下的全部条目整体搬到 to
            val prefix = "$from/"
            val keys = files.keys.filter { it.startsWith(prefix) }.toList()
            for (key in keys) {
                files[to + key.removePrefix(from)] = files.remove(key)!!
                moved = true
            }
            return moved
        }

        override fun remove(path: String, recursive: Boolean): Boolean {
            val exact = files.remove(path) != null
            var removedAny = exact
            if (recursive) {
                val keys = files.keys.filter { it.startsWith("$path/") }.toList()
                for (key in keys) {
                    files.remove(key)
                    removedAny = true
                }
            }
            return removedAny
        }

        override fun listFiles(directory: String): List<String>? {
            val prefix = if (directory.endsWith("/")) directory else "$directory/"
            val entries = files.keys.filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix) }
            return if (entries.isEmpty() && !files.containsKey(directory)) {
                if (directory.isNotEmpty() && files.keys.any { it.startsWith("$directory/") }) {
                    entries.sorted()
                } else {
                    null
                }
            } else {
                entries.filter { it.isNotEmpty() }.sorted()
            }
        }
    }

    /** 脚本化 HTTP 客户端（URL → 内容）。 */
    private class FakeSkillHttp(
        private val textResponses: Map<String, String> = emptyMap(),
        private val byteResponses: Map<String, String> = emptyMap()
    ) : KitSkillHttpClient {
        override fun fetchText(url: String): String? = textResponses[url]
        override fun fetchBytes(url: String): ByteArray? =
            byteResponses[url]?.toByteArray(Charsets.UTF_8)
    }

    // ═══════════════════════ 文档解析 ═══════════════════════

    @Test
    fun `parses frontmatter and body`() {
        val raw = """
            ---
            name: pdf-report-writer
            description: 生成品牌一致 PDF 周报的完整工作流
            autoinvoke: false
            version: 1.2.0
            ---
            # 步骤
            先取数，再渲染。
        """.trimIndent()
        val info = KitSkillDocParser.parse(raw, "pdf-report-writer")!!
        assertEquals("pdf-report-writer", info.id)
        assertEquals("pdf-report-writer", info.name)
        assertEquals("生成品牌一致 PDF 周报的完整工作流", info.description)
        assertFalse(info.autoinvoke)
        assertEquals("1.2.0", info.version)
        assertTrue(info.content.startsWith("# 步骤"))
        assertTrue(info.content.endsWith("先取数，再渲染。"))
    }

    @Test
    fun `document without frontmatter falls back to identity`() {
        val info = KitSkillDocParser.parse("只有正文", "plain-skill")!!
        assertEquals("plain-skill", info.id)
        assertEquals("plain-skill", info.name)
        assertNull(info.description)
        assertTrue(info.autoinvoke)
        assertNull(info.version)
        assertEquals("只有正文", info.content)
    }

    @Test
    fun `unsafe identity is rejected`() {
        assertNull(KitSkillDocParser.parse("正文", "../escape"))
        assertNull(KitSkillDocParser.parse("正文", ""))
        assertNull(KitSkillDocParser.parse("正文", "a/b"))
    }

    @Test
    fun `invalid declared name falls back to identity as display`() {
        val raw = "---\nname: 带 空格 的 名字\ndescription: d\n---\nbody"
        val info = KitSkillDocParser.parse(raw, "safe-id")!!
        assertEquals("safe-id", info.id)
        assertEquals("safe-id", info.name)
    }

    @Test
    fun `resolveInstructionFile prefers SKILL md then convention`() {
        assertEquals("SKILL.md", KitSkillDocParser.resolveInstructionFile(listOf("SKILL.md", "x.md"), "x"))
        assertEquals("x.md", KitSkillDocParser.resolveInstructionFile(listOf("x.md", "y.txt"), "x"))
        assertNull(KitSkillDocParser.resolveInstructionFile(listOf("y.txt"), "x"))
    }

    // ═══════════════════════ 路径安全 ═══════════════════════

    @Test
    fun `path safety rejects traversal and absolute forms`() {
        assertFalse(KitSkillPathSafety.isSafeSegment(".."))
        assertFalse(KitSkillPathSafety.isSafeSegment("."))
        assertFalse(KitSkillPathSafety.isSafeSegment(""))
        assertFalse(KitSkillPathSafety.isSafeSegment("a/b"))
        assertFalse(KitSkillPathSafety.isSafeSegment("a\\b"))
        assertTrue(KitSkillPathSafety.isSafeSegment("normal-name"))

        assertFalse(KitSkillPathSafety.isSafeRelativePath("../escape"))
        assertFalse(KitSkillPathSafety.isSafeRelativePath("/absolute"))
        assertFalse(KitSkillPathSafety.isSafeRelativePath("C:/windows"))
        assertFalse(KitSkillPathSafety.isSafeRelativePath("https://evil.example/x"))
        assertFalse(KitSkillPathSafety.isSafeRelativePath("a?b"))
        assertFalse(KitSkillPathSafety.isSafeRelativePath(""))
        assertTrue(KitSkillPathSafety.isSafeRelativePath("scripts/build.sh"))
        assertTrue(KitSkillPathSafety.isSafeRelativePath("SKILL.md"))
        assertTrue(KitSkillPathSafety.isSafeRelativePath("docs/reference notes.md"))
    }

    @Test
    fun `contains resolves traversal lexically`() {
        assertTrue(KitSkillPathSafety.contains("/cache/src-1", "/cache/src-1/skill/SKILL.md"))
        assertFalse(KitSkillPathSafety.contains("/cache/src-1", "/cache/src-1"))
        assertFalse(KitSkillPathSafety.contains("/cache/src-1", "/cache/src-1/../src-2/x"))
        assertFalse(KitSkillPathSafety.contains("/cache/src-1", "/cache/src-11/x"))
        assertFalse(KitSkillPathSafety.contains("", "/x"))
    }

    // ═══════════════════════ 注册表 ═══════════════════════

    private fun skill(id: String, description: String? = "desc of $id"): KitSkillInfo =
        KitSkillInfo(
            id = id,
            name = id,
            description = description,
            path = "/skills/$id/SKILL.md",
            content = "body of $id"
        )

    @Test
    fun `registry add update remove with events`() {
        val registry = KitSkillRegistry()
        val events = mutableListOf<KitSkillRegistryEvent>()
        registry.addListener { events.add(it) }

        registry.add(skill("alpha"))
        registry.add(skill("beta"))
        registry.add(skill("alpha")) // 覆盖 → UPDATED

        assertEquals(listOf("alpha", "beta"), registry.list().map { it.id })
        assertEquals(2, registry.size())

        registry.update("beta") { it.copy(description = "new desc") }
        assertEquals("new desc", registry.get("beta")?.description)

        registry.remove("alpha")
        assertNull(registry.get("alpha"))
        assertTrue(registry.remove("ghost").let { registry.size() == 1 })

        assertEquals(
            listOf(
                KitSkillRegistryEventKind.ADDED,
                KitSkillRegistryEventKind.ADDED,
                KitSkillRegistryEventKind.UPDATED,
                KitSkillRegistryEventKind.UPDATED,
                KitSkillRegistryEventKind.REMOVED
            ),
            events.map { it.kind }
        )
    }

    @Test
    fun `availability filters denied and non autoinvoke`() {
        val all = listOf(
            skill("alpha"),
            skill("beta", description = null),
            KitSkillInfo("gamma", "gamma", "g", autoinvoke = false, path = "/p", content = "c")
        )
        val visible = KitSkillAvailability.visible(all) { it.id == "alpha" }.map { it.id }
        assertEquals(listOf("beta", "gamma"), visible)
        val auto = KitSkillAvailability.autoInvokable(all) { false }.map { it.id }
        assertEquals(listOf("alpha"), auto)
    }

    // ═══════════════════════ 激活渲染 ═══════════════════════

    @Test
    fun `activation renders skill content block with sampled files`() {
        val info = skill("pdf")
        val output = KitSkillActivation.toModelOutput(
            info,
            listOf("SKILL.md", "scripts/build.sh", "reference/brand.md", "zz-tail.md")
        )
        assertTrue(output.startsWith("<skill_content name=\"pdf\">"))
        assertTrue(output.contains("# Skill: pdf"))
        assertTrue(output.contains("body of pdf"))
        assertTrue(output.contains("Base directory for this skill: /skills/pdf"))
        assertTrue(output.contains("<file>reference/brand.md</file>"))
        assertTrue(output.contains("<file>scripts/build.sh</file>"))
        assertFalse(output.contains("<file>SKILL.md</file>"))
        assertTrue(output.trimEnd().endsWith("</skill_content>"))
    }

    @Test
    fun `activation sampling is capped and sorted`() {
        val files = (1..30).map { "f%02d.txt".format(it) }
        val output = KitSkillActivation.toModelOutput(skill("cap"), files)
        val fileLines = output.lines().filter { it.startsWith("<file>") }
        assertEquals(KitSkillActivation.SAMPLE_FILE_LIMIT, fileLines.size)
        assertEquals("<file>f01.txt</file>", fileLines.first())
    }

    // ═══════════════════════ 指引渲染 ═══════════════════════

    @Test
    fun `instructions render full list then small deltas`() {
        val frame1 = KitSkillInstructions.summarize(listOf(skill("a", "first"), skill("b", "second")))
        val full = KitSkillInstructions.render(frame1)
        assertTrue(full.contains("<available_skills>"))
        assertTrue(full.contains("<id>a</id>"))
        assertTrue(full.contains("<description>first</description>"))

        // 纯新增 → 小 delta
        val frame2 = KitSkillInstructions.summarize(
            listOf(skill("a", "first"), skill("b", "second"), skill("c", "third"))
        )
        val delta = KitSkillInstructions.renderUpdate(frame1, frame2)
        assertTrue(delta.contains("New skills are available"))
        assertFalse(delta.contains("<available_skills>"))

        // 纯移除 → 提示不可用 id
        val frame3 = KitSkillInstructions.summarize(listOf(skill("a", "first")))
        val removal = KitSkillInstructions.renderUpdate(frame2, frame3)
        assertTrue(removal.contains("no longer available"))
        assertTrue(removal.contains("b, c."))

        // 内容变化 → 全量重述 + supersedes
        val frame4 = KitSkillInstructions.summarize(listOf(skill("a", "changed")))
        val restated = KitSkillInstructions.renderUpdate(frame1, frame4)
        assertTrue(restated.contains("supersedes the previous"))
        assertTrue(restated.contains("<available_skills>"))

        // 无变化 → 全量重述（同口径）
        val same = KitSkillInstructions.renderUpdate(frame1, frame1)
        assertTrue(same.contains("supersedes"))
    }

    // ═══════════════════════ 远程索引拉取 ═══════════════════════

    @Test
    fun `index parsing is tolerant`() {
        val puller = KitSkillRemotePull(FakeSkillFs(), FakeSkillHttp())
        val entries = puller.parseIndex(
            """{"skills":[{"name":"alpha","version":"1.0","files":["SKILL.md","lib/a.txt"]},{"name":"beta","files":["beta.md"]}]}"""
        )
        assertEquals(2, entries.size)
        assertEquals("alpha", entries[0].name)
        assertEquals(listOf("SKILL.md", "lib/a.txt"), entries[0].files)
        assertNull(entries[1].version)
        assertTrue(entries[0].isValid())
        assertTrue(puller.parseIndex("{}").isEmpty())
        assertTrue(puller.parseIndex("not json").isEmpty())
    }

    @Test
    fun `unsafe index entries are invalid`() {
        val bad1 = KitSkillIndexEntry("..", null, listOf("SKILL.md"))
        assertFalse(bad1.isValid())
        val bad2 = KitSkillIndexEntry("ok-name", null, listOf("other.md"))
        assertFalse(bad2.isValid()) // 没有指令文档
        val bad3 = KitSkillIndexEntry("ok-name", null, listOf("SKILL.md", "../escape.txt"))
        assertFalse(bad3.isValid())
        val good = KitSkillIndexEntry("ok-name", "2.0", listOf("SKILL.md", "res/x.txt"))
        assertTrue(good.isValid())
    }

    @Test
    fun `remote pull downloads and swaps atomically`() = kotlinx.coroutines.test.runTest {
        val fs = FakeSkillFs()
        val base = "https://skills.example/sources/alpha"
        val indexJson = """
            {"skills":[{"name":"report-writer","version":"1.0","files":["SKILL.md","lib/util.txt"]}]}
        """.trimIndent()
        val http = FakeSkillHttp(
            textResponses = mapOf("$base/index.json" to indexJson),
            byteResponses = mapOf(
                "$base/report-writer/SKILL.md" to "---\nname: report-writer\ndescription: makes reports\n---\nDo the thing.",
                "$base/report-writer/lib/util.txt" to "utility body"
            )
        )
        val puller = KitSkillRemotePull(fs, http)
        val roots = puller.pull("https://skills.example/sources/alpha/", "/cache")
        assertEquals(1, roots.size)
        val root = roots[0]
        assertTrue(root.startsWith("/cache/src-"))
        assertTrue(root.endsWith("/report-writer"))
        assertEquals("---\nname: report-writer\ndescription: makes reports\n---\nDo the thing.",
            fs.readFileStringSafe("$root/SKILL.md"))
        assertEquals("utility body", fs.readFileStringSafe("$root/lib/util.txt"))
        assertEquals("1.0", fs.readFileStringSafe("$root/.version"))
        // 原子交换不留 staging/backup 残留
        assertFalse(fs.exists("$root.staging"))
        assertFalse(fs.exists("$root.backup"))
    }

    @Test
    fun `version pin skips re-download and bump swaps again`() = kotlinx.coroutines.test.runTest {
        val fs = FakeSkillFs()
        val base = "https://src.example/kit"
        var skillBody = "---\nname: pinned\ndescription: v1 body\n---\nV1"
        val http = object : KitSkillHttpClient {
            var downloads = 0
            override fun fetchText(url: String): String? =
                if (url == "$base/index.json") {
                    """{"skills":[{"name":"pinned","version":"1.0","files":["SKILL.md"]}]}"""
                } else {
                    null
                }

            override fun fetchBytes(url: String): ByteArray? {
                if (url == "$base/pinned/SKILL.md") {
                    downloads += 1
                    return skillBody.toByteArray(Charsets.UTF_8)
                }
                return null
            }
        }
        val puller = KitSkillRemotePull(fs, http)
        val first = puller.pull(base, "/cache")
        assertEquals(1, first.size)
        assertEquals(1, http.downloads)

        // 同版本再 pull：版本钉住，文件已存在 → 零下载
        val second = puller.pull(base, "/cache")
        assertEquals(1, second.size)
        assertEquals(1, http.downloads)

        // 版本升级 → staging 交换到新内容
        skillBody = "---\nname: pinned\ndescription: v2 body\n---\nV2"
        val bumping = object : KitSkillHttpClient by http {
            override fun fetchText(url: String): String? =
                if (url == "$base/index.json") {
                    """{"skills":[{"name":"pinned","version":"2.0","files":["SKILL.md"]}]}"""
                } else {
                    null
                }
        }
        val third = KitSkillRemotePull(fs, bumping).pull(base, "/cache")
        assertEquals(1, third.size)
        assertTrue(fs.readFileStringSafe("${third[0]}/SKILL.md")!!.endsWith("V2"))
        assertEquals("2.0", fs.readFileStringSafe("${third[0]}/.version"))
        assertFalse(fs.exists("${third[0]}.staging"))
        assertFalse(fs.exists("${third[0]}.backup"))
    }

    @Test
    fun `unreachable index yields empty result`() = kotlinx.coroutines.test.runTest {
        val puller = KitSkillRemotePull(FakeSkillFs(), KitSkillHttpClient.Unreachable)
        assertTrue(puller.pull("https://nowhere.example", "/cache").isEmpty())
    }

    // ═══════════════════════ 目录装载 ═══════════════════════

    @Test
    fun `loader scans skill directories`() {
        val fs = FakeSkillFs()
        fs.files["/skills/alpha/SKILL.md"] = "---\nname: alpha\ndescription: first\n---\nAlpha body."
        fs.files["/skills/alpha/scripts/run.sh"] = "echo hi"
        fs.files["/skills/beta/beta.md"] = "Beta body without frontmatter."
        fs.files["/skills/gamma/README.txt"] = "no instruction file"
        fs.files["/skills/top-level.txt"] = "not a skill dir"

        val loader = KitSkillLoader(fs)
        val loaded = loader.scan("/skills")
        assertEquals(2, loaded.size)
        val alpha = loaded.first { it.id == "alpha" }
        assertEquals("/skills/alpha/SKILL.md", alpha.path)
        assertEquals("/skills/alpha", alpha.baseDirectory)
        assertEquals("first", alpha.description)
        val beta = loaded.first { it.id == "beta" }
        assertEquals("Beta body without frontmatter.", beta.content)
        assertEquals("/skills/beta", beta.source.let { (it as KitSkillSource.Directory).path })
    }

    // ═══════════════════════ 编码与杂项 ═══════════════════════

    @Test
    fun `encodeSegment percent-encodes unsafe bytes`() {
        val puller = KitSkillRemotePull(FakeSkillFs(), FakeSkillHttp())
        assertEquals("plain-name", puller.encodeSegment("plain-name"))
        assertEquals("a%20b", puller.encodeSegment("a b"))
        assertEquals("%E4%B8%AD", puller.encodeSegment("中"))
        assertEquals("a~b.c_d", puller.encodeSegment("a~b.c_d"))
    }

    @Test
    fun `cacheKey is stable and distinct per url`() {
        val puller = KitSkillRemotePull(FakeSkillFs(), FakeSkillHttp())
        assertEquals(puller.cacheKey("https://x.example/a/"), puller.cacheKey("https://x.example/a/"))
        assertTrue(puller.cacheKey("https://x.example/a/") != puller.cacheKey("https://x.example/b/"))
        assertTrue(puller.cacheKey("https://x.example/a/").startsWith("src-"))
    }
}
