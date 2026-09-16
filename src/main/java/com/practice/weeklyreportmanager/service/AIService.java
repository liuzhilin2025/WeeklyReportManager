package com.practice.weeklyreportmanager.service;

import com.practice.weeklyreportmanager.dto.*;
import com.practice.weeklyreportmanager.entity.WeeklyReport;
import com.practice.weeklyreportmanager.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 辅助服务：把「提示词模板 + 动态内容」组装成一次大模型请求，再把返回结果转成前端要用的结构。
 *
 * 对外 5 个入口：
 *   - generateDraft      生成草稿（结构化 DTO）
 *   - polishReport       润色（结构化 DTO）
 *   - summarizeHistory   个人近 4 周摘要（结构化 DTO）
 *   - summarizeTeam      团队单周汇总（结构化 DTO）
 *   - chatWithReports    周报问答（自由文本，不是 JSON）
 *
 * 前四个入口的流程一致：callForEntity 发请求 → 按各自上限截断字段 → 校验「到底有没有内容」。
 *
 * 提示词不在 Java 里写死，放在 resources/prompts/ 下，启动时读进内存：
 *   - ai-report-system.txt / ai-polish-system.txt / ai-summary-system.txt
 *   - ai-team-summary-system.txt / ai-chat-system.txt（以上都是系统指令）
 *   - ai-report-user.st（生成草稿的用户消息模板，含 {userInput} 占位符）
 */
@Slf4j
@Service
public class AIService {

    /** 单个字段最大长度，与前端 textarea 的 maxlength 保持一致，超长会被截断 */
    private static final int MAX_FIELD_LENGTH = 1000;

    /** 摘要每个部分的最大长度：prompt 约定最多 5 条 × 50 字，留一点余量给编号和换行 */
    private static final int MAX_SUMMARY_LENGTH = 300;

    /**
     * 单次请求里「拼给模型的周报数据」总长度上限（按字符数）。
     *
     * 为什么必须有：问答接口的时间范围由用户决定，个人摘要的正文长度由用户书写决定，
     * 两者都没有天然上限。用户把范围拉到一年，就是 52 篇 × 最多 4000 字 ≈ 20 万字，
     * 一次请求的延迟和费用会线性上涨，还可能直接超出模型的上下文窗口。
     *
     * 4000 的来源：4 周 × 4 字段 × 1000 字（MAX_FIELD_LENGTH）最坏约 1.6 万字，
     * 取 4000 是「正常情况下装得下最近几周、异常情况下不会失控」的折中，
     * 超出部分由 buildReportContext 按「优先保留最近的周报」规则取舍。
     */
    private static final int MAX_PROMPT_REPORT_CHARS = 4000;

    /** 周报数据查询走 WeeklyReportService，AIService 只负责拼提示词、调模型、收拾返回值 */
    private final WeeklyReportService weeklyReportService;
    private final MockUserService mockUserService;

    /** Spring AI 的大模型客户端，由构造器注入的 Builder 构建 */
    private final ChatClient chatClient;
    /** 生成草稿的系统提示词，启动时从 ai-report-system.txt 读入 */
    private final String systemPrompt;
    /** 润色的系统提示词，来自 ai-polish-system.txt */
    private final String systemPolishPrompt;
    /** 个人摘要的系统提示词，来自 ai-summary-system.txt */
    private final String systemSummaryPrompt;
    /** 团队摘要的系统提示词，来自 ai-team-summary-system.txt */
    private final String systemTeamSummaryPrompt;
    /** 周报问答的系统提示词，来自 ai-chat-system.txt */
    private final String systemChatPrompt;
    /** 用户消息模板：渲染后得到 UserMessage，唯一的动态内容就是用户输入的 userInput */
    private final PromptTemplate userPromptTemplate;

