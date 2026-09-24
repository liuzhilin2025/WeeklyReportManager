# PGvector VectorStore：官方要点与本项目落地

> 适用范围：本项目 `WeeklyReportManager`，Spring AI **1.1.8**。
> 涉及代码：`config/VectorStoreConfig.java`、`application.properties`、`service/tool/WeeklyReportIndexer.java`。
> 官方依据：Spring AI Reference — *PGvector*（中文译文来自 springdoc.cn，原页面版本标识 `1.1.0-SNAPSHOT`；本项目实际使用 1.1.8，API 无实质差异）。
> 配套阅读：`spring-ai-rag.md`（本项目的 RAG 设计、类比与决策记录）、`spring-ai-framework-overview.md` 第八节（RAG 总览）。

---

## 一、PGvector 是什么

**PostgreSQL 的一个扩展**，让 PG 能存储和搜索机器学习生成的**嵌入向量（embedding）**，支持**精确**与**近似**最近邻搜索，并且能和 PG 原生的索引、事务、SQL 查询特性协同一处工作。

**一句话定位**：

> 它把"向量检索"这件本来需要专门中间件（Milvus / Qdrant / Pinecone）的事，**变成了一张普通的 PG 表**。

**这带来三个实际好处**：

| 好处 | 说明 |
|---|---|
| 少维护一个中间件 | 你已经有 PG 了，不用再起一套向量库 |
| 事务与备份免费获得 | 向量数据和业务数据在同一套运维体系里 |
| 元数据过滤用 SQL 语义 | 过滤表达式最终转成 **PostgreSQL JSON 路径表达式** |

**代价**：向量检索的极致性能不如专用向量库；但对"几千到几百万条"的规模完全够用。

---

## 二、前置条件与本地环境

### 2.1 三个必需的扩展

PGvector 需要一个**已启用这三个扩展**的 PostgreSQL 实例：

| 扩展 | 用途 |
|---|---|
| `vector` | 向量类型与相似度运算符（**核心**） |
| `hstore` | 键值对类型，元数据过滤相关 |
| `uuid-ossp` | 生成 UUID 主键 |

**关键澄清（官方表述容易误解）**：

官方"前置条件"一节写的是"启动时 `PgVectorStore` 会尝试自动安装所需扩展，并在不存在时创建带索引的表"——**但这只在 `initializeSchema(true)` 时成立**。

因为同页"自动配置"一节明确写着：

> `spring.ai.vectorstore.pgvector.initialize-schema` —— 是否初始化所需 Schema，**默认 `false`**
> ⚠️ **此为破坏性变更！** 旧版 Spring AI 中该模式初始化默认为自动执行。

**所以准确的说法是**：

| 场景 | 表与扩展会被创建吗 |
|---|---|
| `initializeSchema(false)`（默认） | ❌ 不会。表和扩展必须**你手动准备好** |
| `initializeSchema(true)` | ✅ 会。启动时执行 `CREATE EXTENSION IF NOT EXISTS ...` 和 `CREATE TABLE IF NOT EXISTS ...` |

**这条是本项目最容易踩的坑之一** —— 不显式打开，启动不报错、但检索永远返回空。

### 2.2 官方给的手动初始化 SQL

如果选择自己建（或用 DBA 帮你建），官方给的脚本是：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE IF NOT EXISTS vector_store (
    id uuid DEFAULT uuid_generate_v4() PRIMARY KEY,
    content text,
    metadata json,
    embedding vector(1536)  -- 1536 是默认嵌入维度
);

CREATE INDEX ON vector_store USING HNSW (embedding vector_cosine_ops);
```

**逐行要点**：

| 位置 | 要点 |
|---|---|
| `vector(1536)` | **换成你实际的嵌入维度**。本项目用 `bge-m3`，是 **1024** |
| `metadata json` | 元数据存成 JSON —— 这就是 `Document.metadata` 的落库形态 |
| `USING HNSW` | 索引类型写死在建表时；换索引类型要重建索引 |
| `vector_cosine_ops` | 对应 `COSINE_DISTANCE`；换距离类型要换这个运算符类 |

> ⚠️ **维度上限**：**PGvector 的 HNSW 索引最多支持 2000 个维度**。本项目 1024 远低于上限，安全。

### 2.3 用 Docker 起一个 PGvector

官方给的一行命令：

```bash
docker run -it --rm --name postgres -p 5432:5432 \
  -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres \
  pgvector/pgvector
