package com.ll.hirehub.company.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateCompanyRequest {

    @NotBlank(message = "企业名称不能为空")
    private String name;

    @NotBlank(message = "统一社会信用代码不能为空")
    private String creditCode;

    private String industry;
    private String scale;
    private String city;
    private String address;
    private String description;
}
