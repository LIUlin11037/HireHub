package com.ll.hirehub.auth.vo;

import lombok.Data;

import java.util.List;

/**
 * 当前用户信息（脱敏：不含 password / idCardNo）
 */
@Data
public class UserInfoVO {

    private Long id;
    private String username;
    private String phone;
    private String email;
    private String nickname;
    private String avatar;
    private Integer realNameStatus;
    private List<String> roles;
}
