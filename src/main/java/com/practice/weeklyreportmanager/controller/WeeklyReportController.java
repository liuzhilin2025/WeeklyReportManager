package com.practice.weeklyreportmanager.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.practice.weeklyreportmanager.common.Result;
import com.practice.weeklyreportmanager.dto.AIDraftRequest;
import com.practice.weeklyreportmanager.dto.WeeklyReportDTO;
import com.practice.weeklyreportmanager.entity.WeeklyReport;
import com.practice.weeklyreportmanager.service.AIService;
import com.practice.weeklyreportmanager.service.WeeklyReportService;
import com.practice.weeklyreportmanager.vo.WeekGroupVO;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/reports")
public class WeeklyReportController {

    private final WeeklyReportService weeklyReportService;
    private final AIService aiService;

    public WeeklyReportController(WeeklyReportService weeklyReportService, AIService aiService) {
        this.weeklyReportService = weeklyReportService;
        this.aiService = aiService;
    }
    @GetMapping("/current")
    public Result<WeeklyReport> getCurrentWeekReport(@RequestParam(required = false) Long userId) {
        Long effectiveUserId = userId != null ? userId : 1L;
        WeeklyReport report = weeklyReportService.getCurrentWeekReport(effectiveUserId);
        return Result.success(report);
    }

    @PostMapping("/save")
    public Result<WeeklyReport> saveWeeklyReport(
            @RequestBody WeeklyReportDTO dto,
            @RequestParam(required = false) Long userId) {
        // 如果前端传了 userId 就用，否则默认 1
        Long effectiveUserId = userId != null ? userId : 1L;
        WeeklyReport report = weeklyReportService.saveWeeklyReport(dto, effectiveUserId);
        return Result.success(report);
    }

    @PostMapping("/submit")
    public Result<WeeklyReport> submitWeeklyReport(
            @RequestBody WeeklyReportDTO dto,
            @RequestParam(required = false) Long userId) {
        Long effectiveUserId = userId != null ? userId : 1L;
        WeeklyReport report = weeklyReportService.submitWeeklyReport(dto, effectiveUserId);
        return Result.success(report);
    }

    @GetMapping("/history")
    public Result<Page<WeeklyReport>> getReports(
            @RequestParam(required = false, defaultValue = "1") Long userId,
            @RequestParam(defaultValue = "1") Integer pageNo,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate endDate) {
        Page<WeeklyReport> page = weeklyReportService.getReports(userId, pageNo, pageSize,startDate, endDate);
        return Result.success(page);
    }

    @GetMapping("/{id}")
    public Result<WeeklyReport> getReportById(@PathVariable Long id) {
        WeeklyReport report = weeklyReportService.getReportById(id);
        return Result.success(report);
    }

    @PutMapping("/{id}")
    public Result<WeeklyReport> updateWeeklyReport(
            @PathVariable Long id,
            @RequestBody WeeklyReportDTO dto,
            @RequestParam(required = false) Long userId) {
        WeeklyReport report = weeklyReportService.updateWeeklyReport(dto, id, userId);
        return Result.success(report);
    }

    @PostMapping("/{id}/submit")
    public Result<WeeklyReport> submitHistoryWeekly(
            @PathVariable Long id,
            @RequestBody WeeklyReportDTO dto,
            @RequestParam(required = false) Long userId) {
        WeeklyReport report = weeklyReportService.submitHistoryWeekly(dto, id, userId);
        return Result.success(report);
    }

    /**
     * 团队视图：按周分组展示所有成员的周报状态
     * 示例：GET /api/reports/team?startDate=2026-08-01&endDate=2026-08-20&pageNo=1&pageSize=10
     */
    @GetMapping("/team")
    public Result<Page<WeekGroupVO>> getTeamViewReports(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate endDate,
            @RequestParam(defaultValue = "1") Integer pageNo,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        // 如果 startDate 和 endDate 都为空，查所有历史数据（或者给个默认范围）
        // 这里建议直接调用 Service，Service 里判断 null 并做相应处理
        Page<WeekGroupVO> page = weeklyReportService.getTeamViewReports(startDate, endDate, pageNo, pageSize);
        return Result.success(page);
    }

    /**
     * AI 辅助撰写：根据用户输入的本周工作关键词/记录，生成结构化周报草稿，前端解析后自动填入表单
     */
    @PostMapping("/ai/draft")
    public Result<WeeklyReportDTO> generateDraft(@Valid @RequestBody AIDraftRequest request) {
        WeeklyReportDTO draft = aiService.generateDraft(request.getInput());
        return Result.success(draft);
    }

    /**
     * AI 完整性检查：按四个字段（总体进度/本周进展/下周目标/其他补充）分别判断，
     * 并自动带上该用户上周的周报供模型做"承接关系"比对。
     */
    @PostMapping("/ai/check")
    public Result<String> checkCompleteness(@RequestBody WeeklyReportDTO dto,
                                            @RequestParam(required = false) Long userId) {
        Long effectiveUserId = userId != null ? userId : 1L;
        WeeklyReport lastWeekReport = weeklyReportService.getLastWeekReport(effectiveUserId);
        return Result.success(aiService.checkCompleteness(dto, lastWeekReport));
    }

    @PostMapping("/ai/polish")
    public Result<WeeklyReportDTO> polishReport(@RequestBody WeeklyReportDTO dto) {
        return Result.success(aiService.polishReport(dto));
    }
}
