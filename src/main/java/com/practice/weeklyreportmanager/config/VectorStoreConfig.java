package com.practice.weeklyreportmanager.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 数据源与向量存储的装配（多数据源）。
 * <p>
 * 本项目要连两个数据库：业务数据在 MySQL（spring.datasource.*），向量数据在 PostgreSQL + pgvector
 * （app.vector-store.pg.*）。两者不能共用 DataSource，也不能共用 JdbcTemplate。
 * <p>
 * 为什么下面有两个 DataSource Bean —— 这点不看会踩坑：
 * 一旦自己定义了任何 DataSource Bean，Spring Boot 的 DataSourceAutoConfiguration 就会整体退让
 * （它的判断条件是 @ConditionalOnMissingBean(DataSource.class)），MySQL 那个也不会再自动创建。
 * 结果就是业务库连接凭空消失，MyBatis 反而拿 PG 的连接去查业务表。
 * 所以 MySQL 的数据源必须在这里显式建出来，并标 @Primary。
 * <p>
 * @Bean  把方法返回值注册成容器里的bean
 * <p>
 * @Qualifier("名字")  作用：按类型注入有多个候选时，用 bean名字精确指定。
 * <p>
 * @ConfigurationProperties(prefix = "...") 作用：把配置里某个前缀下的属性，批量绑定到对象上。
 * <p>
 * @Primary  作用：同类型有多个候选时，标记"没特别指定就选我"。
 * 关于 @Primary：容器里同时存在两个 DataSource 时，MyBatis-Plus、事务管理器、自动配置的
 * JdbcTemplate 都按类型注入。没有 @Primary 会直接抛 NoUniqueBeanDefinitionException。
 */
@Configuration
public class VectorStoreConfig {

    /**
     * bge-m3 的输出维度。它会被写进表的 embedding 列，改动等于重建表。
     */
    private static final int VECTOR_DIMENSIONS = 1024;

    // ── 业务库（MySQL）：主数据源 ──────────────────────────────

    /**
     * 承载 spring.datasource.* 的属性对象。
     * <p>
     * 为什么用 DataSourceProperties 而不是直接把它绑到 HikariDataSource：属性名不一样。
     * 它内部的字段叫 url，而 HikariDataSource 的 setter 叫 setJdbcUrl（属性名 jdbc-url）。
     * 直接绑 Hikari 时，配置里的 url 会静默绑不上、只有 driver-class-name 生效，
     * 最后 HikariCP 抛「jdbcUrl is required with driverClassName」——本类最初就栽在这一条上。
     * 另外它的 initializeDataSourceBuilder() 会顺带推断驱动、应用连接池参数。
     */
    @Bean
    @Primary
    @ConfigurationProperties(prefix = "spring.datasource")
    public DataSourceProperties mysqlDataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * 主数据源（MySQL）。MyBatis-Plus、事务、下面那个默认 JdbcTemplate 都走它。
     */
    @Bean
    @Primary
    public DataSource mysqlDataSource(
            @Qualifier("mysqlDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    /**
     * 业务库用的 JdbcTemplate。
     * <p>
     * 为什么要显式定义：Spring Boot 的 JdbcTemplateAutoConfiguration 条件是
     *
     * @ConditionalOnMissingBean(JdbcOperations.class)，而下面那个 vectorStoreJdbcTemplate
     * 也是 JdbcOperations —— 它一存在，默认的 jdbcTemplate 就不会被创建。
     * 于是任何 @Autowired JdbcTemplate 的地方都会拿到 PG 那个，静默连错库。
     * 这里显式建一个 @Primary 的，把名字和语义都钉死。
     */
    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(@Qualifier("mysqlDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    // ── 向量库（PostgreSQL + pgvector）──────────────────────────

    /**
     * PGvector 专用数据源的配置对象。
     * <p>
     * 独立于 spring.datasource.*：共用的话 pgvector 的建表语句会被发到 MySQL。
     * 前缀是自定义的 app.vector-store.pg，属性名与 spring.datasource 保持一致（都用 url）。
     */
    @Bean
    @ConfigurationProperties(prefix = "app.vector-store.pg")
    public DataSourceProperties vectorStoreDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    public DataSource vectorStoreDataSource(
            @Qualifier("vectorStoreDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    /**
     * 向量库专用 JdbcTemplate。必须显式 @Qualifier 指定 PG 数据源——
     * 容器里有两个 DataSource，不指定就会连到 MySQL 上。
     */
    @Bean
    public JdbcTemplate vectorStoreJdbcTemplate(
            @Qualifier("vectorStoreDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /**
     * 向量存储。
     * <p>
     *
     * @Qualifier("ollamaEmbeddingModel") 是必须的：openai starter 也会自动配一个 OpenAiEmbeddingModel
     * （本项目 base-url 指向 DeepSeek，本就没有 embedding 接口），两个候选会造成注入歧义。
     * application.properties 里用 spring.ai.model.embedding=ollama 指定「只启用 Ollama 的 embedding」。
     * （旧的 spring.ai.openai.embedding.enabled / spring.ai.ollama.embedding.enabled 这类开关在 1.1.x 已移除。）
     * <p>
     * 换实现时本类以外一行都不用改——ReportQueryTools 只依赖 VectorStore 接口。
     *
     * dimensions 嵌入维度。维度在创建表时设置到嵌入列。若更改维度，需重新创建 vector_store表
     * distanceType 搜索距离类型。默认是 COSINE_DISTANCE。
     * indexType 最近邻搜索索引类型。默认是 HNSW。
     * initializeSchema 是否初始化模式。默认是false。
     */
    @Bean
    public VectorStore weeklyReportVectorStore(
            @Qualifier("vectorStoreJdbcTemplate") JdbcTemplate jdbcTemplate,
            @Qualifier("ollamaEmbeddingModel") EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                // 不写也会从 EmbeddingModel 推导；显式写出来是为了让「换模型必须重建表」有据可依
                .dimensions(VECTOR_DIMENSIONS)
                // 距离算法  余弦距离(Cosine Distance) 文本语义检索最常用的算法。
                // 衡量的是两个向量在方向上的差异，而不是绝对距离(不受文本长短影响)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                // HNSW：查询更快，且空表就能建索引（IVFFlat 需要先有数据才能训练）
                .indexType(PgVectorStore.PgIndexType.HNSW)
                // 关键：1.0 起默认是 false（破坏性变更），不显式打开就不会建表
                .initializeSchema(true)
                .vectorTableName("weekly_report_vectors")
                .build();
    }
}
