package com.apex.standardkit.loop

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * # Kit Skills — 技能子系统（发现 / 注册 / 激活 / 指引 / 远程索引）
 *
 * 「技能」是业界 agent 的事实标准扩展机制：一个目录（或远程索引条目）
 * 携带一份 `SKILL.md` 指令文档 + 任意资源文件，模型在任务匹配其描述时
 * 通过技能工具把指令加载进会话。本文件把该机制的**全部语义**收进库内：
 *
 * 1. **文档解析**（[KitSkillDocParser]）：`SKILL.md` frontmatter
 *    （name / description / autoinvoke / version / license）+ 正文；
 *    备选文件名约定 `{name}.md` 同样支持；
 * 2. **路径安全**（[KitSkillPathSafety]）：远程索引条目与相对路径的
 *    段级校验（拒绝 `..`、绝对路径、反斜杠、URL 形态、null 字节）+
 *    目录包含性校验（词法规范化后前缀比对——穿越段直接解掉）；
 * 3. **注册表**（[KitSkillRegistry]）：线程安全的增删改查 + 变更监听
 *    （`skill.updated` 事件语义，宿主据此刷新工具面/指引段）；
 * 4. **激活渲染**（[KitSkillActivation]）：`<skill_content>` 装载块
 *    （名称 + 正文 + 基目录 + 资源文件采样清单 ≤10 + 相对路径说明）；
 * 5. **指引渲染**（[KitSkillInstructions]）：系统提示词的
 *    `<available_skills>` 段 + 增量 delta（新增/移除/内容变化分级播报，
 *    避免每轮全量重述）；
 * 6. **权限过滤**（[KitSkillAvailability]）：deny 规则命中的技能对
 *    模型不可见——可用性是权限视图而非全局事实；
 * 7. **远程索引拉取**（[KitSkillRemotePull]）：`index.json` 发现 +
 *    版本钉住 + staging/backup 两阶段原子交换 + 并发钳制
 *    （技能 4 / 文件 8）——坏下载绝不半替换好技能；
 * 8. **目录装载**（[KitSkillLoader]）：本地目录扫描 → 解析 → 装载，
 *    autoinvoke=false 的技能不进指引段但保留显式调用能力。
 *
 * 所有 IO 走端口（[KitSkillFileSystem] / [KitSkillHttpClient]）——
 * 库本体零 IO，宿主注入真实实现，测试注入内存实现。
 */
// ═══════════════════════ 数据模型 ═══════════════════════

/**
 * 一条技能的完整描述（注册表条目）。
 *
 * @param id 稳定标识（目录形态 = 目录名；远程形态 = 索引名；嵌入形态 = 调用方给定）
 * @param name 展示名（frontmatter `name`；缺省回退 [id]）
 * @param description 一行描述（指引段展示；frontmatter `description`）
 * @param autoinvoke false = 不出现在自动指引段（仅显式引用可达）
 * @param path 指令文档路径（宿主侧语义；库内只做字符串透传）
 * @param content 指令正文（frontmatter 之后的全部内容）
 * @param source 来源形态（目录 / 远程 / 嵌入）
 * @param version 远程形态的版本钉（本地形态为 null）
 */
data class KitSkillInfo(
    val id: String,
    val name: String,
    val description: String?,
    val autoinvoke: Boolean = true,
    val path: String,
    val content: String,
    val source: KitSkillSource = KitSkillSource.Directory(path),
    val version: String? = null
) {
    /** 指令文档所在目录（激活渲染的「基目录」；无目录分隔 → 空串）。 */
    val baseDirectory: String
        get() {
            val idx = path.lastIndexOf('/')
            return if (idx > 0) path.substring(0, idx) else ""
        }
}

/** 技能来源（sealed 三型——与注册表去重键对齐）。 */
sealed interface KitSkillSource {
    /** 本地目录形态。 */
    data class Directory(val path: String) : KitSkillSource

    /** 远程索引形态（pull 源 URL）。 */
    data class Url(val url: String) : KitSkillSource

    /** 嵌入形态（宿主代码内建技能）。 */
    data class Embedded(val skillId: String) : KitSkillSource
}

// ═══════════════════════ 文档解析 ═══════════════════════

/**
 * `SKILL.md` / `{name}.md` 文档解析器。
 *
 * frontmatter 约定（YAML 子集——仅支持平面 `key: value`，不展开嵌套）：
 * ```
 * ---
 * name: pdf-report-writer
 * description: 生成品牌一致 PDF 周报的完整工作流
 * autoinvoke: false
 * version: 1.2.0
 * license: MIT
 * ---
 * 正文（markdown 指令）……
 * ```
 *
 * 解析规则：
 * - frontmatter 块必须出现在文档最前（允许前导空白行）；
 * - `name` 合法形 [NAME_PATTERN]（字母数字连字符、首字符字母数字、≤64）
 *   ——非法声明的 name 回退到调用方身份标识作展示名；
 * - `description` 单行（多行值按原样保留，渲染时原样透传）；
 * - `autoinvoke` 仅认 `false`（其余任何值 = 默认 true）；
 * - 无 frontmatter → 整文档当正文，全部字段取回退值。
 */
