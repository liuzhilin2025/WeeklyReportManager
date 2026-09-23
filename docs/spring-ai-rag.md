# 周报语义检索（RAG）：设计、实现与类比

> 适用范围：本项目 `WeeklyReportManager`，Spring AI **1.1.8**。
> 涉及代码：`service/tool/WeeklyReportIndexer.java`、`config/VectorStoreConfig.java`、`service/tool/ReportQueryTools.java`、`service/tool/WeeklyReportVectorSyncListener.java`、`application.properties`。
> 官方依据：Spring AI Reference 1.1.8 — *Retrieval Augmented Generation*、*Vector Databases*、*Ollama Embeddings*。
> 配套阅读：`spring-ai-tool-calling.md`（本功能以工具形态接入）、`spring-ai-chat-memory.md`（记忆机制）。

---

## 一、先纠正一个常见误解：这里的 RAG 不是用来"替代截断"的

学习计划里这一阶段的原始动机是"替代现有的截断策略"（`MAX_PROMPT_REPORT_CHARS`）。但**这个动机在工具化改造之后已经消失了**：

| 场景 | 现状 |
|---|---|
| 周报问答 | 已改成工具按需查，**不再全量塞** |
| 个人摘要 / 团队汇总 | 仍用截断，但范围本身很小（4 周 / 1 周），基本不会触发 |

**那 RAG 在这里解决的是什么？** ——补上"**按语义找内容**"这块空缺。

证据在现有工具的设计里：`listSubmittedWeeks` 返回的标题是日期区间（`08.17~08.21`），**不含任何内容语义**；而 `getReport` 要求精确的周一日期，注释还明确写着"不要盲猜"。

于是这类问题模型完全无能为力：

> "我什么时候做过缓存相关的工作？"
> "之前有没有提到过某个话题？"

它只能：拉目录 → 逐周 `getReport` → 撞运气。**token 爆炸且不可靠。**

**语义检索补的正是这一块。**

---

## 二、类比：给病历建一套「索引卡」

延续诊所世界观。

**问题**：病历（周报）越来越多，医生（模型）不能每次都把所有病历摊在诊台上——token 装不下，钱也烧不起。

**方案**：给每份病历做一张**索引卡**，建一个**能按"意思"查找的索引柜**。

| 类比中的东西 | 真实组件 | 说明 |
|---|---|---|
| **索引卡** | `Document` | 一段可被检索的文本 + 一组元数据 |
| **卡片上的字段** | Document 的 metadata | "病人号"`userId`、"周次"`weekStartDate` |
| **卡片的编号** | Document 的 id | 这里用 `reportId` |
| **索引柜** | `VectorStore`（`SimpleVectorStore`） | 存卡、按相似度找卡 |
| **做卡的过程** | ETL（`WeeklyReportIndexer`） | 从数据库读出周报 → 转成卡片 → 存柜 |
| **卡片增补** | `WeeklyReportVectorSyncListener` | 周报改了，卡片跟着换 |
| **按意思找卡** | `vectorStore.similaritySearch()` | 不是关键词匹配，是**语义相似度** |
| **"多像才算相关"的门槛** | `similarityThreshold` | 本项目用 0.5 |
| **只让看自己的卡** | metadata 的 `userId` 过滤 | 租户隔离 |
| **只看某段时间的卡** | metadata 的 `weekStartDate` 过滤 | ⚠️ **这个功能在内存实现上失效了**，见第八节坑 2 |

**为什么叫"按意思找"**：搜"缓存"，能命中写着"Redis 缓存优化"的卡——**字面并不完全相同**。这就是 embedding 的作用：把文本变成向量，语义相近的向量距离近。

---

## 三、Embedding 是什么（理解整条链路的前提）

在讲四个环节之前，先把最底层的概念说清楚——**后面几乎所有设计决策，都是从 embedding 的性质推出来的**。

### 3.1 一句话定义

**Embedding = 把一段文本变成一串数字（向量），让「意思相近」的文本在数字空间里距离也相近。**

