package com.apex.standardkit.loop

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * # Kit Event Bus — 类型化事件总线
 *
 * 标准任务循环各子系统（技能注册表 / MCP 注册表 / 工具执行器 / 上下文
 * 策略）需要一个**进程内广播骨干**：发布方不关心订阅者，订阅方按类型
 * 过滤。本文件提供：
 *
 * 1. **类型化总线**（[KitEventBus]）：`MutableSharedFlow` 骨干 +
 *    滑动丢弃背压（extraBufferCapacity = [buffer]，溢出丢最旧——
 *    「最新状态」语义，慢订阅者绝不反压发布方）；
 * 2. **按类型订阅**（[subscribeTo]）：`filterIsInstance` 直通管道；
 * 3. **防抖合并订阅**（[debounced]）：业界 MCP 工具再平衡的核心
 *    形态——事件成批到来时**滑动合并 + 防抖窗后统一处理**（conflated
 *    tick + collectLatest，全稳定协程 API，零 FlowPreview 依赖）；
 * 4. **事件录制**（[KitBusRecorder]）：环形缓冲最近 N 条事件（诊断 /
 *    测试断言 / 宿主调试面板直用）；
 * 5. **域事件清单**（[KitDomainEvents]）：库内子系统的标准事件类型——
 *    宿主观察技能/MCP/工具生命周期无需自造协议。
 */
// ═══════════════════════ 总线 ═══════════════════════

/** 总线事件标记（所有库事件的公共父接口）。 */
interface KitBusEvent {
    /** 事件发生的墙钟（毫秒；宿主注入时钟时为注入值）。 */
    val occurredAtMs: Long
}

/**
 * 类型化事件总线。
 *
 * 语义要点：
 * - **无回放**（replay = 0）：晚订阅者只看到订阅之后的事件——总线是
 *   通知骨干，不是状态存储（状态请用注册表快照）；
 * - **滑动丢弃**：缓冲 [buffer] 条，溢出丢最旧——发布永不挂起、永不
 *   失败（订阅慢是订阅方的事，「最新通知」语义下丢旧合理）；
 * - [publish] 立即返回（无订阅者 = 事件蒸发——测试断言请先订阅再触发）。
 */