object KitSkillDocParser {

    /** 技能展示名合法形（字母数字连字符；首字符字母数字；≤64）。 */
    val NAME_PATTERN: Regex = Regex("^[a-zA-Z0-9][a-zA-Z0-9-]{0,63}$")

    /** frontmatter 结束标记之前允许的最多行数（防无界扫描）。 */
    private const val FRONTMATTER_MAX_LINES = 64

    /**
     * 解析文档。
     *
     * @param raw 文档全文
     * @param identityId 身份标识（目录名 / 索引条目名——作为 id 与缺省展示名）
     * @return 身份标识段级不安全 → null；否则返回技能信息（path 由调用方补）
     */
    fun parse(raw: String, identityId: String): KitSkillInfo? {
        if (!KitSkillPathSafety.isSafeSegment(identityId)) return null
        val (frontmatter, body) = splitFrontmatter(raw)
        val fields = parseFrontmatterFields(frontmatter)
        val declaredName = fields["name"]?.trim().orEmpty()
        val display = declaredName.takeIf { NAME_PATTERN.matches(it) } ?: identityId
        return KitSkillInfo(
            id = identityId,
            name = display,
            description = fields["description"]?.trim()?.takeIf { it.isNotEmpty() },
            autoinvoke = fields["autoinvoke"]?.trim()?.lowercase() != "false",
            path = "",
            content = body.trim(),
            version = fields["version"]?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    /** frontmatter 块与正文的切分（无 frontmatter / 未闭合 → 空表 + 全文正文）。 */
    internal fun splitFrontmatter(raw: String): Pair<String, String> {
        val lines = raw.split('\n')
        var start = -1
        for (i in lines.indices) {
            val trimmed = lines[i].trim()
            if (trimmed.isEmpty()) continue
            if (trimmed == "---") start = i
            break
        }
        if (start < 0) return "" to raw
        var end = -1
        val scanLimit = minOf(lines.size, start + 1 + FRONTMATTER_MAX_LINES)
        for (i in (start + 1) until scanLimit) {
            if (lines[i].trim() == "---") {
                end = i
                break
            }
        }
        if (end < 0) return "" to raw
        val frontmatter = lines.subList(start + 1, end).joinToString("\n")
        val body = lines.subList(end + 1, lines.size).joinToString("\n")
        return frontmatter to body
    }

    /** 平面 `key: value` 字段解析（`#` 注释行跳过；键值两侧空白剥除）。 */
    internal fun parseFrontmatterFields(frontmatter: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (line in frontmatter.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val colon = trimmed.indexOf(':')
            if (colon <= 0) continue
            val key = trimmed.substring(0, colon).trim()
            val value = trimmed.substring(colon + 1).trim()
            if (key.isNotEmpty()) result[key] = value
        }
        return result
    }

    /**
     * 判定目录内哪个文件是技能指令文档。
     *
     * 约定：`SKILL.md` 优先；否则回退目录内的 `{目录名}.md`
     * （不存在则 null——本库不做「多个 md 任选」的宽松匹配）。
     *
     * @param files 目录内全部文件相对路径（嵌套路径形如 `scripts/x.sh`）
     * @param directoryName 技能目录名（身份标识）
     */
    fun resolveInstructionFile(files: List<String>, directoryName: String): String? {
        val topLevel = files.map { it.substringBefore('/') }
        if (topLevel.contains("SKILL.md")) return "SKILL.md"
        val convention = "$directoryName.md"
        return convention.takeIf { topLevel.contains(it) }
    }
}

// ═══════════════════════ 路径安全 ═══════════════════════

/**
 * 技能路径安全校验（远程索引与本地扫描共用）。
 *
 * 威胁模型：远程 index.json / 技能目录树被恶意构造——`../` 穿越到缓存根
 * 之外、绝对路径覆盖系统文件、URL 形态把下载目标指向别源。这里做
 * 段级 / 形态级拒绝，写落点另过 [contains] 包含性校验（词法规范化后
 * 前缀比对，`..` 段先解掉再比，穿越必失效）。
 */
object KitSkillPathSafety {

    /** 单段安全（非空、非 `.`/`..`、无分隔符、无 null 字节）。 */
    fun isSafeSegment(value: String): Boolean =
        value.isNotEmpty() &&
            value != "." &&
            value != ".." &&
            !value.contains('/') &&
            !value.contains('\\') &&
            !value.contains('\u0000')

    /**
     * 相对路径安全（段级全过 + 拒绝反斜杠/null/查询/锚点 + 拒绝 URL
     * 形态 + 拒绝 POSIX/Windows 绝对路径）。
     */
    fun isSafeRelativePath(value: String): Boolean {
        if (value.isEmpty()) return false
        if (value.contains('\\')) return false
        if (value.contains('\u0000')) return false
        if (value.contains('?') || value.contains('#')) return false
        if (looksLikeUrl(value)) return false
        if (value.startsWith("/")) return false
        val windowsAbsolute = Regex("^[a-zA-Z]:[\\\\/]")
        if (windowsAbsolute.containsMatchIn(value)) return false
        return value.split('/').all { segment -> isSafeSegment(segment) }
    }

    /** URL 形态嗅探（`scheme://` 前缀且 scheme 仅含合法字符）。 */
    private fun looksLikeUrl(value: String): Boolean {
        val schemeColon = value.indexOf("://")
        if (schemeColon <= 0) return false
        val scheme = value.substring(0, schemeColon)
        return scheme.isNotEmpty() &&
            scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }
    }

    /**
     * 包含性校验：候选路径必须严格落在父目录之内（规范化后前缀比对，
     * 且不等于父目录本身）。
     *
     * @param parentDir 父目录
     * @param candidate 待写入/读取路径
     */
    fun contains(parentDir: String, candidate: String): Boolean {
        val parent = normalize(parentDir).trimEnd('/')
        val child = normalize(candidate)
        if (parent.isEmpty() || child.isEmpty()) return false
        if (child == parent) return false
        return child.startsWith("$parent/")
    }

    /**
     * 词法规范化：分隔符统一 `/`、空段与 `.` 段折叠、`..` 段对前段消解
     * （保留穿越语义可被前缀比对识别）。
     */
    internal fun normalize(path: String): String {
        val raw = path.replace('\\', '/')
        val out = ArrayDeque<String>()
        for (segment in raw.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeLast()
                else -> out.addLast(segment)
            }
        }
        return out.joinToString("/")
    }
}

