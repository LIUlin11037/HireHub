package com.ll.hirehub.company.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("company_verify_record")
public class CompanyVerifyRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long companyId;
    private Long submitterId;
    private String creditCode;
    private String companyName;
    private String legalPersonName;
    private String licenseUrl;
    private String verifyChannel;
    private String requestSnapshot;
    private String responseSnapshot;
    private String result;
    private String remark;
    private Long operatorId;
    private LocalDateTime createTime;
}
