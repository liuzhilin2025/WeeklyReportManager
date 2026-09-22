package com.practice.weeklyreportmanager.service;

import com.practice.weeklyreportmanager.dto.*;
import com.practice.weeklyreportmanager.entity.WeeklyReport;
import com.practice.weeklyreportmanager.service.tool.ReportQueryTools;
import com.practice.weeklyreportmanager.utils.DateUtils;
import com.practice.weeklyreportmanager.utils.ReportTextFormatter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import com.practice.weeklyreportmanager.advisor.AILogAdvisor;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 辅助服务：把「提示词模板 + 动态内容」组装成一次大模型请求，再把返回结果转成前端要用的结构。

 * 对外 5 个入口：
 * - generateDraft      生成草稿（结构化 DTO）
 * - polishReport       润色（结构化 DTO）
 * - summarizeHistory   个人近 4 周摘要（结构化 DTO）
 * - summarizeTeam      团队单周汇总（结构化 DTO）
 * - chatWithReports    周报问答（自由文本，不是 JSON）

 * 前四个入口的流程一致：callForEntity 发请求 → 按各自上限截断字段 → 校验「到底有没有内容」。

 * 提示词不在 Java 里写死，放在 resources/prompts/ 下，启动时读进内存：
 * - ai-report-system.txt / ai-polish-system.txt / ai-summary-system.txt
 * - ai-team-summary-system.txt / ai-chat-system.txt（以上都是系统指令）
 * - ai-report-user.st（生成草稿的用户消息模板，含 {userInput} 占位符）
 */
@Slf4j
@Service
public class AIService {

    /** 摘要每个部分的最大长度：prompt 约定最多 5 条 × 50 字，留一点余量给编号和换行 */
    private static final int MAX_SUMMARY_LENGTH = 300;

    /** 周报数据查询走 WeeklyReportService，AIService 只负责拼提示词、调模型、收拾返回值 */
    private final WeeklyReportService weeklyReportService;
    private final MockUserService mockUserService;

    /**
     * 问答专用的记忆 Advisor。注意它**不是**注册在共用 chatClient 上的：
     * 另外四个入口（草稿/润色/摘要/团队汇总）都是「一次性、无上下文」的调用，
     * 一旦挂上记忆，它们会被塞进一堆与本次任务无关的历史消息里，纯属浪费 token。
     */
    private final MessageChatMemoryAdvisor memoryAdvisor;

    /**
     * 工具调用循环的 Advisor。为什么需要它：默认情况下工具是在 ChatModel 内部执行的
     * （OpenAiChatModel.internalStream 里自己递归），那些 tool 消息不经过 advisor 链，
     * AILogAdvisor 只能看到「一次请求 + 一个最终回答」，没法确认模型到底调没调工具、传了什么参数。
     * ToolCallAdvisor 把循环搬到 advisor 链上（同时关掉模型内部的工具执行），每轮往返都会过一遍日志。

     * disableMemory()：循环内的历史交给上面的 memoryAdvisor 管，不要两份。
     * suppressToolCallStreaming()：只把最终回答推给前端。显式写出来是因为 Builder 的字段默认值
     * 和它自己的 javadoc 说法不一致，不能赌——工具调用那一轮的 chunk 一旦漏进 SSE，
     * 前端会把工具调用的 JSON 当正文渲染出来。
     */
    private final ToolCallAdvisor toolCallAdvisor;

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

    /** 构造器加参数并保存字段 */
    private final VectorStore vectorStore;

