package com.practice.weeklyreportmanager.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class VisitCountService {

    private static final String VISIT_KEY = "visit:count";

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * 访问次数 +1，返回最新的访问次数
     */
    public Long incrementAndGet() {
        return redisTemplate.opsForValue().increment(VISIT_KEY);
    }

    /**
     * 获取当前访问次数
     */
    public Long getCurrentCount() {
        Object value = redisTemplate.opsForValue().get(VISIT_KEY);
        if (value == null) {
            return 0L;
        }
        return ((Number) value).longValue();
    }
}