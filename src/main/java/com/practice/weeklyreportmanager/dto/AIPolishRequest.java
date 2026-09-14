package com.practice.weeklyreportmanager.dto;

import lombok.Data;

/**
 * AI 润色请求体：「周报体检」先出问题清单，用户再点「按建议润色」时，
 * 把体检结论一起带上，让润色针对结论里指出的问题调整表达。
 */
@Data
public class AIPolishRequest {

    /** 待润色的周报四字段（允许部分为空） */
    private WeeklyReportDTO report;

    /** 周报体检结论（可为空；为空就按普通润色处理） */
    private String checkResult;
}