    /**
     * 构造器注入：AIService 由 Spring 托管，启动时自动调用本构造器把依赖和提示词都装好。
     * 提示词以 Resource 注入、在这里一次性读成 String，之后每次请求复用内存里这一份。
     * 文件缺失会抛 IOException 让应用直接启动失败（fail-fast），而不是等用户点了按钮才报错。
     *
     * @param chatClientBuilder Spring AI 自动配置好的 ChatClient 建造器（可定制模型/超时等）
     * @throws IOException 提示词文件缺失或读取失败时抛出，导致应用启动失败
     */
    public AIService(ChatClient.Builder chatClientBuilder,
                     WeeklyReportService weeklyReportService,
                     MockUserService mockUserService,
                     ChatMemory chatMemory,
                     VectorStore vectorStore,
                     @Value("classpath:prompts/ai-report-system.txt") Resource systemPromptResource,
                     @Value("classpath:prompts/ai-polish-system.txt") Resource systemPolishPromptResource,
                     @Value("classpath:prompts/ai-summary-system.txt") Resource systemSummaryPromptResource,
                     @Value("classpath:prompts/ai-team-summary-system.txt") Resource systemTeamSummaryPromptResource,
                     @Value("classpath:prompts/ai-chat-system.txt") Resource systemChatPromptResource,
                     @Value("classpath:prompts/ai-report-user.st") Resource userPromptResource) throws IOException {
        // 日志 Advisor 挂在共用 client 上，五个入口的请求都会打出来。
        // order = 0 > memory 的默认 order，故意排在它后面，这样能看到记忆注入后的完整消息
        this.chatClient = chatClientBuilder.defaultAdvisors(new AILogAdvisor(0)).build();
        this.weeklyReportService = weeklyReportService;
        this.mockUserService = mockUserService;
        // ChatMemory 不用自己 new：spring-ai-starter-model-openai 会传递引入
        // spring-ai-autoconfigure-model-chat-memory，ChatMemoryAutoConfiguration 已经准备好了
        // ChatMemoryRepository（InMemoryChatMemoryRepository）+ ChatMemory（MessageWindowChatMemory，窗口 20 条）。
        // 想改窗口大小，或换成落库的仓储，自己定义一个 @Bean ChatMemory 覆盖掉就行（它带 @ConditionalOnMissingBean）
        this.memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();
        this.toolCallAdvisor = ToolCallAdvisor.builder()
                .disableMemory()
                .suppressToolCallStreaming()
                .build();
        this.vectorStore = vectorStore;
        this.systemPrompt = systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
        this.systemPolishPrompt = systemPolishPromptResource.getContentAsString(StandardCharsets.UTF_8);
        this.systemSummaryPrompt = systemSummaryPromptResource.getContentAsString(StandardCharsets.UTF_8);
        this.systemTeamSummaryPrompt = systemTeamSummaryPromptResource.getContentAsString(StandardCharsets.UTF_8);
        this.systemChatPrompt = systemChatPromptResource.getContentAsString(StandardCharsets.UTF_8);
        this.userPromptTemplate = new PromptTemplate(userPromptResource.getContentAsString(StandardCharsets.UTF_8));
    }

    /**
     * 统一发一次「结构化输出」请求：系统指令 + 用户消息 → DTO。四个入口共用这一条路。
     * 两类失败要分开报，因为用户能做的事不一样：
     * - RestClientException：网络超时、鉴权失败、额度用尽等，属于「服务不可用」，稍后重试有意义；
     * - 其它 RuntimeException：`.entity()` 拿到回答后在本地做 JSON→DTO 转换，转换失败说明模型没按格式输出，
     * 重试多少次都一样。（HTTP 在 `.call()` 里就发完了，所以这两类异常的来源不会串。）
     *
     * @param errorLabel 日志里区分是哪个入口出的问题，如「草稿」「团队汇总」
     *                   <T>位于 private 和返回值之间，是泛型声明。它告诉编译器：“本方法内部要使用一个类型参数，名字叫 T，具体是什么类型，调用时再确定。”
     *                   T 位于 <T> 后面，是返回类型。意思是“这个方法会返回一个 T 类型的对象。”
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
        if (dto == null || (isBlank(dto.getOverallProgress()) && isBlank(dto.getWeeklyWorkReport())
                && isBlank(dto.getNextWeekPlan()))) {
            throw new IllegalArgumentException("AI 生成内容为空，请补充更多工作描述后重试");
        }
        return dto;
    }

    /**
     * 把草稿/润色结果的四个字段各截到 ReportTextFormatter.MAX_FIELD_LENGTH（1000 字）。

     * 为什么必须在 .entity() 之后手动做：.entity() 只负责把模型输出的 JSON 反序列化成 DTO，不做长度校验，
     * 而模型偶尔会不守提示词里「每个字段不超过 1000 字」的约定；这些内容会回填表单再落库，
     * 超长会在保存时被 validateContent 拦下（WeeklyReportServiceImpl:49），用户只能自己删——不如在这里就切掉。
     */
    private void limitReportFields(WeeklyReportDTO dto) {
        if (dto == null) {
            return;
        }
        dto.setOverallProgress(ReportTextFormatter.limit(dto.getOverallProgress()));
        dto.setWeeklyWorkReport(ReportTextFormatter.limit(dto.getWeeklyWorkReport()));
        dto.setNextWeekPlan(ReportTextFormatter.limit(dto.getNextWeekPlan()));
        dto.setOther(ReportTextFormatter.limit(dto.getOther()));
    }

