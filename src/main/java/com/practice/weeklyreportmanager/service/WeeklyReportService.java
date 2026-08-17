package com.practice.weeklyreportmanager.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.practice.weeklyreportmanager.dto.WeeklyReportSumbitDTO;
import com.practice.weeklyreportmanager.dto.WeeklyReportUpdateDTO;
import com.practice.weeklyreportmanager.entity.WeeklyReport;

import java.time.LocalDate;

public interface WeeklyReportService {
    WeeklyReport getCurrentWeekReport();
    WeeklyReport saveWeeklyReport(WeeklyReportSumbitDTO dto);
    WeeklyReport submitWeeklyReport(WeeklyReportSumbitDTO dto);
    Page<WeeklyReport> getReports(long pageNo, long pageSize, LocalDate start, LocalDate end);
    WeeklyReport getReportById(Long id);
    WeeklyReport updateWeeklyReport(WeeklyReportUpdateDTO dto, Long id);
}
