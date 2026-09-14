package com.ll.hirehub.company.dto;

import lombok.Data;

@Data
public class AdminVerifyRequest {

    private Boolean approve;
    private String remark;
}
