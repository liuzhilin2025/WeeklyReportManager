package com.practice.weeklyreportmanager.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.practice.weeklyreportmanager.dto.WeeklyReportDTO;
import com.practice.weeklyreportmanager.entity.WeeklyReport;
import com.practice.weeklyreportmanager.vo.WeekGroupVO;

import java.time.LocalDate;
import java.util.List;

public interface WeeklyReportService {
    WeeklyReport getCurrentWeekReport(Long userId);
    WeeklyReport getLastWeekReport(Long userId);
    WeeklyReport saveWeeklyReport(WeeklyReportDTO dto, Long userId);
    WeeklyReport submitWeeklyReport(WeeklyReportDTO dto, Long userId);
    Page<WeeklyReport> getReports(Long userId,Integer pageNo, Integer pageSize, LocalDate startDate, LocalDate endDate);
    WeeklyReport getReportById(Long id);
    WeeklyReport updateWeeklyReport(WeeklyReportDTO dto, Long id, Long userId);
    WeeklyReport submitHistoryWeekly(WeeklyReportDTO dto, Long id, Long userId);
    Page<WeekGroupVO> getTeamViewReports(LocalDate startDate, LocalDate endDate, Integer pageNo, Integer pageSize);
    List<WeeklyReport> getSubmittedReportsBetween(Long userId, LocalDate startMonday, LocalDate endMonday);
    List<WeeklyReport> getSubmittedReportOfWeek(LocalDate Monday);
}
