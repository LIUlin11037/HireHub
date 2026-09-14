package com.ll.hirehub.delivery.enums;

/**
 * 投递状态（见 D-27）：8 个状态，终态 3 个（HIRED / REJECTED / CANCELLED）
 */
public enum DeliveryStatus {
    PENDING,
    VIEWED,
    COMMUNICATING,
    INTERVIEW,
    OFFER,
    HIRED,
    REJECTED,
    CANCELLED
}
