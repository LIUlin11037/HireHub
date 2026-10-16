package com.ll.hirehub.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;
    private String password;
    private String phone;
    private String email;
    private String nickname;
    private String avatar;
    private Integer status;
    private LocalDateTime lastLoginTime;

    private String realName;
    private String idCardNo;
    /** 身份证 HMAC-SHA256 指纹（审计 F-11）：GCM 密文无法查重，用不可逆指纹支撑"一证一号" */
    private String idCardHash;
    private Integer realNameStatus;
    private LocalDateTime realNameTime;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
