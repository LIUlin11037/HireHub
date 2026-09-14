package com.ll.hirehub.interview.enums;

/**
 * 面试状态（见 D-28）：5 个状态，终态 3 个（COMPLETED / REJECTED / CANCELLED）
 */
public enum InterviewStatus {
    SCHEDULED,
    CONFIRMED,
    COMPLETED,
    REJECTED,
    CANCELLED
}
