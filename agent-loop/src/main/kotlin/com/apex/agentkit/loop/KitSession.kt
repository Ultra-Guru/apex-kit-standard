package com.apex.agentkit.loop

import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * # Kit Session — 任务循环的会话管理器
 *
 * 会话 = 消息时间线（[KitSessionMessage] 列表）+ fork 谱系 + 统计派生。
 *
 * - **append 追加**：用户/助手（含 toolCalls）/工具结果/系统注入全部经
 *   [append]，每条获得单调 id；
 * - **fork / 回退**（[forkFrom]）：从某条消息回退生成子会话——父会话保留
 *   完整历史，子会话截取 `[0, anchor]` 后继续；谱系 [lineage] 上限
 *   [MAX_LINEAGE_DEPTH] 防无限分叉；
 * - **不可变快照**（[snapshot]）：读写锁保护并发；
 * - **token 估算**：chars/4（拉丁）+ CJK ×1.5 加权 + 工具结果折半——
 *   仅作仪表与压缩触发的启发式，真实用量以服务端 usage 帧为准。
 *
 * 本类**纯内存**；落盘由引擎经 [KitMemory] 完成，恢复时 [restore] 接管。
 */
class KitSession(
    /** 会话 id（workspace 内唯一；fork 生成新 id）。 */
    val sessionId: String,
    /** 谱系（含自身；根会话 = 长度 1）。 */
    private val lineage: List<String> = listOf(sessionId)
) {

    private val lock = java.util.concurrent.locks.ReentrantReadWriteLock()
    private val messages = mutableListOf<KitSessionMessage>()
    private var idSeq = 0L

    /** 上次活动时刻（懒更新，供列表排序）。 */
    @Volatile
    var lastActiveAt: Long = System.currentTimeMillis()
        private set

    /** 会话标题（首个用户消息截断；空会话 = null）。 */
    @Volatile
    var title: String? = null
        private set

    // ═══════════════════════ 写入 API ═══════════════════════

    /** 追加一条消息（首条用户消息自动成为标题）。 */
    fun append(message: KitMessage): KitSessionMessage = lock.write {
        val wrapped = KitSessionMessage(
            id = ++idSeq,
            message = message,
            createdAt = System.currentTimeMillis()
        )
        messages.add(wrapped)
        lastActiveAt = wrapped.createdAt
        if (title == null && message is KitMessage.User) {
            title = message.content
                .lineSequence()
                .firstOrNull()
                ?.take(TITLE_MAX_CHARS)
                ?.takeIf { it.isNotBlank() }
        }
        wrapped
    }

    /**
     * 从某条消息回退 fork 出子会话：截取 `[0, anchorId]`（含锚点）。
     */
    fun forkFrom(anchorId: Long, childId: String): KitSession {
        val child = KitSession(childId, lineage + childId)
        val prefix = lock.read { messages.takeWhile { it.id <= anchorId } }
        prefix.forEach { child.append(it.message) }
        if (child.title == null) {
            child.title = title
        }
        child.lastActiveAt = System.currentTimeMillis()
        return child
    }

    /** 整体替换（恢复历史 / 压缩替换旧段时用）。 */
    fun replaceAll(newMessages: List<KitMessage>) = lock.write {
        messages.clear()
        idSeq = 0
        title = null
        newMessages.forEach { append(it) }
    }

    // ═══════════════════════ 读取 API ═══════════════════════

    /** 不可变快照（副本；调用方可自由持引用）。 */
    fun snapshot(): List<KitSessionMessage> = lock.read { messages.toList() }

    /** 消息条数。 */
    fun size(): Int = lock.read { messages.size }

    /** 按 id 查消息（fork 锚点定位用）。 */
    fun findById(id: Long): KitSessionMessage? = lock.read { messages.firstOrNull { it.id == id } }

    /**
     * 恢复历史（持久化装载）：整体替换 + id 重排。
     *
     * 工具结果与前一条 assistant.toolCalls 的对齐由装载源保证——悬挂
     * 修复属引擎职责（repairDanglingResults）。
     */
    fun restore(history: List<KitMessage>) {
        replaceAll(history)
        lastActiveAt = System.currentTimeMillis()
    }

    /** 会话统计（UI 徽标 / 报告）。 */
    fun stats(tokenEstimator: (String) -> Int): KitSessionStats = lock.read {
        var userTurns = 0
        var toolResults = 0
        var assistant = 0
        var tokens = 0
        for (m in messages) {
            when (val msg = m.message) {
                is KitMessage.User -> {
                    userTurns++
                    tokens += tokenEstimator(msg.content)
                }
                is KitMessage.Assistant -> {
                    assistant++
                    tokens += tokenEstimator(msg.content) +
                        msg.toolCalls.sumOf { tokenEstimator(it.arguments) }
                }
                is KitMessage.ToolResult -> {
                    toolResults++
                    tokens += (tokenEstimator(msg.content) + 1) / 2
                }
                is KitMessage.System -> tokens += tokenEstimator(msg.content)
            }
        }
        KitSessionStats(
            messageCount = messages.size,
            userTurns = userTurns,
            toolResults = toolResults,
            assistantMessages = assistant,
            estimatedTokens = tokens
        )
    }

    /** 谱系深度（根 = 1）。 */
    fun lineageDepth(): Int = lineage.size

    /** 谱系副本（展示 / 日志）。 */
    fun lineageChain(): List<String> = lineage.toList()

    companion object {
        /** 标题截断长度。 */
        const val TITLE_MAX_CHARS = 48

        /** 谱系深度上限。 */
        const val MAX_LINEAGE_DEPTH = 8

        /**
         * 默认 token 估算器（会话统计口径；引擎另有 usage 真值通道）。
         *
         * 启发式：ASCII 类 ~4 chars/token；CJK（含全角标点）按 1.5 字/token
         * 折算（中文 token 密度更高，经验系数 1.5）。
         */
        fun defaultTokenEstimator(text: String): Int {
            if (text.isEmpty()) return 0
            var cjk = 0
            var other = 0
            for (ch in text) {
                if (ch.code in 0x2E80..0x9FFF ||
                    ch.code in 0xFF00..0xFFEF ||
                    ch.code in 0x3000..0x303F
                ) cjk++ else other++
            }
            return (other / 4) + ((cjk * 3) / 2)
        }
    }
}

/**
 * 标准会话消息（KitMessage 的会话侧封装：补 id / 时间戳 / fork 谱系锚点）。
 */
data class KitSessionMessage(
    /** 会话内稳定 id（fork/回退的锚点）。 */
    val id: Long,
    val message: KitMessage,
    /** 创建时刻（System.currentTimeMillis）。 */
    val createdAt: Long = System.currentTimeMillis()
)

/** 会话快照派生统计（UI 徽标 / 运行报告消费）。 */
data class KitSessionStats(
    val messageCount: Int,
    val userTurns: Int,
    val toolResults: Int,
    val assistantMessages: Int,
    val estimatedTokens: Int
)

/** 会话内 id 生成器（进程级单调，fork 后子会话延续父序列防撞）。 */
internal object KitIds {
    private val counter = AtomicLong(System.currentTimeMillis() * 1000)

    fun next(): Long = counter.incrementAndGet()

    /** 工具调用 id（前缀语义：主/子代理命名空间隔离）。 */
    fun toolCallId(prefix: String): String =
        "$prefix${counter.incrementAndGet()}"
}
