package com.practice.weeklyreportmanager.controller;

import com.practice.weeklyreportmanager.service.VisitCountService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestRedisController {

    @Autowired
    private VisitCountService visitCountService;

    @GetMapping("/visit-count")
    public String getVisitCount() {
        Long count = visitCountService.incrementAndGet();
        return "当前页面已访问" + count + "次（Redis计数）";
    }
}