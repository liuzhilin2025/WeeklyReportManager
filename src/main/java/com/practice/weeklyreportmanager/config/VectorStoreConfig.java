package com.practice.weeklyreportmanager.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 周报语义检索用的向量存储。
 * <p>
 * 为什么用 SimpleVectorStore（内存实现）：零外部依赖，先把「embedding → 检索」这条链路跑通，
 * 不用同时折腾 Redis Stack / 建索引 / 维度对齐。代价是进程退出即失，所以索引必须在每次启动时
 * 重建——这正是 WeeklyReportIndexer 存在的理由，它不是可选的优化。
 * <p>
 * 以后换 Redis / PGvector 等持久化实现时，只需要替换这一个 Bean，工具与调用方代码一行都不用动
 * （ReportQueryTools 只依赖 VectorStore 接口）。
 * <p>
 * 注意注入的是 EmbeddingModel：openai starter 也会自动配一个 OpenAiEmbeddingModel（本项目 base-url
 * 指向 DeepSeek，本就没有 embedding 接口），两个候选会造成注入歧义。
 * 在 application.properties 里用 spring.ai.model.embedding=ollama 指定「只启用 Ollama 的 embedding」即可。
 * （旧的 spring.ai.openai.embedding.enabled / spring.ai.ollama.embedding.enabled 这类开关在 1.1.x 已移除，不再生效。）
 * <p>
 * 另：SimpleVectorStore 官方定位是「仅用于测试与演示」（Vector Databases 页原文），线上要换持久化实现。
 * 但换实现时本类以外一行都不用改，这正是当初让工具依赖 VectorStore 接口而非具体实现的价值。
 */
@Configuration
public class VectorStoreConfig {

    @Bean
    public VectorStore weeklyReportVectorStore(
            @Qualifier("ollamaEmbeddingModel") EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}