```

连接验证：

```bash
psql -U postgres -h localhost -p 5432
```

⚠️ **镜像必须带 pgvector**：

| 镜像 | 能用吗 |
|---|---|
| `pgvector/pgvector`（官方推荐） | ✅ |
| `pgvector/pgvector:pg17`（指定 PG 大版本） | ✅ |
| `postgres:17` | ❌ **官方镜像不含 `vector` 扩展** |

**踩坑特征**：用错镜像时，容器能起、密码也对，但 `initializeSchema(true)` 会报 `extension "vector" is not available` 或 `CREATE EXTENSION` 权限错误 —— **报错形态和"连不上"完全不同**，能连上 DB 但 SQL 挂。

### 2.4 与本项目对照

| 项 | 本项目做法 |
|---|---|
| 扩展安装 | 靠 `initializeSchema(true)` 自动装（`VectorStoreConfig` 第 68 行） |
| 表名 | 自定义为 `weekly_report_vectors`，不用默认的 `vector_store` |
| Schema | `public`（默认） |
| 维度 | `1024`（`bge-m3`），显式写在 `.dimensions(VECTOR_DIMENSIONS)` |
| 索引类型 | `HNSW` |
| 距离类型 | `COSINE_DISTANCE` |
| 容器 | `docker-compose.yml` 里跑 pgvector 容器，端口 `5432` |

---

## 三、依赖坐标

### 3.1 两条路线，依赖不同

**路线 A：自动配置（用 starter）**

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-vector-store-pgvector</artifactId>
</dependency>
```

再加一个 Embedding 模型 starter（官方示例用 OpenAI）：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

**路线 B：手动配置（自己 `new PgVectorStore`）**

```xml
<!-- 提供 JdbcTemplate -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>

<!-- PG 驱动 -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>

<!-- PgVectorStore 本体 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-pgvector-store</artifactId>
</dependency>
```

> 版本不用写，`spring-ai-bom` 统一管理。
> ⚠️ 官方特别提示：Spring AI 的 **starter 与自动配置模块构件名发生过重大变更**，查旧资料时注意（1.0.0 GA 起统一成 `spring-ai-starter-{类型}-{名称}`）。

### 3.2 与本项目对照

本项目**走路线 B**（手动配置），原因是**多数据源**：

- 业务库是 MySQL，向量库是 PG，需要两个 `DataSource`
- 自动配置会自己去接管/创建数据源，和我们的双数据源方案冲突
- 手动配置才能用 `@Qualifier` 把"哪张表用哪个连接"钉死

详细原因与代码见 `spring-ai-rag.md` 第四节的存储部分，以及 `VectorStoreConfig` 的类注释。

---

## 四、自动配置路线（了解即可，本项目未用）

### 4.1 配置示例

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/postgres
    username: postgres
    password: postgres
  ai:
    vectorstore:
      pgvector:
        index-type: HNSW
        distance-type: COSINE_DISTANCE
        dimensions: 1536
        max-document-batch-size: 10000   # 可选：每批次最大文档数
```

**注意**：如果通过 Docker Compose 或 Testcontainers 把 PGvector 作为 Spring Boot 的**开发时服务（Development-time Service）**运行，则**无需配置 url/username/password**，Spring Boot 会自动接上。

### 4.2 配置属性全表

| 属性名 | 说明 | 默认值 |
|---|---|---|
| `spring.ai.vectorstore.pgvector.index-type` | 最近邻索引类型，见第六节 | `HNSW` |
| `spring.ai.vectorstore.pgvector.distance-type` | 距离类型 | `COSINE_DISTANCE` |
| `spring.ai.vectorstore.pgvector.dimensions` | 嵌入维度。不指定时从 `EmbeddingModel` 推导。**维度在建表时写进列定义，改了必须重建表** | 取自模型 |
| `spring.ai.vectorstore.pgvector.remove-existing-vector-store-table` | 启动时删除已存在的表 | `false` |
| `spring.ai.vectorstore.pgvector.initialize-schema` | 是否初始化 Schema（建扩展 + 建表 + 建索引） | `false` |
| `spring.ai.vectorstore.pgvector.schema-name` | Schema 名 | `public` |
| `spring.ai.vectorstore.pgvector.table-name` | 表名 | `vector_store` |
| `spring.ai.vectorstore.pgvector.schema-validation` | 启用 Schema 与表名验证 | `false` |
| `spring.ai.vectorstore.pgvector.max-document-batch-size` | 单批次处理的最大文档数 | `10000` |

> 🔒 **官方安全建议**：若配置了自定义 Schema / 表名，**建议打开 `schema-validation=true`** —— 确保名称是合法且已存在的对象，**降低 SQL 注入风险**。
>
> 这一条容易被忽略但值得重视：`table-name` 是拼进 SQL 的字符串（不能用参数占位符），所以它天然是注入面。本项目表名是硬编码常量，风险可控；但如果哪天做成可配置项，务必同时打开这个开关。

### 4.3 使用示例

```java
@Autowired VectorStore vectorStore;

