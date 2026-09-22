package com.practice.weeklyreportmanager.task;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.practice.weeklyreportmanager.entity.WeeklyReport;
import com.practice.weeklyreportmanager.mapper.WeeklyReportMapper;
import com.practice.weeklyreportmanager.service.MockUserService;
import com.practice.weeklyreportmanager.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 周报定时提醒任务
 * 每周五 09:00 扫描当周未提交周报的成员并提醒
 */
@Slf4j
@Component
public class ReportReminderTask {

    @Autowired
    private WeeklyReportMapper weeklyReportMapper;

    @Autowired
    private MockUserService mockUserService;

    /**
     * cron 表达式：秒 分 时 日 月 周
     * 默认每周五 09:00，可通过 application.properties 的 report.reminder.cron 修改
     */
    @Scheduled(cron = "${report.reminder.cron:0 0 9 ? * FRI}")
    public void remindUnsubmitted() {
        LocalDate monday = DateUtils.getMondayOfWeek(LocalDate.now());

        // 查本周所有周报
        QueryWrapper<WeeklyReport> wrapper = new QueryWrapper<>();
        wrapper.eq("week_start_date", monday);
        List<WeeklyReport> reports = weeklyReportMapper.selectList(wrapper);

        // 收集本周已提交的 userId
        Set<Long> submittedIds = new HashSet<>();
        for (WeeklyReport report : reports) {
            if ("SUBMITTED".equals(report.getStatus())) {
                submittedIds.add(report.getUserId());
            }
        }

        // 提醒未提交的成员
        List<MockUserService.MockUser> unsubmitted = mockUserService.getAllMembers().stream()
                .filter(user -> !submittedIds.contains(user.getId()))
                .toList();

        if (unsubmitted.isEmpty()) {
            log.info("【周报提醒】本周{}所有成员均已提交周报", monday);
            return;
        }

        for (MockUserService.MockUser user : unsubmitted) {
            // TODO: 换成真实的发送渠道（邮件 / 钉钉群机器人 / 站内信）
            log.warn("【周报提醒】成员 {} 本周（{}）还未提交周报", user.getName(), monday);
        }
        log.info("【周报提醒】本周{}共 {} 人未提交", monday, unsubmitted.size());
    }
}
