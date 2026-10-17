package com.apex.agentkit.loop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * # Kit LLM Resilience — 流式请求容错（阶梯重试 + 拒绝降级 + 中断/溢出信号）
 *
 * 一次「回合内 LLM 请求」的完整容错矩阵：
 *
 * | 状态 | 失败类别 | 处置 | 结果 |
 * |---|---|---|---|
 * | 零输出 | 瞬时（限流/超时/断连/5xx） | 阶梯延迟重试同一轮（2s/5s/10s/…/160s；下一级达 180s 封顶停止） | 穷尽 → [ResilienceOutcome.Failed] |
 * | 零输出 | 请求级拒绝（400/404/鉴权/配置） | **一次**去工具纯文本降级重发（重发同一请求体无意义，但任务不该死） | 降级再败 → [Failed] |
 * | 零输出 | 上下文溢出 | 不重试——交引擎压缩后重试整轮 | [ResilienceOutcome.Overflow] |
 * | 部分输出 | 任一失败 | 不重放（重放整轮会造成 UI 重复拼接）——交引擎**断点续跑** | [ResilienceOutcome.Interrupted] |
 * | 成功 | — | — | [ResilienceOutcome.Completed]（携带 finishReason） |
 *
 * ## 与旧语义的差异（增强）
 *
 * - 部分输出 + 失败：旧语义是「追加中断说明后按完成收尾」——一次网络
 *   抖动把半截回答定格成最终答案。新语义改为**中断信号**上抛给引擎，
 *   引擎保留部分输出为 assistant 消息并注入续跑指令继续循环（对齐业界
 *   标准 agent 的 continue-after-interrupt 语义）；
 * - 上下文溢出独立分类（旧语义淹没在 400 拒绝里）——引擎拿到信号后
 *   走溢出压缩恢复（shrink 阶梯）而不是降级成无工具轮（那救不了溢出）。
 *
 * 过程可见性：每次重试/降级发射 [KitEvent.LlmRetryScheduled]（时间轴
 * 状态行，不弹错误横幅）。
 */
internal object KitLlmResilience {

    /** 容错收尾信号（引擎消费）。 */
    sealed class ResilienceOutcome {
        /** 流式请求正常完成（携带 provider 收尾原因；null = 未透传）。 */
        data class Completed(val finishReason: String?) : ResilienceOutcome()

        /** 已有部分输出时失败（引擎走断点续跑）。 */
        data class Interrupted(val reason: String?) : ResilienceOutcome()

        /** 零输出 + 上下文溢出（引擎走溢出压缩恢复）。 */
        data class Overflow(val reason: String?) : ResilienceOutcome()

        /** 阶梯穷尽 / 降级再败 / 未分类异常（诚实上抛）。 */
        data class Failed(val error: Throwable) : ResilienceOutcome()
    }

    /**
     * 带容错地执行一次流式请求。
     *
     * @param emit 事件发射器（与引擎同通道）
     * @param retrySleeper 阶梯延迟执行器（注入点；null = 真实 delay——
     *   注意不能写成 suspend lambda 默认值：那是 Kotlin IR lower 的已知
     *   崩溃触发器（AddContinuationLowering 断言），这里用可空参数 +
     *   函数体内部归一规避）
     * @param classifier 失败分类器
     * @param hasPartialOutput 本轮是否已有部分输出（内容/思考/工具片段任一）
     * @param attempt 一次流式请求（collect 向调用方闭包持有的累积器写输出）；
     *   返回流尾 finishReason（无则 null）；请求级拒绝降级续跑时以
     *   `textOnly = true` 再入（调用方据此把工具面换成空表——消息列表不动）
     */
    suspend fun run(
        emit: suspend (KitEvent) -> Unit,
        retrySleeper: (suspend (Long) -> Unit)?,
        classifier: KitLlmErrorClassifier,
        hasPartialOutput: suspend () -> Boolean,
        attempt: suspend (textOnly: Boolean) -> String?
    ): ResilienceOutcome {
        val sleeper: suspend (Long) -> Unit = retrySleeper ?: { ms -> delay(ms) }
        var retryIndex = 0
        var degradedToTextOnly = false
        while (true) {
            try {
                val finishReason = attempt(degradedToTextOnly)
                return ResilienceOutcome.Completed(finishReason)
            } catch (e: CancellationException) {
                // 全库纪律：协程取消必须继续抛出（用户 abort 语义）。
                throw e
            } catch (e: Throwable) {
                // ── 部分输出：中断信号（断点续跑），不重放 ──
                if (hasPartialOutput()) {
                    return ResilienceOutcome.Interrupted(e.message ?: e::class.simpleName)
                }
                when (classifier.classify(e)) {
                    KitLlmErrorKind.CONTEXT_OVERFLOW ->
                        // 溢出：重发同一请求体无意义，交引擎压缩后整轮重试
                        return ResilienceOutcome.Overflow(e.message ?: "context overflow")

                    KitLlmErrorKind.REQUEST_REJECTION ->
                        if (!degradedToTextOnly) {
                            // 一次降级：同消息去工具纯文本重发（「换种方式继续」）
                            degradedToTextOnly = true
                            emit(
                                KitEvent.LlmRetryScheduled(
                                    attempt = 0,
                                    delayMs = 0L,
                                    reason = "请求被服务端拒绝（${e.message}），本轮已降级为无工具纯文本继续"
                                )
                            )
                        } else {
                            return ResilienceOutcome.Failed(e)
                        }

                    KitLlmErrorKind.TRANSIENT ->
                        if (!ladderExhausted(retryIndex)) {
                            val delayMs = ladderDelayMs(retryIndex)
                            emit(
                                KitEvent.LlmRetryScheduled(
                                    attempt = retryIndex + 1,
                                    delayMs = delayMs,
                                    reason = (e.message ?: e::class.simpleName ?: "unknown").take(80)
                                )
                            )
                            sleeper(delayMs)
                            retryIndex++
                        } else {
                            return ResilienceOutcome.Failed(e)
                        }

                    KitLlmErrorKind.FATAL ->
                        return ResilienceOutcome.Failed(e)
                }
            }
        }
    }

    // ═══════════════ 阶梯延迟表（与宿主工具重试同张表语义）═══════════════

    /** 阶梯（ms）。索引 = 重试序号（0 = 第一次重试前等待 2s）。 */
    internal val AGENT_LADDER_MS: List<Long> = listOf(
        2_000L, 5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L
    )

    /** 封顶：下一次延迟达到该值即自动停止重试（3 分钟）。 */
    internal const val LADDER_CAP_MS: Long = 180_000L

    /** 阶梯第 [retryIndex] 级的延迟（越界收敛到封顶值）。 */
    internal fun ladderDelayMs(retryIndex: Int): Long {
        if (retryIndex < 0) return 0L
        return if (retryIndex < AGENT_LADDER_MS.size) AGENT_LADDER_MS[retryIndex] else LADDER_CAP_MS
    }

    /** 阶梯是否已穷尽（下一级会达到/超过封顶）。 */
    internal fun ladderExhausted(retryIndex: Int): Boolean {
        if (retryIndex < 0) return false
        if (retryIndex + 1 < AGENT_LADDER_MS.size) return false
        // 已在末级（或越界）：下一级延迟 ≥ 封顶
        val next = if (retryIndex < AGENT_LADDER_MS.size) {
            AGENT_LADDER_MS.getOrNull(retryIndex + 1) ?: LADDER_CAP_MS
        } else LADDER_CAP_MS
        return next >= LADDER_CAP_MS
    }
}