    /**
     * 摘要/团队汇总的三个部分各截到 MAX_SUMMARY_LENGTH：提示词只约定「最多 5 条 × 50 字」，模型不一定守
     */
    private void limitSummaryFields(WeeklySummaryDTO dto) {
        if (dto == null) {
            return;
        }
        dto.setAchievements(ReportTextFormatter.limit(dto.getAchievements(), MAX_SUMMARY_LENGTH));
        dto.setIssues(ReportTextFormatter.limit(dto.getIssues(), MAX_SUMMARY_LENGTH));
        dto.setSuggestions(ReportTextFormatter.limit(dto.getSuggestions(), MAX_SUMMARY_LENGTH));
    }

    /**
     * 把模型可能「顺手补写」的字段清掉：以用户原始输入为准，原来为空的字段在结果里一律置为空串。

     * 为什么需要这一步：润色只把非空字段拼进 prompt，模型从没见过空字段的内容，
     * 但 .entity(WeeklyReportDTO.class) 是照整个类生成 JSON schema 的，四个键都要求出现，
     * 于是模型偶尔会给那个「必须存在」的键编点内容。提示词里其实已经写了
     * 「凡是在用户输入中未出现的字段，输出中一律返回空字符串」（见 ai-polish-system.txt），
     * 但那只是约定、不是保证——长度上限、空返回这些约定，代码里也都各留了一道兜底。

     * 为什么必须由服务端兜住：模型编出来的是「静默污染」。前端 renderPolishDiff 只跳过空值和没变化的字段，
     * 编出来的非空内容会被当成一条正常差异展示，用户勾选后就写进表单并落库——
     * 等于润色凭空新增了原文没有的事实，而用户完全看不出来（其它几种失败用户至少能感知到）。

     * 为什么以「用户原始输入」当白名单、而不是比对前后差异：服务端手里唯一的真相就是用户提交的那一份，
     * 直接判断「这个字段原来空不空」最直白，也不用去猜某个变化是否合法。

     * 调用点必须「先 mask，再判空」：极端情况下 mask 完四个字段全空，说明模型其实什么都没留下，该报错；
     * 若先判空，这份全空结果会被放过去，前端只会显示「AI 认为内容无需修改」，把失败伪装成没事。
     *
     * @param source 用户提交的原始四字段（白名单依据）
     * @param target 模型返回的四字段（就地修改）
     */
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
     * AI 润色：把用户已填写的字段交给模型优化措辞，返回润色后的字段 。
     * 与生成草稿不同，润色允许只填一部分（至少一项即可），且未填字段不会发给模型，
     * 返回结果里这些字段会被强制置空（见 maskEmptyFields），前端只回填「原来填过的」字段。
     *
     * @param report 用户当前表单里的四字段内容（允许部分为空）
     * @return 润色后的字段，未填字段为空串
     * @throws IllegalArgumentException 四个字段全空，或模型没吐出可用内容时抛出
     */
    public WeeklyReportDTO polishReport(WeeklyReportDTO report) {
        if (report == null || allBlank(report)) {
            throw new IllegalArgumentException("请至少填写一项周报内容后再润色");
        }

        // 只把已填字段发给模型；空字段不出现，模型就没机会（也不该）脑补它们
        String userText = "【待润色周报】\n" + formatFilledFields(report);

        // 发起对话，让大模型结构化输出
        WeeklyReportDTO dto = callForEntity(systemPolishPrompt, new UserMessage(userText),
                WeeklyReportDTO.class, "润色");
        // 截断保护，每个字段不超过1000个字
        limitReportFields(dto);
        // 未填字段强制置空
        maskEmptyFields(report, dto);
        // 顺序不能颠倒：mask 完有可能四个字段全空，那时应该报"没返回内容"（理由见 maskEmptyFields 的注释）
        if (dto == null || allBlank(dto)) {
            throw new IllegalArgumentException("AI未返回内容，请稍后重试");
        }
        return dto;
    }

