package com.practice.weeklyreportmanager.service.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * 周报变更后同步向量索引（增量更新）。
 * <p>
 * 与启动时的全量索引（{@link WeeklyReportIndexer}）分工：那个负责「把已有的都灌进去」，
 * 这个负责「之后改动的跟上」。两者用同一个 {@link WeeklyReportIndexer#toDocument} 生成文档，
 * 否则检索到的文本会和启动时灌进去的格式不一致。
 * <p>
 * 为什么用 {@code @TransactionalEventListener(AFTER_COMMIT)} 而不是普通的 {@code @EventListener}：
 * 向量库操作**不参与数据库事务**。如果挂在事务内执行，一旦数据库回滚，索引却已经改了——
 * 索引里会留下一条数据库根本不存在的记录。AFTER_COMMIT 保证只有事务真正提交才触发。
 * <p>
 * 整段包 try-catch：索引失败只应影响语义检索，不能让「保存周报」这种主流程报错。
 * 最坏结果是检索到旧内容，而不是用户存不了周报。
 */
@Component
public class WeeklyReportVectorSyncListener {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReportVectorSyncListener.class);

    private final VectorStore vectorStore;

    public WeeklyReportVectorSyncListener(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * fallbackExecution = true 的理由：正常情况下调用方都带 @Transactional，
     * 但哪天有人去掉了注解，事件会被 Spring **静默丢弃**（既不报错也不执行），
     * 索引就此悄悄不再同步。宁可退化成「无事务时也同步」，也不要这种无声的失效。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onReportChanged(WeeklyReportChangedEvent event) {
        try {
            String documentId = String.valueOf(event.report().getId());

            // 先删后加：文档 id 就是 reportId，能精确命中旧版本。
            // 少了删除这步，同一个 id 可能留下新旧两份（不同实现的覆盖/新增行为不一致）。
            vectorStore.delete(List.of(documentId));

            // 只有「已提交」才进索引，与启动时全量索引的口径一致（草稿不参与检索）
            if ("SUBMITTED".equals(event.report().getStatus())) {
                vectorStore.add(List.of(WeeklyReportIndexer.toDocument(event.report())));
            }
        } catch (Exception e) {
            log.error("周报 [{}] 的向量索引同步失败，语义检索可能命中旧内容",
                    event.report().getId(), e);
        }
    }
}