// ═══════════════════════ 注册表 ═══════════════════════

/** 技能注册表变更事件（宿主据此刷新指引段 / 工具面）。 */
data class KitSkillRegistryEvent(
    val kind: KitSkillRegistryEventKind,
    val skillId: String
)

enum class KitSkillRegistryEventKind { ADDED, UPDATED, REMOVED }

/**
 * 线程安全技能注册表（编辑器语义——add/update/remove 即时生效并广播）。
 *
 * - [list] 按 id 字典序稳定输出（指引渲染确定性）；
 * - [get] 大小写敏感精确匹配；
 * - 变更监听为同步回调（轻量；宿主需要异步语义自行包装）。
 */
class KitSkillRegistry {

    private val skills = ConcurrentHashMap<String, KitSkillInfo>()
    private val listeners = CopyOnWriteArrayList<(KitSkillRegistryEvent) -> Unit>()

    /** 注册变更监听（返回注销函数）。 */
    fun addListener(listener: (KitSkillRegistryEvent) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    /** 新增（同 id 已存在 → 覆盖并广播 UPDATED）。 */
    fun add(skill: KitSkillInfo) {
        val existed = skills.put(skill.id, skill) != null
        notify(if (existed) KitSkillRegistryEventKind.UPDATED else KitSkillRegistryEventKind.ADDED, skill.id)
    }

    /** 原地更新（不存在 → no-op）。 */
    fun update(skillId: String, update: (KitSkillInfo) -> KitSkillInfo) {
        val current = skills[skillId] ?: return
        skills[skillId] = update(current)
        notify(KitSkillRegistryEventKind.UPDATED, skillId)
    }

    /** 移除（不存在 → no-op）。 */
    fun remove(skillId: String) {
        if (skills.remove(skillId) != null) {
            notify(KitSkillRegistryEventKind.REMOVED, skillId)
        }
    }

    /** 精确获取。 */
    fun get(skillId: String): KitSkillInfo? = skills[skillId]

    /** 按 id 字典序全量清单。 */
    fun list(): List<KitSkillInfo> = skills.values.sortedBy { it.id }

    /** 当前规模。 */
    fun size(): Int = skills.size

    /** 全量重建（装载器批量替换用；广播口径由调用方自理）。 */
    fun replaceAll(newSkills: List<KitSkillInfo>) {
        skills.clear()
        newSkills.forEach { skills[it.id] = it }
    }

    private fun notify(kind: KitSkillRegistryEventKind, skillId: String) {
        val event = KitSkillRegistryEvent(kind, skillId)
        listeners.forEach { listener -> runCatching { listener(event) } }
    }
}

// ═══════════════════════ 权限过滤 ═══════════════════════

/**
 * 技能可用性视图（权限谓词过滤）。
 *
 * 业界语义：技能可用性是**权限视图**——deny 规则命中的技能从指引段
 * 与自动激活面消失，但显式调用是否放行由权限引擎全权裁决（本库只
 * 做视图裁剪，不做裁决）。
 */
object KitSkillAvailability {

