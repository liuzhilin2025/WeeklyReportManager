# Spring AI 学习计划（基于本项目 + 官方文档）

> 适用范围：本项目 `WeeklyReportManager`，Spring AI BOM `1.1.8`（见 `pom.xml`）。
> 版本基线（2026-09 按各 tag 根 pom 核对）：1.1.8 的构建基线是 **Spring Boot 3.5.15 + JDK 17**，与本项目的 Boot 3.5.x / JDK 17 同线；而官网默认展示的是 **2.0.x（基线 Boot 4.1）**，所以「文档对不上」是常态。详见第三节。
> 组织思路：**把项目里已经在用的 5 个 AI 入口当教材**，按「接入 → 提示词 → 结构化输出 → 拦截器/记忆 → 流式 → 工具 → RAG → 可观测性」的顺序，补齐官方文档里尚未接触过的模块。
> 官方文档入口：<https://docs.spring.io/spring-ai/reference/index.html>

---

## 一、先盘点：项目已经用到了什么

| 已用能力 | 代码位置 | 对应官方章节 |
|---|---|---|
| BOM + starter 依赖管理 | `pom.xml`（`spring-ai-bom`、`spring-ai-starter-model-openai`） | Getting Started |
| OpenAI 兼容协议接 DeepSeek | `application.properties`（`spring.ai.openai.*`） | OpenAI Chat |
| `ChatClient.Builder` 自动配置注入 | `AIService` 构造器 | ChatClient → Creating a ChatClient |
| 结构化输出 `.entity(Class)` | `AIService#callForEntity` | Structured Output |
| 自由文本 `.content()` | `AIService#chatWithReports` | ChatClient → Responses |
| PromptTemplate + 外置提示词 | `AIService` 构造器、`resources/prompts/` | ChatClient → Prompt Templates |
| 消息角色 `system` / `Message` / `UserMessage` | `AIService#callForEntity`、`#polishReport`、`#summarizeTeam` | Concepts → Messages |
| 上下文预算截断 | `ReportTextFormatter#context`（`utils` 包） | —— 官方未覆盖，属工程实践 |
| AI 异常分类 → 400 / 503 | `AIService#callForEntity` + `GlobalExceptionHandler` | —— 同上 |
| AI 结果缓存 | `AIService#summarizeHistory`、`#summarizeTeam` | —— 同上 |

**尚未接触过的官方模块**：Advisors、Chat Memory、Streaming、Tool Calling、RAG（Embedding / VectorStore / ETL Pipeline）、Observability、Testing & Evaluation、MCP。

### 五个 AI 入口（后续每个阶段的练习素材）

| 入口 | 方法 | 输出形态 |
|---|---|---|
| 生成草稿 | `AIService#generateDraft` | 结构化 DTO（`.entity`） |
| 润色 | `AIService#polishReport` | 结构化 DTO（`.entity`） |
| 个人摘要 | `AIService#summarizeHistory` | 结构化 DTO + 缓存 |
| 团队汇总 | `AIService#summarizeTeam` | 结构化 DTO + 缓存 |
| 周报问答 | `AIService#chatWithReports` | 自由文本（`.content`） |

---

## 二、学习计划（按依赖顺序，不按周次）

### 阶段 1 · 模型接入层：配置是怎么变成 Bean 的

**要搞懂**：`spring.ai.openai.*` 那几行配置，中间经过几个 Bean 才到手上的 `chatClientBuilder`。

- 读：Getting Started、Concepts、ChatModel API、OpenAI Chat
- 对照：`pom.xml` 依赖、`application.properties`、`AIService` 构造器
- 练习：
  1. 从 `ChatClient.Builder` 往上游找 Bean 定义（`ChatClientAutoConfiguration` → `OpenAiChatAutoConfiguration`），画出「properties → `OpenAiChatOptions` → `OpenAiChatModel` → `ChatClient.Builder`」这条链
  2. 断点或日志打印 `OpenAiChatOptions` 的默认值，区分哪些是 Spring AI 默认、哪些是自己配的
  3. 改 `temperature`（1.1.8 实测默认 **0.7**，见 `OpenAiChatProperties#DEFAULT_TEMPERATURE`），观察 `generateDraft` 输出稳定性的变化
