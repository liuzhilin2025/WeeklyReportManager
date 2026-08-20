package com.practice.weeklyreportmanager.service;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MockUserService {

    private final Map<Long, MockUser> mockUserMap = new HashMap<>();

    @PostConstruct
    public void init() {
        // 模拟团队成员数据（和 VoiceManager 一样，在启动时塞数据）
        mockUserMap.put(1L, new MockUser(1L, "AAA"));
        mockUserMap.put(2L, new MockUser(2L, "BBB"));
        mockUserMap.put(3L, new MockUser(3L, "CCC"));
        mockUserMap.put(4L, new MockUser(4L, "DDD"));
        // 以后想加人就在这里加
    }

    /**
     * 获取所有成员
     */
    public List<MockUser> getAllMembers() {
        return new ArrayList<>(mockUserMap.values());
    }

    /**
     * 根据 ID 获取成员信息（用于校验）
     */
    public MockUser getMemberById(Long userId) {
        return mockUserMap.get(userId);
    }

    /**
     * 判断用户是否存在
     */
    public boolean exists(Long userId) {
        return mockUserMap.containsKey(userId);
    }

    /**
     * 根据 ID 获取用户名
     */
    public String getUserName(Long userId) {
        MockUser user = mockUserMap.get(userId);
        return user != null ? user.getName() : null;
    }


    // ========== 内部类：Mock 用户 ==========
    public static class MockUser {
        private Long id;
        private String name;

        public MockUser(Long id, String name) {
            this.id = id;
            this.name = name;
        }

        public Long getId() { return id; }
        public String getName() { return name; }
    }
}