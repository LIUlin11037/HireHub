package com.ll.hirehub.notification.dto;

import lombok.Data;

import java.util.List;

@Data
public class NotifyRequest {

    private String type;
    private String title;
    private String content;
    private List<Long> receiverIds;
}