    /**
     * 过滤出当前权限视图下可见的技能。
     *
     * @param skills 全量清单
     * @param denied 判定谓词（true = 该技能被 deny，不可见）
     */
    fun visible(skills: List<KitSkillInfo>, denied: (KitSkillInfo) -> Boolean): List<KitSkillInfo> =
        skills.filterNot { denied(it) }

    /** 自动指引段可见集（权限可见 ∧ autoinvoke=true ∧ 有描述）。 */
    fun autoInvokable(skills: List<KitSkillInfo>, denied: (KitSkillInfo) -> Boolean): List<KitSkillInfo> =
        visible(skills, denied).filter { it.autoinvoke && !it.description.isNullOrEmpty() }
}

// ═══════════════════════ 激活渲染 ═══════════════════════

/**
 * 技能激活渲染（技能工具的模型输出格式）。
 *
 * 装载块形状（业界约定形态——模型侧已被训练识别）：
 * ```
 * <skill_content name="pdf-report-writer">
 * # Skill: pdf-report-writer
 *
 * （正文）
 *
 * Base directory for this skill: /path/to/skill
 * Relative paths in this skill (e.g., scripts/, reference/) are relative to this base directory.
 * Note: file list is sampled.
 *
 * <skill_files>
 * <file>scripts/build.sh</file>
 * <file>reference/brand.md</file>
 * </skill_files>
 * </skill_content>
 * ```
 *
 * 文件清单为采样（≤ [SAMPLE_FILE_LIMIT] 个、字典序）：技能目录可能巨大，
 * 全量清单只会烧上下文；模型需要更多文件时用读工具按需取。
 */
object KitSkillActivation {

    /** 采样文件数上限。 */
    const val SAMPLE_FILE_LIMIT = 10

    /** 指令文档主约定文件名（采样清单里排除——正文已内联）。 */
    const val INSTRUCTION_FILE = "SKILL.md"

    /**
     * 渲染装载块。
     *
     * @param skill 技能信息
     * @param resourceFiles 技能目录内资源文件相对路径清单（调用方扫描）
     */
    fun toModelOutput(skill: KitSkillInfo, resourceFiles: List<String>): String = buildString {
        appendLine("<skill_content name=\"${skill.name}\">")
        appendLine("# Skill: ${skill.name}")
        appendLine()
        appendLine(skill.content.trim())
        appendLine()
        appendLine("Base directory for this skill: ${skill.baseDirectory}")
        appendLine(
            "Relative paths in this skill (e.g., scripts/, reference/) are relative to this base directory."
        )
        appendLine("Note: file list is sampled.")
        appendLine()
        appendLine("<skill_files>")
        resourceFiles
            .filterNot { relative -> isInstructionFile(relative, skill.id) }
            .sorted()
            .take(SAMPLE_FILE_LIMIT)
            .forEach { file -> appendLine("<file>$file</file>") }
        appendLine("</skill_files>")
        appendLine("</skill_content>")
    }.trim() + "\n"

    /** 相对路径是否指令文档本体（两种约定名都排除）。 */
    internal fun isInstructionFile(relativePath: String, skillId: String): Boolean {
        val fileName = relativePath.substringAfterLast('/')
        return fileName == INSTRUCTION_FILE || fileName == "$skillId.md"
    }
}

// ═══════════════════════ 指引段渲染 ═══════════════════════

/** 指引段渲染输入（技能摘要形态）。 */
data class KitSkillSummary(
    val id: String,
    val name: String,
    val description: String
)

/**
 * 系统提示词的技能指引段渲染 + 增量更新。
 *
 * 全量段（首轮）：
 * ```
 * Skills provide specialized instructions and workflows for specific tasks.
 * Use the skill tool to load a skill when a task matches its description.
 * ...
 * <available_skills>
 *   <skill>
 *     <id>pdf-report-writer</id>
 *     <name>pdf-report-writer</name>
 *     <description>…</description>
 *   </skill>
 * </available_skills>
 * ```
 *
 * 增量段（后续轮）分级播报：
 * - 纯新增/纯移除 → 小 delta（新增清单 / 「以下 id 不再可用」行）；
 * - 任何内容变化 / 无变化 → 全量重述 + 「supersedes」前导行
 *   （模型侧旧列表必须被显式取代，否则幻觉引用旧技能）。
 */
object KitSkillInstructions {

