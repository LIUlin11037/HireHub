package com.ll.hirehub.notification.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class NotificationVO {

    private Long id;
    private String type;
    private String title;
    private String content;
    private LocalDateTime createTime;
    private Integer readStatus;
    private LocalDateTime readTime;
}
