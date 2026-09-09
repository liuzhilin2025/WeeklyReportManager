package com.practice.weeklyreportmanager.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * AI 辅助撰写的请求体：前端把用户输入的"本周工作内容"传过来
 */
@Data
public class AIDraftRequest {

    @NotBlank(message = "请先填写本周工作内容")
    private String input;
}
