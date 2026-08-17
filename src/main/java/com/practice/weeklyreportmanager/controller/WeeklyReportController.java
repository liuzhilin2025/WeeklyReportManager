package com.practice.weeklyreportmanager.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.practice.weeklyreportmanager.common.Result;
import com.practice.weeklyreportmanager.dto.WeeklyReportSumbitDTO;
import com.practice.weeklyreportmanager.dto.WeeklyReportUpdateDTO;
import com.practice.weeklyreportmanager.entity.WeeklyReport;
import com.practice.weeklyreportmanager.service.WeeklyReportService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/reports")
public class WeeklyReportController {

    @Autowired
    private WeeklyReportService weeklyReportService;

    @GetMapping("/current")
    public Result<WeeklyReport> getCurrentWeekReport() {
        WeeklyReport report = weeklyReportService.getCurrentWeekReport();
        return Result.success(report);
    }

    @PostMapping("/save")
    public Result<WeeklyReport> saveWeeklyReport(@RequestBody @Valid WeeklyReportSumbitDTO dto) {
        WeeklyReport report = weeklyReportService.saveWeeklyReport(dto);
        return Result.success(report);
    }

    @PostMapping("/submit")
    public Result<WeeklyReport> submitWeeklyReport(@RequestBody @Valid WeeklyReportSumbitDTO dto) {
        WeeklyReport report = weeklyReportService.submitWeeklyReport(dto);
        return Result.success(report);
    }

    @GetMapping("/history")
    public Result<Page<WeeklyReport>> getReports(
            @RequestParam(defaultValue = "1") long pageNo,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate end) {
        Page<WeeklyReport> page = weeklyReportService.getReports(pageNo, pageSize, start, end);
        return Result.success(page);
    }

    @GetMapping("/{id}")
    public Result<WeeklyReport> getReportById(@PathVariable Long id) {
        WeeklyReport report = weeklyReportService.getReportById(id);
        return Result.success(report);
    }

    @PutMapping("/{id}")
    public Result<WeeklyReport> updateWeeklyReport(@RequestBody @Valid @PathVariable Long id, WeeklyReportUpdateDTO dto) {
        WeeklyReport report = weeklyReportService.getReportById(id);
        if (report != null) {
            weeklyReportService.updateWeeklyReport(dto, id);
        }
        return Result.success(report);
    }

}
