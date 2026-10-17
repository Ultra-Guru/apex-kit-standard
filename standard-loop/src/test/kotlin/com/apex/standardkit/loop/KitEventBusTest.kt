package com.apex.standardkit.loop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KitEventBus 事件总线测试：发布订阅 / 类型过滤 / 防抖合并 / 录制器 /
 * 域事件工厂。
 */
class KitEventBusTest {

    private data class Tick(override val occurredAtMs: Long, val n: Int) : KitBusEvent
    private data class Other(override val occurredAtMs: Long, val tag: String) : KitBusEvent

    @Test
    fun `publish delivers to subscribers in order`() = runTest {
        val bus = KitEventBus<KitBusEvent>()
        val received = mutableListOf<KitBusEvent>()
        val job = launch { bus.subscribe().toList(received) }
        kotlinx.coroutines.delay(10) // 等订阅建立
        bus.publish(Tick(1, 1))
        bus.publish(Tick(2, 2))
        bus.publish(Other(3, "x"))
        kotlinx.coroutines.delay(50)
        job.cancel()
        assertEquals(3, received.size)
        assertEquals(1, (received[0] as Tick).n)
        assertEquals("x", (received[2] as Other).tag)
    }

    @Test
    fun `typed subscription filters by event class`() = runTest {
        val bus = KitEventBus<KitBusEvent>()
        val ticks = mutableListOf<Tick>()
        val job = launch { bus.subscribeTo<Tick>().toList(ticks) }
        kotlinx.coroutines.delay(10)
        bus.publish(Tick(1, 10))
        bus.publish(Other(2, "ignored"))
        bus.publish(Tick(3, 30))
        kotlinx.coroutines.delay(50)
        job.cancel()
        assertEquals(listOf(10, 30), ticks.map { it.n })
    }

    @Test
    fun `subscriber count reflects active collectors`() = runTest {
        val bus = KitEventBus<KitBusEvent>()
        assertEquals(0, bus.subscriberCount())
        val job = launch { bus.subscribe().toList(mutableListOf()) }
        kotlinx.coroutines.delay(10)
        assertEquals(1, bus.subscriberCount())
        job.cancel()
        kotlinx.coroutines.delay(10)
        assertEquals(0, bus.subscriberCount())
    }

    @Test
    fun `debounced collapses bursts into last value`() = runTest {
        val burst = flowOf(Tick(1, 1), Tick(2, 2), Tick(3, 3))
        val result = burst.debounced(windowMs = 100L).toList()
        assertEquals(1, result.size)
        assertEquals(3, result[0].n)
    }

    @Test
    fun `debounced emits spaced values independently`() = runTest {
        val source = kotlinx.coroutines.flow.flow {
            emit(Tick(1, 1))
            kotlinx.coroutines.delay(200)
            emit(Tick(2, 2))
        }
        val result = withTimeout(5_000) {
            source.debounced(windowMs = 50L).toList()
        }
        assertEquals(listOf(1, 2), result.map { it.n })
    }

    @Test
    fun `debounced flushes final pending value on completion`() = runTest {
        val source = kotlinx.coroutines.flow.flow {
            emit(Tick(1, 1))
            kotlinx.coroutines.delay(10)
            emit(Tick(2, 2))
        }
        val result = source.debounced(windowMs = 100L).toList()
        assertEquals(1, result.size)
        assertEquals(2, result[0].n)
    }

    @Test
    fun `recorder keeps newest events within capacity`() {
        val recorder = KitBusRecorder<Tick>(capacity = 3)
        recorder.record(Tick(1, 1))
        recorder.record(Tick(2, 2))
        recorder.record(Tick(3, 3))
        recorder.record(Tick(4, 4))
        assertEquals(3, recorder.size())
        assertEquals(listOf(2, 3, 4), recorder.snapshot().map { it.n })
        recorder.clear()
        assertEquals(0, recorder.size())
        assertTrue(recorder.snapshot().isEmpty())
    }

    @Test
    fun `recordInto bridges subscription into recorder`() = runTest {
        val bus = KitEventBus<KitBusEvent>()
        val recorder = KitBusRecorder<KitBusEvent>()
        val job = launch { bus.subscribe().recordInto(recorder) }
        kotlinx.coroutines.delay(10)
        bus.publish(Tick(1, 5))
        bus.publish(Other(2, "o"))
        kotlinx.coroutines.delay(50)
        job.cancel()
        assertEquals(2, recorder.size())
    }

    @Test
    fun `domain event factory stamps occurrence times`() {
        val skill = KitDomainEventFactory.skillChanged("pdf", KitSkillRegistryEventKind.ADDED)
        assertTrue(skill.occurredAtMs > 0)
        assertEquals("pdf", skill.skillId)
        val rebalanced = KitDomainEventFactory.mcpToolsRebalanced(7, listOf("a", "b"))
        assertEquals(7, rebalanced.toolCount)
        assertEquals(listOf("a", "b"), rebalanced.servers)
        val circuit = KitDomainEventFactory.mcpServerCircuitChanged("srv", true)
        assertTrue(circuit.tripped)
        val batch = KitDomainEventFactory.toolBatchCompleted(10, 8, 1, 1, 0, 99L)
        assertEquals(10, batch.totalCalls)
        val compacted = KitDomainEventFactory.contextCompacted(5000, 2000, "shrink-0.7")
        assertEquals("shrink-0.7", compacted.strategy)
    }

    @Test
    fun `bus drops oldest under sustained pressure instead of blocking publishers`() = runTest {
        val bus = KitEventBus<KitBusEvent>(buffer = 8)
        // 无订阅者：发布不挂起、不异常
        repeat(1_000) { n -> bus.publish(Tick(n.toLong(), n)) }
        // 有一个慢订阅者：滑动丢弃生效（发布侧仍然即时返回）
        val received = MutableStateFlow(0)
        val job = launch {
            bus.subscribe().collect { received.value = received.value + 1 }
        }
        kotlinx.coroutines.delay(10)
        val start = System.nanoTime()
        repeat(200) { n -> bus.publish(Tick(n.toLong(), n)) }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        job.cancel()
        assertTrue(elapsedMs < 2_000)
    }
}