    /** 技能指引不可用时的收尾说明（防模型沿用记忆中的旧技能）。 */
    const val REMOVED_NOTICE =
        "Skill guidance is no longer available. Do not use any previously listed skill."

    /** 摘要投影（按 id 字典序）。 */
    fun summarize(skills: List<KitSkillInfo>): List<KitSkillSummary> =
        skills.map { KitSkillSummary(it.id, it.name, it.description ?: "") }.sortedBy { it.id }

    /** 全量段渲染。 */
    fun render(skills: List<KitSkillSummary>): String = buildString {
        appendLine("Skills provide specialized instructions and workflows for specific tasks.")
        appendLine("Use the skill tool to load a skill when a task matches its description.")
        appendLine(
            "The user may also invoke a skill directly. When that happens, its instructions " +
                "appear in the conversation as a <skill_content> block, the same shape the skill " +
                "tool returns. A skill that is already present this way does not need to be " +
                "invoked again."
        )
        if (skills.isEmpty()) {
            appendLine("No skills are currently available.")
        } else {
            appendLine("<available_skills>")
            skills.forEach { skill -> append(renderEntry(skill)) }
            appendLine("</available_skills>")
        }
    }.trim()

    /** 单条 `<skill>` 条目渲染（两空格缩进，与业界形态一致）。 */
    private fun renderEntry(skill: KitSkillSummary): String = buildString {
        appendLine("  <skill>")
        appendLine("    <id>${skill.id}</id>")
        appendLine("    <name>${skill.name}</name>")
        appendLine("    <description>${skill.description}</description>")
        appendLine("  </skill>")
    }

    /**
     * 增量更新渲染（上一帧 → 当前帧）。
     *
     * 分级口径：
     * - [previous] 为 null（首轮）→ 全量；
     * - 仅新增或仅移除 → 小 delta；
     * - 存在内容变化（同 id 的 name/description 漂移）或无任何变化 → 全量重述。
     */
    fun renderUpdate(previous: List<KitSkillSummary>?, current: List<KitSkillSummary>): String {
        if (previous == null) return render(current)
        val previousById = previous.associateBy { it.id }
        val currentById = current.associateBy { it.id }
        val added = current.filter { it.id !in previousById }
        val removed = previous.filter { it.id !in currentById }
        val changed = current.filter { candidate ->
            val before = previousById[candidate.id]
            before != null && (before.name != candidate.name || before.description != candidate.description)
        }
        if (changed.isNotEmpty() || (added.isEmpty() && removed.isEmpty())) {
            return listOf(
                "The available skills have changed. This list supersedes the previous " +
                    "available skills list.",
                render(current)
            ).joinToString("\n")
        }
        return buildString {
            if (added.isNotEmpty()) {
                appendLine("New skills are available in addition to those previously listed:")
                added.forEach { skill -> append(renderEntry(skill)) }
            }
            if (removed.isNotEmpty()) {
                append(
                    "The following skill IDs are no longer available and must not be used: " +
                        removed.joinToString(", ") { it.id } + "."
                )
            }
        }.trim()
    }
}

// ═══════════════════════ 端口 ═══════════════════════

/**
 * 技能文件系统端口（宿主实现；库零 IO）。
 *
 * 契约：
 * - 全部路径为宿主语义的路径字符串；
 * - 读类方法失败返回 null（不抛）；
 * - 写类方法尽力而为返回布尔（失败返回 false，调用方按下载失败口径收尾）；
 * - [listFiles] 返回目录下**文件**相对路径清单（递归、含点文件、
 *   正斜杠分隔；不存在 → null）。
 */
interface KitSkillFileSystem {

    fun exists(path: String): Boolean

    fun readFileStringSafe(path: String): String?

    fun writeFileString(path: String, content: String): Boolean

    /** 父目录不存在时一并创建。 */
    fun writeWithDirs(path: String, content: String): Boolean

    fun rename(from: String, to: String): Boolean

    fun remove(path: String, recursive: Boolean): Boolean

    fun listFiles(directory: String): List<String>?

