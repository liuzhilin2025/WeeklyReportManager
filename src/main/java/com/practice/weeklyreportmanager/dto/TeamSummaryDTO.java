package com.practice.weeklyreportmanager.dto;

import lombok.Data;

@Data
public class TeamSummaryDTO {
    private String teamProgress = "";

    private String commonIssues = "";

    private String collaborationNeeds = "";

    private String missingMembers = "";
}
