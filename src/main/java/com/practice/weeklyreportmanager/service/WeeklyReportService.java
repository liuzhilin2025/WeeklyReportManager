package com.practice.weeklyreportmanager.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.practice.weeklyreportmanager.dto.WeeklyReportDTO;
import com.practice.weeklyreportmanager.entity.WeeklyReport;
import com.practice.weeklyreportmanager.vo.WeekGroupVO;

import java.time.LocalDate;

public interface WeeklyReportService {
    WeeklyReport getCurrentWeekReport(Long userId);
    WeeklyReport saveWeeklyReport(WeeklyReportDTO dto, Long userId);
    WeeklyReport submitWeeklyReport(WeeklyReportDTO dto, Long userId);
    Page<WeeklyReport> getReports(Long userId,Integer pageNo, Integer pageSize, LocalDate startDate, LocalDate endDate);
    WeeklyReport getReportById(Long id);
    WeeklyReport updateWeeklyReport(WeeklyReportDTO dto, Long id);
    WeeklyReport submitHistoryWeekly(WeeklyReportDTO dto, Long id);
    Page<WeekGroupVO> getTeamViewReports(LocalDate startDate, LocalDate endDate, Integer pageNo, Integer pageSize);
}