    /**
     * 判断单个字符串是否为 null 或纯空白
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 判断 DTO 四个字段是否全为空
     */
    private boolean allBlank(WeeklyReportDTO report) {
        return isBlank(report.getOverallProgress())
                && isBlank(report.getWeeklyWorkReport())
                && isBlank(report.getNextWeekPlan())
                && isBlank(report.getOther());
    }

    /**
     * 只把非空字段拼成"标签：内容"行；空字段直接省略（润色专用，避免空字段干扰模型）
     */
    private String formatFilledFields(WeeklyReportDTO report) {
        StringBuilder sb = new StringBuilder();
        appendFilled(sb, "总体进度", report.getOverallProgress());
        appendFilled(sb, "本周进展", report.getWeeklyWorkReport());
        appendFilled(sb, "下周目标", report.getNextWeekPlan());
        appendFilled(sb, "其他补充", report.getOther());
        return sb.toString();
    }

    /**
     * 仅当字段有内容时才追加一行；字段 trim 后写入，去掉首尾多余空白
     */
    private void appendFilled(StringBuilder sb, String label, String value) {
        if (!isBlank(value)) {
            sb.append(label).append("：").append(value.trim()).append('\n');
        }
    }

    /**
     * 总结某用户最近 4 周已提交的周报。
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
        // 拼接总量受 ReportTextFormatter.MAX_PROMPT_REPORT_CHARS 约束，见 ReportTextFormatter#context
        String userText = ReportTextFormatter.context(reportList, true);

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

        // 每个成员一段，标题是成员名；正文块格式与摘要/问答共用 ReportTextFormatter#block，免得各写一份
        StringBuilder sb = new StringBuilder();
        for (WeeklyReport report : submitted) {
            String userName = mockUserService.getUserName(report.getUserId());
            sb.append(ReportTextFormatter.block(report,
                    "【" + (userName != null ? userName : "成员" + report.getUserId()) + "】"));
        }
        String userText = sb.toString();

        // 这里用 TeamSummaryAI 而不是直接反序列化成 TeamSummaryDTO：.entity() 会把类的字段做成 schema 塞进 prompt，
        // 多出来的 missingMembers 会诱使模型自己去编一份「未提交名单」，而那本该由系统算
        TeamSummaryAI ai = callForEntity(systemTeamSummaryPrompt, new UserMessage(userText),
                TeamSummaryAI.class, "团队汇总");
        TeamSummaryDTO dto = new TeamSummaryDTO();
        if (ai != null) {
            dto.setTeamProgress(ReportTextFormatter.limit(ai.getTeamProgress(), MAX_SUMMARY_LENGTH));
            dto.setCommonIssues(ReportTextFormatter.limit(ai.getCommonIssues(), MAX_SUMMARY_LENGTH));
            dto.setCollaborationNeeds(ReportTextFormatter.limit(ai.getCollaborationNeeds(), MAX_SUMMARY_LENGTH));
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
     * 对话式周报问答（流式）：让模型通过 ReportQueryTools 按需查该用户在指定时间范围内的周报，再连同问题交给它，
     * 回答边生成边以 SSE 推给前端。与其它入口不同，这里要的是给用户直接看的自由文本，
     * 所以不要求 JSON，也不做截断。
     *
     * 异常分两段处理，这是流式接口的核心约束：
     * - 开流之前（本方法执行期间）抛出的异常还能变成 HTTP 状态码——同步抛出 →
     *   GlobalExceptionHandler → 400/503 的 JSON；
     * - 开流之后（返回的 Flux 被订阅之后）状态码已经发出去了，异常只能由 onErrorResume 降级成流里的一段文本。
     *
     * @param request 问答请求（userId、question 必填，startDate/endDate 可选）
     * @return 增量文本的流；区间内没有已提交周报时同样走模型（由工具返回结果告知用户），不再返回固定文案
     * @throws IllegalArgumentException 开始时间晚于结束时间时抛出（开流前，会变成 400）
     */
    public Flux<String> chatWithReports(ChatRequest request) {
        // 不传日期默认查最近 4 周（本周 + 前 3 周），与个人摘要的时间口径一致
        LocalDate end = request.getEndDate() != null ? request.getEndDate() : LocalDate.now();
        LocalDate start = request.getStartDate() != null
                ? request.getStartDate()
                : DateUtils.getMondayOfWeek(end).minusWeeks(3);
        if (start.isAfter(end)) {
            // 同步校验失败，直接返回一个包含错误消息的流
            throw new IllegalArgumentException("开始时间不能晚于结束时间");
        }
        // week_start_date 存的是周一：起始日也对齐到所在周的周一，否则会整周漏查
        start = DateUtils.getMondayOfWeek(start);

        // 这里故意不做「区间内没有周报就直接返回固定文案」的提前返回：
        // 那条捷径会绕过 chatClient.prompt()，也就绕过了挂在链上的 MessageChatMemoryAdvisor——
        // 记忆的写入点正是它的 before()（写用户消息）和 after()（写回答），没进链就等于这一轮没发生过。
        // 用户接着追问「那上个月呢」时，模型看不到上一轮问过「有没有」，只能从零理解。
        // 代价是「没数据」这一轮也要多走 1~2 次模型往返；空区间由 ReportQueryTools 告知模型后自然作答。

        // 周报原文不再拼进 prompt：改成让模型用 ReportQueryTools 按需查，token 只花在真正被读到的那几周上。
        // 但它仍然不能进用户消息——记忆只沉淀 user / assistant 两种消息（SystemMessage 不进记忆），
        // 原文一旦拼进 user 消息就会被逐轮存进记忆，第 5 轮时模型要读 5 份完整原文，token 和延迟都是平方级上涨。

        // 会话隔离：一个用户一条会话。
        String conversationId = "user:" + request.getUserId();

        // 关键改动：使用.stream().content()得到Flux<String>
        return chatClient.prompt()
                .system(systemChatPrompt)
                // 用户消息里只留问题本身
                // 外层 .advisors()：“我要开始配置 Advisor了。“
                // 内层 .advisors()：“把这些 Advisor 加进来。”
                .user(request.getQuestion())
                // start/end 是用户在界面上选的范围（没选时上面已算好默认值），钉进工具实例：
                // 模型看不到它，也就查不到范围外的数据——和 userId 是同一个原则
                .tools(new ReportQueryTools(request.getUserId(), start, end, weeklyReportService, vectorStore))
                .advisors(a -> a
                        .advisors(memoryAdvisor, toolCallAdvisor)
                        .param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content()
                .onErrorResume(e -> {  // 统一异常处理，转成业务错误
                    log.error("AI问答流式调用失败，异常类型：{}", e.getClass().getName(), e);
                    if (e instanceof RestClientException) {
                        return Flux.just("\n\n[AI服务暂时不可用，请稍后重试]");
                    }
                    return Flux.just("\n\n[AI返回异常，请稍后重试]");
                })
                .switchIfEmpty(Flux.just("AI未返回内容，请稍后重试")); // 整个流为空时的兜底
    }
}
