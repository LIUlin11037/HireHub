package com.ll.hirehub.company.vo;

import com.ll.hirehub.company.entity.Company;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 企业详情对外视图（审计 S-01）。
 * <p>
 * 公司主页是公开的，绝不能直接返回 {@link Company} 实体——实体里的
 * {@code inviteCode} / {@code inviteCodeExpireTime} 一旦泄露，任何人都能调
 * {@code POST /api/company/join} 成为该企业 HR；{@code verifyRemark} 是审核内部留痕。
 * 邀请码只允许 OWNER 通过 {@code POST /{id}/invite-code} 获取。
 */
@Data
public class CompanyVO {

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
    private String riskLevel;
    private LocalDateTime createTime;

    public static CompanyVO from(Company c) {
        CompanyVO vo = new CompanyVO();
        vo.setId(c.getId());
        vo.setName(c.getName());
        vo.setCreditCode(c.getCreditCode());
        vo.setLegalPersonName(c.getLegalPersonName());
        vo.setBusinessStatus(c.getBusinessStatus());
        vo.setLogoUrl(c.getLogoUrl());
        vo.setIndustry(c.getIndustry());
        vo.setScale(c.getScale());
        vo.setCity(c.getCity());
        vo.setAddress(c.getAddress());
        vo.setDescription(c.getDescription());
        vo.setVerifyStatus(c.getVerifyStatus());
        vo.setVerifyTime(c.getVerifyTime());
        vo.setRiskLevel(c.getRiskLevel());
        vo.setCreateTime(c.getCreateTime());
        return vo;
    }
}
