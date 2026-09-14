package com.ll.hirehub.api.dto;

import lombok.Data;

@Data
public class JobDTO {

    private Long id;
    private Long companyId;
    private String title;
    private Integer status;
}
