package com.practice.weeklyreportmanager.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.practice.weeklyreportmanager.dto.WeeklyReportDTO;
import com.practice.weeklyreportmanager.entity.WeeklyReport;
import com.practice.weeklyreportmanager.mapper.WeeklyReportMapper;
import com.practice.weeklyreportmanager.service.MockUserService;
import com.practice.weeklyreportmanager.service.WeeklyReportService;
import com.practice.weeklyreportmanager.utils.DateUtils;
import com.practice.weeklyreportmanager.vo.TeamMemberReportVO;
import com.practice.weeklyreportmanager.vo.WeekGroupVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.rmi.MarshalledObject;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
public class WeeklyReportServiceImpl implements WeeklyReportService {

    @Autowired
    private WeeklyReportMapper weeklyReportMapper;

    @Autowired
    private MockUserService mockUserService;

    // 私有校验方法（只在本类内部使用）
    private void validateContent(WeeklyReportDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("周报内容不能为空");
        }
        if (dto.getOverallProgress() == null || dto.getOverallProgress().trim().isEmpty()) {
            throw new IllegalArgumentException("总体进度不能为空");
        }
        if (dto.getWeeklyWorkReport() == null || dto.getWeeklyWorkReport().trim().isEmpty()) {
            throw new IllegalArgumentException("本周进展不能为空");
        }
        if (dto.getNextWeekPlan() == null || dto.getNextWeekPlan().trim().isEmpty()) {
            throw new IllegalArgumentException("下周目标不能为空");
        }
        if (dto.getOverallProgress().length() > 1000) {
            throw new IllegalArgumentException("总体进度不能超过1000字");
        }
        if (dto.getWeeklyWorkReport().length() > 1000) {
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
    public WeeklyReport getCurrentWeekReport(Long userId) {
        // 计算周一的日期
        LocalDate Monday = DateUtils.getMondayOfWeek(LocalDate.now());
        log.info("查询周报，本周一日期：{}", Monday);
        // 用week_start_date查询数据库
        QueryWrapper<WeeklyReport> wrapper = new QueryWrapper<>();
        wrapper.eq("week_start_date", Monday);
        wrapper.eq("user_id", userId);
        return weeklyReportMapper.selectOne(wrapper);
    }


    // 2. 保存周报（草稿），不校验周五
    @Override
    @Transactional
    public WeeklyReport saveWeeklyReport(WeeklyReportDTO dto, Long userId) {
        // 参数校验
        validateContent(dto);

        if (userId == null) {
            userId = 1L;
        }
        // 计算周一的日期 格式化标题
        LocalDate Monday = DateUtils.getMondayOfWeek(LocalDate.now());
        String title = DateUtils.formatWeekTitle(Monday);

        // 校验周报是否已存在
        QueryWrapper<WeeklyReport> wrapper = new QueryWrapper<>();
        wrapper.eq("week_start_date", Monday);
        wrapper.eq("user_id", userId);

        // existingReport相当于数据库的快照
        WeeklyReport existingReport = weeklyReportMapper.selectOne(wrapper);

        // 新建一个report，从DTO拿前端传过来的内容
        WeeklyReport report = new WeeklyReport();
        report.setWeekStartDate(Monday);
        report.setTitle(title);
        report.setUserId(userId);
        report.setOverallProgress(dto.getOverallProgress());
        report.setWeeklyWorkReport(dto.getWeeklyWorkReport());
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
            // 插入：状态为编辑中，因为是保存草稿不是提交
            report.setStatus("EDITING");
            weeklyReportMapper.insert(report);
            log.info("插入周报成功，id：{}, week：{}", report.getId(), Monday);
        }
        return report;
    }

