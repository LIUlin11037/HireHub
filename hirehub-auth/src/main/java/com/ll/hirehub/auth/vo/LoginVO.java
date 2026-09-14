package com.ll.hirehub.auth.vo;

import lombok.Data;

import java.util.List;

@Data
public class LoginVO {

    private String accessToken;
    private Long userId;
    private String username;
    private String nickname;
    private List<String> roles;
}
