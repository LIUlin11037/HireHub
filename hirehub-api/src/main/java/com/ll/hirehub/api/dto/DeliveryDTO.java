package com.ll.hirehub.api.dto;

import lombok.Data;

@Data
public class DeliveryDTO {

    private Long id;
    private Long jobId;
    private Long resumeId;
    private Long seekerId;
    private Long companyId;
    private String status;
}
