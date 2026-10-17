# apex-agent-kit

> `com.apex.agentkit:agent-loop` — Pure-Kotlin agent task-loop engine for mobile coding workspaces. **Zero Android deps.**

从 Android-Guru-Agent 的 Coding「标准思考逻辑线」提取的独立库模块：业界标准 Agent 任务循环的完整内核，经端口-适配器模式与宿主解耦——任何 JVM/Android 宿主实现端口即可接入。

## 模块

| 模块 | 坐标 | 说明 |
|---|---|---|
| `agent-loop` | `com.apex.agentkit:agent-loop` | 任务循环引擎（纯 Kotlin JVM，仅依赖 coroutines） |

## 能力总览

```
user msg ──► [steer drain] ──► [compaction?] ──► build system(profile+rules+tools+mcp)
     ▲                                                  │
     │                                                  ▼
     │                                            chatStream ◄── Thinking/Response/Usage 事件
     │                                                  │
     │              ┌── finish=length ── 续跑指令 ──┐└── tool calls ── 纯文本 ──► ResponseComplete ──► DONE
     │              │        (≤2 次)              │        │
     │   [interrupt?]── 续跑指令 ──┐               │        ▼
     │   [overflow?]── 压缩shrink ─┤               │   permission gate (allow/ask/deny)
     │                            │               │        ▼
     │                            └───────────────┤   execute (并行安全组 ∥ / 有序组串行 / task → 子代理)
     │                                            ▼
     └──────────── append ToolResult（循环至无工具调用 / 预算耗尽 → 收官轮 toolChoice=none）
```

- **六画像**：构建者 / 规划师 / 通用（主代理）+ 探索 / 调研 / 评审（隔离子代理）
- **回合循环**：预算 × 思考档位倍率；预算收官轮 toolChoice=none（工具定义保留，prompt cache 前缀不破坏）
- **并行工具执行**：连续只读/task/MCP/skill 调用成组并行；shell/写/编辑与 ASK 门控按原顺序串行（保序不破）
- **输出截断续跑**：finishReason=length 不当结论——注入续跑指令继续（≤2 次）
- **中断续跑**：流式传输中断但已有部分输出 → 保留部分输出 + 断点续跑指令（≤3 次，超过诚实收尾）
- **溢出压缩恢复**：provider 明示上下文超限 → shrink 阶梯（0.7/0.5/0.35）压缩后整轮重试（≤3 次）
- **上下文压缩**：七节结构化摘要模板（Objective/Requirements/Decisions/Work State/Next Move/Relevant Files/Important Context）+ NUDGE 纠偏 + 增量合并 + token 保留窗 + 滑窗降级
- **权限三态门**：allow/ask/deny × 模式兜底 × 规则通配 × 命令级通配 × 会话记忆 × 敏感文件保护 × 子代理 ASK 折叠
- **MCP 使用指引**：握手 instructions 注入系统提示词（仅当该服务器工具在当前工具面上——防幻觉指引）
- **工具面编排**：画像白名单 / 别名归一 / provider 名清洗（`^[a-zA-Z0-9_-]{1,64}$`）/ MAX_TOOLS 预算
- **read-before-edit 硬约束**：三形态路径归一（raw / 相对 / guest 前缀）
- **运行中转向（steering）**：补充指令入队，回合边界注入——不打断也不丢失
- **工具输出头尾截断**：行（2000）/字节（50KiB）/字符三重预算，头 70% + 尾 30%
- **容错阶梯**：瞬时失败 2s/5s/10s/…/160s 阶梯重试；请求级拒绝一次去工具降级续跑

## 快速接入

```kotlin
dependencies {
    implementation("com.apex.agentkit:agent-loop:1.0.0")
}
```

实现四个必选端口（通常每个十行以内），可选端口按需：

```kotlin
val engine = AgentLoopEngine(
    modelClient = myModelClient,        // KitModelClient：chatStream / chat
    toolSource = myToolSource,          // KitToolSource：definitions()
    toolRunner = myToolRunner,          // KitToolRunner：executeStream()
    logger = myLogger,                  // KitLogger（或 KitLogger.Silent）
    config = KitEngineConfig(...),      // 预算与行为参数（全有默认值）
    memory = myMemory,                  // KitMemory：会话持久化（可选）
    permissionSource = myPermSource,    // KitPermissionSource（可选）
    modelInfoSource = { ... },          // 模型 id / 真实窗口（可选）
    mcpInstructionSource = { ... },     // MCP 服务器指引（可选）
    // ... 其余端口见 KitPorts.kt
)

engine.execute(KitUserInput.text("修复登录崩溃")).collect { event ->
    when (event) {                       // 事件协议见 KitEvent
        is KitEvent.ResponseChunk -> render(event.text)
        is KitEvent.ToolCallComplete -> renderTool(event)
        is KitEvent.Complete -> finish(event.summary)
        else -> Unit
    }
}
```

## 端口清单

| 端口 | 必选 | 职责 |
|---|---|---|
| `KitModelClient` | ✅ | 流式/非流式 LLM 请求（含 toolChoice / reasoningEffort 透传） |
| `KitToolSource` / `KitToolRunner` | ✅ | 工具定义清单与执行流 |
| `KitLogger` | ✅ | 结构化日志（可用 Silent） |
| `KitLlmErrorClassifier` | ✅* | 失败分类（瞬时/拒绝/溢出；默认启发式可用） |
| `KitMemory` | ❌ | 会话持久化（null = 纯内存） |
| `KitTextKernel` | ❌ | token 估算加速核（native JNI 注入点） |
| `KitPermissionSource` | ❌ | 权限配置快照（改设置即时生效） |
| `KitModelInfoSource` | ❌ | 模型 id + 真实上下文窗口 |
| `KitEnvironmentInfoSource` | ❌ | 平台 / 工作目录 / git 探测 |
| `KitWorkspaceContextSource` | ❌ | 工作区 / 全局规则 / 项目规则提示词段 |
| `KitMcpInstructionSource` | ❌ | MCP 服务器使用指引 |
| `KitSubAgentBudgetSource` | ❌ | 子代理预算快照 |

## 兼容矩阵

| 库 | Kotlin | Gradle | JDK |
|---|---|---|---|
| 1.0.0 | 2.2.21 | 8.14 | 17 |

与宿主 [Android-Guru-Agent](https://github.com/Ultra-Guru/Android-Guru-Agent) 的工具链矩阵对齐（composite build 要求两侧 Kotlin/AGP 版本一致）。

## 消费方式（宿主）

迁移期 composite build：宿主 `settings.gradle.kts` 条件 `includeBuild`（兄弟目录 `../apex-agent-kit` 或嵌套目录），坐标 `com.apex.agentkit:agent-loop` 自动替换为本地构建。CI 由宿主流水线按 `gradle/agent-kit.lock` 锁定版本检出。

## 开发

```bash
./gradlew :agent-loop:test          # 纯 JVM 单测（零 Android 依赖的可执行证明）
./gradlew publishToMavenLocal       # 可发布性冒烟
```

CI（`.github/workflows/ci.yml`）：三模块编译 + 单测 + publishToMavenLocal + **consumer-check**（跨仓库验证宿主仍能编译——库的每次 PR 都对宿主跑一遍编译，坏下游立刻看到）。

## License

MIT —— 见 [LICENSE](LICENSE)。