List<Document> documents = List.of(
    new Document("Spring AI rocks!! ...", Map.of("meta1", "meta1")),
    new Document("The World is Big and Salvation Lurks Around the Corner"),
    new Document("You walk forward facing the past and you turn back toward the future.",
                 Map.of("meta2", "meta2")));

vectorStore.add(documents);

List<Document> results = vectorStore.similaritySearch(
    SearchRequest.builder().query("Spring").topK(5).build());
```

**注意**：`similaritySearch` 的返回值是 `List<Document>`，**它不做任何去重或排序保证** —— 排序由相似度决定，但"哪几条入选"由 `topK` 和 `similarityThreshold` 决定。

---

## 五、手动配置路线（本项目走这条）

### 5.1 Builder 方法全表

```java
@Bean
public VectorStore vectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
    return PgVectorStore.builder(jdbcTemplate, embeddingModel)
        .dimensions(1536)                    // 可选：默认取模型维度或 1536
        .distanceType(COSINE_DISTANCE)       // 可选：默认 COSINE_DISTANCE
        .indexType(HNSW)                     // 可选：默认 HNSW
        .initializeSchema(true)              // 可选：默认 false  ← 最容易漏
        .schemaName("public")                // 可选：默认 "public"
        .vectorTableName("vector_store")     // 可选：默认 "vector_store"
        .maxDocumentBatchSize(10000)         // 可选：默认 10000
        .build();
}
```

| 方法 | 说明 | 默认值 |
|---|---|---|
| `PgVectorStore.builder(jdbcTemplate, embeddingModel)` | 静态工厂入口，**必须给** `JdbcTemplate` + `EmbeddingModel` | — |
| `.dimensions(int)` | 嵌入维度 | 取自模型维度或 `1536` |
| `.distanceType(...)` | 距离类型 | `COSINE_DISTANCE` |
| `.indexType(...)` | 索引类型 | `HNSW` |
| `.initializeSchema(boolean)` | 是否初始化 Schema | **`false`** |
| `.schemaName(String)` | Schema 名 | `"public"` |
| `.vectorTableName(String)` | 表名 | `"vector_store"` |
| `.maxDocumentBatchSize(int)` | 单批次最大文档数 | `10000` |

> **两个必须记住的点**：
> 1. **`initializeSchema` 默认 `false`**，不显式打开就不会建表 —— 这是 1.0 的破坏性变更，也是本项目第一次启动检索永远为空的原因。
> 2. **`JdbcTemplate` 是必需参数** —— `PgVectorStore` 不自己创建连接，必须外部传一个绑好目标库的 `JdbcTemplate`。这正是多数据源场景下必须手动配置的根本原因。

### 5.2 与本项目对照

本项目的实际写法（`VectorStoreConfig`）：

```java
@Bean
public VectorStore weeklyReportVectorStore(
        @Qualifier("vectorStoreJdbcTemplate") JdbcTemplate jdbcTemplate,
        @Qualifier("ollamaEmbeddingModel") EmbeddingModel embeddingModel) {
    return PgVectorStore.builder(jdbcTemplate, embeddingModel)
            .dimensions(VECTOR_DIMENSIONS)                                    // 1024
            .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
            .indexType(PgVectorStore.PgIndexType.HNSW)
            .initializeSchema(true)
            .vectorTableName("weekly_report_vectors")
            .build();
}
```

**三个参数的选择理由**：

| 参数 | 取值 | 理由 |
|---|---|---|
| `.dimensions(1024)` | `bge-m3` 的输出维度 | 显式写出来，是为了让"**换模型必须重建表**"这件事有据可依，而不是靠框架默默推导 |
| `.indexType(HNSW)` | HNSW | **空表也能建索引**（IVFFlat 需要先有数据才能训练），新项目起步阶段这点很关键 |
| `.initializeSchema(true)` | 显式打开 | 首次部署自动建表；不需要再用时可移交给 DBA 手动管理 |

**为什么两个参数都加了 `@Qualifier`**：

- 容器里有**两个 `JdbcTemplate`**（MySQL 一个、PG 一个）→ 不指定就注入歧义
- 容器里有**两个 `EmbeddingModel`**（openai starter 自动配的 + ollama starter 配的）→ 同上

详见 `spring-ai-framework-overview.md` 第五节关于 `@Qualifier` 与 `@Primary` 的说明。

---

## 六、索引类型与距离类型怎么选

### 6.1 三种索引类型

| 类型 | 原理 | 构建速度 | 内存 | 查询性能 | 需要训练 |
|---|---|---|---|---|---|
| `NONE` | 不建索引，**精确**最近邻 | — | — | 最慢（全表扫描） | — |
| `IVFFlat` | 把向量划分到多个列表，查询时只搜最接近的几个列表 | 快 | 少 | 中等 | **是**（要有数据） |
| `HNSW` | 多层图结构 | 慢 | 多 | **最优** | 否 |

**官方对 IVFFlat 与 HNSW 的对比原文要点**：

> - **IVFFlat**：将向量划分到列表中，再搜索与查询向量最接近的列表子集；相比 HNSW **构建更快、内存更少，但查询性能较低**（速度-召回率权衡）。
> - **HNSW**：创建多层图结构；相比 IVFFlat **构建更慢、内存更多，但查询性能更优**；与 IVFFlat 不同，**无需训练步骤，可在表中无数据时创建索引**。

**怎么选**：

| 场景 | 建议 |
|---|---|
| 数据量小（几千条以内） | `NONE` 都够，精确还更准 |
| 需要极致召回率、能接受慢 | `NONE` |
| 数据量大、内存紧张 | `IVFFlat` |
| 数据量大、要低延迟查询 | `HNSW`（默认，也是本项目的选择） |

### 6.2 距离类型

| 类型 | 适用 |
|---|---|
| `COSINE_DISTANCE`（默认） | 通用，看"方向是否一致"，与向量长度无关 |
| `EUCLIDEAN_DISTANCE` | 向量已归一化为长度 1 时，与余弦等价但**计算更快** |
| `NEGATIVE_INNER_PRODUCT` | 同上，也是归一化向量的高效选择 |

**官方原文**：

> 默认为 `COSINE_DISTANCE`。但**若向量已归一化为长度 1**，使用 `EUCLIDEAN_DISTANCE` 或 `NEGATIVE_INNER_PRODUCT` 可获得最佳性能。

**本项目选 `COSINE_DISTANCE` 的理由**：不假设 `bge-m3` 的输出已归一化，余弦距离是**语义检索的默认安全选择**。如果将来确认模型输出已归一化，可以换成 `NEGATIVE_INNER_PRODUCT` 换取一点性能。

### 6.3 与建表 SQL 的对应关系

⚠️ **索引类型和距离类型是"建表时决定"的**，不是查询时能改的：

| 配置项 | 落到的 DDL |
|---|---|
| `indexType` | `CREATE INDEX ON ... USING HNSW (...)` |
| `distanceType` | `... (embedding vector_cosine_ops)` ← 运算符类 |

**所以改这两项等于改表结构**，已有数据需要重建索引或重建表。

---

## 七、元数据过滤

PGvector 支持**通用的、可移植的**元数据过滤器，表达式会被转换成 **PostgreSQL JSON 路径表达式**来高效过滤。

### 7.1 方式一：文本表达式语言

```java
vectorStore.similaritySearch(
    SearchRequest.builder()
        .query("The World")
        .topK(TOP_K)
        .similarityThreshold(SIMILARITY_THRESHOLD)
        .filterExpression("author in ['john', 'jill'] && article_type == 'blog'")
        .build());
