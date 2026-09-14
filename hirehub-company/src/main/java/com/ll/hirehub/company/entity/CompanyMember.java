package com.ll.hirehub.company.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("company_member")
public class CompanyMember {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long companyId;
    private Long userId;
    private String role;
    private Integer isLegalRep;
    private Integer status;
    private LocalDateTime joinTime;
    private LocalDateTime createTime;
}
