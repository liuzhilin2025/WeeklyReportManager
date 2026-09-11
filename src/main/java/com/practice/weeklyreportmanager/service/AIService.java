package com.practice.weeklyreportmanager.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.practice.weeklyreportmanager.dto.TeamSummaryDTO;
import com.practice.weeklyreportmanager.dto.WeeklyReportDTO;
import com.practice.weeklyreportmanager.dto.WeeklySummaryDTO;
import com.practice.weeklyreportmanager.entity.WeeklyReport;
import com.practice.weeklyreportmanager.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 辅助撰写服务：把用户输入 / 已有周报组装成对话请求发给大模型，再把返回结果解析成前端要用的结构。
 *
 * 职责：
 * 1. 把「提示词模板文件 + 动态内容」组装成一次大模型对话请求（借助 ChatClient）；
 * 2. 把模型返回的原始文本解析成 DTO，供前端回填 / 展示。
 *
 * 对外提供 5 类能力：
 *   - generateDraft      生成草稿（结构化 DTO）
 *   - checkCompleteness  完整性检查（自由文本）
 *   - polishReport       润色（结构化 DTO）
 *   - summarizeHistory   个人近 4 周摘要（结构化 DTO）
 *   - summarizeTeam      团队单周汇总（结构化 DTO）
 *
 * 提示词不再写死在 Java 里，而是放在 resources 下的模板文件中：
 *   - prompts/ai-report-system.txt：       生成草稿的系统指令（纯静态，启动时读入内存）
 *   - prompts/ai-check-system.txt：        完整性检查的系统指令（纯静态）
 *   - prompts/ai-polish-system.txt：       润色的系统指令（纯静态）
 *   - prompts/ai-summary-system.txt：      个人摘要的系统指令（纯静态）
 *   - prompts/ai-team-summary-system.txt： 团队摘要的系统指令（纯静态）
 *   - prompts/ai-report-user.st：          生成草稿的用户消息模板，含 {userInput} 占位符
 */
@Slf4j
@Service
public class AIService {

    /** 单个字段最大长度，与前端 textarea 的 maxlength 保持一致，超长会被截断 */
    private static final int MAX_FIELD_LENGTH = 1000;

    /** 摘要每个部分的最大长度：prompt 约定最多 5 条 × 50 字，留一点余量给编号和换行 */
    private static final int MAX_SUMMARY_LENGTH = 300;

    /** 周报数据查询走 WeeklyReportService，AIService 只负责拼提示词和调模型（构造器注入，便于单测替换） */
    private final WeeklyReportService weeklyReportService;
    private final MockUserService mockUserService;

