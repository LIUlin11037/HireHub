package com.ll.hirehub.interview.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RescheduleRequest {

    private LocalDateTime interviewTime;
}
