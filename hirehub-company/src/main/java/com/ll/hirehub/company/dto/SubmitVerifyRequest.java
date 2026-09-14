package com.ll.hirehub.company.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SubmitVerifyRequest {

    @NotBlank(message = "法定代表人姓名不能为空")
    private String legalPersonName;

    private String licenseUrl;
}
