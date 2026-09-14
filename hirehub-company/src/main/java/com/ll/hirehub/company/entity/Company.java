package com.ll.hirehub.company.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("company")
public class Company {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String creditCode;
    private String legalPersonName;
    private String businessStatus;
    private String logoUrl;
    private String industry;
    private String scale;
    private String city;
    private String address;
    private String description;

    private Integer verifyStatus;
    private LocalDateTime verifyTime;
    private String verifyRemark;

    private String inviteCode;
    private LocalDateTime inviteCodeExpireTime;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
