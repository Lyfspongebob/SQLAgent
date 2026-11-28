package com.example.sqlagent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentStepResponse {
    private String id;
    private String type;
    private String title;
    private String status;
    private String summary;
    private String detail;
    private String startedAt;
    private String completedAt;
}