    /** 便捷默认实现：JVM 平台直接落 java.io.File。 */
    companion object {
        val Jvm: KitSkillFileSystem = object : KitSkillFileSystem {
            private fun file(path: String) = java.io.File(path)

            override fun exists(path: String): Boolean = file(path).exists()

            override fun readFileStringSafe(path: String): String? =
                runCatching { file(path).readText() }.getOrNull()

            override fun writeFileString(path: String, content: String): Boolean =
                runCatching { file(path).writeText(content) }.isSuccess

            override fun writeWithDirs(path: String, content: String): Boolean =
                runCatching {
                    val target = file(path)
                    target.parentFile?.mkdirs()
                    target.writeText(content)
                }.isSuccess

            override fun rename(from: String, to: String): Boolean =
                runCatching { file(from).renameTo(file(to)) }.getOrDefault(false)

            override fun remove(path: String, recursive: Boolean): Boolean =
                runCatching {
                    val target = file(path)
                    if (recursive) target.deleteRecursively() else target.delete()
                }.getOrDefault(false)

            override fun listFiles(directory: String): List<String>? {
                val dir = file(directory)
                if (!dir.isDirectory) return null
                return dir.walkTopDown()
                    .filter { it.isFile }
                    .map { it.relativeTo(dir).path.replace('\\', '/') }
                    .sorted()
            }
        }
    }
}

/**
 * 技能 HTTP 端口（远程索引与文件下载；宿主注入真实网络栈）。
 *
 * 契约：瞬时失败在实现内部完成 2 次指数退避重试（200ms 基数、抖动），
 * 仍失败返回 null——库侧不再重试（口径与业界客户端一致）。
 */
interface KitSkillHttpClient {

    /** GET 文本（失败 → null）。 */
    fun fetchText(url: String): String?

    /** GET 字节（失败 → null）。 */
    fun fetchBytes(url: String): ByteArray?