class KitEventBus<E : KitBusEvent>(
    private val buffer: Int = DEFAULT_BUFFER
) {

    private val backbone = MutableSharedFlow<E>(
        replay = 0,
        extraBufferCapacity = buffer,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** 广播视图（inline 订阅管道的可发布访问点）。 */
    @kotlinx.coroutines.flow.PublishedApi
    internal val sharedFlow: Flow<E> = backbone.asSharedFlow()

    /** 发布一条事件（无订阅者 = 蒸发）。 */
    fun publish(event: E) {
        backbone.tryEmit(event)
    }

    /** 全类型订阅。 */
    fun subscribe(): Flow<E> = backbone.asSharedFlow()

    /** 按类型订阅（`filterIsInstance` 直通管道）。 */
    inline fun <reified T : E> subscribeTo(): Flow<T> = sharedFlow.filterIsInstance<T>()

    /** 当前活跃订阅者数（诊断）。 */
    fun subscriberCount(): Int = backbone.subscriptionCount.value

    companion object {
        /** 默认滑动缓冲（溢出丢最旧）。 */
        const val DEFAULT_BUFFER = 64
    }
}

/**
 * 防抖合并（滑动合并 + 防抖窗）。
 *
 * 用法（MCP 工具再平衡形态）：
 * ```
 * scope.launch {
 *     bus.subscribeTo<KitDomainEvents.McpToolsChanged>()
 *         .debounced(windowMs = 100L)
 *         .collect { reconcile() }
 * }
 * ```
 *
 * 语义：防抖窗内新事件到达会取消上一轮未出窗的处理，窗静默期满后
 * 下游收到**最后一次**事件；上游完结时挂起中的最后一条也会冲刷。
 *
 * 实现口径：conflated 最新值（`MutableStateFlow`）+ `collectLatest +
 * delay`——新值到来取消上一轮 delay（即防抖重启），全稳定 API。
 */
fun <E> Flow<E>.debounced(windowMs: Long): Flow<E> = channelFlow {
    require(windowMs > 0) { "windowMs must be > 0" }
    val pending = kotlinx.coroutines.flow.MutableStateFlow<E?>(null)
    launch {
        pending.collectLatest { value ->
            if (value != null) {
                delay(windowMs)
                send(value)
                pending.value = null
            }
        }
    }
    collect { value -> pending.value = value }
    // 上游完结：等消费者清空挂起值（至多再等一个防抖窗）——
    // 恰一次发射由消费者保证，这里绝不重复发
    kotlinx.coroutines.withTimeoutOrNull(windowMs) {
        pending.first { it == null }
    }
}

// ═══════════════════════ 事件录制 ═══════════════════════

/**
 * 环形事件录制器（最近 N 条）。
 *
 * 用途：测试断言（「事件序列必须是 A→B→C」）、宿主调试面板（「最近
 * 发生了什么」）。线程安全；[snapshot] 返回时序序。
 */
class KitBusRecorder<E : KitBusEvent>(private val capacity: Int = DEFAULT_CAPACITY) {

    private val ring = ArrayDeque<E>(capacity)

    /** 记录一条（满容量丢最旧）。 */
    @Synchronized
    fun record(event: E) {
        if (ring.size >= capacity) {
            ring.removeFirst()
        }
        ring.addLast(event)
    }

    /** 时序快照（最旧在前）。 */
    @Synchronized
    fun snapshot(): List<E> = ring.toList()

    /** 清空。 */
    @Synchronized
    fun clear() {
        ring.clear()
    }

    /** 已录制条数。 */
    @Synchronized
    fun size(): Int = ring.size

    companion object {
        const val DEFAULT_CAPACITY = 256
    }
}

// ═══════════════════════ 域事件 ═══════════════════════

/**
 * 库内子系统标准域事件。
 *
 * 宿主观察这些类型即可获得全部生命周期可见性（UI 高亮 / 日志 / 统计
 * 面板），无需侵入子系统内部。
 */
sealed interface KitDomainEvents : KitBusEvent {

    /** 技能注册表变更（增/改/删）。 */
    data class SkillChanged(
        override val occurredAtMs: Long,
        val skillId: String,
        val kind: KitSkillRegistryEventKind
    ) : KitDomainEvents

    /** MCP 工具清单完成一次再平衡（发现清单已刷新）。 */
    data class McpToolsRebalanced(
        override val occurredAtMs: Long,
        val toolCount: Int,
        val servers: List<String>
    ) : KitDomainEvents

    /** MCP 服务器熔断状态翻转。 */
    data class McpServerCircuitChanged(
        override val occurredAtMs: Long,
        val server: String,
        val tripped: Boolean
    ) : KitDomainEvents

    /** 工具批执行完成（汇总口径）。 */
    data class ToolBatchCompleted(
        override val occurredAtMs: Long,
        val totalCalls: Int,
        val completed: Int,
        val failed: Int,
        val declined: Int,
        val interrupted: Int,
        val durationMs: Long
    ) : KitDomainEvents

    /** 上下文压缩发生。 */
    data class ContextCompacted(
        override val occurredAtMs: Long,
        val beforeTokens: Int,
        val afterTokens: Int,
        val strategy: String
    ) : KitDomainEvents
}

/**
 * 域事件工厂（宿主/引擎侧构造事件的便捷入口——统一墙钟）。
 */
object KitDomainEventFactory {

    private val clock: () -> Long = { System.currentTimeMillis() }

    fun skillChanged(skillId: String, kind: KitSkillRegistryEventKind) =
        KitDomainEvents.SkillChanged(clock(), skillId, kind)

    fun mcpToolsRebalanced(toolCount: Int, servers: List<String>) =
        KitDomainEvents.McpToolsRebalanced(clock(), toolCount, servers)

    fun mcpServerCircuitChanged(server: String, tripped: Boolean) =
        KitDomainEvents.McpServerCircuitChanged(clock(), server, tripped)

    fun toolBatchCompleted(
        totalCalls: Int,
        completed: Int,
        failed: Int,
        declined: Int,
        interrupted: Int,
        durationMs: Long
    ) = KitDomainEvents.ToolBatchCompleted(
        clock(), totalCalls, completed, failed, declined, interrupted, durationMs
    )

    fun contextCompacted(beforeTokens: Int, afterTokens: Int, strategy: String) =
        KitDomainEvents.ContextCompacted(clock(), beforeTokens, afterTokens, strategy)
}

// ═══════════════════════ 总线便捷装配 ═══════════════════════

/**
 * 录制桥：把总线订阅接到录制器（宿主调试面板装配糖）。
 *
 * ```
 * val bus = KitEventBus<KitDomainEvents>()
 * val recorder = KitBusRecorder<KitDomainEvents>()
 * scope.launch { bus.subscribe().recordInto(recorder) }
 * ```
 */
suspend fun <E : KitBusEvent> Flow<E>.recordInto(recorder: KitBusRecorder<E>) {
    collect { event -> recorder.record(event) }
}

/** 单事件流工具（录制断言用）。 */
internal fun <E : KitBusEvent> singleEventFlow(event: E): Flow<E> = flowOf(event)

/** 空流占位（契约测试用）。 */
internal fun <E> emptyKitEventFlow(): Flow<E> = flow { }
