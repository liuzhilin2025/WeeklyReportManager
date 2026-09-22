package com.practice.weeklyreportmanager.entity;


import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

// Lombok 就会在编译时自动生成：
// 所有字段的 getter 和 setter 方法
// toString() 方法
// equals() 和 hashCode() 方法
@Data
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
    private String weeklyWorkReport;

    @NotBlank(message = "下周目标不能为空")
    private String nextWeekPlan;

    private String other;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime submittedAt;

    private Long userId;

}
