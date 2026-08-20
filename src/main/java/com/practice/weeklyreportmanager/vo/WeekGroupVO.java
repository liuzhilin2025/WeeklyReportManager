package com.practice.weeklyreportmanager.vo;

import lombok.Data;
import java.time.LocalDate;
import java.util.List;

@Data
public class WeekGroupVO {
    private LocalDate weekStartDate;
    private String weekTitle;              // "周报0810~0814"
    private List<TeamMemberReportVO> members;
}