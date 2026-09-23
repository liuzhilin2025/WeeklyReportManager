package com.practice.weeklyreportmanager.service.tool;

import com.practice.weeklyreportmanager.entity.WeeklyReport;
import com.practice.weeklyreportmanager.service.WeeklyReportService;
import com.practice.weeklyreportmanager.utils.ReportTextFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;


/**
 * 启动时把「已提交」的周报全量灌进向量库，让语义检索有数据可查。
 * <p>
 * 与增量同步的分工：本类负责「把已有的都灌进去」（启动时一次），
 * WeeklyReportVectorSyncListener 负责「之后改动的跟上」（每次周报变更的事务提交后）。
 * 两者共用下面的 toDocument，这是两条路径生成文档格式一致的结构性保证。
 * <p>
 * 什么时候需要重建，取决于向量库的实现：
 * <ul>
 *   <li>内存实现（SimpleVectorStore）：进程退出即失，每次启动都必须重建；</li>
 *   <li>持久化实现（PGvector 等）：数据一直在，重建纯属浪费——每次都要白算一遍 embedding。
 *       用 app.vector-store.rebuild-on-startup 控制，持久化时设为 false。</li>
 * </ul>
 * <p>
 * 为什么用 ApplicationRunner 而不是 @PostConstruct：前者保证「整个容器都就绪了」才执行，
 * 后者只保证「当前这个 Bean 的依赖注入完成」。本类要查数据库，位置越晚越安全。
 */
@Component
public class WeeklyReportIndexer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReportIndexer.class);

    /**
     * 向量库，由 VectorStoreConfig 提供（声明成接口类型：换实现时本类不用改）
     */
    private final VectorStore vectorStore;

    /**
     * 只用它的 getAllSubmittedReports：草稿不参与检索，不进索引
     */
    private final WeeklyReportService weeklyReportService;

    /**
     * 是否在启动时全量重建索引。
     * 默认 true（内存向量库的必需行为）；换成持久化实现后应设为 false，
     * 只在首次部署、或改了文档结构（换 embedding 模型、改正文拼接格式）时手动打开一次。
     */
    private final boolean rebuildOnStartup;

    public WeeklyReportIndexer(VectorStore vectorStore,
                               WeeklyReportService weeklyReportService,
                               @Value("${app.vector-store.rebuild-on-startup:true}") boolean rebuildOnStartup) {
        this.vectorStore = vectorStore;
        this.weeklyReportService = weeklyReportService;
        this.rebuildOnStartup = rebuildOnStartup;
    }

    /**
     * 启动时执行一次：判断是否需要重建 → 全量查 → 批量转换 → 批量写入。
     * <p>
     * 注意 vectorStore.add 内部会对每篇周报真实调用 embedding 服务，由此带来两个后果：
     * <p>
     * 1. 启动耗时与周报数量正相关，数据一多会明显变慢（所以持久化时要靠 rebuildOnStartup 跳过）；
     * 2. embedding 服务不可用时（Ollama 没起 / bge-m3 没拉）会抛异常——这里包了 try-catch 兜住，
     * 让它降级成「语义检索不可用」，而不是拖垮整个应用的启动。
     * <p>
     * 空集合提前返回并留日志：让启动日志能自解释——看到「跳过」就知道不是失败了，是本来就没数据。
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!rebuildOnStartup) {
            // 持久化向量库的数据一直在，进这里说明配置里关了重建 —— 不是失败
            log.info("向量索引跳过：rebuild-on-startup=false");
            return;
        }

        try {
            List<WeeklyReport> list = weeklyReportService.getAllSubmittedReports();
            if (list.isEmpty()) {
                log.info("向量索引跳过：没有已提交周报");
                return;
            }

            // stream：把集合转成流式管道（源 list 本身不会被修改）
            // map：  逐个把 WeeklyReport 转成 Document —— WeeklyReportIndexer::toDocument 是方法引用，
            //        等价于 r -> WeeklyReportIndexer.toDocument(r)
            // toList：收集回 List，交给 add 一次性提交（批量走 embedding，比逐条 add 快得多）
            vectorStore.add(list.stream().map(WeeklyReportIndexer::toDocument).toList());
            log.info("向量索引完成，共 {} 篇", list.size());
        } catch (Exception e) {
            // embedding 服务不可用时不能拦住整个应用启动：
            // 语义检索降级为「没有相关记录」，周报主功能与其它 AI 入口照常可用
            log.error("向量索引失败，本次语义检索不可用（检查 Ollama 是否运行、bge-m3 是否已拉取）", e);
        }
    }


    /**
     * 把一条周报转成向量库里的 Document（id + 正文 + 元数据）。
     * <p>
     * Document 是向量库里最基础的数据单元——把 VectorStore 看成一张表，Document 就是其中一行。
     * <p>
     * 做成 static 是为了「口径唯一」：本类的全量灌入与 WeeklyReportVectorSyncListener 的增量同步都要调用它。
     * 若各自写一份，两条路径生成的文本格式迟早会分叉。
     *
     * @param r 已提交的周报；草稿不应传进来
     */
    public static Document toDocument(WeeklyReport r) {
        return new Document(
                // 文档 id 用 reportId（转字符串）：增量更新时靠它精确定位并删除旧文档。
                // 不指定 id 的话框架会生成随机 id，改一次周报就多留一份副本，检索结果里会出现同一周的多个历史版本。
                String.valueOf(r.getId()),
                // 正文复用「按周读取」那条路径的格式化方法：两条路径喂给模型的文本块格式必须一致，
                // 否则模型会在「检索说这条命中」与「读到的却长这样」之间产生认知偏差。
                ReportTextFormatter.block(r, ReportTextFormatter.headerByDate(r)),
                // 元数据的 key 是这里自定的，与数据库列名无关（表里叫 user_id，这里必须是 userId）。
                // 写过滤条件时要盯着这个 Map，而不是表结构——key 写错不会报错，只会静默地匹配不到任何文档。
                Map.of(
                        // 租户隔离：searchReports 靠它过滤，是防越权的唯一屏障
                        "userId", r.getUserId(),
                        // 溯源用：排查「这条文档对应哪一周报」
                        "reportId", r.getId(),
                        // 日期范围过滤的依据，也是检索结果里展示给模型的周一日期。
                        // 转成 yyyy-MM-dd 字符串：Document 元数据只支持简单类型，且这个格式的字典序恰好等于时间序
                        "weekStartDate", r.getWeekStartDate().toString()
                ));
    }
}
