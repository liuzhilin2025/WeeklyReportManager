package com.practice.weeklyreportmanager.service.tool;

import com.practice.weeklyreportmanager.entity.WeeklyReport;

/**
 * 周报发生变更（保存草稿 / 提交 / 更新）时发布，用来触发向量索引同步。
 * <p>
 * 为什么需要它：向量索引是「某一时刻的快照」，而周报是**可编辑**的。不同步的话，
 * 用户改完周报再问「我之前写过什么」，模型读到的还是旧内容——而且这种错误很难察觉，
 * 因为答案看上去完全正常，不像报错那样会暴露自己。
 * <p>
 * 携带整个 {@link WeeklyReport} 而不是只带 id：监听发生在事务提交之后，
 * 那时数据库里的值就是最终值，直接读实体字段即可，不必再回查一次。
 */
public record WeeklyReportChangedEvent(WeeklyReport report) {
}
