package com.ll.hirehub.interview.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CreateInterviewRequest {

    @NotNull(message = "投递不能为空")
    private Long deliveryId;

    private LocalDateTime interviewTime;
    private String interviewType;
    private String addressOrLink;
    private String interviewerName;
}
