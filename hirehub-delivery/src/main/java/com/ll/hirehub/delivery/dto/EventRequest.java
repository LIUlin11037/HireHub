package com.ll.hirehub.delivery.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class EventRequest {

    /** 事件名：CONTACT / INVITE_INTERVIEW / SEND_OFFER / REJECT / HIRE / CANCEL */
    @NotBlank(message = "事件不能为空")
    private String event;
}