    // 3. 提交周报
    @Override
    @Transactional
    public WeeklyReport submitWeeklyReport(WeeklyReportDTO dto, Long userId) {
        // 校验前三个文本框内容不为空，且四个文本框内容不超过1000字
        validateContent(dto);

        LocalDate currentDate = LocalDate.now();
        DayOfWeek dayOfWeek = currentDate.getDayOfWeek();
        if (!dayOfWeek.equals(DayOfWeek.FRIDAY)) {
            throw new IllegalArgumentException("只有周五才能提交周报");
        }

        LocalDate Monday = DateUtils.getMondayOfWeek(LocalDate.now());
        String title = DateUtils.formatWeekTitle(Monday);

        // 校验周报是否存在
        QueryWrapper<WeeklyReport> wrapper = new QueryWrapper<>();
        wrapper.eq("week_start_date", Monday);
        wrapper.eq("user_id", userId);
        WeeklyReport existingReport = weeklyReportMapper.selectOne(wrapper);

        // 与save接口同理
        WeeklyReport report = new WeeklyReport();
        report.setWeekStartDate(Monday);
        report.setTitle(title);
        report.setUserId(userId);
        report.setOverallProgress(dto.getOverallProgress());
        report.setWeeklyWorkReport(dto.getWeeklyWorkReport());
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


    // 个人历史周报
    @Override
    public Page<WeeklyReport> getReports(Long userId, Integer pageNo, Integer pageSize, LocalDate startDate, LocalDate endDate) {
        if (userId == null) {
            log.error("userId不能为空");
            throw new IllegalArgumentException("userId不能为空");
        }
        if (pageNo == null || pageNo < 1) {
            pageNo = 1;
        }
        if (pageSize == null || pageSize < 1 || pageSize > 100) {
            pageSize = 10;
        }
        if ((startDate != null && endDate == null) || (startDate == null && endDate != null)) {
            throw new IllegalArgumentException("开始时间和结束时间必须同时为空或同时不为空");
        }
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("开始时间不能晚于结束时间");
        }

        QueryWrapper<WeeklyReport> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId);
        if (startDate != null) {
            wrapper.ge("week_start_date", startDate);
        }
        if (endDate != null) {
            wrapper.le("week_start_date", endDate);
        }
        wrapper.orderByDesc("week_start_date");