```

**语法要点**：`&&` 是 AND，`in [...]` 是集合判断，字符串用单引号。

### 7.2 方式二：`Filter.Expression` DSL

```java
FilterExpressionBuilder b = new FilterExpressionBuilder();

vectorStore.similaritySearch(
    SearchRequest.builder()
        .query("The World")
        .topK(TOP_K)
        .similarityThreshold(SIMILARITY_THRESHOLD)
        .filterExpression(b.and(
            b.in("author", "john", "jill"),
            b.eq("article_type", "blog")
        ).build())
        .build());
```

**⚠️ 这里有个官方文档不会提醒你的 DSL 陷阱**（本项目实测踩过）：

```java
// ❌ 编译不过：'Op' 中没有方法 'gte'
new FilterExpressionBuilder()
    .eq("userId", 1)
    .gte("weekStartDate", "2026-08-24")
```

**原因**：`FilterExpressionBuilder` 的**全部比较方法（`eq`/`ne`/`gt`/`gte`/`lt`/`lte`/`in`/`nin`/`isNull`/`isNotNull`/`and`/`or`/`not`/`group`）都返回嵌套的 `Op` 对象**，而 `Op` **只有 `build()`，没有任何比较方法**。

所以第一个 `.eq(...)` 之后接收者就变成了 `Op`，链子断了。**而且 `and`/`or` 是二元方法**（`and(Op left, Op right)`），多个条件必须**嵌套组合**：

```java
// ✅ 正确写法
FilterExpressionBuilder b = new FilterExpressionBuilder();
Filter.Expression filter = b.and(
        b.eq("userId", userId),
        b.and(b.gte("weekStartDate", startMonday.toString()),
              b.lte("weekStartDate", endDate.toString()))
).build();
```

> 单条件时 `b.eq("k", v).build()` 是可以的（`Op` 上有 `build()`）—— 这也是为什么在别的文档里会看到那种链式写法，容易误导。

### 7.3 与本项目对照

本项目用的是 **DSL 方式**，原因是要**动态拼接**条件：

| 条件 | 来源 | 用途 |
|---|---|---|
| `userId` | 构造器字段（模型看不到） | **租户隔离** |
| `weekStartDate >= 起始周一` | 构造器字段 | 日期下界 |
| `weekStartDate <= 结束日期` | 构造器字段 | 日期上界 |

**三个条件必须同时满足**，所以必然是"嵌套的 `and`"。而且 `userId` 写错不会报错、只会**静默匹配不到任何文档** —— 这是本项目踩过的坑之一（见 `spring-ai-rag.md` 坑 1）。

---

## 八、访问原生客户端

`PgVectorStore` 通过 `getNativeClient()` 暴露底层的 `JdbcTemplate`，用来访问 `VectorStore` 接口没覆盖的 **PG 特有功能**（比如直接查 `pg_indexes` 确认索引状态、执行 `ANALYZE` 等）：

```java
PgVectorStore vectorStore = context.getBean(PgVectorStore.class);
Optional<JdbcTemplate> nativeClient = vectorStore.getNativeClient();