    /**
     * 构造器注入：AIService 由 Spring 托管，启动时自动调用本构造器把依赖和提示词都装好。
     *
     * 提示词以 Resource 注入、在这里一次性读成 String，之后每次请求复用内存里这一份。
     * 文件缺失会抛 IOException 让应用直接启动失败（fail-fast），而不是等用户点了按钮才报错。
     *
     * @param chatClientBuilder Spring AI 自动配置好的 ChatClient 建造器（可定制模型/超时等）
     * @throws IOException      提示词文件缺失或读取失败时抛出，导致应用启动失败
     */
    public AIService(ChatClient.Builder chatClientBuilder,
                     WeeklyReportService weeklyReportService,
                     MockUserService mockUserService,
                     @Value("classpath:prompts/ai-report-system.txt") Resource systemPromptResource,
                     @Value("classpath:prompts/ai-polish-system.txt") Resource systemPolishPromptResource,
                     @Value("classpath:prompts/ai-summary-system.txt") Resource systemSummaryPromptResource,
                     @Value("classpath:prompts/ai-team-summary-system.txt") Resource systemTeamSummaryPromptResource,
                     @Value("classpath:prompts/ai-chat-system.txt") Resource systemChatPromptResource,
                     @Value("classpath:prompts/ai-report-user.st") Resource userPromptResource) throws IOException {
        this.chatClient = chatClientBuilder.build();
        this.weeklyReportService = weeklyReportService;
        this.mockUserService = mockUserService;
        this.systemPrompt = systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
        this.systemPolishPrompt = systemPolishPromptResource.getContentAsString(StandardCharsets.UTF_8);
        this.systemSummaryPrompt = systemSummaryPromptResource.getContentAsString(StandardCharsets.UTF_8);
        this.systemTeamSummaryPrompt = systemTeamSummaryPromptResource.getContentAsString(StandardCharsets.UTF_8);
        this.systemChatPrompt = systemChatPromptResource.getContentAsString(StandardCharsets.UTF_8);
        this.userPromptTemplate = new PromptTemplate(userPromptResource.getContentAsString(StandardCharsets.UTF_8));
    }

    /**
     * 统一发一次「结构化输出」请求：系统指令 + 用户消息 → DTO。四个入口共用这一条路。
     *
     * 两类失败要分开报，因为用户能做的事不一样：
     * - RestClientException：网络超时、鉴权失败、额度用尽等，属于「服务不可用」，稍后重试有意义；
     * - 其它 RuntimeException：`.entity()` 拿到回答后在本地做 JSON→DTO 转换，转换失败说明模型没按格式输出，
     *   重试多少次都一样。（HTTP 在 `.call()` 里就发完了，所以这两类异常的来源不会串。）
     *
     * @param errorLabel 日志里区分是哪个入口出的问题，如「草稿」「团队汇总」
     * <T>位于 private 和返回值之间，是泛型声明。它告诉编译器：“本方法内部要使用一个类型参数，名字叫 T，具体是什么类型，调用时再确定。”
     * T 位于 <T> 后面，是返回类型。意思是“这个方法会返回一个 T 类型的对象。”
     */
    private <T> T callForEntity(String systemPrompt, Message userMessage, Class<T> type, String errorLabel) {
        try {
            return chatClient.prompt()
                    .system(systemPrompt)    // 存入系统提示词
                    .messages(userMessage)   // 存入用户消息
                    .call()
                    .entity(type);           // 把大模型返回的文本反序列化为指定的Java实体类
        } catch (RestClientException e) {
            log.error("AI{}调用失败：服务不可用或鉴权异常", errorLabel, e);
            throw new IllegalStateException("AI 服务暂时不可用，请稍后重试", e);
        } catch (RuntimeException e) {
            log.warn("AI{}返回内容无法转成 DTO", errorLabel, e);
            throw new IllegalArgumentException("AI返回格式异常，请稍后重试");
        }
    }

