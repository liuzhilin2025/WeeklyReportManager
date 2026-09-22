package com.practice.weeklyreportmanager.dto;

import java.time.LocalDate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 周报问答请求体：问谁（userId）、问什么（question），以及可选的时间范围（startDate ~ endDate）
 */
@Data
public class ChatRequest {

    @NotNull(message = "userId不能为空")
    private Long userId;

    @NotBlank(message = "请输入要询问的问题")
    private String question;

    private LocalDate startDate;
    private LocalDate endDate;
}
