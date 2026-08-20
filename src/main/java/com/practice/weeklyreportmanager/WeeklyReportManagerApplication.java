package com.practice.weeklyreportmanager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling   // 开启定时任务
public class WeeklyReportManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WeeklyReportManagerApplication.class, args);
    }

}