### 3.2 类比：给每段文本一个「语义坐标」

| | 城市 | 文本 |
|---|---|---|
| 坐标 | 经度、纬度（2 个数） | `[0.023, -0.441, 0.887, ...]`（**1024 个数**） |
| 怎么比远近 | 测地理距离 | 测余弦相似度 |
| 结论 | 上海离苏州近 | 「缓存」离「Redis 缓存优化」近 |

具体到本项目：

```
「缓存」                          → [0.023, -0.441, 0.887, ...]   ┐
「完成 AI 功能集成与 Redis 缓存优化」 → [0.031, -0.428, 0.902, ...]   ├→ 夹角小 → 相似度 0.575
                                                                ┘
「娥儿雪柳黄金缕…」                → [-0.51, 0.22, -0.08, ...]      ─→ 夹角大 → 相似度 0.394
```

这几个数就是调试接口里返回的 `score`，也是第六节「阈值 0.5」那个决策的实测依据。

### 3.3 它解决了什么问题

| | 关键词搜索 | 语义检索 |
|---|---|---|
| 搜「缓存」 | 只能找到含「缓存」两个字的文档 | 能找到「Redis 缓存策略」「本地缓存」「Caffeine」 |
| 依据 | 字面匹配 | **语义距离** |

这是模型在海量文本上**训练出来的性质**——没有人规定「缓存」要和「Redis」靠近，是模型自己学到的。

### 3.4 在项目里的三个位置

| 位置 | 做什么 | 调用 embedding 吗 |
|---|---|---|
| `WeeklyReportIndexer`（建索引） | 每篇周报 → 一个向量 | ✅ 28 篇 → 28 次（按批） |
| `searchReports`（检索） | 用户的 query → 一个向量 | ✅ 每次提问 1 次 |
| `SimpleVectorStore`（比较） | 算余弦相似度、排序 | ❌ 纯本地内存计算 |

**这三行解释了项目里多个「看起来奇怪」的现象**：

| 现象 | 原因 |
|---|---|
| 启动变慢 | 要给 28 篇周报挨个算向量 |
| Ollama 挂了整个应用起不来 | `vectorStore.add()` 内部要调 embedding，失败就抛异常 |
| 换 embedding 模型要全量重建 | 不同模型的向量维度与含义都变了，库里旧向量全部作废 |
| `bge-m3` 必须预先拉取 | 它就是干这个活的模型，没有它就没有向量 |

### 3.5 关键区分：EmbeddingModel ≠ ChatModel

这是最容易混的地方：

| | ChatModel（DeepSeek） | EmbeddingModel（Ollama + bge-m3） |
|---|---|---|
| 输入 | 文本 | 文本 |
| **输出** | **文本**（回答） | **一串数字**（向量） |
| 干什么 | **生成内容** | **算相似度** |
| 能互换吗 | ❌ 完全不同的模型 | ❌ |
| 成本 | 按 token 计费，较贵 | 便宜得多，本地跑免费 |
| 项目里 | 对话、摘要、润色、工具调用 | 语义检索 |

**这就是项目要同时配两个 provider 的原因**：

```properties
spring.ai.model.chat=openai        # 生成用 DeepSeek
spring.ai.model.embedding=ollama   # 相似度用本地 bge-m3
```

一个负责「**说**」，一个负责「**找**」。

### 3.6 一个反直觉的地方：维度不可解释

**没有人能解释这 1024 个数字里每一维代表什么。**

- 经纬度：第 1 维是经度、第 2 维是纬度 → 人能理解
- embedding：1024 维，每维都是抽象的「语义特征组合」 → **不可解释**

这是深度学习的常态，它带来三个工程后果：

1. **阈值必须实测，不能推导**——没法定量解释「多少分算相关」，只能试出来；
2. **「命中不相关」时难以问责**——你没法指着某一维说它错了，只能整体调策略；
3. **不同模型之间没有通用的好坏标准**——效果差异来自各自训练出的语义空间。

---

## 四、四个环节

