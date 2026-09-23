package com.ll.hirehub.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 刷新 / 登出请求体：携带 refresh token（见 D-02） */
@Data
public class RefreshRequest {

    @NotBlank(message = "refreshToken 不能为空")
    private String refreshToken;
}