        Page<WeeklyReport> page = new Page<>(pageNo, pageSize);
        return weeklyReportMapper.selectPage(page, wrapper);
    }

    // 4. 查找周报（按id）
    @Override
    public WeeklyReport getReportById(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("id不能为空，必须存在");
        }
        WeeklyReport report = weeklyReportMapper.selectById(id);  // 按主键id查
        if (report == null) {
            throw new IllegalArgumentException("周报不存在");
        }
        return report;
    }

    // 5. 更新周报（按id）
    @Override
    @Transactional
    public WeeklyReport updateWeeklyReport(WeeklyReportDTO dto, Long id) {
        // 校验周报是否存在
        WeeklyReport existing = weeklyReportMapper.selectById(id);  // 按主键id查
        if (existing == null) {
            throw new IllegalArgumentException("周报不存在");
        }

        if ("SUBMITTED".equals(existing.getStatus())) {
            throw new IllegalArgumentException("已经提交过的周报不允许再更改");
        }

        validateContent(dto);

        // 更新内容（不更改状态、week_start_date、submitted_at）
        existing.setOverallProgress(dto.getOverallProgress());
        existing.setWeeklyWorkReport(dto.getWeeklyWorkReport());
        existing.setNextWeekPlan(dto.getNextWeekPlan());
        existing.setOther(dto.getOther());
        weeklyReportMapper.updateById(existing);
        log.info("更新周报成功，id：{}", id);

        return existing;
    }

    // 6. 提交历史周报
    @Override
    @Transactional
    public WeeklyReport submitHistoryWeekly(WeeklyReportDTO dto, Long id) {
        WeeklyReport existing = weeklyReportMapper.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("周报不存在");
        }

        if ("SUBMITTED".equals(existing.getStatus())) {
            throw new IllegalArgumentException("该周报已经提交过了");
        }

        LocalDate currentDate = LocalDate.now();
        DayOfWeek dayOfWeek = currentDate.getDayOfWeek();
        if (!dayOfWeek.equals(DayOfWeek.FRIDAY)) {
            throw new IllegalArgumentException("只有周五才能提交周报");
        }

        validateContent(dto);
        existing.setStatus("SUBMITTED");
        existing.setSubmittedAt(LocalDateTime.now());
        // 更新覆盖
        if (dto.getOverallProgress() != null) {
            existing.setOverallProgress(dto.getOverallProgress());
        }
        if (dto.getWeeklyWorkReport() != null) {
            existing.setWeeklyWorkReport(dto.getWeeklyWorkReport());
        }
        if (dto.getNextWeekPlan() != null) {
            existing.setNextWeekPlan(dto.getNextWeekPlan());
        }
        if (dto.getOther() != null) {
            existing.setOther(dto.getOther());
        }

        weeklyReportMapper.updateById(existing);
        log.info("提交历史周报成功，id：{}", id);
        return existing;
    }

    // 7. 查看所有成员的周报填写情况
    @Override
    public Page<WeekGroupVO> getTeamViewReports(LocalDate startDate, LocalDate endDate, Integer pageNo, Integer pageSize) {
        // 1. 获取所有成员
        List<MockUserService.MockUser> allMembers = mockUserService.getAllMembers();

        if (pageNo == null || pageNo < 1) {
            pageNo = 1;
        }
        if (pageSize == null || pageSize < 1 || pageSize > 100) {
            pageSize = 10;
        }

        // 2. 校验日期参数
        if ((startDate != null && endDate == null) || (startDate == null && endDate != null)) {
            throw new IllegalArgumentException("开始时间和结束时间必须同时为空或同时不为空");
        }
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("开始时间不能大于结束时间");
        }

        // 3. 查询该区间内所有周报（日期都为空时查询全部）
        QueryWrapper<WeeklyReport> wrapper = new QueryWrapper<>();
        if (startDate != null) {
            wrapper.ge("week_start_date", startDate);
        }
        if (endDate != null) {
            wrapper.le("week_start_date", endDate);
        }
        List<WeeklyReport> reportList = weeklyReportMapper.selectList(wrapper);

        // 4. 生成周一日期列表：指定了日期范围就用范围生成；未指定时从已有周报数据推导
        List<LocalDate> mondayList;

        if (startDate != null && endDate != null) {
            mondayList = DateUtils.getMondayListBetween(startDate, endDate);
        } else {
            // Set解决同一个周一重复出现问题，TreeSet按日期自然升序排序
            Set<LocalDate> weekSet = new TreeSet<>();

            for (WeeklyReport report : reportList) {
                weekSet.add(report.getWeekStartDate());
            }
            // Set转成List方便遍历
            mondayList = new ArrayList<>(weekSet);
        }


        // 4. 按 userId + weekStartDate 构建快速查找索引
        //    Key: userId + "_" + weekStartDate, Value: WeeklyReport
        Map<String, WeeklyReport> reportIndex = new HashMap<>();
        for (WeeklyReport report : reportList) {
            String key = report.getUserId() + "_" + report.getWeekStartDate().toString();
            reportIndex.put(key, report);
        }

        // 5. 组装数据：遍历每个周一，再遍历每个成员，构建 WeekGroupVO
        List<WeekGroupVO> groupList = new ArrayList<>();
        for (LocalDate monday : mondayList) {
            WeekGroupVO group = new WeekGroupVO();
            group.setWeekStartDate(monday);
            group.setWeekTitle(DateUtils.formatWeekTitle(monday));

            List<TeamMemberReportVO> memberList = new ArrayList<>();
            for (MockUserService.MockUser user : allMembers) {
                String key = user.getId() + "_" + monday;
                WeeklyReport report = reportIndex.get(key);

                TeamMemberReportVO member = new TeamMemberReportVO();

                member.setUserId(user.getId());
                member.setUserName(user.getName());

                if (report != null) {
                    member.setReportId(report.getId());
                    member.setStatus(report.getStatus());
                    member.setUpdateTime(report.getUpdatedAt());
                    member.setSubmittedAt(report.getSubmittedAt());
                } else {
                    member.setReportId(null);
                    member.setStatus("NOT_SUBMITTED");
                    member.setUpdateTime(null);
                    member.setSubmittedAt(null);
                }
                memberList.add(member);
            }
            group.setMembers(memberList);
            groupList.add(group);
        }
        // 降序排序
        groupList.sort((a, b) -> b.getWeekStartDate().compareTo(a.getWeekStartDate()));
        // 6. 内存分页  start:当前页的起始索引 end:当前页的结束索引（不包含）
        int total = groupList.size();
        int start = (pageNo - 1) * pageSize;
        int end = Math.min(start + pageSize, total);
        // 截取当前页的数据
        List<WeekGroupVO> pageList;
        if (start >= total) { // 当请求的页码超出总页数，返回空列表
            pageList = new ArrayList<>();   // 空页，而不是报错
        } else {
            pageList = groupList.subList(start, end);
        }

        // 7. 组装分页结果
        Page<WeekGroupVO> pageResult = new Page<>(pageNo, pageSize);
        pageResult.setRecords(pageList);
        pageResult.setTotal(total);

        return pageResult;
    }
}