### 4.1 ETL：把周报做成卡片

**ETL 是数据工程的老概念**（Extract 抽取 / Transform 转换 / Load 加载），几十年前用于数据仓库；RAG 借用了这个词，来描述「把原始资料加工成可检索文档」的流水线。Spring AI 官方对应三个接口：`DocumentReader → DocumentTransformer → DocumentWriter`。

**本项目是「手写的 ETL」**——没走官方那三个接口，因为场景足够简单：

| 阶段 | 官方组件 | 本项目实现 |
|---|---|---|
| Extract | `DocumentReader`（PDF / 网页 / JSON…） | `getAllSubmittedReports()` 查 MySQL |
| Transform | `DocumentTransformer`（切分 / 清洗 / 加元数据） | `toDocument()` 拼正文、建元数据 |
| Load | `DocumentWriter` | `vectorStore.add()` |

**什么时候值得换成官方 ETL 框架**：数据源变多（PDF、网页、Confluence 等异构来源）、需要切分长文档（`TokenTextSplitter`）、需要自动补元数据（`KeywordMetadataEnricher`）。当前只有一个数据源、一篇周报对应一个文档，手写四行反而更清楚。

```java
@Component
public class WeeklyReportIndexer implements ApplicationRunner {

    @Override
    public void run(ApplicationArguments args) {
        List<WeeklyReport> list = weeklyReportService.getAllSubmittedReports();
        if (list.isEmpty()) {
            log.info("向量索引跳过：没有已提交周报");
            return;
        }
        vectorStore.add(list.stream().map(WeeklyReportIndexer::toDocument).toList());
    }
```

用 `ApplicationRunner` 而不是 `@PostConstruct`：前者保证"整个容器都就绪"，后者只保证"当前 Bean 依赖注入完成"。要查数据库的场景，位置越晚越安全。

**类型转换的核心：**

```java
public static Document toDocument(WeeklyReport r) {
    return new Document(
            String.valueOf(r.getId()),
            ReportTextFormatter.block(r, ReportTextFormatter.headerByDate(r)),
            Map.of(
                    "userId", r.getUserId(),
                    "reportId", r.getId(),
                    "weekStartDate", r.getWeekStartDate().toString()
            ));
}
```

三个参数各有一个不容做错的理由，见第六节。

### 4.2 存储：`VectorStore` Bean

```java
@Configuration
public class VectorStoreConfig {

    @Bean
    public VectorStore weeklyReportVectorStore(
            @Qualifier("ollamaEmbeddingModel") EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}
```

用得是官方定位为"**仅用于测试与演示**"的内存实现。选它的理由：**零外部依赖，先把链路跑通**。代价是进程退出即失——**这正是全量索引必须在每次启动时重建的原因**。

换实现（Redis / PGvector 等）时只需要替换这一个 Bean，工具和调用方一行都不用改（`ReportQueryTools` 只依赖 `VectorStore` 接口）。

**配置（`application.properties`）：**

```properties
# 向量化：本地 Ollama
spring.ai.ollama.base-url=http://localhost:11434
spring.ai.ollama.embedding.options.model=bge-m3

# 两个 starter 共存时必须显式指定「每种能力用哪个 provider」，
# 否则两者都因 matchIfMissing=true 生效 → 两个 ChatModel / 两个 EmbeddingModel → 注入歧义
spring.ai.model.chat=openai
spring.ai.model.embedding=ollama
```

### 4.3 检索：以工具形态接入

```java
@Tool(description = "按语义检索周报主题。当用户问的是『什么时候做过某事』『有没有提到过某个话题』"
        + "这类不记得具体周次的模糊问题时使用；拿到周次后必须再用 getReport 读正文。"
        + "如果已经知道确切周次，直接用 getReport，不要调用本工具。"
        + "查询范围由系统固定，范围外的周报检索不到。",
        resultConverter = PlainTextResultConverter.class)
public String searchReports(
        @ToolParam(description = "用自然语言描述要找的主题，例如『数据库优化』『性能问题排查』") String query) {

    FilterExpressionBuilder b = new FilterExpressionBuilder();
    Filter.Expression filter = b.eq("userId", userId).build();

    List<Document> hits = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(SEARCH_TOP_K)
                            .similarityThreshold(0.5)
                            .filterExpression(filter)
                            .build())
            .stream()
            .filter(this::inRange)
            .limit(MAX_HITS)
            .toList();
    ...
```