    /**
     * 根据用户输入的本周工作内容，生成一份可直接填入表单的结构化周报草稿。
     *
     * @param userInput 用户描述的本周工作（关键词或流水记录）
     * @return 四个字段与 WeeklyReportDTO 一一对应的草稿
     * @throws IllegalArgumentException 模型没吐出可用内容时抛出
     * @throws IllegalStateException    AI 服务不可用时抛出
     */
    public WeeklyReportDTO generateDraft(String userInput) {
        // 渲染用户消息模板：把 {userInput} 换成用户真实输入
        Message userMessage = userPromptTemplate.createMessage(Map.of("userInput", userInput));
        // 结构化输出
        WeeklyReportDTO dto = callForEntity(systemPrompt, userMessage, WeeklyReportDTO.class, "草稿");

        // 截断保护，每个字段不能超过1000个字
        limitReportFields(dto);
        // 三个核心字段至少一个非空。全空说明用户给的料太少、模型也没得编，
        // 这时候返回空草稿会直接盖掉用户正在写的表单，不如报错让他补点描述
        if (dto == null || (isBlank(dto.getOverallProgress()) && isBlank(dto.getWeeklyWorkReport())
                && isBlank(dto.getNextWeekPlan()))) {
            throw new IllegalArgumentException("AI 生成内容为空，请补充更多工作描述后重试");
        }
        return dto;
    }

    /** 按 MAX_FIELD_LENGTH（1000 字）截断，周报正文用这个上限，与前端 textarea 的 maxlength 一致 */
    private String limit(String value) {
        return limit(value, MAX_FIELD_LENGTH);
    }

