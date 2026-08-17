package com.practice.weeklyreportmanager.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.practice.weeklyreportmanager.dto.WeeklyReportSumbitDTO;
import com.practice.weeklyreportmanager.dto.WeeklyReportUpdateDTO;
import com.practice.weeklyreportmanager.entity.WeeklyReport;
import com.practice.weeklyreportmanager.mapper.WeeklyReportMapper;
import com.practice.weeklyreportmanager.service.WeeklyReportService;
import com.practice.weeklyreportmanager.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Slf4j
@Service
public class WeeklyReportServiceImpl implements WeeklyReportService {

    @Autowired
    private WeeklyReportMapper weeklyReportMapper;

    // ★ 私有校验方法（只在本类内部使用）
    private void validateContent(WeeklyReportSumbitDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("周报内容不能为空");
        }
        if (dto.getOverallProgress() == null || dto.getOverallProgress().trim().isEmpty()) {
            throw new IllegalArgumentException("总体进度不能为空");
        }
        if (dto.getWeeklyReportWork() == null || dto.getWeeklyReportWork().trim().isEmpty()) {
            throw new IllegalArgumentException("本周进展不能为空");
        }
        if (dto.getNextWeekPlan() == null || dto.getNextWeekPlan().trim().isEmpty()) {
            throw new IllegalArgumentException("下周目标不能为空");
        }
        if (dto.getOverallProgress().length() > 1000) {
            throw new IllegalArgumentException("总体进度不能超过1000字");
        }
        if (dto.getWeeklyReportWork().length() > 1000) {
            throw new IllegalArgumentException("本周进展不能超过1000字");
        }
        if (dto.getNextWeekPlan().length() > 1000) {
            throw new IllegalArgumentException("下周目标不能超过1000字");
        }
        if (dto.getOther() != null && dto.getOther().length() > 1000) {
            throw new IllegalArgumentException("其他补充不能超过1000字");
        }
    }

    // 1. 获取当前周周报
    @Override
    public WeeklyReport getCurrentWeekReport() {
        // 计算周一的日期
        LocalDate Monday = DateUtils.getMondayoOfWeek(LocalDate.now());
        log.info("查询周报，本周一日期：{}", Monday);
        // 用week_start_date查询数据库
        QueryWrapper<WeeklyReport> wrapper = new QueryWrapper<>();
        wrapper.eq("week_start_date", Monday);
        return weeklyReportMapper.selectOne(wrapper);
    }


    // 2. 保存周报（草稿），不校验周五
    @Override
    @Transactional
    public WeeklyReport saveWeeklyReport(WeeklyReportSumbitDTO dto) {
        // 参数校验
        validateContent(dto);

        LocalDate Monday = DateUtils.getMondayoOfWeek(LocalDate.now());
        String title = DateUtils.formatWeekTitle(Monday);

        // 校验周报是否已存在
        QueryWrapper<WeeklyReport> wrapper = new QueryWrapper<>();
        wrapper.eq("week_start_date", Monday);

        WeeklyReport existingReport = weeklyReportMapper.selectOne(wrapper);

        WeeklyReport report = new WeeklyReport();
        report.setWeekStartDate(Monday);
        report.setTitle(title);
        report.setOverallProgress(dto.getOverallProgress());
        report.setWeeklyReportWork(dto.getWeeklyReportWork());
        report.setNextWeekPlan(dto.getNextWeekPlan());
        report.setOther(dto.getOther());

        if (existingReport != null) {
            // 更新：保留原有状态和提交时间
            report.setId(existingReport.getId());
            report.setStatus(existingReport.getStatus());
            report.setSubmittedAt(existingReport.getSubmittedAt());

            weeklyReportMapper.updateById(report);
            log.info("更新周报成功，id：{}, week：{}", report.getId(), Monday);
        } else {
            // 插入：状态为编辑中
            report.setStatus("Editing");
            weeklyReportMapper.insert(report);
            log.info("插入周报成功，id：{}, week：{}", report.getId(), Monday);
        }
        return report;
    }


    @Override
    @Transactional
    public WeeklyReport submitWeeklyReport(WeeklyReportSumbitDTO dto) {
        validateContent(dto);

        LocalDate currentDate = LocalDate.now();
        DayOfWeek dayOfWeek = currentDate.getDayOfWeek();
        if (!dayOfWeek.equals(DayOfWeek.FRIDAY)) {
            throw new IllegalArgumentException("只有周五才能提交周报");
        }

        LocalDate Monday = DateUtils.getMondayoOfWeek(LocalDate.now());
        String title = DateUtils.formatWeekTitle(Monday);

        QueryWrapper<WeeklyReport> wrapper = new QueryWrapper<>();
        wrapper.eq("week_start_date", Monday);
        WeeklyReport existingReport = weeklyReportMapper.selectOne(wrapper);

        WeeklyReport report = new WeeklyReport();
        report.setWeekStartDate(Monday);
        report.setTitle(title);
        report.setOverallProgress(dto.getOverallProgress());
        report.setWeeklyReportWork(dto.getWeeklyReportWork());
        report.setNextWeekPlan(dto.getNextWeekPlan());
        report.setOther(dto.getOther());

        if (existingReport != null) {
            report.setId(existingReport.getId());
            report.setStatus("SUBMITTED");
            report.setSubmittedAt(LocalDateTime.now());

            weeklyReportMapper.updateById(report);
            log.info("更新并提交周报成功，id：{}", report.getId());
        } else {
            report.setStatus("SUBMITTED");
            weeklyReportMapper.insert(report);
            log.info("新建并提交周报成功，id：{}", report.getId());
        }
        return report;
    }


    @Override
    public Page<WeeklyReport> getReports(long pageNo, long pageSize, LocalDate start, LocalDate end) {
        if ((start != null && end == null) || (start == null && end != null)) {
            throw new IllegalArgumentException("开始时间和结束时间必须同时为空或同时不为空");
        }
        if (start != null && end != null && start.isAfter(end)) {
            throw new IllegalArgumentException("开始时间不能大于结束时间");
        }
        Page<WeeklyReport> page = new Page<>(pageNo, pageSize);
        QueryWrapper<WeeklyReport> wrapper = new QueryWrapper<>();

        if (start != null) {
            log.debug("查询条件：{}", wrapper.getCustomSqlSegment());
            wrapper.ge("week_start_date", start);
        }

        if (end != null) {
            log.debug("查询条件：{}", wrapper.getCustomSqlSegment());
            wrapper.le("week_start_date", end);
        }
        wrapper.orderByDesc("week_start_date");
        return weeklyReportMapper.selectPage(page, wrapper);
    }


    @Override
    public WeeklyReport getReportById(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id不能为空，必须存在");
        }
        QueryWrapper<WeeklyReport> wrapper = new QueryWrapper<>();
        wrapper.eq("id", id);
        WeeklyReport report = weeklyReportMapper.selectOne(wrapper);
        if (report == null) {
            throw new IllegalArgumentException("周报不存在");
        }
        return report;
    }


    @Override
    @Transactional
    public WeeklyReport updateWeeklyReport(WeeklyReportUpdateDTO dto, Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id不能为空，必须存在");
        }
        if (dto == null) {
            throw new IllegalArgumentException("周报内容不能为空");
        }

        if (dto.getOverallProgress() == null || dto.getOverallProgress().trim().isEmpty()) {
            throw new IllegalArgumentException("总体进度不能为空");
        }
        if (dto.getWeeklyReportWork() == null || dto.getWeeklyReportWork().trim().isEmpty()) {
            throw new IllegalArgumentException("本周进展不能为空");
        }
        if (dto.getNextWeekPlan() == null || dto.getNextWeekPlan().trim().isEmpty()) {
            throw new IllegalArgumentException("下周目标不能为空");
        }

        if (dto.getOverallProgress().length() > 1000) {
            throw new IllegalArgumentException("总体进度不能大于1000字");
        }
        if (dto.getWeeklyReportWork().length() > 1000) {
            throw new IllegalArgumentException("本周进展不能大于1000字");
        }
        if (dto.getNextWeekPlan().length() > 1000) {
            throw new IllegalArgumentException("下周目标不能大于1000字");
        }

        LocalDate Monday = DateUtils.getMondayoOfWeek(LocalDate.now());
        String title = DateUtils.formatWeekTitle(Monday);

        QueryWrapper<WeeklyReport> wrapper = new QueryWrapper<>();
        wrapper.eq("week_start_date", Monday);
        WeeklyReport existingReport = weeklyReportMapper.selectOne(wrapper);

        WeeklyReport report = new WeeklyReport();
        report.setWeekStartDate(Monday);
        report.setTitle(title);
        report.setOverallProgress(dto.getOverallProgress());
        report.setWeeklyReportWork(dto.getWeeklyReportWork());
        report.setNextWeekPlan(dto.getNextWeekPlan());
        report.setOther(dto.getOther());

        if (existingReport != null) {
            report.setId(existingReport.getId());
            report.setStatus(existingReport.getStatus());
            report.setUpdatedAt(LocalDateTime.now());

            weeklyReportMapper.updateById(report);
            log.info("周报更新成功，id：{}, week：{}", report.getId(), Monday);
        }
        return report;
    }
}
