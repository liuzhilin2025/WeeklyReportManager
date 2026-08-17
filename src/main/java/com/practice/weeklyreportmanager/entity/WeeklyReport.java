package com.practice.weeklyreportmanager.entity;


import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("t_weeklyreport")
public class WeeklyReport {

    @TableId(type = IdType.AUTO)
    private Long id;

    @NotBlank(message="标题不能为空")
    private String title;

    @NotNull(message = "每周周一开始日期不能为空")
    private LocalDate weekStartDate;

    @NotBlank(message = "总体进度不能为空")
    private String overallProgress;

    @NotBlank(message = "本周进展不能为空")
    private String weeklyReportWork;

    @NotBlank(message = "下周目标不能为空")
    private String nextWeekPlan;

    private String other;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime submittedAt;

    private Long userId;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public LocalDate getWeekStartDate() {
        return weekStartDate;
    }

    public void setWeekStartDate(LocalDate weekStartDate) {
        this.weekStartDate = weekStartDate;
    }

    public String getOverallProgress() {
        return overallProgress;
    }

    public void setOverallProgress(String overallProgress) {
        this.overallProgress = overallProgress;
    }

    public String getWeeklyReportWork() {
        return weeklyReportWork;
    }

    public void setWeeklyReportWork(String weeklyReportWork) {
        this.weeklyReportWork = weeklyReportWork;
    }

    public String getNextWeekPlan() {
        return nextWeekPlan;
    }

    public void setNextWeekPlan(String nextWeekPlan) {
        this.nextWeekPlan = nextWeekPlan;
    }

    public String getOther() {
        return other;
    }

    public void setOther(String other) {
        this.other = other;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(LocalDateTime submittedAt) {
        this.submittedAt = submittedAt;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }
}
