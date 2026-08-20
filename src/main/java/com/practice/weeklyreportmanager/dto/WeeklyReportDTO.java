package com.practice.weeklyreportmanager.dto;

import lombok.Data;

@Data
public class WeeklyReportDTO {  // 前端传入后端的请求体
    private String overallProgress;   // 总体进度
    private String weeklyWorkReport;   // 本周进展
    private String nextWeekPlan;   // 下周目标
    private String other;   // 其他（可空）
}