**注意这里的过滤策略是"混合"的**：

| 条件 | 在哪过滤 | 原因 |
|---|---|---|
| `userId` | **向量库层**（`filterExpression`） | 实测 `eq` 生效，且能让 `topK` 取到正确范围 |
| 日期范围 | **应用层**（`inRange`） | 实测 `gte`/`lte` 在 SimpleVectorStore 上**不生效**（见坑 2） |

```java
private boolean inRange(Document d) {
    Object ws = d.getMetadata().get("weekStartDate");
    if (ws == null) {
        return false;
    }
    String s = ws.toString();
    return s.compareTo(startMonday.toString()) >= 0 && s.compareTo(endDate.toString()) <= 0;
}
```

`weekStartDate` 存的是 `yyyy-MM-dd` 字符串，**字典序恰好等于时间序**，所以直接字符串比较即可，不用解析成日期。

### 4.4 增量：周报改了，卡片要跟着换

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
public void onReportChanged(WeeklyReportChangedEvent event) {
    try {
        String documentId = String.valueOf(event.report().getId());
        vectorStore.delete(List.of(documentId));
        if ("SUBMITTED".equals(event.report().getStatus())) {
            vectorStore.add(List.of(WeeklyReportIndexer.toDocument(event.report())));
        }
    } catch (Exception e) {
        log.error("周报 [{}] 的向量索引同步失败，语义检索可能命中旧内容", event.report().getId(), e);
    }
}
```

**为什么必须做这一步**：周报是**可编辑**的（`submitHistoryWeekly` / `updateWeeklyReport` 都能改历史周报）。不同步的话，用户改完内容再问"我写过什么"，检索到的还是**旧内容**——而且这种错误**不会报错**，答案看起来完全正常。

**三个设计要点**：

| 要点 | 原因 |
|---|---|
| `AFTER_COMMIT` 而非普通 `@EventListener` | 向量库操作不参与数据库事务。挂在事务内，一旦数据库回滚，索引却已经改了，会留下"数据库里不存在"的记录 |
| **先 delete 再 add** | 文档 id = `reportId`，能精确定位旧版本；直接 add 可能留下新旧两份 |
| `fallbackExecution = true` | 万一哪天调用方丢失了 `@Transactional`，普通 `@TransactionalEventListener` 会**静默丢弃**事件（不报错也不执行），索引就此悄悄停止同步 |

---

## 五、为什么是「检索定位 → 读全文」两步

`searchReports` **只返回周次，不返回正文**：

```java
sb.append("语义检索命中（按相关度排序，周一日期）：\n");
for (Document d : hits) {
    sb.append("- ").append(d.getMetadata().get("weekStartDate")).append("\n");
}
sb.append("请用 getReport 读取其中相关周的正文后再回答。");
```

这么做换来三个好处：

| 好处 | 说明 |
|---|---|
| **token 可控** | 命中 5 条也只返回 5 行日期，而不是 5 篇正文 |
| **护栏复用** | 正文一律走 `getReport`——那里已有越界检查、单篇长度上限，不用在两条路径上各维护一份 |
| **与既有模式一致** | 和 `listSubmittedWeeks → getReport` 的两步结构相同，模型学一次就会 |

代价是**多一次模型往返**。从实际日志看，这个代价是值得的——模型会自然地走完"定位 → 读取 → 作答"三步。

---

## 六、八个关键设计决策

### 决策 1：文档粒度——1 篇周报 = 1 个 `Document`

没有按字段切分（那样一周会变成 4 条）。理由：**保持整周的上下文完整**，且与 `getReport` 返回的正文口径一致。

**代价**：长文本会稀释语义聚焦度。实测中关键词完全命中的周报（写着"完成缓存"）得分也只有 **0.616**。如果将来命中率不理想，可以改按字段切（`weeklyWorkReport` 通常最关键），代价是同一周可能返回多条，需要在工具里做去重。

### 决策 2：文档 id = `reportId`

**这是为增量更新预留的设计。** 不指定 id 时框架会生成随机 id，于是：

- 改一次周报 → 索引里多一份副本 → 检索结果中出现同一周的多个历史版本

指定成稳定的 `reportId`，`delete(id)` 才能精确命中旧版本。

### 决策 3：三个元数据字段，各自有明确用途

| key | 值 | 用途 |
|---|---|---|
| `userId` | `Long` | **租户隔离**——`searchReports` 靠它过滤，是防越权的唯一屏障 |
| `weekStartDate` | `String`（`yyyy-MM-dd`） | **范围过滤** + 检索结果展示（模型看到的就是它） |
| `reportId` | `Long` | 溯源用（排查"这条文档对应哪一周报"） |

> ⚠️ **元数据的 key 是 ETL 时自定的，与数据库列名毫无关系。** 表里叫 `user_id`，元数据里必须是 `userId`。写过滤条件时要盯着 `toDocument()` 里的 `Map.of(...)`，**不是**表结构——写错不会报错，只会静默匹配不到任何文档（见坑 1）。

### 决策 4：正文复用 `ReportTextFormatter.block()`

```java
// 正文与工具路径完全一致（复用 ReportTextFormatter.block），
// 否则「语义检索」和「按周读取」两条路径给的文本会长歪，对比就不成立。
```

**单一数据表示**原则：检索路径和读取路径给模型的文本块必须格式相同，否则模型会在"检索说这条命中"与"读到的却长这样"之间产生认知偏差。

### 决策 5：`userId` 走库层过滤，日期走应用层筛

见 4.3。这是被实现逼出来的混合策略——**不要赌向量库的过滤能力**。

### 决策 6：相似度阈值 0.5

实测三组数据的分界线都落在 0.47~0.55：

| 查询 | 相关文档 | 噪音 |
|---|---|---|
| `q=缓存` | 0.616、0.575 | — |
| `q=缓存&userId=1` | 0.616、0.575 | 0.472（讲需求文档，无关） |
| `q=缓存&userId=4` | 0.548 | 0.394（占位文本，无关） |

0.5 卡在中间。**注意两件事**：

- 这个值是**实测定的**，不是拍的；
- **不同措辞的分数分布不同**——模型自己会把 query 扩写成 `缓存 cache 相关工作`，分数反而更高（0.667）。所以调阈值必须用**模型实际会传的措辞**去测，不能只用你自己想的词。

### 决策 7：RAG 以**工具**形态接入，而不是独立入口

这是本功能最重要的架构选择。

| 方案 | 特点 |
|---|---|
| 独立入口（如 `/ai/chat/rag`） | 适合 A/B 对照，但只是"另一种问答" |
| **包装成 `@Tool` 加进 `ReportQueryTools`** | **保留 Agent 架构**——模型自己决定"精确查"还是"语义查" |

选后者的三个收益：

1. **护栏天然延续**——`userId` 和日期范围已经在构造器字段里，检索时直接复用；
2. **模型可以混合使用**——先语义检索定位，再精确读取；
3. **符合生产实践**——工具调用 + 向量检索混合，而不是二选一。

### 决策 8：全量索引在启动时执行，且**不做容错**

`WeeklyReportIndexer.run()` 没有 try-catch。后果：

> embedding 服务不可用时（Ollama 没起 / 模型没拉），异常会直接冒到 `SpringApplication.run`，**整个应用启动失败**，而不只是语义检索不可用。

这与增量同步那边（有 try-catch）**口径不一致**——启动这次的风险更大。是否补容错需要权衡：

| 选择 | 好处 | 代价 |
|---|---|---|
| 不包 try-catch（现状） | 问题立刻暴露，不会悄悄降级 | 一个附属功能拖垮整个应用 |
| 包 try-catch | 主流程不受影响 | 索引失败**静默降级**，需要靠日志发现 |

---

## 七、分层验证：三层，从下往上

**混在一起测会无法定位问题**——"向量库没数据""阈值把结果筛掉了""模型没调工具"这三种情况，在页面上表现完全一样（都是"没有相关记录"）。

### 第 1 层：直接验证向量检索（绕过模型）

加一个临时调试接口，直接查向量库并返回分数。关键是把 `similarityThreshold` 传 **0**，这样才能看到"本来会被 0.5 卡掉"的结果长什么样。

| 现象 | 结论 |
|---|---|
| 返回空数组 | 索引没建成 → 看启动日志 |
| 有结果但分数全 < 0.5 | 检索能工作，**阈值设高了** |
| 有结果、高分排前面且确实相关 | ✅ 检索正常，问题在上面两层 |
| 命中的明显不相关 | embedding 模型或文档粒度问题 |

**顺便用两个 URL 的对比验证隔离**：

```
?q=缓存              → 看所有人里命中了什么
?q=缓存&userId=1     → 加隔离条件
```

两者结果**完全相同**就说明隔离失效——这是最需要立刻查的情况。

### 第 2 层：验证模型会不会调用工具

问一个**不提日期、只提主题**的问题（这正是 `searchReports` 存在的意义），看后端日志：

| 期望 | 说明 |
|---|---|
| 出现 `↳ 调用 searchReports({...})` | 选对了工具 |
| 参数是自然语言而非日期 | 认出了这是模糊查询 |
| 紧接着 `↳ 调用 getReport(...)` | 两步链路走通 |

### 第 3 层：端到端 + 两个必测项

| 验证项 | 方法 | 期望 |
|---|---|---|
| **用户隔离（双向）** | userId=1 和 userId=4 互查对方写过的主题 | 都查不到 |
| **范围约束** | 选一个早于目标周的区间再问 | 查不到 |
| 正例 | 选包含目标周的区间 | 能查到 |
| 增量一致 | 编辑周报加一个独特词 → 提交 → 用该词检索 | 能查到新内容 |

---

## 八、踩过的四个坑

### 坑 1：元数据 key 写错 → 整个过滤恒为 false

**现象**：`searchReports` 一直返回"没有语义相关的周报"，即使范围里明明有相关内容。

**原因**：过滤条件写成了数据库列名：

```java
Filter.Expression filter = b.and(
        b.eq("user_id", userId),                          // ← 下划线
        b.and(b.gte("week_start_date", startMonday),
                b.lte("week_start_date", endDate))
).build();
```

而元数据里的 key 是**驼峰**（`toDocument()` 里写的 `"userId"`、`"weekStartDate"`）。

key 不存在 → `eq` 永远匹配不到 → **所有文档被过滤掉** → `hits` 为空。

**教训**：**元数据的 key 与表结构无关**。写过滤条件前，先去 `toDocument()` 确认字段名。

（另外 `endDate` 是 `LocalDate`，元数据存的是 `String`——即使 key 对了，类型也不匹配，需要 `.toString()`。）

### 坑 2：SimpleVectorStore 不支持 `gte`/`lte`

**现象**：带日期条件和不带日期条件的查询，结果**完全一样**（连分数都一模一样到小数点后 13 位）。

实测数据：

| 查询 | 日期条件 | 返回 |
|---|---|---|
| ① | 无 | 09-14、09-07、**08-03** |
| ② | `08-31 ~ 09-21` | 09-14、09-07、**08-03** ← 08-03 不该出现 |
| ③ | `08-24 ~ 08-28` | 09-14、09-07、**08-03** ← 一个都不在范围内 |

**结论**：内存实现支持 `eq`，但**忽略 `gte`/`lte`**。

**修复**：日期范围改到应用层筛（见 4.3）。多召回（`topK=20`）再筛选（`limit=5`），给筛除留余量。

**教训**：**内存实现的能力和生产级向量库不一样**。护栏条件宁可放应用层，不要赌库的实现——尤其是"这个库号称支持，但实际不生效"这种情况，它**不会报错**。

### 坑 3：空结果文案诱导模型下错结论

`searchReports` 返回空时，文案里原本有"或告知用户没有相关记录"这个出口。模型于是**跳过复核**，直接断言"其中都没有提到缓存相关的内容"——**而它根本没调 `getReport` 读正文**。

**修复**：堵掉出口，改成强制复核（见 `spring-ai-tool-calling.md` 坑 2）。

**教训**：语义检索**永远可能漏召**。如果空结果的文案允许模型直接下结论，那么"检索漏召"就会变成"用户被告知没做过这件事"——一个看起来完全正常的错误答案。

### 坑 4：前端日期没传进来

**现象**：用户在界面上选了 `08-24 ~ 08-28`，但日志里显示的范围是 `08-31 ~ 09-21`。

**原因**：`08-31 ~ 09-21` 正是"不传日期"时的**默认窗口**（今天所在周的周一往前推 3 周）。所以是日期压根没传到后端。

两个可能：
- 填错了地方——页面上有**两处**日期框：团队视图/我的历史的**筛选栏**（与问答无关）vs **问答弹窗里**的两个框（只有这个会传给后端）；
- 或者填完点了"清空"——`clearChatThread(true)` 会把日期一起清掉。

**教训**：排查"范围不生效"时，**第一步先确认范围真的传到了**（看日志里的范围值，而不是看界面）。界面显示 ≠ 传给了后端。

---

## 九、边界与限制

| 限制 | 说明 |
|---|---|
| **`SimpleVectorStore` 非生产级** | 官方原文："不适用于生产，仅用于测试或演示" |
| **重启即失** | 内存实现 → 每次启动都要全量重建（`WeeklyReportIndexer` 存在的理由） |
| **启动强依赖 embedding 服务** | 见决策 8：Ollama 挂了整个应用起不来 |
| **启动耗时随数据量增长** | `add()` 内部批量调用 embedding，N 篇周报要调 N 次（分批） |
| **过滤能力有限** | `gte`/`lte` 不生效（坑 2）；生产实现（Redis / PGvector）支持情况需另测 |
| **换 embedding 模型要全量重建** | 向量维度变了，旧向量全部作废 |
| **无跨会话语义检索** | 检索按 `userId` + 日期范围过滤，不支持"跨用户找相似经验" |
| **检索质量不可控** | 阈值、topK、文档粒度都要调；答错时难判断是"没召回到"还是"模型没用结果" |

**代价清单**（面试/复盘时值得能说清）：

| 代价 | 说明 |
|---|---|
| 多一次 embedding 调用 | 每次提问要把 query 向量化；ETL 阶段每篇周报也要向量化 |
| 向量库运维 | 索引管理、维度对齐、换模型要重建 |
| 数据一致性 | 周报可编辑 → ETL 不是一次性的（已用增量同步解决） |
| 延迟 | 多一次网络往返 |

---

## 附：一句话速记

1. **这里的 RAG 不是用来替代截断的**——截断问题已被工具化解决，RAG 补的是"按语义找内容"。
2. **卡片（Document）= 文本 + 元数据**；元数据的 key 是 ETL 时自定的，**与数据库列名无关**。
3. **文档 id 用 `reportId`**——稳定 id 才能支持增量更新。
4. **`eq` 走库层，`gte`/`lte` 走应用层**——内存实现会静默忽略不支持的过滤操作。
5. **只返回周次、不返回正文**——token 可控，且能复用 `getReport` 的护栏。
6. **以工具形态接入**，保留 Agent 架构，护栏天然延续。
7. **增量同步挂 `AFTER_COMMIT`**，先删后加，失败不阻断主流程。
8. **语义检索永远可能漏召**——空结果的文案不能允许模型直接下结论。