    /** 静默实现（测试 / 离线宿主）。 */
    companion object {
        val Unreachable: KitSkillHttpClient = object : KitSkillHttpClient {
            override fun fetchText(url: String): String? = null
            override fun fetchBytes(url: String): ByteArray? = null
        }
    }
}

// ═══════════════════════ 远程索引拉取 ═══════════════════════

/**
 * 远程技能索引单条目（`index.json` 的 `skills[]` 元素）。
 *
 * @param name 技能名（段级安全校验的对象）
 * @param version 版本钉（缺省 = 永远重新下载）
 * @param files 文件相对路径清单（必须含 `SKILL.md` 或 `{name}.md`）
 */
data class KitSkillIndexEntry(
    val name: String,
    val version: String?,
    val files: List<String>
) {
    /** 条目合法性（名字段级安全 + 文件清单含指令文档 + 文件路径全安全）。 */
    fun isValid(): Boolean =
        KitSkillPathSafety.isSafeSegment(name) &&
            files.any { it == "SKILL.md" || it == "$name.md" } &&
            files.all { KitSkillPathSafety.isSafeRelativePath(it) }
}

/**
 * 远程技能索引拉取器（发现 → 校验 → 下载 → 原子交换）。
 *
 * 流程（对齐业界技能发现语义）：
 * 1. `GET {url}/index.json` → 解析 `{"skills":[{name,version,files}]}`；
 * 2. 条目校验（[KitSkillIndexEntry.isValid]——非法条目**静默丢弃**，
 *    单条毒丸不拖垮整批）；
 * 3. 版本钉住：条目有 version 且本地 `.version` 文件一致 → 跳过下载；
 * 4. 无版本或版本漂移 → staging 目录全量下载 → 校验指令文档存在 →
 *    写入版本文件 → **两阶段原子交换**（旧目录 rename 为 backup →
 *    staging rename 为正式目录 → 删 backup；任一步失败回滚）；
 * 5. 并发钳制：技能级 [SKILL_CONCURRENCY] / 文件级 [FILE_CONCURRENCY]；
 * 6. 缓存根外绝不落盘（段级校验 + [KitSkillPathSafety.contains] 双保险）。
 */
class KitSkillRemotePull(
    private val fs: KitSkillFileSystem,
    private val http: KitSkillHttpClient,
    private val logger: KitLogger = KitLogger.Silent
) {

    companion object {
        /** 技能级并发。 */
        const val SKILL_CONCURRENCY = 4

        /** 文件级并发。 */
        const val FILE_CONCURRENCY = 8

        /** 远程技能的版本钉文件名。 */
        const val VERSION_FILE = ".version"

        private const val TAG = "KitSkillRemotePull"
    }

    /**
     * 拉取一个远程技能源。
     *
     * @param url 源根 URL（末尾 `/` 可有可无）
     * @param cacheRoot 本地缓存根目录（各源独立子目录）
     * @return 成功落地的技能目录路径清单（失败条目不进结果）
     */
    suspend fun pull(url: String, cacheRoot: String): List<String> = coroutineScope {
        val base = if (url.endsWith("/")) url else "$url/"
        val indexText = http.fetchText("${base}index.json")
        if (indexText == null) {
            logger.warn(TAG, "failed to fetch skill index: $base")
            return@coroutineScope emptyList()
        }
        val entries = parseIndex(indexText)
        if (entries.isEmpty()) {
            logger.warn(TAG, "skill index empty or unparsable: $base")
            return@coroutineScope emptyList()
        }
        val sourceRoot = joinPath(cacheRoot, cacheKey(base))
        val skillSemaphore = Semaphore(SKILL_CONCURRENCY)
        val fileSemaphore = Semaphore(FILE_CONCURRENCY)
        entries.map { entry ->
            async {
                skillSemaphore.withPermit { pullEntry(base, sourceRoot, entry, fileSemaphore) }
            }
        }.awaitAll().filterNotNull()
    }

    /** 单技能条目拉取（版本钉住判定 → 下载/原子交换 → 指令文档核验）。 */
    private suspend fun pullEntry(
        base: String,
        sourceRoot: String,
        entry: KitSkillIndexEntry,
        fileSemaphore: Semaphore
    ): String? {
        if (!entry.isValid()) {
            logger.warn(TAG, "skill entry rejected (unsafe shape): ${entry.name}")
            return null
        }
        val root = joinPath(sourceRoot, entry.name)
        if (!KitSkillPathSafety.contains(sourceRoot, root)) {
            logger.warn(TAG, "skill root escapes cache: ${entry.name}")
            return null
        }
        val versionFile = joinPath(root, VERSION_FILE)
        val version = entry.version
        val pinned = version != null && fs.readFileStringSafe(versionFile) == version
        val targets = entry.files.mapNotNull { file -> resolveTarget(base, root, entry.name, file) }
        if (targets.size != entry.files.size) {
            logger.warn(TAG, "skill entry rejected (unsafe file path): ${entry.name}")
            return null
        }
        if (pinned) {
            // 版本一致：补齐缺失文件即可（幂等）
            targets.forEach { (url, destination) ->
                fileSemaphore.withPermit { download(url, destination) }
            }
        } else {
            refreshWithAtomicSwap(root, entry.name, version, targets, fileSemaphore)
        }
        val instructionReady = fs.exists(joinPath(root, "SKILL.md")) ||
            fs.exists(joinPath(root, "${entry.name}.md"))
        if (!instructionReady) {
            logger.warn(TAG, "skill instruction file missing after pull: ${entry.name}")
            return null
        }
        return root
    }

    /** staging 全量下载 + 两阶段交换（失败自动回滚，绝不留半替换状态）。 */
    private suspend fun refreshWithAtomicSwap(
        root: String,
        skillName: String,
        version: String?,
        targets: List<Pair<String, String>>,
        fileSemaphore: Semaphore
    ) {
        val staging = "$root.staging"
        val backup = "$root.backup"
        fs.remove(staging, recursive = true)
        fs.remove(backup, recursive = true)
        coroutineScope {
            val downloads = targets.map { (url, destination) ->
                val stagingDestination = staging + destination.substring(root.length)
                async { fileSemaphore.withPermit { download(url, stagingDestination) } }
            }
            downloads.awaitAll()
        }.let { downloaded ->
            if (!downloaded.all { it }) {
                fs.remove(staging, recursive = true)
                return
            }
        }
        // staging 侧指令文档核验（两种约定名）
        val stagingInstructionReady =
            fs.exists(joinPath(staging, "SKILL.md")) || fs.exists(joinPath(staging, "$skillName.md"))
        if (!stagingInstructionReady) {
            fs.remove(staging, recursive = true)
            return
        }
        if (version != null) {
            fs.writeFileString(joinPath(staging, VERSION_FILE), version)
        }
        // ── 两阶段交换：cached → backup，staging → root；失败回滚 ──
        val cached = fs.exists(root)
        if (cached && !fs.rename(root, backup)) {
            fs.remove(staging, recursive = true)
            return
        }
        if (!fs.rename(staging, root)) {
            if (cached) fs.rename(backup, root)
            fs.remove(staging, recursive = true)
            return
        }
        if (cached) {
            fs.remove(backup, recursive = true)
        }
    }

    /** 下载单文件（幂等：目标已存在直接成功）。 */
    private fun download(url: String, destination: String): Boolean {
        if (fs.exists(destination)) return true
        val bytes = http.fetchBytes(url) ?: return false
        return fs.writeWithDirs(destination, String(bytes, Charsets.UTF_8))
    }

    /** 解析远程文件 URL 与本地落点（非法 → null）。 */
    internal fun resolveTarget(
        base: String,
        root: String,
        skillName: String,
        file: String
    ): Pair<String, String>? {
        if (!KitSkillPathSafety.isSafeRelativePath(file)) return null
        val encoded = file.split('/').joinToString("/") { segment -> encodeSegment(segment) }
        val url = "$base${encodeSegment(skillName)}/$encoded"
        val destination = joinPath(root, file)
        if (!KitSkillPathSafety.contains(root, destination)) return null
        return url to destination
    }

    /** 解析 index.json（容错：坏文档 → 空表）。 */
    internal fun parseIndex(indexText: String): List<KitSkillIndexEntry> {
        val root = KitJson.parseObject(indexText) ?: return emptyList()
        val skills = root["skills"]?.array ?: return emptyList()
        return skills.items.mapNotNull { item ->
            val name = item["name"].text ?: return@mapNotNull null
            val version = item["version"].text
            val files = item["files"].textList
            KitSkillIndexEntry(name = name, version = version, files = files)
        }
    }

    /** 源缓存键（URL 的 FNV-1a 稳定短哈希，十六进制 16 位定长）。 */
    internal fun cacheKey(base: String): String {
        var hash = 0xcbf29ce484222325UL
        for (b in base.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (b.toLong() and 0xff).toULong()
            hash *= 0x100000001b3UL
        }
        return "src-" + hash.toString(16).padStart(16, '0')
    }

    /** 路径拼接（`/` 分隔；父以 `/` 结尾时直接拼接）。 */
    internal fun joinPath(parent: String, child: String): String =
        when {
            parent.isEmpty() -> child
            parent.endsWith("/") -> parent + child
            else -> "$parent/$child"
        }

    /** URL 段百分号编码（字母数字与 `-_.~` 直通，其余按 UTF-8 字节编码）。 */
    internal fun encodeSegment(segment: String): String = buildString {
        for (b in segment.toByteArray(Charsets.UTF_8)) {
            val v = b.toInt() and 0xff
            val c = v.toChar()
            if (v in 32..126 && (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' || c == '~')) {
                append(c)
            } else {
                append('%')
                append(String.format("%02X", v))
            }
        }
    }
}

// ═══════════════════════ 目录装载 ═══════════════════════

/**
 * 本地技能目录装载器（扫描 → 解析 → 装载）。
 *
 * 约定：
 * - 每个直接子目录是一个技能（`SKILL.md` 或 `{目录名}.md` 为指令文档）；
 * - 歧义/非法/解析失败 → 跳过该目录并记一条 warn（单目录毒丸不拖垮装载）；
 * - 指令文档路径写入 [KitSkillInfo.path]（激活渲染的基目录派生自它）；
 * - 装载完成返回成功清单（注册动作由调用方执行——装载器不持有注册表）。
 */
class KitSkillLoader(
    private val fs: KitSkillFileSystem,
    private val logger: KitLogger = KitLogger.Silent
) {

    /**
     * 扫描单个技能根目录下的全部技能。
     *
     * @param skillRoot 技能根目录（其直接子目录 = 技能）
     * @return 解析成功的技能清单（IO 失败目录跳过）
     */
    fun scan(skillRoot: String): List<KitSkillInfo> {
        val files = fs.listFiles(skillRoot) ?: return emptyList()
        // 直接子目录集合：仅来自嵌套路径的首段（顶层文件不属于任何技能）
        val subdirectories = files.mapNotNull { file ->
            val firstSlash = file.indexOf('/')
            if (firstSlash <= 0) null else file.substring(0, firstSlash)
        }.toSortedSet()
        val results = mutableListOf<KitSkillInfo>()
        for (sub in subdirectories) {
            val directory = joinPath(skillRoot, sub)
            loadOne(directory, sub)?.let { results.add(it) }
        }
        return results
    }

    /** 装载单个技能目录（歧义/缺失/不可解析 → null）。 */
    internal fun loadOne(directory: String, directoryName: String): KitSkillInfo? {
        val files = fs.listFiles(directory) ?: return null
        val instruction = KitSkillDocParser.resolveInstructionFile(files, directoryName) ?: run {
            logger.warn(TAG, "skill directory has no instruction file: $directory")
            return null
        }
        val instructionPath = joinPath(directory, instruction)
        val raw = fs.readFileStringSafe(instructionPath) ?: return null
        val info = KitSkillDocParser.parse(raw, directoryName) ?: run {
            logger.warn(TAG, "skill document unparsable: $instructionPath")
            return null
        }
        return info.copy(
            path = instructionPath,
            source = KitSkillSource.Directory(directory)
        )
    }

    private fun joinPath(parent: String, child: String): String =
        when {
            parent.endsWith("/") -> parent + child
            else -> "$parent/$child"
        }

    private companion object {
        const val TAG = "KitSkillLoader"
    }
}
