package com.ll.hirehub.company.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 法人授权请求（见 D-24 第三层）：**法人无需注册账号**，凭令牌 + 实名完成授权。
 */
@Data
public class AuthorizeRequest {

    @NotBlank(message = "授权令牌不能为空")
    private String token;

    @NotBlank(message = "姓名不能为空")
    private String realName;

    @NotBlank(message = "身份证号不能为空")
    private String idCard;
}