    /** Spring AI 的大模型客户端：负责发请求、收回答（由构造器注入的 Builder 构建） */
    private final ChatClient chatClient;
    /** Jackson 的 JSON 工具，用来把模型返回的文本解析成 JsonNode 树 */
    private final ObjectMapper objectMapper;
    /** 生成草稿的系统提示词，启动时从 ai-report-system.txt 读入（静态文本，不含占位符） */
    private final String systemPrompt;
    /** 完整性检查的系统提示词，来自 ai-check-system.txt */
    private final String systemCheckPrompt;
    /** 润色的系统提示词，来自 ai-polish-system.txt */
    private final String systemPolishPrompt;
    /** 个人摘要的系统提示词，来自 ai-summary-system.txt */
    private final String systemSummaryPrompt;
    /** 团队摘要的系统提示词，来自 ai-team-summary-system.txt */
    private final String systemTeamSummaryPrompt;
    /** 用户消息模板：渲染后生成 UserMessage，真正动态的内容只有用户输入的 userInput */
    private final PromptTemplate userPromptTemplate;
    /**
     * 构造器注入：AIService 被 @Service 托管，Spring 启动时会自动调用该构造器并传入所有依赖。
     *
     * @param chatClientBuilder               Spring AI 自动配置好的 ChatClient 建造器（可定制模型/超时等）
     * @param objectMapper                    Spring Boot 自动配置的 Jackson ObjectMapper
     * @param weeklyReportService             周报业务服务，用于读取待总结的历史周报
     * @param mockUserService                 模拟用户服务，提供成员名单与用户名（团队汇总用）
     * @param systemPromptResource            生成草稿的系统提示词文件（classpath 根目录下的 prompts/ 目录）
     * @param systemCheckPromptResource       完整性检查的系统提示词文件
     * @param systemPolishPromptResource      润色的系统提示词文件
     * @param systemSummaryPromptResource     个人摘要的系统提示词文件
     * @param systemTeamSummaryPromptResource 团队摘要的系统提示词文件
     * @param userPromptResource              生成草稿的用户消息模板文件
     * @throws IOException                    资源文件缺失或读取失败时抛出，导致应用启动失败（fail-fast）
     */
    public AIService(ChatClient.Builder chatClientBuilder,
                     ObjectMapper objectMapper,
                     WeeklyReportService weeklyReportService,
                     MockUserService mockUserService,
                     @Value("classpath:prompts/ai-report-system.txt") Resource systemPromptResource,
                     @Value("classpath:prompts/ai-check-system.txt") Resource systemCheckPromptResource,
                     @Value("classpath:prompts/ai-polish-system.txt") Resource systemPolishPromptResource,
                     @Value("classpath:prompts/ai-summary-system.txt") Resource systemSummaryPromptResource,
                     @Value("classpath:prompts/ai-team-summary-system.txt") Resource systemTeamSummaryPromptResource,
                     @Value("classpath:prompts/ai-report-user.st") Resource userPromptResource) throws IOException {
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
        this.weeklyReportService = weeklyReportService;
        this.mockUserService = mockUserService;
        // 把文件内容整体读成 UTF-8 字符串，只读一次，之后复用到每次请求
        this.systemPrompt = systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
        // Resource是[提示词外置化]的载体，负责把resources/prompts/下的模板文件安全、可复用、启动即加载地送进AIService
        this.systemCheckPrompt = systemCheckPromptResource.getContentAsString(StandardCharsets.UTF_8);
        this.systemPolishPrompt = systemPolishPromptResource.getContentAsString(StandardCharsets.UTF_8);
        this.systemSummaryPrompt = systemSummaryPromptResource.getContentAsString(StandardCharsets.UTF_8);
        this.systemTeamSummaryPrompt = systemTeamSummaryPromptResource.getContentAsString(StandardCharsets.UTF_8);
        // 用文件里的模板文本构造 PromptTemplate，调用时再往里填 {userInput}
        this.userPromptTemplate = new PromptTemplate(userPromptResource.getContentAsString(StandardCharsets.UTF_8));
    }

    /**
     * 根据用户输入的本周工作内容，生成一份可直接填入表单的结构化周报草稿。
     *
     * @param userInput 用户描述的本周工作（关键词或流水记录）
     * @return 四个字段与 WeeklyReportDTO 一一对应的草稿
     * @throws IllegalArgumentException 模型未返回内容、返回格式异常，或前三个核心字段全空时抛出
     */
    public WeeklyReportDTO generateDraft(String userInput) {
        // 1. 渲染用户消息模板：把占位符 {userInput} 替换成用户真实输入，得到一个 UserMessage
        Message userMessage = userPromptTemplate.createMessage(Map.of("userInput", userInput));

        // 2. 发起一次对话：system 用静态指令，user 用刚渲染好的消息，同步等待模型返回文本
        String content = chatClient.prompt()
                .system(systemPrompt)   // 设置“系统角色”消息（全局指令/人设），取自 ai-report-system.txt 的静态文本
                .messages(userMessage)  // 已渲染好的 UserMessage
                // 真正发送请求并同步阻塞等待模型返回，等价于一次完整的大模型调用；
                // 返回 ChatResponse（含模型回答、token 用量、元信息等）
                .call()
                .content();            // 从 ChatResponse 中取出模型生成的纯文本答案，即下面 parseDraft(content) 要解析的字符串

        // 3. 把模型返回的原始字符串解析成结构化的 DTO
        WeeklyReportDTO dto = parseDraft(content);
        // 生成草稿必须产出核心内容（总体进度/本周进展/下周目标至少其一非空），
        // 否则说明用户输入信息量不够，给一个可理解的错误提示
        // isBlank：判断单个字符串是否为null或纯空白
        if (isBlank(dto.getOverallProgress()) && isBlank(dto.getWeeklyWorkReport()) && isBlank(dto.getNextWeekPlan())) {
            throw new IllegalArgumentException("AI 生成内容为空，请补充更多工作描述后重试");
        }
        return dto;
    }

