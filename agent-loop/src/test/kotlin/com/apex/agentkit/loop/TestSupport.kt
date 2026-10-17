package com.apex.agentkit.loop

import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 测试基座：剧本化模型客户端 + 假工具执行器 + 引擎工厂。
 */
internal class ScriptedKitModelClient : KitModelClient {
    /** 剧本帧：吐 chunk / 抛错 / 先吐 chunk 再抛错。 */
    sealed interface Script {
        data class Emit(val chunks: List<KitStreamChunk>) : Script
        data class Fail(val error: Exception) : Script
        data class EmitThenFail(val chunks: List<KitStreamChunk>, val error: Exception) : Script
    }

    val scripts = ConcurrentLinkedQueue<Script>()
    val streamRequests = mutableListOf<Pair<List<KitMessage>, List<KitToolDefinition>>>()
    val streamOptions = mutableListOf<KitRequestOptions>()
    val chatCalls = mutableListOf<Pair<List<KitMessage>, Int>>()

    fun enqueue(vararg chunks: KitStreamChunk) {
        scripts.add(Script.Emit(chunks.toList()))
    }

    fun enqueueFailure(error: Exception) {
        scripts.add(Script.Fail(error))
    }

    fun enqueuePartialThenFailure(vararg chunks: KitStreamChunk) {
        scripts.add(
            Script.EmitThenFail(
                chunks.toList(),
                java.io.IOException("mid-stream drop")
            )
        )
    }

    override fun chatStream(
        contextTag: String,
        messages: List<KitMessage>,
        tools: List<KitToolDefinition>,
        options: KitRequestOptions
    ): Flow<KitStreamChunk> {
        streamRequests += messages to tools
        streamOptions += options
        val script = scripts.poll()
            ?: return flow { emit(KitStreamChunk(content = "(script 缺失)", isFinish = true)) }
        return when (script) {
            is Script.Emit -> flow { script.chunks.forEach { emit(it) } }
            is Script.Fail -> flow { throw script.error }
            is Script.EmitThenFail -> flow {
                script.chunks.forEach { emit(it) }
                throw script.error
            }
        }
    }

    override suspend fun chat(
        contextTag: String,
        messages: List<KitMessage>,
        temperature: Float,
        maxTokens: Int
    ): KitChatResponse {
        chatCalls += messages to maxTokens
        return KitChatResponse(content = chatResponse)
    }

    /** chat 固定回复（压缩测试用）。 */
    var chatResponse: String? = null
}

internal class FakeKitToolRunner : KitToolRunner {
    val executed = mutableListOf<Pair<String, String>>()
    var handler: (suspend (String, String) -> String)? = null
    var streamOutputs: Map<String, List<String>> = emptyMap()

    override fun executeStream(name: String, arguments: String): Flow<KitToolStreamEvent> {
        executed += name to arguments
        val custom = handler
        if (custom != null) {
            return flow {
                emit(KitToolStreamEvent.Output(custom(name, arguments)))
                emit(KitToolStreamEvent.Complete(custom(name, arguments)))
            }
        }
        val outputs = streamOutputs[name] ?: listOf("out:$name")
        return flow {
            outputs.forEach { emit(KitToolStreamEvent.Output(it)) }
            emit(KitToolStreamEvent.Complete(outputs.joinToString("\n")))
        }
    }
}

internal fun fakeToolDefs(vararg names: String): List<KitToolDefinition> =
    names.map { name ->
        KitToolDefinition(
            name = name,
            description = "fake $name",
            parameters = """{"type":"object","properties":{},"required":[]}"""
        )
    }

internal fun testEngine(
    modelClient: KitModelClient,
    toolRunner: KitToolRunner,
    tools: List<String> = listOf(
        "code_read", "code_edit", "code_write", "code_grep", "code_glob",
        "code_todo", "shell_execute", "code_check"
    ),
    config: KitEngineConfig = KitEngineConfig(),
    memory: KitMemory? = null,
    permissionSource: KitPermissionSource = KitPermissionSource.NONE,
    modelInfoSource: KitModelInfoSource? = null,
    environmentInfoSource: KitEnvironmentInfoSource? = null,
    workspaceContextSource: KitWorkspaceContextSource? = null,
    mcpInstructionSource: KitMcpInstructionSource? = null,
    errorClassifier: KitLlmErrorClassifier = KitLlmErrorClassifier.Default,
    retrySleeper: (suspend (Long) -> Unit)? = null
): AgentLoopEngine = AgentLoopEngine(
    modelClient = modelClient,
    toolSource = object : KitToolSource {
        override fun definitions(): List<KitToolDefinition> = fakeToolDefs(*tools.toTypedArray())
    },
    toolRunner = toolRunner,
    config = config,
    logger = KitLogger.Silent,
    memory = memory,
    permissionSource = permissionSource,
    modelInfoSource = modelInfoSource,
    environmentInfoSource = environmentInfoSource,
    workspaceContextSource = workspaceContextSource,
    mcpInstructionSource = mcpInstructionSource,
    errorClassifier = errorClassifier,
    retrySleeper = retrySleeper
)