- **验收**：能说清「为什么只写几行配置就有 ChatClient 可用」。

### 阶段 2 · Prompt 与消息体系

**要搞懂**：`Prompt` / `SystemMessage` / `UserMessage` 的区别，模板渲染发生在哪一步。

- 读：ChatClient → Fluent API、Prompt Templates、Message Metadata；Concepts
- 对照：`AIService#callForEntity` 的 `.system(...)` + `.messages(...)`、`generateDraft` 的模板渲染、`prompts/ai-report-user.st`
- 练习：
  1. 把 `.system(String)` 换成显式 `SystemMessage`，体会 String 重载省掉了什么
  2. `PromptTemplate` 默认使用 `StTemplateRenderer`，把分隔符改成 `<userInput>` 试一次
  3. 把 `generateDraft` 里的 `Message` 换成直接 `.user(text)`，对比 `chatWithReports` 的写法，理解两条路径的等价性
- **验收**：能解释 `{userInput}` 是在**本地**渲染的，与模型无关。

### 阶段 3 · 结构化输出 ★ 最该补的一节

**要搞懂**：`.entity()` 内部做了什么——生成 JSON Schema → 拼进 prompt → 解析。项目里三个「坑」都能在这一节找到官方解释。

- 读：Structured Output（Typed Response / Generic Types 子页）。**EntityParamSpec / Schema Validation / Native Structured Output 三个子页是 2.0.x 的，1.1.8 用不上**，看的时候心里要区分开
- 对照：
  - `AIService#callForEntity` 的 `.entity(type)`
  - `AIService#summarizeTeam` 中**为什么用 `TeamSummaryAI` 而不是 `TeamSummaryDTO`**：官方文档原话是 schema 被追加到 system context，多一个 `missingMembers` 字段就会诱使模型自造名单——项目注释与文档结论一致
  - `prompts/ai-report-system.txt` 手写「只输出合法 JSON」：这是 prompt 层约束的做法
  - `ReportTextFormatter` 的 `limit` + `AIService` 的 `maskEmptyFields` / 判空三道防线：对应文档所说「默认 `.entity()` 没有任何保证」
- 练习：
  1. 用 `BeanOutputConverter` 手工走一遍 schema 生成 → 拼 prompt → 解析，把 `.entity()` 的黑盒打开
  2. 故意把 `TeamSummaryAI` 换回 `TeamSummaryDTO`，复现「模型自己编未提交名单」
  3. ~~试 `validateSchema()`（校验失败自动带错误重试）与 `useProviderStructuredOutput()`（把 schema 交给 provider 强制约束）~~ → **1.1.8 做不了，跳过**。已核实 1.1.8 的 `ChatClient.CallResponseSpec.entity(...)` 只有 3 个重载（`Class` / `ParameterizedTypeReference` / `StructuredOutputConverter`），带 `Consumer<EntityParamSpec>` 的重载是 **2.0.0** 才加的。知道它是什么、想用要等升到 2.0.x（而那要求把 Spring Boot 升到 4），先记着即可
  4. 换成 `responseEntity(...)`，把 token 用量打进日志，给「提示词预算」补上真实度量
- **验收**：能说清「为什么模型偶尔不守提示词」以及「代码里为什么必须兜底」。

### 阶段 4 · Advisors 与对话记忆

**要搞懂**：Advisor 链的拦截模型与 order 语义（请求顺序与响应顺序是相反的）。

