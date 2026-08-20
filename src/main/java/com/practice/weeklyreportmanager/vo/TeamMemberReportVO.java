package com.practice.weeklyreportmanager.vo;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class TeamMemberReportVO {
    private Long userId;
    private String userName;
    private Long reportId;          // null 表示未提交
    private String status;          // EDITING / SUBMITTED / NOT_SUBMITTED
    private LocalDateTime updateTime;
}