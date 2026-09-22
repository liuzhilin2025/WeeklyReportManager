package com.practice.weeklyreportmanager.service.tool;

import com.practice.weeklyreportmanager.entity.WeeklyReport;
import com.practice.weeklyreportmanager.service.WeeklyReportService;
import com.practice.weeklyreportmanager.utils.DateUtils;
import com.practice.weeklyreportmanager.utils.ReportTextFormatter;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;


import java.time.LocalDate;
import java.util.List;


/**
 * 周报问答专用工具集。一次请求 new 一个，把「本次请求里可信的输入」全部钉成构造器字段、不进 schema：
 * <p>
 * - userId：模型没有任何途径指定别人，越权查询在结构上就不可能
 * - startMonday / endDate：用户在界面上选的时间范围（没选时由 AIService 算默认值）。
 * 范围同样不让模型看见——它不需要知道，也正因为不知道，查不到范围外的数据
 * <p>
 * 为什么范围必须钉进来：改成工具后出过一次「范围被静默丢弃」的回归——旧写法是按范围把周报查出来
 * 直接塞进 prompt，范围是隐含生效的；换成工具后范围一度只剩「判断区间内有没有数据」这一个用途，
 * 用户在弹窗里选 6 月、模型却完全不知道，只会照自己的猜测去查，答成「没有相关记录」。
 * <p>
 * 类本身不用是 Spring Bean：工具实例是我们手动 new 的，AOT 反射注册那套约束用不上。
 */
public class ReportQueryTools {

    /**
     * 单次工具返回的正文上限，防止「按需查」退化成「全量塞」
     */
    private static final int MAX_TOOL_BODY_CHARS = 3000;

    /** 先多召回再筛日期，避免「取 topK 时把范围内的都挤掉了」 */
    private static final int SEARCH_TOP_K = 20;

    /** 筛完日期后真正返回给模型的条数 */
    private static final int MAX_HITS = 5;

    /**
     * 目录最多列这么多周。为什么必须有：范围由用户在界面上决定，而前端日期选择器没有上下限
     * （index.html 的 flatpickr 没配 minDate/maxDate），「选个几年」是可能的——
     * 不设上限的话，目录本身就会变成一大块 token（10 年 ≈ 520 行 ≈ 13KB）。
     * <p>
     * 超出时保留最近的（列表按 week_start_date 升序，最近的就在尾部），并在开头写明丢了多少，
     * 与 ReportTextFormatter#context 是同一个原则：宁可说清「没给你看」，也不能让模型以为「不存在」。
     */
    private static final int MAX_CATALOG_WEEKS = 60;

    private final Long userId;
    private final LocalDate startMonday;
    private final LocalDate endDate;
    private final WeeklyReportService weeklyReportService;
    private final VectorStore vectorStore;

    /**
     * @param userId 当前用户，构造后不可改
     * @param start  查询范围起，归一到所在周的周一（与 week_start_date 的存法一致）
     * @param end    查询范围止
     *               调用方必须传非 null：AIService#chatWithReports 会先算好默认值（不传日期即最近 4 周）
     */
    public ReportQueryTools(Long userId, LocalDate start, LocalDate end, WeeklyReportService weeklyReportService, VectorStore vectorStore) {
        this.userId = userId;
        this.startMonday = DateUtils.getMondayOfWeek(start);
        this.endDate = end;
        this.weeklyReportService = weeklyReportService;
        this.vectorStore = vectorStore;
    }

    @Tool(description = "列出用户在当前查询范围内已提交周报的周次清单（周一日期 + 标题）。"
            + "不确定某段时间有没有周报、或需要先知道有哪些周可查时先调用它。无参数。"
            + "查询范围由系统固定，范围外的周报这里看不到。",
            resultConverter = PlainTextResultConverter.class)
    public String listSubmittedWeeks() {
        List<WeeklyReport> reports = weeklyReportService.getSubmittedReportsBetween(
                userId, startMonday, endDate);
        if (reports.isEmpty()) {
            return "在 " + startMonday + " ~ " + endDate + " 范围内没有已提交的周报。"
                    + "请直接告知用户这段时间没有周报，不要从其它途径补充内容。";
        }
        // 列表按 week_start_date 升序，所以「最近的」在尾部
        int from = Math.max(0, reports.size() - MAX_CATALOG_WEEKS);
        StringBuilder sb = new StringBuilder();
        if (from > 0) {
            sb.append("（注：范围内共 ").append(reports.size()).append(" 周有已提交周报，")
                    .append("此处只列出最近的 ").append(MAX_CATALOG_WEEKS)
                    .append(" 周；如需更早的，请让用户缩小时间范围）\n");
        }
        sb.append("已提交周报（周一日期 + 标题）：\n");
        for (int i = from; i < reports.size(); i++) {
            WeeklyReport r = reports.get(i);
            sb.append("- ").append(r.getWeekStartDate())
                    .append(" ").append(r.getTitle()).append("\n");
        }
        return sb.toString();
    }