- 读：Advisors API、Chat Memory
- 对照：`chatWithReports` 目前是**无状态**的——每次请求都重新拼一遍周报原文，没有多轮上下文
- 练习：
  1. 加 `MessageWindowChatMemory` + `MessageChatMemoryAdvisor`，用 `userId` 作为 `conversationId`，实现「追问上一句」（`ChatMemory` 其实可以直接注入自动配置好的那个，见练习 3；手动 `new` 一遍是为了看清它是什么）
  2. 加一个自定义日志 Advisor（文档中的 `SimpleLoggerAdvisor` 示例），打印 request / response，比散落的 `log.error` 更能看清模型实际收到了什么
  3. **`ChatMemory` 不用自己 new**（1.0.0 起就是如此，我上一版计划写错了）：`spring-ai-starter-model-openai` 会传递引入 `spring-ai-autoconfigure-model-chat-memory`（1.0.0 和 1.1.8 两版 starter 的 pom 都核对过），`ChatMemoryAutoConfiguration` 会自动准备好 `ChatMemoryRepository`（=`InMemoryChatMemoryRepository`）和 `ChatMemory`（=`MessageWindowChatMemory`，窗口 20 条），两者都带 `@ConditionalOnMissingBean`，直接注入即可、也可以自己覆盖。**注意窗口大小没有对应的配置属性**，想从 20 改成 10 只能自己定义 `ChatMemory` bean（这与练习 1 写的 `MessageWindowChatMemory.builder().maxMessages(...)` 是同一件事的两种写法，对比一下很值得）
  4. **注意（已按 1.1.8 源码修正，结论与上一版计划相反）**：从 **1.1.6** 起 `CONVERSATION_ID` 变成**必传**——`BaseChatMemoryAdvisor` 只剩 `getConversationId(Map)` 单参版本，context 里没有该 key 时直接抛 `IllegalArgumentException("conversationId cannot be null")`。**1.1.5 及以前**才有「回退到 `ChatMemory.DEFAULT_CONVERSATION_ID`（`"default"`）」的行为（`getConversationId(context, defaultConversationId)`）。本项目在 1.1.8，所以**每次调用都必须 `.param(ChatMemory.CONVERSATION_ID, ...)`**，否则直接报错
  5. 同一个改动还删掉了 `MessageChatMemoryAdvisor.Builder.conversationId(...)`（1.1.8 的 Builder 只剩 `order()` / `scheduler()`），「默认会话 ID」这个口子彻底封死，会话隔离只能由调用方负责——正好是这个练习想让你体会的点
  6. **1.1.x 起 `before()` 会把 `SystemMessage` 移到消息列表最前面**（源码里那行 "Ensure system message, if present, appears first in the list"）。1.0.0 是 `memory + instructions` 直接首尾相接，系统消息会落在历史之后变成 `[USER, ASSISTANT, SYSTEM, USER]`；现在固定是 `[SYSTEM, ...历史..., ...本次消息]`。用日志 Advisor 打印一次，把这个顺序看出来
- **验收**：能解释「为什么 memory advisor 要放在日志 advisor 之前 / 之后」。

### 阶段 5 · 流式输出

**要搞懂**：`call()` 与 `stream()` 的编程模型差异。

- 读：ChatClient → Streaming Responses；OpenAI Chat → Streaming
- 对照：`chatWithReports` 现在是阻塞返回整个字符串
- 练习：
  1. 新增 `/ai/chat/stream` 返回 `Flux<String>`（SSE），前端逐字显示
  2. 记住文档的硬约束：**`.entity()` 只支持 `call()`，流式拿不到结构化对象**，需要自行用 converter 拼装
  3. 开启流式下的 token 统计，属性名在 1.1.8 是 **`spring.ai.openai.chat.options.stream-usage`**（在嵌套的 `options` 里；2.0.x 文档写的是 `spring.ai.openai.chat.stream-usage`，照抄会不生效）。它绑定的其实是 `OpenAiChatOptions#setStreamUsage(Boolean)`，底层是往请求里塞 `stream_options.include_usage`