if (nativeClient.isPresent()) {
    JdbcTemplate jdbc = nativeClient.get();
    // 执行 PostgreSQL 特有操作
}
```

**用途场景**：

| 场景 | 说明 |
|---|---|
| 排查索引是否存在 | 查 `pg_indexes` 确认 HNSW 索引真的建上了 |
| 排查维度是否对 | 查 `information_schema.columns` 看 `embedding` 列的实际维度 |
| 手动重建索引 | 改完 `indexType` 后，不想重建整张表时 |
| 性能诊断 | `EXPLAIN ANALYZE` 看查询是否走了索引 |

**这是排查"检索结果不对"类问题最有力的工具** —— 比在 Java 侧猜要直接得多。

---

## 九、官方没写、但会踩的坑（本项目实录）

### 坑 1：`url` 绑不上 —— `HikariDataSource` 没有 `setUrl`

**现象**：启动报 `HikariPool-1 - jdbcUrl is required with driverClassName.`

**原因**：`DataSourceBuilder.create().build()` 返回的是 `HikariDataSource`，而 `@ConfigurationProperties` 是**直接往这个实例上绑属性**的：

| 配置里写的 | HikariDataSource 的 setter | 结果 |
|---|---|---|
| `...pg.url` | 它叫 `setJdbcUrl`（属性名 `jdbc-url`） | ❌ **静默绑不上** |
| `...pg.driver-class-name` | `setDriverClassName` | ✅ 绑上了 |

于是 Hikari 拿到"有 driverClassName、没有 jdbcUrl"的状态 → 拒绝启动。

**修法**：绑到 `DataSourceProperties`（它有 `setUrl`），再由 `initializeDataSourceBuilder()` 转过去。这样配置里两个数据源都统一用 `url`。

### 坑 2：自定义 `DataSource` 会"挤掉" Spring Boot 的自动数据源

**现象**：多数据源配好之后，MyBatis 拿到的连接指向了错误的库。

**原因**：`DataSourceAutoConfiguration` 的触发条件是

```java
@ConditionalOnMissingBean({ DataSource.class, XADataSource.class })
```

**只要容器里已经存在任何 `DataSource` Bean，Spring Boot 就整体不创建 `spring.datasource.*` 对应的那个了**。

**修法**：MySQL 侧的数据源也必须显式定义出来，并标 `@Primary`（否则两个 `DataSource` 按类型注入会抛 `NoUniqueBeanDefinitionException`）。

**同类问题**：`JdbcTemplate` 也一样。`JdbcTemplateAutoConfiguration` 的条件是 `@ConditionalOnMissingBean(JdbcOperations.class)` —— 一旦你定义了 `vectorStoreJdbcTemplate`，默认的 `jdbcTemplate` 就不会被创建，任何 `@Autowired JdbcTemplate` 的地方都会**静默拿到 PG 那个**。所以要显式定义一个 `@Primary` 的 `jdbcTemplate`。

### 坑 3：`initializeSchema` 默认 `false`

不显式打开，**启动不报错、但表和扩展根本没建**。表现是检索永远返回空 —— 很像"embedding 效果不好"，其实是压根没数据。

### 坑 4：改维度 = 重建表

维度在 `CREATE TABLE` 时就写进了列定义（`embedding vector(1024)`）。**换 embedding 模型 → 维度可能变 → 旧向量全部作废 → 必须重建表 + 重新灌数据。**

本项目 `VECTOR_DIMENSIONS` 写成常量并加注释，就是为了让这个约束在代码里可见。

### 坑 5：镜像选错

用 `postgres:17` 而不是 `pgvector/pgvector`，会在建扩展时报 `extension "vector" is not available`。**报错形态与"连不上"完全不同** —— 能连上 DB，只是 SQL 挂。

### 坑 6：`jdbc-url` 与 `url` 混用导致"两个数据源看起来都配了"

如果 MySQL 用 `spring.datasource.url`、PG 用 `app.vector-store.pg.jdbc-url`，两份配置长得不一样，读代码时容易误判。**统一用 `DataSourceProperties` + `url`** 是最好的解法（见坑 1）。

---

## 十、速查清单

**接入 PGvector 的完整步骤**：

1. 起一个**带 pgvector 的 PG**（`pgvector/pgvector` 镜像，或确认已装 `vector` / `hstore` / `uuid-ossp` 三个扩展）
2. 引依赖 → 自动配置用 `spring-ai-starter-vector-store-pgvector`；手动配置用 `spring-boot-starter-jdbc` + `postgresql` + `spring-ai-pgvector-store`
3. 配数据源与维度、索引类型、距离类型
4. **显式打开 `initializeSchema(true)`**（或用 SQL 手工建表）
5. 注入 `VectorStore` → `add(documents)` → `similaritySearch(...)`
6. 自定义 Schema / 表名时，打开 `schema-validation=true`
7. 需要 PG 特有能力时，`getNativeClient()` 拿到底层 `JdbcTemplate`

**排查顺序（按"最可能"排）**：

| 症状 | 先查 |
|---|---|
| 连不上（1 秒内失败） | 容器起了吗？密码对吗？库名对吗？ |
| 能连上但报 `extension` 错 | 镜像是不是 `postgres:17` 而非 `pgvector/pgvector` |
| 检索永远返回空 | `initializeSchema` 打开了吗？有数据吗？`similarityThreshold` 是不是太高？ |
| 过滤不生效 | 元数据 **key 写对了吗**？（首字母大小写敏感） |
| 维度相关的错误 | 表建的维度 vs 模型输出的维度是否一致 |

---

## 附：一页速记

| 概念 | 一句话 |
|---|---|
| PGvector 本质 | 让向量检索变成一张普通的 PG 表 |
| 三个必需扩展 | `vector`（核心）、`hstore`、`uuid-ossp` |
| 维度上限 | **HNSW 索引最多 2000 维** |
| `initializeSchema` | **默认 false**（1.0 破坏性变更）；不开就不建表 |
| 索引类型三选 | `NONE` 精确 / `IVFFlat` 省内存 / `HNSW` 快（默认，空表可建） |
| 距离类型 | 默认 `COSINE_DISTANCE`；向量归一化后可用 `NEGATIVE_INNER_PRODUCT` 更快 |
| `JdbcTemplate` | **必需参数**，`PgVectorStore` 不自己建连接 |
| 手动配置专属方法 | `.schemaName()` / `.vectorTableName()` / `.maxDocumentBatchSize()` |
| 过滤 | 文本表达式 或 `Filter.Expression` DSL（**DSL 不能链式堆比较，要嵌套 `and`**） |
| 原生客户端 | `getNativeClient()` → `Optional<JdbcTemplate>`，排查利器 |
| 本项目关键差异 | 手动配置（双数据源）、表名 `weekly_report_vectors`、维度 1024 |