    /**
     * 把模型返回的文本解析成 WeeklyReportDTO。
     * 兼容模型偶尔输出 ```json 围栏或前后夹杂说明文字的情况。
     * 避免围栏的方式：
     * 1. 提示词约束：在 system prompt 里写“只输出合法 JSON，不要 markdown 代码块”
     * 2. 代码容错截取：用 indexOf('{') 和 lastIndexOf('}') 截取主体
     */
    private WeeklyReportDTO parseDraft(String raw) {
        // 模型什么都没返回（网络异常/超时等原因导致内容为空）——直接报业务错误
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("AI 未返回内容，请稍后重试");
        }
        // 截取第一个 { 到最后一个 } 之间的子串：
        // 模型偶尔会夹带 "```json" 围栏或前后解释性废话，只取 JSON 主体可提高解析成功率
        String body = raw.trim();
        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                // 把 JSON 子串解析成 JsonNode 树，之后才能按字段名取值
                JsonNode root = objectMapper.readTree(body.substring(start, end + 1));
                if (root != null && root.isObject()) {
                    // 逐个字段取出来，extract() 兼容中文别名；limit() 做超长截断
                    WeeklyReportDTO dto = new WeeklyReportDTO();
                    dto.setOverallProgress(limit(extract(root, "overallProgress", "总体进度", "整体进度")));
                    dto.setWeeklyWorkReport(limit(extract(root, "weeklyWorkReport", "本周进展", "本周工作", "本周完成")));
                    dto.setNextWeekPlan(limit(extract(root, "nextWeekPlan", "下周目标", "下周计划", "下周安排")));
                    dto.setOther(limit(extract(root, "other", "其他补充", "其他", "补充")));
                    return dto;
                }
            } catch (JsonProcessingException e) {
                // JSON 语法错误：不往上抛，落到方法末尾统一的"格式异常"提示；
                // 这里留日志（带原始内容），否则线上只能看到"格式异常"四个字，无从排查
                log.warn("AI 草稿返回内容解析失败，原始内容：{}", raw, e);
            }
        }
        throw new IllegalArgumentException("AI 返回格式异常，请稍后重试");
    }

    /**
     * 把模型返回的文本解析成 WeeklySummaryDTO（个人历史周报摘要）。
     * 结构与 parseDraft 一致，只是目标字段不同，因此同样做「围栏/废话容错 + JSON 主体截取」。
     */
    private WeeklySummaryDTO parseSummary(String raw) {
        // 模型什么都没返回（网络异常/超时等原因导致内容为空）——直接报业务错误
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("AI未返回内容，请稍后重试");
        }
        // 截取第一个 { 到最后一个 } 之间的子串，去掉 ```json 围栏和前后解释性文字
        String body = raw.trim();
        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                // 把 JSON 子串解析成 JsonNode 树，之后才能按字段名取值
                JsonNode root = objectMapper.readTree(body.substring(start, end + 1));
                // 确认解析出来的是JSON对象
                if (root != null && root.isObject()) {
                    WeeklySummaryDTO dto = new WeeklySummaryDTO();
                    // 三个字段都是字符串（内部按 1、2、3 编号，换行分隔），复用 extract() 取值；
                    // 截断用摘要自己的上限 MAX_SUMMARY_LENGTH，不套周报正文的 1000 字
                    dto.setAchievements(limit(extract(root, "achievements", "核心产出", "主要成果", "本期产出"), MAX_SUMMARY_LENGTH));
                    dto.setIssues(limit(extract(root, "issues", "问题", "持续问题", "风险"), MAX_SUMMARY_LENGTH));
                    dto.setSuggestions(limit(extract(root, "suggestions", "建议", "关注方向", "下一步建议"), MAX_SUMMARY_LENGTH));
                    return dto;
                }
            } catch (JsonProcessingException e) {
                // 同上：落到统一的"格式异常"提示，日志里留原始内容便于排查
                log.warn("AI 个人摘要返回内容解析失败，原始内容：{}", raw, e);
            }
        }
        throw new IllegalArgumentException("AI返回格式异常，请稍后重试");
    }

    /**
     * 把模型返回的文本解析成 TeamSummaryDTO（团队周报汇总）。
     * 与个人摘要结构相同，容错逻辑一致，只是目标字段换成团队三项。
     * 注意：未提交成员（missingMembers）不在这里解析，由调用方在解析后回填。
     */
    private TeamSummaryDTO parseTeamSummary(String raw) {
        // 模型什么都没返回（网络异常/超时等原因导致内容为空）——直接报业务错误
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("AI未返回内容，请稍后重试");
        }
        // 截取第一个 { 到最后一个 } 之间的子串，去掉 ```json 围栏和前后解释性文字
        String body = raw.trim();
        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                // 把 JSON 子串解析成 JsonNode 树，之后才能按字段名取值
                JsonNode root = objectMapper.readTree(body.substring(start, end + 1));
                // 确认解析出来的是JSON对象
                if (root != null && root.isObject()) {
                    TeamSummaryDTO dto = new TeamSummaryDTO();
                    // 三个字段都是字符串，复用 extract() 取值（兼容中文别名），按摘要上限 MAX_SUMMARY_LENGTH 截断
                    dto.setTeamProgress(limit(extract(root, "teamProgress", "团队进展", "整体进展", "团队产出"), MAX_SUMMARY_LENGTH));
                    dto.setCommonIssues(limit(extract(root, "commonIssues", "共性问题", "共同问题", "团队风险"), MAX_SUMMARY_LENGTH));
                    dto.setCollaborationNeeds(limit(extract(root, "collaborationNeeds", "协作需求", "需要协作", "协作事项"), MAX_SUMMARY_LENGTH));
                    return dto;
                }
            } catch (JsonProcessingException e) {
                // 同上：落到统一的"格式异常"提示，日志里留原始内容便于排查
                log.warn("AI 团队摘要返回内容解析失败，原始内容：{}", raw, e);
            }
        }
        throw new IllegalArgumentException("AI返回格式异常，请稍后重试");
    }

    /**
     * 按字段名从 JSON 节点中取值，支持多个中文别名。
     * 模型偶尔不守规矩、用了中文键名（如"本周进展"）而不是约定的英文键，
     * 这里按顺序逐个尝试，取到第一个非空值即返回；全部找不到则返回空串。
     * 在方法内部，aliases 会被当作 String[] 数组 来处理。写成 String... 只是一种简写
     */
    private String extract(JsonNode node, String... aliases) {  // 可变参数（...）必须放在方法参数列表的最后一个位置
        for (String alias : aliases) {
            JsonNode field = node.get(alias);
            // 前者判断 node.get(alias) 是否真的从 JSON 里找到了这个字段，后者判断这个字段的值是不是 JSON 中的字面量 null
            if (field != null && !field.isNull()) {
                // 字符串节点用 asText()；万一值是数字/数组等，用 toString() 兜底转成文本
                String value = field.isTextual() ? field.asText() : field.toString();
                if (!value.isBlank()) {
                    return value.trim();
                }
            }
        }
        return "";
    }

    /**
     * 截断的默认重载：固定用 MAX_FIELD_LENGTH（1000 字），与前端 textarea 的 maxlength 保持一致，
     * 避免超长内容破坏前端/数据库的字段长度限制。周报正文字段走这里。
     * 摘要类字段上限不同（300 字），需改用带 maxLength 的重载。
     */
    private String limit(String value) {
        return limit(value, MAX_FIELD_LENGTH);
    }

    /**
     * 按调用方传入的上限截断（真正干活的方法）：
     * 上限完全由实参决定——周报正文传 MAX_FIELD_LENGTH(1000)，摘要部分传 MAX_SUMMARY_LENGTH(300)，
     * 这里不写死任何长度。按字符数（String.length()）截断，不做代理对（emoji 等）的边界保护。
     */
    private String limit(String value, int maxLength) {
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    /**
     * AI 完整性检查：按周报的四个字段分别判断，并把（可选）上周周报一并交给模型做承接关系比对。
     * 前三个字段（总体进度/本周进展/下周目标）必填，方法内会先校验；「其他补充」允许为空。
     *
     * @param currentReport  待检查的本周周报四字段
     * @param lastWeekReport 上周周报（可为 null，null 时模型跳过"承接关系"检查）
     * @return 模型按字段列出的缺失项与改进建议
     * @throws IllegalArgumentException 本周周报为 null，或前三个必填字段有空时抛出
     */
    public String checkCompleteness(WeeklyReportDTO currentReport, WeeklyReport lastWeekReport) {
        // 前三个字段为必填，缺失时直接拦截（与保存/提交的 validateContent 规则一致），
        // 避免"前三个字段为空"的非法周报被发给模型。
        if (currentReport == null || isBlank(currentReport.getOverallProgress())
                || isBlank(currentReport.getWeeklyWorkReport())
                || isBlank(currentReport.getNextWeekPlan())) {
            throw new IllegalArgumentException("总体进度、本周进展、下周目标不能为空");
        }

        // 组装用户消息：分两段（本周周报 + 上周周报），模型据此做"逐字段缺失"和"承接关系"判断。
        // 上周周报没有时，用一句话明确告知模型跳过承接检查，避免它脑补不存在的上周内容。
        String userText = "【本周周报】\n" + formatReportFields(
                    currentReport.getOverallProgress(), currentReport.getWeeklyWorkReport(),
                    currentReport.getNextWeekPlan(), currentReport.getOther())
                + "\n\n【上周周报】\n"
                + (lastWeekReport == null
                    ? "（未提供上周周报，「本周进展」的承接关系项可跳过检查）"
                    : formatReportFields(lastWeekReport.getOverallProgress(),
                        lastWeekReport.getWeeklyWorkReport(),
                        lastWeekReport.getNextWeekPlan(),
                        lastWeekReport.getOther()));

        // 发起检查请求，模型返回的是自由文本检查报告（不是 JSON），原样返回给前端展示
        String content = chatClient.prompt()
                .system(systemCheckPrompt)
                .user(userText)
                .call()
                .content();
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("AI 未返回内容，请稍后重试");
        }
        return content;
    }

    /**
     * AI 润色：把用户已填写的周报字段交给模型做语言与格式优化，返回润色后的四字段 DTO。
     * 与"生成草稿"不同，润色不要求前三个字段都填——只要至少填了一项即可；
     * 未填写的字段不会发给模型（防止模型脑补内容），返回结果中对应字段为空串，
     * 由前端只回填"已填过"的字段。
     *
     * @param report 用户当前表单里的四字段内容（允许部分为空）
     * @return 润色后的四字段，未填字段为空串
     * @throws IllegalArgumentException 四个字段全空，或模型返回内容为空 / 格式异常时抛出
     */
    public WeeklyReportDTO polishReport(WeeklyReportDTO report) {
        // 润色允许"只填了部分"：至少一个字段有内容即可（保存/提交时才要求前三个必填）
        if (report == null || allBlank(report)) {
            throw new IllegalArgumentException("请至少填写一项周报内容后再润色");
        }

        // 只把已填字段发给模型；空字段不出现，模型就不需要也不应脑补它们的内容
        String userText = "【待润色周报】\n" + formatFilledFields(report);

        String content = chatClient.prompt()
                .system(systemPolishPrompt)
                .user(userText)
                .call()
                .content();
        // 复用生成草稿的 JSON 解析：模型返回的仍是四字段 DTO 结构的纯文本
        return parseDraft(content);
    }

    /** 判断单个字符串是否为 null 或纯空白 */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 判断 DTO 四个字段是否全为空 */
    private boolean allBlank(WeeklyReportDTO report) {
        return isBlank(report.getOverallProgress())
                && isBlank(report.getWeeklyWorkReport())
                && isBlank(report.getNextWeekPlan())
                && isBlank(report.getOther());
    }

    /** 只把非空字段拼成"标签：内容"行；空字段直接省略（润色专用，避免空字段干扰模型） */
    private String formatFilledFields(WeeklyReportDTO report) {
        StringBuilder sb = new StringBuilder();
        appendFilled(sb, "总体进度", report.getOverallProgress());
        appendFilled(sb, "本周进展", report.getWeeklyWorkReport());
        appendFilled(sb, "下周目标", report.getNextWeekPlan());
        appendFilled(sb, "其他补充", report.getOther());
        return sb.toString();
    }

    /** 仅当字段有内容时才追加一行；字段 trim 后写入，去掉首尾多余空白 */
    private void appendFilled(StringBuilder sb, String label, String value) {
        if (!isBlank(value)) {
            sb.append(label).append("：").append(value.trim()).append('\n');
        }
    }

    /**
     * 把周报四字段拼成人话交给模型。前三个字段必填（本周入口已校验、上周来自 @NotBlank 的实体），
     * 无需判空；textOrEmpty 只为可选的「其他补充」兜底。
     */
    private String formatReportFields(String overallProgress, String weeklyWorkReport,
                                      String nextWeekPlan, String other) {
        return "总体进度：" + overallProgress
                + "\n本周进展：" + weeklyWorkReport
                + "\n下周目标：" + nextWeekPlan
                + "\n其他补充：" + textOrEmpty(other);
    }

    /** null/空串显示为"（空）"，有内容则原样返回 */
    private String textOrEmpty(String value) {
        return isBlank(value) ? "（空）" : value;
    }

    /**
     * 总结某用户最近 4 周已提交的周报。
     * 结果按「用户 + 本周周一」缓存（value 决定 Redis 前缀、key 决定对应哪次调用，见 @Cacheable）：
     * 重复请求命中缓存即不再调用大模型，省钱也省等待时间。
     * 注意：缓存有效期由 Redis 全局 TTL 决定（当前 5 分钟，见 RedisConfig#cacheManager），并非缓存一整周。
     *
     * @throws IllegalArgumentException 该用户区间内已提交周报少于 2 篇时抛出
     */
    @Cacheable(value = "aiSummary",
            key = "#userId + '_' + T(com.practice.weeklyreportmanager.utils.DateUtils).getCurrentMonday()")
    public WeeklySummaryDTO summarizeHistory(Long userId) {
        // 收集最近 4 周的周一，从早到晚：[0]=4 周前、[1]=3 周前、[2]=上周、[3]=本周
        List<LocalDate> mondayList = new ArrayList<>();
        for (int i = 3; i >= 0; i--) {
            mondayList.add(DateUtils.getMondayOfWeek(LocalDate.now().minusWeeks(i)));
        }
        // 只总结已提交的周报，草稿不参与（数据查询交给 WeeklyReportService）；
        // 查询区间取 [0]（最早）到 [3]（本周）
        List<WeeklyReport> reportList = weeklyReportService.getSubmittedReportsBetween(
                userId, mondayList.get(0), mondayList.get(3));
        if (reportList.size() < 2) {
            throw new IllegalArgumentException("周报数量不足，无法生成摘要");
        }
        // 按实际查到的条数拼接：用户可能漏写某周，四周里查出 2~3 条是正常的，
        // 不能按 0~3 的下标硬取，否则会 IndexOutOfBoundsException
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < reportList.size(); i++) {
            WeeklyReport report = reportList.get(i);
            sb.append("【第").append(i + 1).append("周 ")
                    .append(DateUtils.formatWeekTitle(report.getWeekStartDate())).append("】")
                    .append("\n总体进度：").append(report.getOverallProgress())
                    .append("\n本周进展：").append(report.getWeeklyWorkReport())
                    .append("\n下周目标：").append(report.getNextWeekPlan())
                    .append("\n其他补充：").append(textOrEmpty(report.getOther()))
                    .append('\n');
        }
        String userText = sb.toString();

        String content = chatClient.prompt()
                .system(systemSummaryPrompt)
                .user(userText)
                .call()
                .content();

        WeeklySummaryDTO dto = parseSummary(content);
        // 三个部分是字符串，直接判空（原来用 toString() 判断，空列表的 "[]" 恒不为空，导致校验失效）
        if (isBlank(dto.getAchievements()) && isBlank(dto.getIssues()) && isBlank(dto.getSuggestions())) {
            throw new IllegalArgumentException("AI未返回内容，请稍后重试");
        }
        return dto;
    }

    /**
     * 汇总某一周所有已提交成员的周报，生成团队周报（团队进展 / 共性问题 / 协作需求）。
     * 未提交成员不进模型，由系统算出后回填到 missingMembers。
     * 结果按「归一化后的周一」缓存（value 决定 Redis 前缀、key 决定对应哪次调用，见 @Cacheable）：
     * 跨周自动换 key，同周内重复请求命中缓存。
     * 注意：缓存有效期由 Redis 全局 TTL 决定（当前 5 分钟，见 RedisConfig#cacheManager），并非缓存一整周。
     *
     * @throws IllegalArgumentException 该周无已提交周报，或少于 2 篇时抛出
     */
    @Cacheable(value = "teamSummary",
            key = "T(com.practice.weeklyreportmanager.utils.DateUtils)"
                    + ".getMondayOfWeek(#weekStartDate != null ? #weekStartDate : T(java.time.LocalDate).now())")
    public TeamSummaryDTO summarizeTeam(LocalDate weekStartDate) {
        // 传日期则汇总该周周报，不传则默认汇总当前周周报
        if (weekStartDate == null) {
            weekStartDate = DateUtils.getCurrentMonday();
        } else {
            weekStartDate = DateUtils.getMondayOfWeek(weekStartDate);
        }
        // 获取已提交的周报
        List<WeeklyReport> submitted = weeklyReportService.getSubmittedReportOfWeek(weekStartDate);
        if (submitted.isEmpty()) {
            throw new IllegalArgumentException("本周暂无已提交周报");
        } else if (submitted.size() < 2) {
            throw new IllegalArgumentException("周报数量不足，无法生成周报");
        }
        // 获取所有成员
        List<MockUserService.MockUser> allmembers = mockUserService.getAllMembers();

        // 按userId构建快速查找索引
        Map<Long, WeeklyReport> reportIndex = new HashMap<>();
        for (WeeklyReport report : submitted) {
            Long key = report.getUserId();
            reportIndex.put(key, report);
        }

        // 遍历所有成员，按userId查找相应周报，如果周报为null，则该成员未提交周报，加入missingMembers列表
        List<String> missingMembers = new ArrayList<>();
        for (MockUserService.MockUser user : allmembers) {
            Long key = user.getId();
            WeeklyReport report = reportIndex.get(key);
            if (report == null) {
                missingMembers.add(user.getName());
            }
        }

        // StringBuilder 必须在循环外声明：写在循环里的话，每轮都是新对象，出循环就拿不到了
        StringBuilder sb = new StringBuilder();
        for (WeeklyReport report : submitted) {
            String userName = mockUserService.getUserName(report.getUserId());
            sb.append("【").append(userName != null ? userName : "成员" + report.getUserId()).append("】")
                    .append("\n总体进度：").append(report.getOverallProgress())
                    .append("\n本周进展：").append(report.getWeeklyWorkReport())
                    .append("\n下周目标：").append(report.getNextWeekPlan())
                    .append("\n其他补充：").append(textOrEmpty(report.getOther()))
                    .append('\n');
        }
        String userText = sb.toString();

        String content = chatClient.prompt()
                .system(systemTeamSummaryPrompt)
                .user(userText)
                .call()
                .content();

        TeamSummaryDTO dto = parseTeamSummary(content);
        // 三个部分是字符串，直接判空（别用 toString()，空列表的 "[]" 恒不为空）
        if (isBlank(dto.getTeamProgress()) && isBlank(dto.getCommonIssues())
                && isBlank(dto.getCollaborationNeeds())) {
            throw new IllegalArgumentException("AI未返回内容，请稍后重试");
        }
        // 未提交成员是系统算出来的，不是模型生成的，所以放在解析之后回填
        dto.setMissingMembers(String.join("、", missingMembers));
        return dto;
    }
}
