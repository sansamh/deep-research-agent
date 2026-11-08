package com.deepprobe.agent.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class ResearchDtos {

    public record StartRequest(
        @NotBlank @Size(max = 500) String topic) {
    }

    public record SourceInfo(int index, String title, String url) {
    }

    public record StepDto(Long id, int seq, String type, String title, String status,
                          String summary, List<SourceInfo> sources) {
    }

    public record SessionDto(Long id, String topic, String status, String reportMd,
                             String createdAt, List<StepDto> steps) {
    }

    public record SessionSummaryDto(Long id, String topic, String status, String createdAt) {
    }
}
