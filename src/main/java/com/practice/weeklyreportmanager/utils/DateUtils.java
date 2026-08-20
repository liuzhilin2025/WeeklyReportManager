package com.practice.weeklyreportmanager.utils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * 日期工具类（周报系统专用）
 */
public class DateUtils {

    /**
     * 获取当前日期所在周的周一日期
     * 周一为一周的开始（和周报需求一致）
     */
    // 将日期调整为“上一个或同一天”的指定星期几。如果当前日期正好是该星期几，则直接返回当前日期；否则返回最近的过去那个星期几。
    public static LocalDate getMondayOfWeek(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /**
     * 格式化周报标题：MM.dd~MM.dd
     * 例：2026-08-17 → "08.17~08.21"
     */
    public static String formatWeekTitle(LocalDate monday) {
        if (monday == null) {
            return "";
        }
        LocalDate friday = monday.plusDays(4);
        return String.format("%02d.%02d~%02d.%02d",
                monday.getMonthValue(), monday.getDayOfMonth(),
                friday.getMonthValue(), friday.getDayOfMonth());
    }

    /**
     * 获取日期区间内所有的周一日期列表
     * 例如：2026-08-01 ~ 2026-08-20
     * 返回：[2026-08-03, 2026-08-10, 2026-08-17]
     */
    public static List<LocalDate> getMondayListBetween(LocalDate startDate, LocalDate endDate) {
        List<LocalDate> mondayList = new ArrayList<>();

        if (startDate == null || endDate == null) {
            return mondayList;
        }

        // 找到 startDate 所在周的周一
        LocalDate firstMonday = startDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        // 如果第一个周一在 startDate 之前，则从下一个周一开始
        if (firstMonday.isBefore(startDate)) {
            firstMonday = firstMonday.plusWeeks(1);
        }

        // 循环生成所有周一
        LocalDate current = firstMonday;
        while (!current.isAfter(endDate)) {
            mondayList.add(current);
            current = current.plusWeeks(1);
        }

        return mondayList;
    }

    /**
     * 判断两个日期是否是同一周
     */
    public static boolean isSameWeek(LocalDate date1, LocalDate date2) {
        if (date1 == null || date2 == null) {
            return false;
        }
        LocalDate monday1 = getMondayOfWeek(date1);
        LocalDate monday2 = getMondayOfWeek(date2);
        return monday1.equals(monday2);
    }

    /**
     * 获取指定日期所在周的周一（字符串格式 yyyy-MM-dd）
     */
    public static String getMondayStr(LocalDate date) {
        return getMondayOfWeek(date).toString();
    }

    /**
     * 获取本周一日期
     */
    public static LocalDate getCurrentMonday() {
        return getMondayOfWeek(LocalDate.now());
    }

}