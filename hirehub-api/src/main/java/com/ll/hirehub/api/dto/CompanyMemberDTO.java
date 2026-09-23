package com.ll.hirehub.api.dto;

import lombok.Data;

/**
 * 企业成员信息（company 服务内部接口返回，供其他服务校验归属）。
 * <p>
 * 三期扩展（D-06）：补 {@code companyName} / {@code verifyStatus}，
 * 让 {@code GET /api/auth/me/identities} 一次拿到"我在哪些企业、什么角色、企业认证到什么程度"。
 */
@Data
public class CompanyMemberDTO {

    private Long companyId;
    private Long userId;
    private String role;
    private Integer status;

    /** 企业名称（身份切换器展示用） */
    private String companyName;

    /** 企业认证状态：0 待审核 / 1 通过 / 2 驳回 / 3 已撤销 */
    private Integer verifyStatus;
}
