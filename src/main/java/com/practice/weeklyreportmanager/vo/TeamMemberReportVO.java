package com.practice.weeklyreportmanager.vo;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class TeamMemberReportVO {
    private Long userId;
    private String userName;
    private Long reportId;          // null 表示未提交
    private String status;          // SUBMITTED / NOT_SUBMITTED
    private LocalDateTime updateTime;
    private LocalDateTime submittedAt;   // 已提交时的提交时间
}