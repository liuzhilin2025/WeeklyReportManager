package com.practice.weeklyreportmanager.dto;

import lombok.Data;

/**
 * 历史周报智能摘要的返回对象。
 * 对应 AI 返回的 JSON 结构：
 * {
 *   "achievements": "1、xxx\n2、xxx",
 *   "issues": "1、xxx",
 *   "suggestions": "1、xxx"
 * }
 * 三个字段都是「按 1、2、3 编号、换行分隔」的单条字符串，不是数组。
 */
@Data
public class WeeklySummaryDTO {

    /** 近四周核心产出 */
    private String achievements = "";

    /** 持续存在的问题或风险 */
    private String issues = "";

    /** 下一步建议或关注方向 */
    private String suggestions = "";
}