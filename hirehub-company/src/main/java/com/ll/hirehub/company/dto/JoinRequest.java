package com.ll.hirehub.company.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class JoinRequest {

    @NotBlank(message = "邀请码不能为空")
    private String inviteCode;
}