    @Tool(description = "读取用户某一周的周报正文（总体进度/本周进展/下周目标/其他补充）。"
            + "weekStart 必须是那一周的周一。先用 listSubmittedWeeks 拿到日期，不要盲猜。",
            resultConverter = PlainTextResultConverter.class)
    public String getReport(
            @ToolParam(description = "周一的日期，格式 yyyy-MM-dd") String weekStart) {
        LocalDate monday = DateUtils.getMondayOfWeek(LocalDate.parse(weekStart));
        // 拒绝范围外的日期：范围是用户在界面上选的，模型推测出来的日期不该突破它。
        // 这里返回一句人能读懂的提示而不是抛异常，模型能据此改口或告诉用户「这段时间没数据」。
        if (monday.isBefore(startMonday) || monday.isAfter(endDate)) {
            return weekStart + " 不在本次查询范围（" + startMonday + " ~ " + endDate + "）内，"
                    + "请改用范围内的日期，或告知用户这段时间没有数据。";
        }
        List<WeeklyReport> reports = weeklyReportService.getSubmittedReportsBetween(
                userId, monday, monday.plusDays(6));
        if (reports.isEmpty()) {
            return monday + " 这一周没有已提交的周报";
        }
        String text = format(reports.get(0));
        // 工具输出同样是 token 预算：不设上限，工具版会比截断版更贵
        return text.length() <= MAX_TOOL_BODY_CHARS ? text : text.substring(0, MAX_TOOL_BODY_CHARS);
    }

    private String format(WeeklyReport r) {
        // 标题口径与「问答全量塞」一致（带完整周一日期），两条路径喂给模型的块格式才可比
        return ReportTextFormatter.block(r, ReportTextFormatter.headerByDate(r));
    }

    /**
     * 语义检索：解决「不记得是哪一周」的问题。
     * <p>
     * 为什么只返回周次、不返回正文：正文一律走 getReport 读，这样
     * 1) 命中结果本身 token 可控；2) 越界检查、单篇长度上限这些护栏自动生效，
     * 不用在两条路径上各维护一份。与 listSubmittedWeeks → getReport 的两步模式保持一致。
     */
    @Tool(description = "按语义检索周报主题。当用户问的是『什么时候做过某事』『有没有提到过某个话题』"
            + "这类不记得具体周次的模糊问题时使用；拿到周次后必须再用 getReport 读正文。"
            + "如果已经知道确切周次，直接用 getReport，不要调用本工具。"
            + "查询范围由系统固定，范围外的周报检索不到。",
            resultConverter = PlainTextResultConverter.class)
    public String searchReports(
            @ToolParam(description = "用自然语言描述要找的主题，例如『数据库优化』『性能问题排查』") String query) {

        // userId 交给向量库（实测eq生效）
        // 日期范围放应用层筛——SimpleVectorStore 对 gte/lte 不生效
        // （实测：带日期条件的查询与不带的结果完全一致），所以 topK 要放大，给筛除留余量。
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

        if (hits.isEmpty()) {
            return "在" + startMonday + " ~ " + endDate + " 范围内没有语义相关的周报。"
                    + "注意：这不等于「用户没做过相关工作」，语义检索可能漏召。"
                    + "请改用 listSubmittedWeeks 查看该范围内有哪些周报，"
                    + "再挑时间或标题上可能相关的几周用 getReport 读正文核实，"
                    + "确认后再回答；不要仅凭本工具的空结果就断言「没有相关记录」。";
        }

        StringBuilder sb = new StringBuilder("语义检索命中（按相关度排序，周一日期）：\n");
        for (Document d : hits) {
            sb.append("- ").append(d.getMetadata().get("weekStartDate")).append("\n");
        }
        sb.append("请用 getReport 读取其中相关周的正文后再回答。");
        return sb.toString();
    }

    /**
     * 日期范围判断。weekStartDate 存的是 yyyy-MM-dd 字符串，字典序即时间序，两端都含。
     * 之所以不交给向量库，见 searchReports 里的注释。
     */
    private boolean inRange(Document d) {
        Object ws = d.getMetadata().get("weekStartDate");
        if (ws == null) {
            return false;
        }
        String s = ws.toString();
        return s.compareTo(startMonday.toString()) >= 0 && s.compareTo(endDate.toString()) <= 0;
    }
}

