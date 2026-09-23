package com.ll.hirehub.auth.vo;

import lombok.Data;

import java.util.List;

/** 登录 / 刷新响应：双 token（见 D-02） */
@Data
public class LoginVO {

    private String accessToken;

    /** 刷新令牌：仅用于换新 accessToken，不带角色信息，服务端可撤销 */
    private String refreshToken;

    private String tokenType = "Bearer";

    /** accessToken 有效期（秒），前端据此决定何时静默刷新 */
    private Long expiresIn;

    private Long userId;
    private String username;
    private String nickname;
    private List<String> roles;
}
