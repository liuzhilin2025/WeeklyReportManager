package com.practice.weeklyreportmanager.dto;

import lombok.Data;

@Data
public class WeeklyReportUpdateDTO {
    private String overallProgress;   // 总体进度
    private String weeklyReportWork;   // 本周进展
    private String nextWeekPlan;   // 下周目标
    private String other;   // 其他（可空）
}
