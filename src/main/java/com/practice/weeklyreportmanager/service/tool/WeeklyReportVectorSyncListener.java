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
 * 与启动时的全量索引（{@link WeeklyReportIndexer}）分工：那个负责「把已有的都灌进去」（启动时一次），
 * 这个负责「之后改动的跟上」（每次周报变更后）。两者共用同一个 {@link WeeklyReportIndexer#toDocument}
 * 生成文档，否则两条路径灌进去的文本格式会分叉。
 * <p>
 * 处理原则：失败只降级、不阻断。索引同步失败顶多是检索到旧内容，绝不能让「保存周报」这种主流程报错，
 * 所以整段包了 try-catch。这与 WeeklyReportIndexer 那边「启动时不加容错」是相反的取舍，
 * 原因在那个类的注释里。
 * <p>
 * 触发方：{@link WeeklyReportChangedEvent}（由 WeeklyReportServiceImpl 的四个变更方法发布）。
 */
@Component
public class WeeklyReportVectorSyncListener {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReportVectorSyncListener.class);

    private final VectorStore vectorStore;

    public WeeklyReportVectorSyncListener(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 用 @TransactionalEventListener(AFTER_COMMIT)
     * 防脏数据：向量库操作不参与数据库事务。
     * 如果放在普通 @EventListener，一旦数据库事务回滚，索引里却留下了数据，导致数据不一致。
     * AFTER_COMMIT 确保只有数据库真正提交成功后才触发同步。
     * <p>
     * fallbackExecution = true 的理由：正常情况下调用方都带 @Transactional，但哪天有人去掉了注解，
     * 事件会被 Spring 静默丢弃（既不报错也不执行），索引就此悄悄停止同步。
     * 宁可退化成「无事务时也同步」，也不要这种无声的失效。
     *
     * @param event 变更后的周报；按它的 status 决定是写入索引，还是仅删掉旧文档
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onReportChanged(WeeklyReportChangedEvent event) {
        try {
            // 把id转换为字符串表示形式
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
