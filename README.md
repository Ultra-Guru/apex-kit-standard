# apex-standard-kit

> `com.apex.standardkit:standard-loop` — Pure-Kotlin standard thinking-loop engine for mobile coding workspaces. **Zero Android deps.**

从 Android-Guru-Agent 的 Coding「标准思考逻辑线」（4 号思考逻辑）提取的独立库模块：业界标准 Agent 任务循环的完整内核，经端口-适配器模式与宿主解耦——任何 JVM/Android 宿主实现端口即可接入。

## 模块

| 模块 | 坐标 | 说明 |
|---|---|---|
| `standard-loop` | `com.apex.standardkit:standard-loop` | 任务循环引擎（纯 Kotlin JVM，仅依赖 coroutines） |

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

### 循环内核（引擎）

- **六画像**：构建者 / 规划师 / 通用（主代理）+ 探索 / 调研 / 评审（隔离子代理）
- **回合循环**：预算 × 思考档位倍率；预算收官轮 toolChoice=none（工具定义保留，prompt cache 前缀不破坏）
- **并行工具执行**：连续只读/task/MCP/skill 调用成组并行；shell/写/编辑与 ASK 门控按原顺序串行（保序不破）
- **输出截断续跑**：finishReason=length 不当结论——注入续跑指令继续（≤2 次）
- **中断续跑**：流式传输中断但已有部分输出 → 保留部分输出 + 断点续跑指令（≤3 次，超过诚实收尾）
- **溢出压缩恢复**：provider 明示上下文超限 → shrink 阶梯（0.7/0.5/0.35）压缩后整轮重试（≤3 次）
- **上下文压缩**：七节结构化摘要模板 + NUDGE 纠偏 + 增量合并 + token 保留窗 + 滑窗降级
- **权限三态门**：allow/ask/deny × 模式兜底 × 规则通配 × 命令级通配 × 会话记忆 × 敏感文件保护 × 子代理 ASK 折叠
- **工具面编排**：画像白名单 / 别名归一 / provider 名清洗（`^[a-zA-Z0-9_-]{1,64}`）/ MAX_TOOLS 预算
- **read-before-edit 硬约束**：三形态路径归一（raw / 相对 / guest 前缀）
- **运行中转向（steering）**：补充指令入队，回合边界注入——不打断也不丢失
- **容错阶梯**：瞬时失败 2s/5s/10s/…/160s 阶梯重试；请求级拒绝一次去工具降级续跑

### 子系统组件（v1.1 新增——业界语义全量补齐）

| 子系统 | 组件 | 语义要点 |
|---|---|---|
| **Skills** | `KitSkillDocParser` / `KitSkillRegistry` / `KitSkillActivation` / `KitSkillInstructions` / `KitSkillRemotePull` / `KitSkillLoader` | `SKILL.md` frontmatter 解析；线程安全注册表 + 变更事件；`<skill_content>` 激活块（基目录 + 采样文件 ≤10）；`<available_skills>` 指引段 + 增量 delta 分级播报；远程 `index.json` 发现 + 版本钉住 + staging/backup 两阶段原子交换（技能并发 4 / 文件并发 8）；autoinvoke=false 隐藏；权限视图过滤 |
| **MCP** | `KitMcpNames` / `KitMcpToolBridge` / `KitMcpToolRegistry` / `KitMcpResourceReader` / `KitMcpServerHealth` | 注册表 id `mcp__{server}__{tool}` + provider 安全复合名；执行管线 = 权限门 → 熔断快失败 → 端口调用 → 错误映射（不可用/调用失败/isError）→ 内容归一（文本拼接 + 二进制 data URI）→ 输出归一（structured > 文本 JSON 探测 > 纯文本）；工具清单防抖再平衡（~100ms 滑动合并）；资源列举与读取；服务器熔断（连续失败阈值 + 冷却窗半开） |
| **工具执行** | `KitToolBatchPlanner` / `KitToolExecutor` / `KitToolExecutionHook` / `KitToolInputNormalizer` / `KitToolOutputBudget` / `KitToolSettlement` | 并行安全组规划（写操作是栅栏）；保序执行（start 顺序广播 + 并发收集 + 结果源序）；空串可选键删除与键大小写归一（模型顽疾补丁）；输出三重预算（行 2000 / 字节 50KiB / 字符 100k）头 70% + 尾 30%；悬挂调用补「Provider did not return a tool result」合成失败；四型出口（完成/拒绝/中断/失败）消息渲染 |
| **事件总线** | `KitEventBus` / `debounced` / `KitBusRecorder` / `KitDomainEvents` | 类型化总线（滑动丢弃背压——慢订阅者不反压）；按类型订阅；防抖合并（全稳定协程 API）；环形事件录制（调试/断言）；库内标准域事件（技能/MCP/工具批/压缩生命周期） |
| **上下文策略** | `KitContextWindowPolicy` / `KitTokenBudgetPlanner` / `KitContextPressure` / `KitOverflowShrinkLadder` / `KitUsageLedger` / `KitCostRates` / `KitTokenEstimates` | 窗口策略（输出预留 + 安全边际）；预算三决策（OK/压缩/结构性溢出）；四级压力水位；收缩阶梯（1.0→0.7→0.5→0.35）；跨回合用量台账（输入/输出/缓存分离计价，微美元精度防漂移）；混合 CJK/拉丁 token 估算 |
| **子代理策略** | `KitSubAgentDepthRule` / `KitSubAgentModelRef` / `KitSubAgentModelOverride` / `KitSubAgentContinuation` / `KitSubAgentAdmission` | 父链上溯深度限制（默认 1，环状谱系防御熔断）；`provider/model#variant` 三段式解析 + 目录校验（未知模型/变体的模型可自纠错文案）；续接裁决（子会话劫持拒绝 + 换代理模型三级语义）；新会话入场白与后台模式「不要 sleep/轮询」文案；`<subagent>` 结构化结果渲染 |
| **JSON** | `KitJson` | 库自含迷你 JSON 值模型 + 递归下降解析 + 紧凑序列化（`\uXXXX` 转义）——skills 索引 / MCP 参数 / 归一化的零依赖基座 |

