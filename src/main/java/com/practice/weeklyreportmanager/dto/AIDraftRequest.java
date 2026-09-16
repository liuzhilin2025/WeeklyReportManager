package com.practice.weeklyreportmanager.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI 辅助撰写的请求体：前端把用户输入的"本周工作内容"传过来
 */
@Data
public class AIDraftRequest {

    @NotBlank(message = "请先填写本周工作内容")
    @Size(max = 2000, message = "描述太长了，请精简到 2000 字以内")
    private String input;
}