    /** 按指定上限截断；null 原样返回（模型漏字段时不能让截断本身抛 NPE）。按字符数切，不处理 emoji 之类的代理对 */
    private String limit(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /**
     * 把草稿/润色结果的四个字段各截到 1000 字。
     *
     * 为什么必须在 .entity() 之后手动做：.entity() 只负责把模型输出的 JSON 反序列化成 DTO，不做长度校验，
     * 而模型偶尔会不守提示词里「每个字段不超过 1000 字」的约定；这些内容会回填表单再落库，
     * 超长会在保存时被 validateContent 拦下（WeeklyReportServiceImpl:49），用户只能自己删——不如在这里就切掉。
     */
    private void limitReportFields(WeeklyReportDTO dto) {
        if (dto == null) {
            return;
        }
        dto.setOverallProgress(limit(dto.getOverallProgress()));
        dto.setWeeklyWorkReport(limit(dto.getWeeklyWorkReport()));
        dto.setNextWeekPlan(limit(dto.getNextWeekPlan()));
        dto.setOther(limit(dto.getOther()));
    }

    /** 摘要/团队汇总的三个部分各截到 MAX_SUMMARY_LENGTH：提示词只约定「最多 5 条 × 50 字」，模型不一定守 */
    private void limitSummaryFields(WeeklySummaryDTO dto) {
        if (dto == null) {
            return;
        }
        dto.setAchievements(limit(dto.getAchievements(), MAX_SUMMARY_LENGTH));
        dto.setIssues(limit(dto.getIssues(), MAX_SUMMARY_LENGTH));
        dto.setSuggestions(limit(dto.getSuggestions(), MAX_SUMMARY_LENGTH));
    }

    /** 把模型可能脑补的内容清掉：用户原本没填的字段，在结果里一律置为空串 */
    private void maskEmptyFields(WeeklyReportDTO source, WeeklyReportDTO target) {
        if (target == null) {
            return;
        }
        if (isBlank(source.getOverallProgress())) {
            target.setOverallProgress("");
        }
        if (isBlank(source.getWeeklyWorkReport())) {
            target.setWeeklyWorkReport("");
        }
        if (isBlank(source.getNextWeekPlan())) {
            target.setNextWeekPlan("");
        }
        if (isBlank(source.getOther())) {
            target.setOther("");
        }
    }

    /**
     * AI 润色：把用户已填写的字段交给模型优化措辞，返回润色后的四字段 DTO。
     * 与生成草稿不同，润色允许只填一部分（至少一项即可），且未填字段不会发给模型，
     * 返回结果里这些字段会被强制置空（见 maskEmptyFields），前端只回填「原来填过的」字段。
     *
     * @param report 用户当前表单里的四字段内容（允许部分为空）
     * @return 润色后的四字段，未填字段为空串
     * @throws IllegalArgumentException 四个字段全空，或模型没吐出可用内容时抛出
     */
    public WeeklyReportDTO polishReport(WeeklyReportDTO report) {
        if (report == null || allBlank(report)) {
            throw new IllegalArgumentException("请至少填写一项周报内容后再润色");
        }

        // 只把已填字段发给模型；空字段不出现，模型就没机会（也不该）脑补它们
        String userText = "【待润色周报】\n" + formatFilledFields(report);

        WeeklyReportDTO dto = callForEntity(systemPolishPrompt, new UserMessage(userText),
                WeeklyReportDTO.class, "润色");
        limitReportFields(dto);
        // 用户原本没填的字段，在结果里一律置为空串
        maskEmptyFields(report, dto);
        // 先 mask 再判空：只填了「其他补充」、模型又什么都没返回时，mask 完四个字段全空，
        // 这时报错比返回空串好——前端拿到空值只会显示「AI 认为内容无需修改」，等于把失败伪装成没事
        if (dto == null || allBlank(dto)) {
            throw new IllegalArgumentException("AI未返回内容，请稍后重试");
        }
        return dto;
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

    /** null/空串显示为"（空）"，有内容则原样返回 */
    private String textOrEmpty(String value) {
        return isBlank(value) ? "（空）" : value;
    }

    /**
     * 把一组周报拼成给模型看的上下文文本，总长度不超过 MAX_PROMPT_REPORT_CHARS。
     *
     * 超预算时优先丢「更早的周报」：最近的工作比几个月前的内容更值得让模型看到，
     * 而最新那篇无论如何都会保留（否则单篇正文就超预算时会拼出一段空上下文）。
     *
     * 真丢了篇时会在开头写明，否则模型容易把「没给它看」误答成「周报里没有」。
     *
     * @param reports       已按 week_start_date 升序排好的周报
     * @param withWeekIndex true = 标题带「第几周」编号（个人摘要用）；false = 带具体周一日期（问答用）
     * @return 拼好的文本，形如「【第1周 09.01~09.05】\n总体进度：…\n」重复若干段
     */
    private String buildReportContext(List<WeeklyReport> reports, boolean withWeekIndex) {
        if (reports.isEmpty()) {
            return "";
        }
        // 「第N周」取周报在原列表里的位置，这样即使前面的篇被丢掉，编号也仍对得上真实位置
        List<String> blocks = new ArrayList<>(reports.size());
        for (int i = 0; i < reports.size(); i++) {
            WeeklyReport report = reports.get(i);
            // 两种调用只差标题：摘要用「第几周」，问答用周一日期（title 只有 MM.dd，跨年时分不清先后）
            String header = withWeekIndex
                    ? "【第" + (i + 1) + "周 " + DateUtils.formatWeekTitle(report.getWeekStartDate()) + "】"
                    : "【" + report.getWeekStartDate() + " " + report.getTitle() + "】";
            blocks.add(reportBlock(report, header));
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
        if (start > 0) {
            sb.append("（注：更早的周报因篇幅限制未包含在内）\n");
        }
        for (int i = start; i < blocks.size(); i++) {
            sb.append(blocks.get(i));
        }
        return sb.toString();
    }

    /**
     * 单篇周报的正文块：标题 + 四个字段 + 结尾换行。
     *
     * 标题由调用方拼好传进来（各处口径不同：摘要「第N周」、问答周一日期、团队汇总成员名），
     * 本方法只负责字段部分，这样三处拼出来的块格式完全一致，不会因为各写一份而长歪。
     *
     * @param header 已经拼好的标题行，如「【第1周 09.01~09.05】」「【张三】」
     */
    private String reportBlock(WeeklyReport report, String header) {
        return header
                + "\n总体进度：" + report.getOverallProgress()
                + "\n本周进展：" + report.getWeeklyWorkReport()
                + "\n下周目标：" + report.getNextWeekPlan()
                + "\n其他补充：" + textOrEmpty(report.getOther())
                + '\n';
    }

    /**
     * 总结某用户最近 4 周已提交的周报。
     *
     * 结果按「用户 + 本周周一」缓存（key 见 @Cacheable）：同一周内重复点击直接命中缓存，不再调模型；
     * 缓存有效期由全局配置决定（当前 5 分钟，见 RedisConfig#cacheManager），并不是缓存一整周。
     *
     * @throws IllegalArgumentException 区间内已提交周报少于 2 篇时抛出
     */
    @Cacheable(value = "aiSummary",
            key = "#userId + '_' + T(com.practice.weeklyreportmanager.utils.DateUtils).getCurrentMonday()")
    public WeeklySummaryDTO summarizeHistory(Long userId) {
        // 最近 4 周的周一，从早到晚：[0]=4 周前、[1]=3 周前、[2]=上周、[3]=本周
        List<LocalDate> mondayList = new ArrayList<>();
        for (int i = 3; i >= 0; i--) {
            mondayList.add(DateUtils.getMondayOfWeek(LocalDate.now().minusWeeks(i)));
        }
        // 只统计已提交的周报；用户可能漏写某周，所以下面按实际查到的条数拼接，不按下标硬取
        List<WeeklyReport> reportList = weeklyReportService.getSubmittedReportsBetween(
                userId, mondayList.get(0), mondayList.get(3));
        if (reportList.size() < 2) {
            throw new IllegalArgumentException("周报数量不足，无法生成摘要");
        }
        // 拼接总量受 MAX_PROMPT_REPORT_CHARS 约束，见 buildReportContext
        String userText = buildReportContext(reportList, true);

        WeeklySummaryDTO dto = callForEntity(systemSummaryPrompt, new UserMessage(userText),
                WeeklySummaryDTO.class, "个人摘要");

        limitSummaryFields(dto);
        // 三个部分全空，等于这次没总结出任何东西
        if (dto == null || (isBlank(dto.getAchievements()) && isBlank(dto.getIssues())
                && isBlank(dto.getSuggestions()))) {
            throw new IllegalArgumentException("AI未返回内容，请稍后重试");
        }
        return dto;
    }

    /**
     * 汇总某一周所有已提交成员的周报，生成团队周报（团队进展 / 共性问题 / 协作需求）。
     * 未提交成员不进模型，由系统算出来后回填到 missingMembers。
     *
     * 结果按「归一化后的周一」缓存（key 见 @Cacheable）：跨周自动换 key，同一周内重复点击命中缓存；
     * 缓存有效期由全局配置决定（当前 5 分钟，见 RedisConfig#cacheManager）。
     *
     * @throws IllegalArgumentException 该周没有已提交周报，或不足 2 篇时抛出
     */
    @Cacheable(value = "teamSummary",
            key = "T(com.practice.weeklyreportmanager.utils.DateUtils)"
                    + ".getMondayOfWeek(#weekStartDate != null ? #weekStartDate : T(java.time.LocalDate).now())")
    public TeamSummaryDTO summarizeTeam(LocalDate weekStartDate) {
        // 传了日期就汇总那一周，不传默认本周；先归一到周一，与 week_start_date 的存法保持一致
        if (weekStartDate == null) {
            weekStartDate = DateUtils.getCurrentMonday();
        } else {
            weekStartDate = DateUtils.getMondayOfWeek(weekStartDate);
        }
        List<WeeklyReport> submitted = weeklyReportService.getSubmittedReportOfWeek(weekStartDate);
        if (submitted.isEmpty()) {
            throw new IllegalArgumentException("本周暂无已提交周报");
        } else if (submitted.size() < 2) {
            throw new IllegalArgumentException("周报数量不足，无法生成周报");
        }
        List<MockUserService.MockUser> allmembers = mockUserService.getAllMembers();

        // 按 userId 建索引，判断谁没提交时就不用反复遍历已提交列表
        Map<Long, WeeklyReport> reportIndex = new HashMap<>();
        for (WeeklyReport report : submitted) {
            reportIndex.put(report.getUserId(), report);
        }

        // 名单里有、当周没查到 = 未提交；名单外的提交者（如已离职、管理员）不算进 missingMembers，
        // 他的周报仍会进 prompt，只是标题退化成「成员+id」
        List<String> missingMembers = new ArrayList<>();
        for (MockUserService.MockUser user : allmembers) {
            if (!reportIndex.containsKey(user.getId())) {
                missingMembers.add(user.getName());
            }
        }

        // 每个成员一段，标题是成员名；正文格式与摘要/问答共用 reportBlock，免得三处各写一份
        StringBuilder sb = new StringBuilder();
        for (WeeklyReport report : submitted) {
            String userName = mockUserService.getUserName(report.getUserId());
            sb.append(reportBlock(report, "【" + (userName != null ? userName : "成员" + report.getUserId()) + "】"));
        }
        String userText = sb.toString();

        // 这里用 TeamSummaryAI 而不是直接反序列化成 TeamSummaryDTO：.entity() 会把类的字段做成 schema 塞进 prompt，
        // 多出来的 missingMembers 会诱使模型自己去编一份「未提交名单」，而那本该由系统算
        TeamSummaryAI ai = callForEntity(systemTeamSummaryPrompt, new UserMessage(userText),
                TeamSummaryAI.class, "团队汇总");
        TeamSummaryDTO dto = new TeamSummaryDTO();
        if (ai != null) {
            dto.setTeamProgress(limit(ai.getTeamProgress(), MAX_SUMMARY_LENGTH));
            dto.setCommonIssues(limit(ai.getCommonIssues(), MAX_SUMMARY_LENGTH));
            dto.setCollaborationNeeds(limit(ai.getCollaborationNeeds(), MAX_SUMMARY_LENGTH));
        }
        // 三个部分全空，等于这次没汇总出任何东西
        if (isBlank(dto.getTeamProgress()) && isBlank(dto.getCommonIssues())
                && isBlank(dto.getCollaborationNeeds())) {
            throw new IllegalArgumentException("AI未返回内容，请稍后重试");
        }
        // 未提交成员是系统算出来的，不是模型生成的，所以放在校验之后回填
        dto.setMissingMembers(String.join("、", missingMembers));
        return dto;
    }

    /**
     * 对话式周报问答：把该用户在指定时间范围内已提交的周报拼成上下文，连同问题一起交给模型。
     * 与其它入口不同，这里要的是给用户直接看的自由文本，所以不要求 JSON，也不做截断。
     *
     * @param request 问答请求（userId、question 必填，startDate/endDate 可选）
     * @return 模型基于周报数据给出的回答；区间内没有已提交周报时返回一句固定提示
     * @throws IllegalArgumentException 开始时间晚于结束时间时抛出
     * @throws IllegalStateException    AI 服务不可用时抛出
     */
    public String chatWithReports(ChatRequest request) {
        // 不传日期默认查最近 4 周（本周 + 前 3 周），与个人摘要的时间口径一致
        LocalDate end = request.getEndDate() != null ? request.getEndDate() : LocalDate.now();
        LocalDate start = request.getStartDate() != null
                ? request.getStartDate()
                : DateUtils.getMondayOfWeek(end).minusWeeks(3);
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("开始时间不能晚于结束时间");
        }
        // week_start_date 存的是周一：起始日也对齐到所在周的周一，否则会整周漏查
        start = DateUtils.getMondayOfWeek(start);

        List<WeeklyReport> reports = weeklyReportService.getSubmittedReportsBetween(request.getUserId(), start, end);
        if (reports.isEmpty()) {
            return "在指定时间范围内没有找到已提交的周报";
        }

        String userText = "以下是该用户的周报数据（按时间从早到晚排列）：\n\n"
                + buildReportContext(reports, false)
                + "用户问题：" + request.getQuestion();

        String content;
        try {
            content = chatClient.prompt()
                    .system(systemChatPrompt)
                    .user(userText)
                    .call()
                    .content();
        } catch (RestClientException e) {
            log.error("AI问答调用失败：服务不可用或鉴权异常", e);
            throw new IllegalStateException("AI 服务暂时不可用，请稍后重试", e);
        }
        // 模型返回空（超时、被内容策略拦掉等）时给和其它入口一致的业务错误，别让前端拿到 null
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("AI未返回内容，请稍后重试");
        }
        return content;
    }
}