## 快速接入

```kotlin
dependencies {
    implementation("com.apex.standardkit:standard-loop:1.1.0")
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

### 子系统组件接入示例

```kotlin
// Skills：本地目录装载 + 远程索引拉取（IO 全走端口）
val loader = KitSkillLoader(KitSkillFileSystem.Jvm)
val registry = KitSkillRegistry()
loader.scan("/data/skills").forEach { registry.add(it) }

val pull = KitSkillRemotePull(KitSkillFileSystem.Jvm, myHttpClient)
val remoteRoots = pull.pull("https://skills.example/index", "/data/cache")  // suspend
remoteRoots.forEach { root -> loader.scan(root).forEach { registry.add(it) } }

// MCP：桥接 + 防抖再平衡
val bridge = KitMcpToolBridge(myMcpClient, myPermissionGate)
val mcpRegistry = KitMcpToolRegistry(myMcpClient, appScope) { fresh ->
    mcpDefinitions = bridge.definitionsFor(fresh)   // 变更风暴合并为一次重建
}

// 工具批执行：保序并行 + 结算（自研循环宿主直用）
val executor = KitToolExecutor(myToolRunner, hooks = listOf(
    KitToolExecutionHook { it.copy(arguments = KitToolInputNormalizer.dropEmptyOptionalKeys(it.arguments, setOf("model"))) }
))
val batch = executor.executeBatch(invocations, myListener)
val settled = KitToolSettlement.settleMissing(declaredCallIds, batch.results)
val messages = KitToolSettlement.renderMessages(settled)
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
| `KitSkillFileSystem` / `KitSkillHttpClient` | ❌ | Skills IO（JVM 默认实现 / 宿主注入） |
| `KitMcpClientPort` / `KitMcpResourcePort` / `KitMcpPermissionGate` | ❌ | MCP 客户端 / 资源 / 权限门 |
| `KitSubAgentCatalogPort` / `KitSubAgentSessionLineage` | ❌ | 子代理画像目录 / 会话谱系 |

## 兼容矩阵

| 库 | Kotlin | Gradle | JDK |
|---|---|---|---|
| 1.1.0 | 2.2.21 | 8.14 | 17 |
| 1.0.0 | 2.2.21 | 8.14 | 17 |

与宿主 [Android-Guru-Agent](https://github.com/Ultra-Guru/Android-Guru-Agent) 的工具链矩阵对齐（composite build 要求两侧 Kotlin/AGP 版本一致）。

## 消费方式（宿主）

迁移期 composite build：宿主 `settings.gradle.kts` 条件 `includeBuild`（兄弟目录 `../apex-standard-kit` 或嵌套目录），坐标 `com.apex.standardkit:standard-loop` 自动替换为本地构建。CI 由宿主流水线按 `gradle/standard-kit.lock` 锁定版本检出。

## 开发

```bash
./gradlew :standard-loop:test          # 纯 JVM 单测（零 Android 依赖的可执行证明）
./gradlew publishToMavenLocal          # 可发布性冒烟
```

CI（`.github/workflows/`）：
- **ci.yml**：模块编译 + 单测 + publishToMavenLocal + **consumer-check**（跨仓库验证宿主仍能编译——库的每次 PR 都对宿主跑一遍编译，坏下游立刻看到）
- **guard-rails.yml**：词汇合规扫描（禁用词零容忍）+ 公共 API 面快照（破坏性变更高亮）
- **release.yml**：tag 触发的构建 + GitHub Release 附产物（jar/sources/POM）
- **notify-host.yml**：main 全绿后通知宿主仓库拉新（无 token 时优雅跳过，宿主定时轮询兜底）

## License

MIT —— 见 [LICENSE](LICENSE)。
