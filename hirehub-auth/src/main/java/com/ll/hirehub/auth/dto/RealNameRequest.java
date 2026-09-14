package com.ll.hirehub.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RealNameRequest {

    @NotBlank(message = "姓名不能为空")
    private String realName;

    @NotBlank(message = "身份证号不能为空")
    private String idCard;
}
