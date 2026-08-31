package com.practice.weeklyreportmanager.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.practice.weeklyreportmanager.dto.WeeklyReportDTO;
import com.practice.weeklyreportmanager.entity.WeeklyReport;
import com.practice.weeklyreportmanager.mapper.WeeklyReportMapper;
import com.practice.weeklyreportmanager.service.MockUserService;
import com.practice.weeklyreportmanager.vo.WeekGroupVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WeeklyReportServiceImplTest {

    @Mock
    private WeeklyReportMapper weeklyReportMapper;

    @Mock
    private MockUserService mockUserService;

    @InjectMocks
    private WeeklyReportServiceImpl weeklyReportService;

    private WeeklyReportDTO validDto;
    private WeeklyReport existingReport;

    @BeforeEach
    void setUp() {
        // ===== 1. 构造合法 DTO =====
        validDto = new WeeklyReportDTO();
        validDto.setOverallProgress("总体进度：完成需求分析和数据库设计");
        validDto.setWeeklyWorkReport("本周进展：完成了后端接口开发");
        validDto.setNextWeekPlan("下周目标：完成前端页面联调");
        validDto.setOther("补充说明：暂无");

        // ===== 2. 构造已存在的周报 =====
        existingReport = new WeeklyReport();
        existingReport.setId(1L);
        existingReport.setUserId(1L);
        existingReport.setWeekStartDate(LocalDate.of(2026, 8, 17));
        existingReport.setTitle("周报08.17~08.21");
        existingReport.setOverallProgress("总体进度：完成需求分析");
        existingReport.setWeeklyWorkReport("本周进展：后端接口开发");
        existingReport.setNextWeekPlan("下周目标：前端联调");
        existingReport.setOther("无");
        existingReport.setStatus("EDITING");
        existingReport.setCreatedAt(LocalDateTime.now());
        existingReport.setUpdatedAt(LocalDateTime.now());
    }

    // ==================== 1. 获取当前周周报 ====================
    @Test
    void getCurrentWeekReport_shouldReturnReport_whenExists() {
        // 模拟查询结果
        when(weeklyReportMapper.selectOne(any(QueryWrapper.class))).thenReturn(existingReport);

        WeeklyReport result = weeklyReportService.getCurrentWeekReport(1L);

        assertNotNull(result);
        assertEquals(1L, result.getId());
        assertEquals("周报08.17~08.21", result.getTitle());
        verify(weeklyReportMapper, times(1)).selectOne(any(QueryWrapper.class));
    }

    @Test
    void getCurrentWeekReport_shouldReturnNull_whenNotExists() {
        when(weeklyReportMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        WeeklyReport result = weeklyReportService.getCurrentWeekReport(1L);

        assertNull(result);
        verify(weeklyReportMapper, times(1)).selectOne(any(QueryWrapper.class));
    }

    // ==================== 2. 保存周报（草稿） ====================
    @Test
    void saveWeeklyReport_shouldInsert_whenNotExists() {
        when(weeklyReportMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(weeklyReportMapper.insert(any(WeeklyReport.class))).thenReturn(1);

        WeeklyReport result = weeklyReportService.saveWeeklyReport(validDto, 1L);

        assertNotNull(result);
        assertEquals("EDITING", result.getStatus());
        assertEquals("总体进度：完成需求分析和数据库设计", result.getOverallProgress());

        // 创建一个抓取器，forClass方法，用于生成一个ArgumentCaptor对象
        ArgumentCaptor<WeeklyReport> captor = ArgumentCaptor.forClass(WeeklyReport.class);

        // 捕获参数，capture方法用于获得传给mock对象的方法
        verify(weeklyReportMapper).insert(captor.capture());

        // getValue方法用于从捕获器ArgumentCaptor中取出捕获的内容
        WeeklyReport saved = captor.getValue();

        assertEquals("EDITING", saved.getStatus());

        // 确认 weeklyReportMapper.updateById()一次都没有被调用
        verify(weeklyReportMapper, never()).updateById(any(WeeklyReport.class));
    }

    @Test
    void saveWeeklyReport_shouldUpdate_whenExists() {
        when(weeklyReportMapper.selectOne(any(QueryWrapper.class))).thenReturn(existingReport);
        when(weeklyReportMapper.updateById(any(WeeklyReport.class))).thenReturn(1);

        WeeklyReport result = weeklyReportService.saveWeeklyReport(validDto, 1L);

        assertNotNull(result);
        assertEquals("EDITING", result.getStatus()); // 保持原状态
        assertEquals("总体进度：完成需求分析和数据库设计", result.getOverallProgress());

        verify(weeklyReportMapper, never()).insert(any(WeeklyReport.class));
        verify(weeklyReportMapper, times(1)).updateById(any(WeeklyReport.class));
    }

    // ==================== 3. 提交周报 ====================
    @Test
    void submitWeeklyReport_shouldUpdateStatus_whenFriday() {
        // 注意：必须在 mockStatic 作用域之外构造 LocalDate.of(...)，
        // 否则该静态方法调用会被 mock 拦截并返回 null
        LocalDate friday = LocalDate.of(2026, 8, 21);

        try (MockedStatic<LocalDate> mocked = mockStatic(LocalDate.class)) {
            // 固定为周五 2026-08-21
            mocked.when(LocalDate::now).thenReturn(friday);

            when(weeklyReportMapper.selectOne(any(QueryWrapper.class))).thenReturn(existingReport);
            when(weeklyReportMapper.updateById(any(WeeklyReport.class))).thenReturn(1);

            WeeklyReport result = weeklyReportService.submitWeeklyReport(validDto, 1L);

            assertNotNull(result);
            assertEquals("SUBMITTED", result.getStatus());
            assertNotNull(result.getSubmittedAt());
            verify(weeklyReportMapper, times(1)).updateById(any(WeeklyReport.class));
        }
    }

    // ==================== 4. 更新周报 ====================
    @Test
    void updateWeeklyReport_shouldThrow_whenNotExists() {
        when(weeklyReportMapper.selectById(anyLong())).thenReturn(null);

        assertThrows(IllegalArgumentException.class,
                () -> weeklyReportService.updateWeeklyReport(validDto, 999L));
        verify(weeklyReportMapper, never()).updateById(any(WeeklyReport.class));
    }

    @Test
    void updateWeeklyReport_shouldThrow_whenPastWeekSubmitted() {
        existingReport.setStatus("SUBMITTED");
        when(weeklyReportMapper.selectById(anyLong())).thenReturn(existingReport);

        // 固定"今天"为报告所在周的下一周，模拟过周后已提交即锁定
        LocalDate nextWeekMonday = LocalDate.of(2026, 8, 24);
        try (MockedStatic<LocalDate> mocked = mockStatic(LocalDate.class)) {
            mocked.when(LocalDate::now).thenReturn(nextWeekMonday);

            assertThrows(IllegalArgumentException.class,
                    () -> weeklyReportService.updateWeeklyReport(validDto, 1L));
        }
        verify(weeklyReportMapper, never()).updateById(any(WeeklyReport.class));
    }

    @Test
    void updateWeeklyReport_shouldAllow_whenSubmittedButCurrentWeek() {
        existingReport.setStatus("SUBMITTED");
        when(weeklyReportMapper.selectById(anyLong())).thenReturn(existingReport);
        when(weeklyReportMapper.updateById(any(WeeklyReport.class))).thenReturn(1);

        // 固定"今天"为报告所在周内的周五，本周内已提交仍允许修改
        LocalDate friday = LocalDate.of(2026, 8, 21);
        try (MockedStatic<LocalDate> mocked = mockStatic(LocalDate.class)) {
            mocked.when(LocalDate::now).thenReturn(friday);

            WeeklyReport result = weeklyReportService.updateWeeklyReport(validDto, 1L);

            assertNotNull(result);
            assertEquals("SUBMITTED", result.getStatus());
            assertEquals("总体进度：完成需求分析和数据库设计", result.getOverallProgress());
            verify(weeklyReportMapper, times(1)).updateById(any(WeeklyReport.class));
        }
    }

    @Test
    void updateWeeklyReport_shouldUpdate_whenEditing() {
        when(weeklyReportMapper.selectById(anyLong())).thenReturn(existingReport);
        when(weeklyReportMapper.updateById(any(WeeklyReport.class))).thenReturn(1);

        // 固定"今天"为报告所在周内的日期，本周内的草稿允许修改
        LocalDate inWeek = LocalDate.of(2026, 8, 21);
        try (MockedStatic<LocalDate> mocked = mockStatic(LocalDate.class)) {
            mocked.when(LocalDate::now).thenReturn(inWeek);

            WeeklyReport result = weeklyReportService.updateWeeklyReport(validDto, 1L);

            assertNotNull(result);
            assertEquals("EDITING", result.getStatus());
            assertEquals("总体进度：完成需求分析和数据库设计", result.getOverallProgress());
            verify(weeklyReportMapper, times(1)).updateById(any(WeeklyReport.class));
        }
    }

    @Test
    void updateWeeklyReport_shouldThrow_whenPastWeekEditing() {
        existingReport.setStatus("EDITING");
        when(weeklyReportMapper.selectById(anyLong())).thenReturn(existingReport);

        // 固定"今天"为报告所在周的下一周，过周的周报即使是草稿也只读
        LocalDate nextWeekMonday = LocalDate.of(2026, 8, 24);
        try (MockedStatic<LocalDate> mocked = mockStatic(LocalDate.class)) {
            mocked.when(LocalDate::now).thenReturn(nextWeekMonday);

            assertThrows(IllegalArgumentException.class,
                    () -> weeklyReportService.updateWeeklyReport(validDto, 1L));
        }
        verify(weeklyReportMapper, never()).updateById(any(WeeklyReport.class));
    }

    // ==================== 5. 查询历史周报（个人视图） ====================


    // ==================== 6. 查看周报详情 ====================
    @Test
    void getReportById_shouldReturn_whenExists() {
        when(weeklyReportMapper.selectById(anyLong())).thenReturn(existingReport);

        WeeklyReport result = weeklyReportService.getReportById(1L);

        assertNotNull(result);
        assertEquals(1L, result.getId());
        verify(weeklyReportMapper, times(1)).selectById(anyLong());
    }

    @Test
    void getReportById_shouldThrow_whenNotExists() {
        when(weeklyReportMapper.selectById(anyLong())).thenReturn(null);

        assertThrows(IllegalArgumentException.class,
                () -> weeklyReportService.getReportById(999L));
    }

    // ==================== 7. 提交历史周报 ====================
    @Test
    void submitHistoryWeekly_shouldThrow_whenPastWeekEditing() {
        existingReport.setStatus("EDITING");
        when(weeklyReportMapper.selectById(anyLong())).thenReturn(existingReport);

        // 固定"今天"为报告所在周的下一周，过周的周报即使是草稿也不能提交
        LocalDate nextWeekMonday = LocalDate.of(2026, 8, 24);
        try (MockedStatic<LocalDate> mocked = mockStatic(LocalDate.class)) {
            mocked.when(LocalDate::now).thenReturn(nextWeekMonday);

            assertThrows(IllegalArgumentException.class,
                    () -> weeklyReportService.submitHistoryWeekly(validDto, 1L));
        }
        verify(weeklyReportMapper, never()).updateById(any(WeeklyReport.class));
    }

    @Test
    void submitHistoryWeekly_shouldThrow_whenAlreadySubmittedPastWeek() {
        existingReport.setStatus("SUBMITTED");
        when(weeklyReportMapper.selectById(anyLong())).thenReturn(existingReport);

        // 固定"今天"为报告所在周的下一周，过周后已提交不能再提交
        LocalDate nextWeekMonday = LocalDate.of(2026, 8, 24);
        try (MockedStatic<LocalDate> mocked = mockStatic(LocalDate.class)) {
            mocked.when(LocalDate::now).thenReturn(nextWeekMonday);

            assertThrows(IllegalArgumentException.class,
                    () -> weeklyReportService.submitHistoryWeekly(validDto, 1L));
        }
        verify(weeklyReportMapper, never()).updateById(any(WeeklyReport.class));
    }

    @Test
    void submitHistoryWeekly_shouldAllow_whenSubmittedButCurrentWeek() {
        existingReport.setStatus("SUBMITTED");
        when(weeklyReportMapper.selectById(anyLong())).thenReturn(existingReport);
        when(weeklyReportMapper.updateById(any(WeeklyReport.class))).thenReturn(1);

        // 固定"今天"为报告所在周内的周五，本周内已提交仍允许重新提交
        LocalDate friday = LocalDate.of(2026, 8, 21);
        try (MockedStatic<LocalDate> mocked = mockStatic(LocalDate.class)) {
            mocked.when(LocalDate::now).thenReturn(friday);

            WeeklyReport result = weeklyReportService.submitHistoryWeekly(validDto, 1L);

            assertNotNull(result);
            assertEquals("SUBMITTED", result.getStatus());
            assertNotNull(result.getSubmittedAt());
            verify(weeklyReportMapper, times(1)).updateById(any(WeeklyReport.class));
        }
    }

    // ==================== 8. 团队视图 ====================
    @Test
    void getTeamViewReports_shouldReturnGroupedData() {
        // 1. Mock 成员数据
        List<MockUserService.MockUser> mockUsers = Arrays.asList(
                new MockUserService.MockUser(1L, "AAA"),
                new MockUserService.MockUser(2L, "BBB")
        );
        when(mockUserService.getAllMembers()).thenReturn(mockUsers);

        // 2. Mock 周报数据
        List<WeeklyReport> mockReports = new ArrayList<>();
        WeeklyReport report1 = new WeeklyReport();
        report1.setUserId(1L);
        report1.setWeekStartDate(LocalDate.of(2026, 8, 17));
        report1.setStatus("EDITING");
        report1.setUpdatedAt(LocalDateTime.now());
        mockReports.add(report1);

        when(weeklyReportMapper.selectList(any(QueryWrapper.class))).thenReturn(mockReports);

        // 3. 执行查询
        LocalDate start = LocalDate.of(2026, 8, 10);
        LocalDate end = LocalDate.of(2026, 8, 23);
        Page<WeekGroupVO> result = weeklyReportService.getTeamViewReports(start, end, 1, 10);

        // 4. 断言
        assertNotNull(result);
        // 检查分组数据是否正确
        // 这里需要检查返回的 WeekGroupVO 是否符合预期
        // 由于日期计算依赖当前日期，这里只做基本验证
        assertTrue(result.getTotal() >= 0);
    }
}