- **验收**：能判断哪些接口适合流式（问答），哪些不适合（草稿 / 摘要，因为前端要一次性填表）。

### 阶段 6 · Tool Calling

**要搞懂**：让模型自己决定「什么时候调你的 Java 方法」，而不是把所有数据塞进 prompt。

- 读：Tools / Function Calling
- 对照：`summarizeTeam` 里「未提交成员」是 Java 算出来再回填的，模型并不知道——这正是文档所说「模型需要访问实时信息」的场景
- 练习：
  1. 把 `WeeklyReportService` 的几个查询方法包成 `@Tool`
  2. 对比：`buildReportContext` 是**全量塞**，tool calling 是**按需查**
- **验收**：能判断一个功能该用「塞上下文」还是「给工具」。

### 阶段 7 · RAG（替代现有的截断策略）

**要搞懂**：Embedding → VectorStore → 检索增强这条链。

- 读：Embeddings、Vector Stores、ETL Pipeline、`QuestionAnswerAdvisor` / `RetrievalAugmentationAdvisor`
- 对照：`MAX_PROMPT_REPORT_CHARS = 4000` 与「更早的周报因篇幅限制未包含在内」——**这就是 RAG 要解决的问题**：不问就不塞，问了才检索
- 练习：
  1. 项目已有 Redis，先用 `SimpleVectorStore`（内存）跑通，再换 Redis VectorStore
  2. 把历史周报灌入（ETL：Reader → Splitter → Writer），`chatWithReports` 换成检索式问答
  3. 对比两种方案：截断版答不出「三个月前那个项目的收尾情况」，RAG 版可以
- **验收**：能说清 RAG 的代价（多一次 embedding 调用 + 向量库运维），以及当初为什么先用原方案。

### 阶段 8 · 可观测性与测试

**要搞懂**：AI 调用怎么被度量、怎么被测试。

- 读：Observability、Testing（含 Evaluation）
- 对照：`AIService` 目前只有日志，没有任何耗时 / 成本指标；`src/test` 中没有 AI 层测试
- 练习：
  1. 接入 Micrometer observation，观察每次 `.call()` 的耗时与 token
  2. 用 mock `ChatModel`（不是 mock HTTP）给 `AIService` 写单测，**优先覆盖 `limit` / `maskEmptyFields` / 判空这些纯本地分支**——它们是逻辑最密、最容易回归的地方
  3. 用官方 Evaluation 工具评估摘要质量，替代「人眼看」
- **验收**：AI 代码有自动化测试，而不是只能靠手点。

### 阶段 9 · 选做：MCP 与多模型

- 读：MCP Overview、各 provider 章节
- 练习：
  1. 把「查周报」暴露成一个 MCP server
  2. 用 `ChatClient.Builder` 的 prototype 特性接第二个模型（如本地 Ollama 做草稿、DeepSeek 做润色），练习多模型路由

---

## 三、版本坑（重要，会影响读文档）

官网默认展示更新的版本，本项目在 `1.1.8`，两者存在差异。下面每一条都是按对应 tag 的源码核出来的，不是推测。

### 3.1 版本基线：BOM 必须跟着 Spring Boot 大版本走

| Spring AI | 构建基线 `spring-boot.version` | Java | 说明 |
|---|---|---|---|
| 1.0.0 | **3.4.5** | 17 | 项目起点 |
| **1.1.8（本项目）** | **3.5.15** | 17 | 与 Boot 3.5.x 同线，是当前 3.5 线上的最新维护版本 |
| 2.0.1 | **4.1.0** | 17 | 要 Spring Boot 4，升它等于整个项目升 Boot 4 |

所以选版本时看的不是「谁更新」，而是「我的 Spring Boot 是哪条线」。本项目 Boot 3.5.2 → 只能待在 1.1.x。（可选：把 Boot 从 3.5.2 提到 3.5.15，与 1.1.8 的基线完全对齐。补丁升级风险很低）

