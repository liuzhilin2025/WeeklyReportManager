package com.practice.weeklyreportmanager.service.tool;

import com.practice.weeklyreportmanager.entity.WeeklyReport;

/**
 * 事件载体：一条周报发生了变更（保存草稿 / 提交 / 更新）。
 * <p>
 * 为什么不直接 {@code publishEvent(report)}、而要单独定义一个类型：
 * <p>
 * 1. Spring 按事件的运行时类型路由到监听器，需要这个类型来标记「这是一次周报变更」。
 * 直接发实体的话，光看类型分不清是新建、更新还是删除；
 * 2. 语义清楚：事件流里出现本类型，一眼就知道发生了什么；
 * 3. 留了扩展口——将来要区分动作（比如加个 ChangeType 枚举），加字段即可，
 * 发布方与监听方都不用改。
 * <p>
 * 为什么携带整个 {@link WeeklyReport} 而不是只带 id：监听发生在事务提交之后，
 * 那时实体的字段就是最终值，直接读即可，不必为了取内容再回查一次数据库。
 * 一条周报只有几个字符串和日期，不存在「对象太重」的问题。
 * <p>
 * 为什么做成 record：它只是数据载体、不该被修改；record 一行就能拿到不可变语义，
 * 以及自动生成的构造器、访问器（{@code report()}）与 equals/hashCode。
 * <p>
 * 发布方：{@code WeeklyReportServiceImpl} 的四个变更方法（都带 {@code @Transactional}）。
 * 监听方：{@link WeeklyReportVectorSyncListener}。
 *
 * @param report 变更后的周报实体；监听方按它的 {@code status} 决定是否写入索引
 */
public record WeeklyReportChangedEvent(WeeklyReport report) {
}
