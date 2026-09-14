package com.ll.hirehub.api.dto;

import lombok.Data;

/**
 * 企业成员信息（company 服务内部接口返回，供其他服务校验归属）
 */
@Data
public class CompanyMemberDTO {

    private Long companyId;
    private Long userId;
    private String role;
    private Integer status;
}