### 3.2 官网（2.0.x）有、但 1.1.8 还没有的东西

| 官网这么写 | 1.1.8 的真相 |
|---|---|
| `spring.ai.openai.chat.model`、`spring.ai.openai.chat.temperature`（属性拍平） | **还是** `spring.ai.openai.chat.options.model` / `.options.temperature`（嵌套 `options` 字段）。拍平是 **2.0.0** 才做的，`options.*` 在 2.0.0 被标了 `@Deprecated(forRemoval = true)` 只是暂时兼容。**`application.properties` 现在这行不用改** |
| `entity(..., Consumer<EntityParamSpec>)`、`validateSchema()`、`useProviderStructuredOutput()` | **不存在**。1.1.8 的 `CallResponseSpec.entity(...)` 只有 `Class` / `ParameterizedTypeReference` / `StructuredOutputConverter` 三个重载，整个 `EntityParamSpec` 是 **2.0.0** 新增的 |
| `MessageChatMemoryAdvisor.builder(memory).conversationId("...")` | **不存在**。1.1.6 起 Builder 只剩 `order()` / `scheduler()` |
| 「`CONVERSATION_ID` 不传会抛 `IllegalArgumentException`」 | 这条**在 1.1.8 成立**，但不是 2.0 才有的——**1.1.6 起**就是这个行为了（1.1.5 及以前回退到 `"default"`） |

结论：**版本坑不只有「属性名」这一类，还有「同一句话在不同小版本里真假相反」这一类**。凡是涉及「默认行为」「必填/可选」的结论，都要指明到具体版本号，不能只写「新版本」。

### 3.3 名字变更（看旧资料时会撞上）

| 旧名 | 现在的名字 |
|---|---|
| `CallAroundAdvisor` / `StreamAroundAdvisor`（1.0 M3） | `CallAdvisor` / `StreamAdvisor`（1.0.0 起） |
| `CallAroundAdvisorChain` / `StreamAroundAdvisorChain` | `CallAdvisorChain` / `StreamAdvisorChain` |
| `AdvisedRequest` / `AdvisedResponse` | `ChatClientRequest` / `ChatClientResponse` |
| `RequestAdvisor` / `ResponseAdvisor`（1.0 M2） | 已合并进上面两个接口 |

**读文档的做法**：先用页面上的版本下拉切到 1.1.x；只要有一处对不上，就直接去 GitHub 按 tag 读源码：

```
https://raw.githubusercontent.com/spring-projects/spring-ai/v1.1.8/<模块路径>/<类名>.java
https://api.github.com/repos/spring-projects/spring-ai/contents/<目录>?ref=v1.1.8   # 列目录
```

上面 3.1 / 3.2 的每一条都是这么核出来的。查一个类的真实签名，比翻三页文档都快，也不会被版本搞混。

---

## 四、贯穿全程的两条线索

1. **AI 的不确定性是一等公民。**
   项目里最值得学习的不是「调通了 API」，而是三处对「模型没按约定来」的处理：`AIService` 的截断与掩码、`buildReportContext` 的丢篇提示、`summarizeTeam` 的 `TeamSummaryAI` 隔离。后续每个阶段都要问同一句：**如果模型不配合，我的代码会怎样？**

2. **成本与延迟是设计约束。**
   `MAX_PROMPT_REPORT_CHARS`、`@Cacheable`、字段截断，本质都是同一个权衡。阶段 5 / 6 / 7 引入的新方案都要重新算这笔账。

---

## 五、建议的推进顺序

从**阶段 3** 开始动手（收益最大、与现有代码咬合最紧），阶段 1 / 2 当作背景阅读快速过掉。

每完成一个阶段，配套做两件事：

1. 在对应的已有类上做一次小改造（而不是新建 demo 类）
2. 问自己一句：「这一步让哪条业务路径变好了？代价是什么？」
