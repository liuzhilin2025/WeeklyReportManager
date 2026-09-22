package com.practice.weeklyreportmanager.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import reactor.core.publisher.Flux;

import java.util.Map;
/**
 * CallAdvisor : 拦截同步调用（.call()）
 * StreamAdvisor : 拦截流式调用（.stream()）
 */
@Slf4j
public class AILogAdvisor implements CallAdvisor, StreamAdvisor {
    /** 单条内容最多打这么多字：周报上下文动辄几千字，全打会把控制台刷爆 */
    private  static final int MAX_LOG_CHARS = 400;

    private final int order;

    public AILogAdvisor(int order) {
        this.order = order;
    }

    @Override
    public String getName() {
        return "AILogAdvisor";
    }

    @Override
    public int getOrder() {
        return this.order;
    }

    /**
     * adviseCall（同步调用拦截）
     * chain.nextCall(request)是关键 —— 它把请求交给链上的下一个Advisor，最终到达模型
     * 如果不调用nextCall，模型就不会被调用
     */
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        logRequest(request);   // 1. 记录请求
        // 2. 放行，执行真正的模型调用
        ChatClientResponse response = chain.nextCall(request);
        logResponse(response); // 3. 记录响应
        return response;       // 4. 原样返回
    }

    /**
     * adviseStream（流式调用拦截）
     * 这里用了 ChatClientMessageAggregator:
     * 它会在内部把所有分片聚合起来，拼成一条完整的响应。
     * 等流全部结束后，调用 this::logResponse 打印完整内容。
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        logRequest(request);
        Flux<ChatClientResponse> responses = chain.nextStream(request);
        // 流式响应是一片片回来的，聚合成完整响应再打一次，否则日志里只有第一片
        return new ChatClientMessageAggregator().aggregateChatClientResponse(responses, this::logResponse);
    }

    /**
     * logRequest（打印请求）
     * request.prompt().getInstructions() ———— 所有发给模型的消息
     * 会按角色打印，比如：
     * │ [SYSTEM] 你是一名专业的周报助手...
     * │ [USER] 我上周做了什么？
     * │ [ASSISTANT] 你上周完成了...
     * │ [USER] 那下周计划呢？
     *
     * request.context() ———— 上下文参数
     */
    private void logRequest(ChatClientRequest request) {
        StringBuilder sb = new StringBuilder("\n┌─ Advisor 链：请求 → 模型 ───────────────");

        for (Message message : request.prompt().getInstructions()) {
            sb.append("\n│ [").append(message.getMessageType()).append("] ")
                    .append(abbreviate(message.getText()));

            // 工具调用与工具结果都不在 getText() 里：
            // ASSISTANT 轮的 tool_calls 在 getToolCalls()，TOOL 轮的返回在 getResponses()。
            // 不单独打印的话，日志里只会剩一个空白的 [TOOL]，看不出模型调了什么、传了什么。
            if (message instanceof AssistantMessage assistant && !assistant.getToolCalls().isEmpty()) {
                for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                    sb.append("\n│   ↳ 调用 ").append(call.name())
                            .append("(").append(abbreviate(call.arguments())).append(")");
                }
            }
            if (message instanceof ToolResponseMessage toolResp) {
                for (ToolResponseMessage.ToolResponse r : toolResp.getResponses()) {
                    sb.append("\n│   ↳ ").append(r.name()).append(" 返回：")
                            .append(abbreviate(r.responseData()));
                }
            }
        }

        // .entity() 生成的 JSON Schema 不在 message 里，而是挂在 request.context() 上，
        // 由链尾的 ChatModelCallAdvisor 拼进系统消息——所以只看 messages 是看不到它的。
        // 顺便这里也能看到 chat_memory_conversation_id，用来确认会话隔离传对了
        for (Map.Entry<String, Object> entry : request.context().entrySet()) {
            sb.append("\n│ (context) ").append(entry.getKey())
                    .append(" = ").append(abbreviate(String.valueOf(entry.getValue())));
        }

        sb.append("\n└─────────────────────────────────────────");
        log.info(sb.toString());
    }

    /**
     * logResponse（打印响应）
     * 先判空，避免 NullPointerException
     * 然后从响应里取出模型返回的文本，打印出来
     * 空响应也会打印 “（空响应）”，方便排查是否是模型超时或异常
     */
    private void logResponse(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null
                || response.chatResponse().getResults().isEmpty()) {
            log.info("\n┌─ Advisor 链：模型 → 响应 ───────────────\n│ （空响应）\n└─────────────────────────────────────────");
            return;
        }
        String text = response.chatResponse().getResult().getOutput().getText();
        log.info("\n┌─ Advisor 链：模型 → 响应 ───────────────\n│ {}\n└─────────────────────────────────────────",
                abbreviate(text));
    }

    /** 压成一行并截断：日志里要的是「大致收到了什么」，不是完整正文 */
    private String abbreviate(String text) {
        // null安全：返回(null), 不报错
        if (text == null) {
            return "(null)";
        }
        // 压成一行：把 \r 去掉，把 \n 换成 ↵ 符号。因为日志是多行的，如果每个消息自带换行符，控制台日志会被刷爆
        String flat = text.replace("\r", "").replace("\n", "⏎");
        // 超长截断
        return flat.length() <= MAX_LOG_CHARS
                ? flat
                : flat.substring(0, MAX_LOG_CHARS) + "…（原文 " + flat.length() + " 字）";
    }
}
