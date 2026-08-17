package com.practice.weeklyreportmanager.utils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

public class DateUtils {
    public static LocalDate getMondayoOfWeek(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        // TemporalAdjusters.previousOrSame(DayOfWeek dayOfWeek) 是 Java 8 日期时间 API 中的一个静态方法
        // 用于将日期调整为“上一个或同一天”的指定星期几。
        // 如果当前日期正好是该星期几，则直接返回当前日期；否则返回最近的过去那个星期几。
    }

    public static String formatWeekTitle(LocalDate monday) {
        LocalDate friday = monday.plusDays(4);
        return String.format("%02d.%02d~%02d.%02d",
                monday.getMonthValue(), monday.getDayOfMonth(),
                friday.getMonthValue(), friday.getDayOfMonth());
    }
}
