package com.practice.weeklyreportmanager.utils;

import com.practice.weeklyreportmanager.entity.WeeklyReport;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 WeeklyReport 拼成「给模型看的文本」。纯函数、无状态、无 Spring 依赖，所以是静态工具类。

 * 为什么独立成类：这份格式有四个消费者——个人摘要 / 问答（全量塞）、团队汇总、ReportQueryTools（按需查）。
 * 同一个格式只能有一份，否则「塞上下文」和「给工具」两条路径喂给模型的文本会长歪，两者的对比也就不成立。

 * 这里只负责「读者是模型」的文本；润色的输入格式（AIService#formatFilledFields）只有一个消费者，
 * 没有「各写一份」的风险，留在原处。
 */
public final class ReportTextFormatter {

    /** 单个字段最大长度，与前端 textarea 的 maxlength、validateContent 的 1000 字校验保持一致 */
    public static final int MAX_FIELD_LENGTH = 1000;

    /**
     * 单次请求里「拼给模型的周报数据」总长度上限（按字符数）。
     * 为什么必须有：问答接口的时间范围由用户决定，个人摘要的正文长度由用户书写决定，
     * 两者都没有天然上限。用户把范围拉到一年，就是 52 篇 × 最多 4000 字 ≈ 20 万字，
     * 一次请求的延迟和费用会线性上涨，还可能直接超出模型的上下文窗口。
     * 4000 的来源：4 周 × 4 字段 × 1000 字（MAX_FIELD_LENGTH）最坏约 1.6 万字，
     * 取 4000 是「正常情况下装得下最近几周、异常情况下不会失控」的折中，
     * 超出部分由 context(...) 按「优先保留最近的周报」规则取舍。
     */
    public static final int MAX_PROMPT_REPORT_CHARS = 4000;

    private ReportTextFormatter() {
    }

    /** 标题口径一：按列表下标编号「第N周」，个人摘要用 */
    public static String headerByIndex(int weekNo, LocalDate weekStartDate) {
        return "【第" + weekNo + "周 " + DateUtils.formatWeekTitle(weekStartDate) + "】";
    }

    /** 标题口径二：带完整周一日期，问答 / 单篇用（周报 title 只有 MM.dd，跨年时分不清先后） */
    public static String headerByDate(WeeklyReport report) {
        return "【" + report.getWeekStartDate() + " " + report.getTitle() + "】";
    }

    /**
     * 单篇周报的正文块：标题 + 四个字段 + 结尾换行。

     * 标题由调用方拼好传进来（各处口径不同：摘要「第N周」、问答周一日期、团队汇总成员名），
     * 本方法只负责字段部分，这样四处拼出来的块格式完全一致，不会因为各写一份而长歪。

     * 字段在拼之前各截到 MAX_FIELD_LENGTH：库里存的值本来就受 validateContent 约束，
     * 但工具（ReportQueryTools）那条路径不经过 DTO 校验，把上限收进格式里才不用赌上游。
     *
     * @param header 已经拼好的标题行，如「【第1周 09.01~09.05】」「【张三】」
     */
    public static String block(WeeklyReport report, String header) {
        return header
                + "\n总体进度：" + limit(report.getOverallProgress())
                + "\n本周进展：" + limit(report.getWeeklyWorkReport())
                + "\n下周目标：" + limit(report.getNextWeekPlan())
                + "\n其他补充：" + textOrEmpty(limit(report.getOther()))
                + '\n';
    }

    /**
     * 把一组周报拼成给模型看的上下文文本，总长度不超过 MAX_PROMPT_REPORT_CHARS。

     * 超预算时优先丢「更早的周报」：最近的工作比几个月前的内容更值得让模型看到，
     * 而最新那篇无论如何都会保留（否则单篇正文就超预算时会拼出一段空上下文）。

     * 真丢了篇时会在开头写明，否则模型容易把「没给它看」误答成「周报里没有」。

     * @param reports       已按 week_start_date 升序排好的周报
     * @param withWeekIndex true = 标题带「第几周」编号（个人摘要用）；false = 带具体周一日期（问答用）
     * @return 拼好的文本，形如「【第1周 09.01~09.05】\n总体进度：…\n」重复若干段
     */
    public static String context(List<WeeklyReport> reports, boolean withWeekIndex) {
        if (reports.isEmpty()) {
            return "";
        }
        // 「第N周」取周报在原列表里的位置，这样即使前面的篇被丢掉，编号也仍对得上真实位置
        List<String> blocks = new ArrayList<>(reports.size());
        for (int i = 0; i < reports.size(); i++) {
            WeeklyReport report = reports.get(i);
            // 两种调用只差标题：摘要用「第几周」，问答用周一日期（title 只有 MM.dd，跨年时分不清先后）
            String header = withWeekIndex ? headerByIndex(i + 1, report.getWeekStartDate())
                    : headerByDate(report);
            blocks.add(block(report, header));
        }
        // 从最新一篇往前扩，装得下就继续、装不下就停在当前这篇
        int start = blocks.size() - 1;
        int used = blocks.get(start).length();
        while (start > 0 && used + blocks.get(start - 1).length() <= MAX_PROMPT_REPORT_CHARS) {
            start--;
            used += blocks.get(start).length();
        }
        // 按时间正序输出，与提示词里承诺的「从早到晚」保持一致
        StringBuilder sb = new StringBuilder();
        // start > 0 说明有被截断的周报
        if (start > 0) {
            sb.append("（注：更早的周报因篇幅限制未包含在内）\n");
        }
        for (int i = start; i < blocks.size(); i++) {
            sb.append(blocks.get(i));
        }
        return sb.toString();
    }

    /**
     * 按 MAX_FIELD_LENGTH（1000 字）截断，周报正文用这个上限
     */
    public static String limit(String value) {
        return limit(value, MAX_FIELD_LENGTH);
    }

    /**
     * 按指定上限截断；null 原样返回（模型漏字段时不能让截断本身抛 NPE）。按字符数切，不处理 emoji 之类的代理对
     */
    public static String limit(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /**
     * null/空串显示为"（空）"，有内容则原样返回
     */
    public static String textOrEmpty(String value) {
        return value == null || value.isBlank() ? "（空）" : value;
    }
}
